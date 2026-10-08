package app.mini.hub.persistence

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ActionState
import app.mini.hub.action.AttachmentDigest
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.ActionIdGenerator
import app.mini.hub.domain.Clock
import app.mini.hub.domain.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ActionStoreTest {
    private val clock = Clock { UtcInstant.parse("2026-10-08T12:00:00.000Z") }
    private val controller = ActionController(clock, DigestProvider { it.hashCode().toUInt().toString(16) }, ActionIdGenerator { "$it-1" })
    private val payload = ExactActionPayload("acct", "conversation", ActionRoute.Api, listOf("recipient"), null, "body", listOf(AttachmentDigest("a", "a.txt", "a".repeat(64), 1)), emptyList(), "grant", "policy")

    @Test fun testStoreIsExplicitlyNotProductionQualified() {
        assertEquals(StorageQualification.InMemoryTestOnly, InMemoryActionStore().qualification)
    }

    @Test fun transactionCommitsApprovalAndOutboxTogether() {
        val store = InMemoryActionStore()
        val command = store.transaction { current -> controller.approve(current, payload).first }.outbox.single()
        assertEquals(command.id, store.snapshot().outbox.single().id)
        assertEquals(1, store.snapshot().approvals.size)
    }

    @Test fun restartRecoveryNeverReturnsDispatchingToReady() {
        val store = InMemoryActionStore()
        val approved = controller.approve(ActionState(), payload).first
        store.transaction { approved.copy(outbox = approved.outbox.map { it.copy(state = app.mini.hub.action.OutboxState.Dispatching) }) }
        val recovered = store.recoverInterrupted(controller)
        assertNotEquals(app.mini.hub.action.OutboxState.Ready, recovered.outbox.single().state)
        assertEquals(app.mini.hub.action.OutboxState.OutcomeUnknown, recovered.outbox.single().state)
    }
}
