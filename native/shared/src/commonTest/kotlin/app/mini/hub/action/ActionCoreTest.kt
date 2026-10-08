package app.mini.hub.action

import app.mini.hub.domain.Clock
import app.mini.hub.domain.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ActionCoreTest {
    private var tick = UtcInstant.parse("2026-10-08T12:00:00.000Z")
    private val clock = Clock { tick }
    private var sequence = 0
    private val ids = ActionIdGenerator { prefix -> "$prefix-${++sequence}" }
    private val digest = DigestProvider { payload -> payload.hashCode().toUInt().toString(16) }
    private val controller get() = ActionController(clock, digest, ids)

    private fun payload(route: ActionRoute = ActionRoute.Api, body: String = "Reviewed body") = ExactActionPayload(
        accountId = "gmail|acct-1",
        conversationId = "gmail|acct-1|conversation|thread-1",
        route = route,
        recipients = if (route == ActionRoute.Api) listOf("anika@example.test") else emptyList(),
        subject = "Subject",
        body = body,
        attachments = listOf(AttachmentDigest("quote", "quote.pdf", "a".repeat(64), 12)),
        sourceVersions = listOf(SourceVersion("message-1", 1)),
        grantVersion = "grants:send",
        policyVersion = "policy-1",
    )

    @Test fun approvalBindsExactPayloadAndCreatesUniqueIds() {
        val first = controller.approve(ActionState(), payload()).first
        val second = controller.approve(first, payload()).first
        assertEquals(2, second.approvals.size)
        assertTrue(second.approvals.map { it.id }.toSet().size == 2)
        assertTrue(second.outbox.map { it.id }.toSet().size == 2)
        assertTrue(second.outbox.all { it.state == OutboxState.Ready })
    }

    @Test fun changedBodyCannotDispatch() {
        val (state, command) = controller.approve(ActionState(), payload())
        val result = controller.dispatch(state, command.id, payload(body = "Changed"), ProviderGateway { _, _ -> GatewayResult.Accepted("r") }, null)
        assertEquals(OutboxState.ExpiredOrInvalidated, result.outbox.single().state)
        assertEquals("payload_changed_since_approval", result.outbox.single().failureReason)
    }

    @Test fun acceptedRequiresReceiptAndConsumesApproval() {
        val (state, command) = controller.approve(ActionState(), payload())
        val result = controller.dispatch(state, command.id, payload(), ProviderGateway { _, _ -> GatewayResult.Accepted("provider-1") }, null)
        assertEquals(OutboxState.AcceptedByProvider, result.outbox.single().state)
        assertEquals("provider-1", result.outbox.single().receipt)
        assertTrue(result.approvals.single().consumed)
        assertEquals(result, controller.dispatch(result, command.id, payload(), ProviderGateway { _, _ -> error("must not retry") }, null))
    }

    @Test fun ambiguousProviderAndInterruptedDispatchNeverBecomeReady() {
        val (state, command) = controller.approve(ActionState(), payload())
        val unknown = controller.dispatch(state, command.id, payload(), ProviderGateway { _, _ -> GatewayResult.Unknown("timeout") }, null)
        assertEquals(OutboxState.OutcomeUnknown, unknown.outbox.single().state)
        val interrupted = controller.recoverInterrupted(state.copy(outbox = state.outbox.map { it.copy(state = OutboxState.Dispatching) }))
        assertEquals(OutboxState.OutcomeUnknown, interrupted.outbox.single().state)
    }

    @Test fun expiryAndClockRollbackFailClosed() {
        val (state, command) = controller.approve(ActionState(), payload())
        tick = tick + 120_001
        assertEquals(OutboxState.ExpiredOrInvalidated, controller.dispatch(state, command.id, payload(), ProviderGateway { _, _ -> error("must not send") }, null).outbox.single().state)
        val fresh = controller.approve(ActionState(), payload()).first
        tick = UtcInstant.parse("2026-10-08T11:59:59.000Z")
        assertEquals("clock_skew_detected", controller.dispatch(fresh, fresh.outbox.single().id, payload(), ProviderGateway { _, _ -> error("must not send") }, null).outbox.single().failureReason)
    }

    @Test fun nativeHandoffHasNoProviderReceipt() {
        val (state, command) = controller.approve(ActionState(), payload(ActionRoute.NativeHandoff))
        val result = controller.dispatch(state, command.id, payload(ActionRoute.NativeHandoff), null, HandoffLauncher { _, _ -> true })
        assertEquals(OutboxState.HandoffOpened, result.outbox.single().state)
        assertEquals(null, result.outbox.single().receipt)
    }

    @Test fun apiApprovalRequiresRecipient() {
        assertFails { controller.approve(ActionState(), payload().copy(recipients = emptyList())) }
    }
}
