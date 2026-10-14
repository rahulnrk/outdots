package app.mini.hub.persistence

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ActionState
import app.mini.hub.action.AttachmentDigest
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.ActionIdGenerator
import app.mini.hub.action.toReviewedSnapshot
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

    @Test fun reviewedSnapshotSurvivesInterruptedRecoveryWithExactBody() {
        val store = InMemoryActionStore()
        val approved = controller.approve(ActionState(), payload)
        val command = approved.second
        val snapshot = payload.toReviewedSnapshot(command).copy(state = app.mini.hub.action.OutboxState.Dispatching)
        store.transaction {
            approved.first.copy(
                outbox = approved.first.outbox.map { it.copy(state = app.mini.hub.action.OutboxState.Dispatching) },
                reviewedSnapshots = listOf(snapshot),
            )
        }
        val recovered = store.recoverInterrupted(controller)
        val kept = recovered.reviewedSnapshots.single()
        assertEquals(app.mini.hub.action.OutboxState.OutcomeUnknown, kept.state)
        assertEquals("body", kept.body)
        assertEquals("acct", kept.accountId)
        assertEquals("conversation", kept.conversationId)
        assertEquals(listOf("recipient"), kept.recipients)
    }

    @Test fun draftsAreKeyedByConversationAndNeverStoreApprovals() {
        val store = InMemoryActionStore()
        store.saveDraft(ConversationDraft("conv-a", "acct-a", "hello", to = "a@b.c", isNewEmail = true, updatedAtMillis = 1L))
        store.saveDraft(ConversationDraft("conv-b", "acct-b", "other", updatedAtMillis = 2L))
        assertEquals("hello", store.loadDraft("conv-a")?.body)
        assertEquals("a@b.c", store.loadDraft("conv-a")?.to)
        assertEquals(true, store.loadDraft("conv-a")?.isNewEmail)
        store.deleteDraft("conv-a")
        assertEquals(null, store.loadDraft("conv-a"))
        assertEquals("other", store.loadDraft("conv-b")?.body)
        assertEquals(0, store.snapshot().approvals.size)
    }
}
