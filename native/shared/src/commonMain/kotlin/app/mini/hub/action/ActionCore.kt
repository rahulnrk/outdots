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
    /**
     * Provider message being replied to (Gmail message id, Outlook message id, Slack ts, Chat
     * thread name, Telegram message id). Part of the approved digest: changing the reply target
     * invalidates approval. Null for a new message in [conversationId].
     */
    val replyTarget: String? = null,
    /** Cc/Bcc for email. Part of the approved digest. */
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    /** Last inbound event used to prove a time-sensitive provider reply window at dispatch. */
    val replyWindowLastInboundMillis: Long? = null,
) {
    init {
        require(accountId.isNotBlank())
        require(conversationId.isNotBlank() || route == ActionRoute.NativeHandoff || subject != null) { "conversationId required except for a new email" }
        require(body.isNotBlank())
        require(recipients.distinct() == recipients) { "duplicate recipients are not allowed" }
        require(attachments.distinctBy { it.id }.size == attachments.size) { "duplicate attachments are not allowed" }
    }

    fun canonical(): String = buildString {
        // v2 format: length-prefixed or field-escaped components prevent delimiter injection.
        append("v2|")
        append(encodeField(accountId)).append('|')
        append(encodeField(conversationId)).append('|')
        append(route.name).append('|')
        recipients.sorted().forEach { append("r:").append(encodeField(it)).append(';') }
        append('|')
        append("subj:").append(encodeField(subject ?: "")).append('|')
        append("body:").append(encodeField(body)).append('|')
        attachments.sortedBy { it.id }.forEach {
            append("a:").append(encodeField(it.id)).append(':')
                .append(encodeField(it.name)).append(':')
                .append(it.sha256).append(':')
                .append(it.sizeBytes).append(';')
        }
        append('|')
        sourceVersions.sortedBy { it.id }.forEach {
            append("s:").append(encodeField(it.id)).append(':')
                .append(it.revision).append(';')
        }
        append('|')
        append("grant:").append(encodeField(grantVersion)).append('|')
        append("policy:").append(encodeField(policyVersion))
        replyTarget?.let { append('|').append("reply:").append(encodeField(it)) }
        if (cc.isNotEmpty()) append("|cc:").append(cc.sorted().joinToString(";") { encodeField(it) })
        if (bcc.isNotEmpty()) append("|bcc:").append(bcc.sorted().joinToString(";") { encodeField(it) })
        replyWindowLastInboundMillis?.let { append("|reply_window_last_inbound:").append(it) }
    }

    private fun encodeField(value: String): String =
        value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace(":", "\\:")
            .replace(";", "\\;")
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

/** Immutable copy of the exact reviewed payload, keyed by outbox command. */
data class ReviewedActionSnapshot(
    val commandId: String,
    val accountId: String,
    val conversationId: String,
    val route: ActionRoute,
    val recipients: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String?,
    val body: String,
    val attachmentNames: List<String>,
    val replyTarget: String?,
    val state: OutboxState,
    val createdAt: UtcInstant,
    val receipt: String? = null,
    val failureReason: String? = null,
)

fun ExactActionPayload.toReviewedSnapshot(command: OutboxCommand): ReviewedActionSnapshot = ReviewedActionSnapshot(
    commandId = command.id,
    accountId = accountId,
    conversationId = conversationId,
    route = route,
    recipients = recipients,
    cc = cc,
    bcc = bcc,
    subject = subject,
    body = body,
    attachmentNames = attachments.map { it.name },
    replyTarget = replyTarget,
    state = command.state,
    createdAt = command.createdAt,
    receipt = command.receipt,
    failureReason = command.failureReason,
)

data class ActionState(
    val approvals: List<ApprovalRecord> = emptyList(),
    val outbox: List<OutboxCommand> = emptyList(),
    val reviewedSnapshots: List<ReviewedActionSnapshot> = emptyList(),
)

fun ActionState.syncReviewedSnapshots(): ActionState {
    if (reviewedSnapshots.isEmpty()) return this
    var changed = false
    val next = reviewedSnapshots.map { snap ->
        val cmd = outbox.find { it.id == snap.commandId } ?: return@map snap
        if (snap.state == cmd.state && snap.receipt == cmd.receipt && snap.failureReason == cmd.failureReason) snap
        else {
            changed = true
            snap.copy(state = cmd.state, receipt = cmd.receipt, failureReason = cmd.failureReason)
        }
    }
    return if (changed) copy(reviewedSnapshots = next) else this
}

sealed class GatewayResult {
    data class Accepted(val receipt: String) : GatewayResult()
    data class Rejected(val reason: String) : GatewayResult()
    data class Unknown(val reason: String) : GatewayResult()
}

fun interface ProviderGateway { fun execute(command: OutboxCommand, payload: ExactActionPayload): GatewayResult }
fun interface HandoffLauncher { fun open(command: OutboxCommand, payload: ExactActionPayload): Boolean }

/** Current authorization and evidence, re-read immediately before dispatch. */
data class LiveDispatchChecks(
    val grantVersion: String,
    val policyVersion: String,
    val sourceVersions: List<SourceVersion>,
)

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

    fun dispatch(state: ActionState, commandId: String, payload: ExactActionPayload, gateway: ProviderGateway?, handoff: HandoffLauncher?, live: LiveDispatchChecks? = null): ActionState {
        val command = state.outbox.find { it.id == commandId } ?: error("command not found")
        if (command.state != OutboxState.Ready) return state
        val consumed = beginDispatch(state, commandId, payload, live)
        val begun = consumed.outbox.first { it.id == commandId }
        if (begun.state != OutboxState.Dispatching) return consumed
        val now = begun.lastAttemptAt ?: clock.now()
        if (command.route == ActionRoute.NativeHandoff) {
            if (handoff == null) return fail(consumed, commandId, OutboxState.OutcomeUnknown, "handoff_unavailable", now)
            return if (handoff.open(command, payload)) update(consumed, commandId, OutboxState.HandoffOpened, now)
            else fail(consumed, commandId, OutboxState.Rejected, "handoff_rejected", now)
        }
        return completeDispatch(consumed, commandId, executeGateway(command, payload, gateway))
    }

    /**
     * Phase 1 of a split dispatch. Runs every approval, expiry, clock and digest check; on success
     * consumes the approval and moves the command to [OutboxState.Dispatching]. Callers must commit
     * this state durably before any provider I/O so a crash can only ever end in OutcomeUnknown.
     * A command that is not Ready is returned unchanged.
     */
    fun beginDispatch(state: ActionState, commandId: String, payload: ExactActionPayload, live: LiveDispatchChecks? = null): ActionState {
        val command = state.outbox.find { it.id == commandId } ?: error("command not found")
        if (command.state != OutboxState.Ready) return state
        val now = clock.now()
        val approval = state.approvals.find { it.id == command.approvalId }
            ?: return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "approval_missing", now)
        if (now < approval.approvedAt) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "clock_skew_detected", now)
        if (now > approval.expiresAt || approval.consumed) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "approval_expired_or_consumed", now)
        if (digest.sha256(payload.canonical()) != command.payloadDigest) return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "payload_changed_since_approval", now)
        if (live != null) {
            if (live.grantVersion != payload.grantVersion || live.policyVersion != payload.policyVersion) {
                return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "policy_or_grants_changed_since_approval", now)
            }
            if (!sameSources(live.sourceVersions, payload.sourceVersions)) {
                return fail(state, commandId, OutboxState.ExpiredOrInvalidated, "source_changed_since_approval", now)
            }
        }
        return state.copy(
            approvals = state.approvals.map { if (it.id == approval.id) it.copy(consumed = true) else it },
            outbox = state.outbox.map { if (it.id == commandId) it.copy(state = OutboxState.Dispatching, lastAttemptAt = now) else it },
        ).syncReviewedSnapshots()
    }

    /** Phase 2: the provider call. No state access, so it can run outside any transaction. */
    fun executeGateway(command: OutboxCommand, payload: ExactActionPayload, gateway: ProviderGateway?): GatewayResult =
        try { gateway?.execute(command, payload) ?: GatewayResult.Rejected("gateway_unconfigured") }
        catch (_: Throwable) { GatewayResult.Unknown("gateway_call_failed") }

    /** Phase 3: records the provider result. Applies only to a command still in Dispatching. */
    fun completeDispatch(state: ActionState, commandId: String, result: GatewayResult): ActionState {
        val command = state.outbox.find { it.id == commandId } ?: error("command not found")
        if (command.state != OutboxState.Dispatching) return state
        val now = command.lastAttemptAt ?: clock.now()
        return when (result) {
            is GatewayResult.Accepted -> if (result.receipt.isNotBlank()) update(state, commandId, OutboxState.AcceptedByProvider, now, result.receipt) else fail(state, commandId, OutboxState.OutcomeUnknown, "provider_receipt_missing", now)
            is GatewayResult.Rejected -> fail(state, commandId, OutboxState.Rejected, result.reason, now)
            is GatewayResult.Unknown -> fail(state, commandId, OutboxState.OutcomeUnknown, result.reason, now)
        }
    }

    fun recoverInterrupted(state: ActionState): ActionState = state.copy(outbox = state.outbox.map { if (it.state == OutboxState.Dispatching) it.copy(state = OutboxState.OutcomeUnknown, failureReason = "interrupted_during_dispatch") else it }).syncReviewedSnapshots()

    private fun update(state: ActionState, id: String, next: OutboxState, now: UtcInstant, receipt: String? = null): ActionState =
        state.copy(outbox = state.outbox.map { if (it.id == id) it.copy(state = next, lastAttemptAt = now, receipt = receipt) else it }).syncReviewedSnapshots()
    private fun fail(state: ActionState, id: String, next: OutboxState, reason: String, now: UtcInstant): ActionState =
        state.copy(outbox = state.outbox.map { if (it.id == id) it.copy(state = next, lastAttemptAt = now, failureReason = reason) else it }).syncReviewedSnapshots()
    private fun sameSources(a: List<SourceVersion>, b: List<SourceVersion>): Boolean =
        a.sortedWith(compareBy({ it.id }, { it.revision })) == b.sortedWith(compareBy({ it.id }, { it.revision }))
}
