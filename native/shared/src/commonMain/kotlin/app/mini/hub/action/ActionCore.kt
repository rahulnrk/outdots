package app.mini.hub.action

import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant

/** Routes are capabilities, not instructions to a model. */
enum class ActionRoute { Api, NativeHandoff }

enum class OutboxState {
    Ready, Dispatching, AcceptedByProvider, Rejected, OutcomeUnknown,
    ExpiredOrInvalidated, HandoffOpened, ExternallyHandledUnverified,
}

data class AttachmentDigest(val id: String, val name: String, val sha256: String, val sizeBytes: Long)
data class SourceVersion(val id: String, val revision: Long)

data class ExactActionPayload(
    val accountId: String,
    val conversationId: String,
    val route: ActionRoute,
    val recipients: List<String>,
    val subject: String?,
    val body: String,
    val attachments: List<AttachmentDigest>,
    val sourceVersions: List<SourceVersion>,
    val grantVersion: String,
    val policyVersion: String,
) {
    init {
        require(accountId.isNotBlank() && conversationId.isNotBlank())
        require(body.isNotBlank())
        require(recipients.distinct() == recipients) { "duplicate recipients are not allowed" }
        require(attachments.distinctBy { it.id }.size == attachments.size) { "duplicate attachments are not allowed" }
    }

    fun canonical(): String = buildString {
        append(accountId).append('|').append(conversationId).append('|').append(route.name).append('|')
        recipients.sorted().forEach { append("r:").append(it).append('|') }
        append("subject:").append(subject ?: "").append('|').append("body:").append(body).append('|')
        attachments.sortedBy { it.id }.forEach { append("a:").append(it.id).append(':').append(it.name).append(':').append(it.sha256).append(':').append(it.sizeBytes).append('|') }
        sourceVersions.sortedBy { it.id }.forEach { append("s:").append(it.id).append(':').append(it.revision).append('|') }
        append("grant:").append(grantVersion).append('|').append("policy:").append(policyVersion)
    }
}

/** Implemented with platform crypto in production. Weak fallback digests are forbidden. */
fun interface DigestProvider {
    fun sha256(canonicalPayload: String): String
}

fun interface ActionIdGenerator { fun next(prefix: String): String }
class DomainActionIdGenerator(private val ids: IdGenerator) : ActionIdGenerator {
    override fun next(prefix: String): String = ids.next(prefix)
}

data class ApprovalRecord(
    val id: String,
    val payloadDigest: String,
    val approvedAt: UtcInstant,
    val expiresAt: UtcInstant,
    val consumed: Boolean = false,
)

data class OutboxCommand(
    val id: String,
    val approvalId: String,
    val payloadDigest: String,
    val route: ActionRoute,
    val state: OutboxState,
    val createdAt: UtcInstant,
    val lastAttemptAt: UtcInstant? = null,
    val receipt: String? = null,
    val failureReason: String? = null,
)

data class ActionState(val approvals: List<ApprovalRecord> = emptyList(), val outbox: List<OutboxCommand> = emptyList())

sealed class GatewayResult {
    data class Accepted(val receipt: String) : GatewayResult()
    data class Rejected(val reason: String) : GatewayResult()
    data class Unknown(val reason: String) : GatewayResult()
}

fun interface ProviderGateway { fun execute(command: OutboxCommand, payload: ExactActionPayload): GatewayResult }
fun interface HandoffLauncher { fun open(command: OutboxCommand, payload: ExactActionPayload): Boolean }

class ActionController(
    private val clock: Clock,
    private val digest: DigestProvider,
    private val ids: ActionIdGenerator,
    private val approvalLifetimeMillis: Long = 120_000L,
) {
    init { require(approvalLifetimeMillis > 0) }

    fun approve(state: ActionState, payload: ExactActionPayload): Pair<ActionState, OutboxCommand> {
        if (payload.route == ActionRoute.Api) require(payload.recipients.isNotEmpty()) { "API approval requires a recipient" }
        val value = digest.sha256(payload.canonical())
        require(value.isNotBlank()) { "cryptographic digest unavailable" }
        val now = clock.now()
        val approval = ApprovalRecord(ids.next("approval"), value, now, now + approvalLifetimeMillis)
        val command = OutboxCommand(ids.next("outbox"), approval.id, value, payload.route, OutboxState.Ready, now)
        return state.copy(approvals = state.approvals + approval, outbox = state.outbox + command) to command
    }

    fun dispatch(state: ActionState, commandId: String, payload: ExactActionPayload, gateway: ProviderGateway?, handoff: HandoffLauncher?): ActionState {
        val command = state.outbox.find { it.id == commandId } ?: error("command not found")
        if (command.state != OutboxState.Ready) return state
        val now = clock.now()
        val approval = state.approvals.find { it.id == command.approvalId }
            ?: return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "approval_missing", now)
        if (now < approval.approvedAt) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "clock_skew_detected", now)
        if (now > approval.expiresAt || approval.consumed) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "approval_expired_or_consumed", now)
        if (digest.sha256(payload.canonical()) != command.payloadDigest) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "payload_changed_since_approval", now)

        val consumed = state.copy(
            approvals = state.approvals.map { if (it.id == approval.id) it.copy(consumed = true) else it },
            outbox = state.outbox.map { if (it.id == commandId) it.copy(state = OutboxState.Dispatching, lastAttemptAt = now) else it },
        )
        if (command.route == ActionRoute.NativeHandoff) {
            if (handoff == null) return fail(consumed, commandId, OutboxState.OutcomeUnknown, "handoff_unavailable", now)
            return if (handoff.open(command, payload)) update(consumed, commandId, OutboxState.HandoffOpened, now)
            else fail(consumed, commandId, OutboxState.Rejected, "handoff_rejected", now)
        }
        val result = try { gateway?.execute(command, payload) ?: GatewayResult.Rejected("gateway_unconfigured") }
        catch (_: Throwable) { GatewayResult.Unknown("gateway_call_failed") }
        return when (result) {
            is GatewayResult.Accepted -> if (result.receipt.isNotBlank()) update(consumed, commandId, OutboxState.AcceptedByProvider, now, result.receipt) else fail(consumed, commandId, OutboxState.OutcomeUnknown, "provider_receipt_missing", now)
            is GatewayResult.Rejected -> fail(consumed, commandId, OutboxState.Rejected, result.reason, now)
            is GatewayResult.Unknown -> fail(consumed, commandId, OutboxState.OutcomeUnknown, result.reason, now)
        }
    }

    fun recoverInterrupted(state: ActionState): ActionState = state.copy(outbox = state.outbox.map { if (it.state == OutboxState.Dispatching) it.copy(state = OutboxState.OutcomeUnknown, failureReason = "interrupted_during_dispatch") else it })

    private fun update(state: ActionState, id: String, next: OutboxState, now: UtcInstant, receipt: String? = null): ActionState = state.copy(outbox = state.outbox.map { if (it.id == id) it.copy(state = next, lastAttemptAt = now, receipt = receipt) else it })
    private fun fail(state: ActionState, id: String, next: OutboxState, reason: String, now: UtcInstant): ActionState = state.copy(outbox = state.outbox.map { if (it.id == id) it.copy(state = next, lastAttemptAt = now, failureReason = reason) else it })
}
