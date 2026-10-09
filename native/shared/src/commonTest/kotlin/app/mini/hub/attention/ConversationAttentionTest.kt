package app.mini.hub.attention

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.NotificationCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationAttentionTest {
    private val personal = AccountId(Provider.WHATSAPP, "local_user")
    private val work = AccountId(Provider.WHATSAPP, "work_profile")
    private val rahulPersonal = ScopedId(personal, ScopedId.Kind.CONVERSATION, "Rahul")
    private val rahulWork = ScopedId(work, ScopedId.Kind.CONVERSATION, "Rahul")
    private val t0 = UtcInstant(1_760_000_000_000L)
    private fun at(minutes: Long) = t0 + minutes * 60_000L

    private fun applied(d: AttentionDecision): ConversationAttention = assertIs<AttentionDecision.Applied>(d).record

    private fun summary(id: ScopedId, latest: UtcInstant, title: String = "Rahul") = ConversationObservationSummary(
        conversationId = id,
        accountId = id.account,
        title = title,
        appName = "WhatsApp",
        senderHint = title,
        packageId = "com.whatsapp",
        latestSnippet = "synthetic",
        category = NotificationCategory.DirectMessage,
        coverage = ConversationCoverage.NotificationOnly,
        coverageExplanation = "synthetic",
        latestEventAt = latest,
        observationCount = 1,
        unreadCount = 1,
        isRemoved = false,
    )

    @Test fun defaultIsNormalAndNeedsAttention() {
        val d = AttentionRules.default(rahulPersonal, t0)
        assertEquals(ConversationPriority.Normal, d.priority)
        assertEquals(FollowUpStatus.NeedsAttention, d.status)
        assertFalse(d.isUserSet)
    }

    @Test fun priorityChangeKeepsFollowUpAndAutomaticTransitionsKeepPriority() {
        var r = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkWaiting, at(0), at(1)))
        r = applied(AttentionRules.apply(r, AttentionAction.SetPriority(ConversationPriority.Important), at(0), at(2)))
        assertEquals(FollowUpStatus.Waiting, r.status)
        val resurfaced = AttentionRules.reconcile(r, at(5), at(6))
        assertEquals(FollowUpStatus.NeedsAttention, resurfaced.status)
        assertEquals(ConversationPriority.Important, resurfaced.priority)
    }

    @Test fun waitingResurfacesOnNewActivityWithReason() {
        val waiting = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkWaiting, at(0), at(1)))
        assertSame(waiting, AttentionRules.reconcile(waiting, at(0), at(2)), "no new activity keeps Waiting")
        val r = AttentionRules.reconcile(waiting, at(3), at(4))
        assertEquals(FollowUpStatus.NeedsAttention, r.status)
        assertEquals(FollowUpStatus.Waiting, r.resurfacedFrom)
        assertEquals(ResurfaceReason.NewActivityWhileWaiting, r.resurfaceReason)
    }

    @Test fun doneResurfacesOnlyForNewerActivity() {
        val done = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkDone, at(10), at(11)))
        assertSame(done, AttentionRules.reconcile(done, at(10), at(20)))
        assertSame(done, AttentionRules.reconcile(done, at(9), at(20)), "older activity is not new")
        val r = AttentionRules.reconcile(done, at(12), at(20))
        assertEquals(FollowUpStatus.NeedsAttention, r.status)
        assertEquals(ResurfaceReason.NewActivityAfterDone, r.resurfaceReason)
        assertEquals(FollowUpStatus.Done, r.resurfacedFrom)
    }

    @Test fun snoozeStaysUntilDueThenResurfaces() {
        val s = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.Snooze(at(60)), at(0), at(1)))
        assertEquals(FollowUpStatus.Snoozed, s.status)
        assertSame(s, AttentionRules.reconcile(s, at(0), at(59)))
        val due = AttentionRules.reconcile(s, at(0), at(60))
        assertEquals(FollowUpStatus.NeedsAttention, due.status)
        assertNull(due.snoozedUntil)
        assertEquals(ResurfaceReason.SnoozeDue, due.resurfaceReason)
        assertFalse(due.activityWhileSnoozed)
    }

    @Test fun newActivityDuringSnoozeIsFlaggedButRespectsChosenTime() {
        val s = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.Snooze(at(60)), at(0), at(1)))
        val flagged = AttentionRules.reconcile(s, at(5), at(6))
        assertEquals(FollowUpStatus.Snoozed, flagged.status)
        assertTrue(flagged.activityWhileSnoozed)
        assertSame(flagged, AttentionRules.reconcile(flagged, at(5), at(7)), "idempotent")
        val due = AttentionRules.reconcile(flagged, at(5), at(61))
        assertEquals(ResurfaceReason.SnoozeDue, due.resurfaceReason)
        assertTrue(due.activityWhileSnoozed)
    }

    @Test fun snoozeInPastIsRejected() {
        val d = AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.Snooze(at(1)), null, at(1))
        assertIs<AttentionDecision.Rejected>(d)
    }

    @Test fun userChoiceAcknowledgesActivityAndClearsMarkers() {
        val waiting = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkWaiting, at(0), at(1)))
        val resurfaced = AttentionRules.reconcile(waiting, at(3), at(4))
        val again = applied(AttentionRules.apply(resurfaced, AttentionAction.MarkWaiting, at(3), at(5)))
        assertNull(again.resurfaceReason)
        assertEquals(at(3), again.acknowledgedActivityAt)
        assertSame(again, AttentionRules.reconcile(again, at(3), at(6)))
    }

    @Test fun reconciliationIsIdempotent() {
        val done = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkDone, at(0), at(1)))
        val once = AttentionRules.reconcile(done, at(2), at(3))
        assertSame(once, AttentionRules.reconcile(once, at(2), at(4)))
    }

    @Test fun identicalContactNamesInDifferentAccountsDoNotMerge() {
        assertNotEquals(rahulPersonal.key, rahulWork.key)
        val store = InMemoryAttentionStore()
        val done = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkDone, at(0), at(1)))
        assertIs<AttentionStoreResult.Ok<Unit>>(store.saveAll(listOf(done)))
        val loaded = assertIs<AttentionStoreResult.Ok<Map<String, ConversationAttention>>>(store.loadAll()).value
        val rec = AttentionReconciler.reconcile(loaded, listOf(summary(rahulPersonal, at(0)), summary(rahulWork, at(0))), at(2))
        assertEquals(FollowUpStatus.Done, rec.records.getValue(rahulPersonal.key).status)
        assertEquals(FollowUpStatus.NeedsAttention, rec.records.getValue(rahulWork.key).status)
        assertFalse(rec.records.getValue(rahulWork.key).isUserSet)
    }

    @Test fun reconcilerPersistsOnlyMeaningfulChanges() {
        val store = mapOf(
            rahulPersonal.key to applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.Snooze(at(30)), at(0), at(1))),
        )
        val notDue = AttentionReconciler.reconcile(store, listOf(summary(rahulPersonal, at(0)), summary(rahulWork, at(0))), at(10))
        assertTrue(notDue.changed.isEmpty(), "defaults and unchanged records are not written")
        val due = AttentionReconciler.reconcile(store, listOf(summary(rahulPersonal, at(0))), at(30))
        assertEquals(listOf(rahulPersonal.key), due.changed.map { it.key })
    }

    @Test fun attentionViewOrdersImportantFirstAndExcludesDone() {
        val a = ScopedId(personal, ScopedId.Kind.CONVERSATION, "A")
        val b = ScopedId(personal, ScopedId.Kind.CONVERSATION, "B")
        val c = ScopedId(personal, ScopedId.Kind.CONVERSATION, "C")
        val d = ScopedId(personal, ScopedId.Kind.CONVERSATION, "D")
        val important = applied(AttentionRules.apply(AttentionRules.default(a, t0), AttentionAction.SetPriority(ConversationPriority.Important), null, t0))
        val done = applied(AttentionRules.apply(AttentionRules.default(c, t0), AttentionAction.MarkDone, at(0), t0))
        val snoozed = applied(AttentionRules.apply(AttentionRules.default(d, t0), AttentionAction.Snooze(at(30)), at(0), t0))
        val view = AttentionView.build(
            listOf(
                AttentionEntry(summary(a, at(1), "A"), important),
                AttentionEntry(summary(b, at(5), "B"), AttentionRules.default(b, t0)),
                AttentionEntry(summary(c, at(0), "C"), done),
                AttentionEntry(summary(d, at(0), "D"), snoozed),
            ),
        )
        assertEquals(listOf("A", "B"), view.needsAttention.map { it.summary.title })
        assertEquals(listOf("D"), view.snoozed.map { it.summary.title })
        assertTrue(view.waiting.isEmpty())
    }

    @Test fun invalidRecordsAreRejected() {
        kotlin.test.assertFails { ConversationAttention(rahulPersonal, status = FollowUpStatus.Snoozed, updatedAt = t0) }
        kotlin.test.assertFails { ConversationAttention(rahulPersonal, status = FollowUpStatus.Waiting, snoozedUntil = t0, updatedAt = t0) }
        kotlin.test.assertFails {
            ConversationAttention(ScopedId(personal, ScopedId.Kind.OBSERVATION, "x"), updatedAt = t0)
        }
    }

    @Test fun storageFailureIsReportedNotSwallowed() {
        val store = InMemoryAttentionStore().apply { failWrites = "disk unavailable" }
        val r = applied(AttentionRules.apply(AttentionRules.default(rahulPersonal, t0), AttentionAction.MarkDone, null, t0))
        assertEquals(AttentionStoreResult.Failed("disk unavailable"), store.saveAll(listOf(r)))
        assertIs<AttentionStoreResult.Failed>(UnavailableAttentionStore("no key").loadAll())
    }
}
