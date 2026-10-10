package app.mini.hub.understanding

import app.mini.hub.attention.AttentionRules
import app.mini.hub.attention.AttentionAction
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageUnderstandingTest {
    private val account = AccountId(Provider.GMAIL, "acct")
    private val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread")
    private val now = UtcInstant(1_700_000_000_000L)

    @Test
    fun deadlineAndRequestAreExplainedLocally() {
        val summary = summary("Please send the signed proposal by Friday")
        val result = MessageUnderstandingEngine.analyze(summary, listOf(observation("Please send the signed proposal by Friday")), AttentionRules.default(conversation, now))
        assertEquals("Time-sensitive", result.urgency)
        assertEquals("by Friday", result.deadline)
        assertEquals("Review and respond to the request", result.requiredAction)
        assertTrue(result.unansweredRequest)
        assertTrue(result.explanation.contains("deadline mentioned"))
    }

    @Test
    fun userDoneOverrideRemovesUnansweredSignalButKeepsEvidence() {
        val summary = summary("Please confirm the meeting today")
        val initial = AttentionRules.default(conversation, now)
        val done = (AttentionRules.apply(initial, AttentionAction.MarkDone, now, now) as app.mini.hub.attention.AttentionDecision.Applied).record
        val result = MessageUnderstandingEngine.analyze(summary, listOf(observation("Please confirm the meeting today")), done)
        assertEquals("High", result.urgency)
        assertTrue(!result.unansweredRequest)
        assertTrue(result.evidenceText.contains("confirm"))
    }

    @Test
    fun deadlineChangeAndWaitingOverrideUpdateUnderstanding() {
        val first = MessageUnderstandingEngine.analyze(summary("Please send the signed proposal by Friday"), listOf(observation("Please send the signed proposal by Friday")), AttentionRules.default(conversation, now))
        val later = MessageUnderstandingEngine.analyze(summary("The deadline changed: please send the signed proposal by Monday"), listOf(observation("The deadline changed: please send the signed proposal by Monday")), AttentionRules.default(conversation, now))
        assertEquals("by Friday", first.deadline)
        assertEquals("by Monday", later.deadline)
        assertTrue(later.importantChange)

        val waitingRecord = (AttentionRules.apply(AttentionRules.default(conversation, now), AttentionAction.MarkWaiting, now, now) as app.mini.hub.attention.AttentionDecision.Applied).record
        val waiting = MessageUnderstandingEngine.analyze(summary("Please send the signed proposal by Monday"), listOf(observation("Please send the signed proposal by Monday")), waitingRecord)
        assertTrue(waiting.waiting)
        assertTrue(!waiting.unansweredRequest)
        assertTrue(waiting.explanation.contains("waiting"))
    }

    @Test
    fun incompleteTextDoesNotInventADeadlineOrRelationship() {
        val result = MessageUnderstandingEngine.analyze(summary("Thanks for the update"), listOf(observation("Thanks for the update")), AttentionRules.default(conversation, now))
        assertEquals(null, result.deadline)
        assertEquals("Routine", result.urgency)
        assertTrue(!result.unansweredRequest)
        assertTrue(result.explanation.contains("no strong action") || !result.hasSignal)
    }

    private fun summary(text: String) = ConversationObservationSummary(
        conversationId = conversation,
        accountId = account,
        title = "Thread",
        appName = "Gmail",
        senderHint = "person@example.com",
        packageId = "provider.gmail",
        latestSnippet = text,
        category = NotificationCategory.DirectMessage,
        coverage = ConversationCoverage.FullProviderSync,
        coverageExplanation = "full",
        latestEventAt = now,
        observationCount = 1,
        unreadCount = 1,
        isRemoved = false,
    )

    private fun observation(text: String) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, text.hashCode().toString()),
        accountId = account,
        conversationId = conversation,
        packageId = "provider.gmail",
        notificationKey = "n:${text.hashCode()}",
        notificationId = text.hashCode(),
        tag = null,
        senderHint = "person@example.com",
        title = "Thread",
        textFragments = listOf(text),
        rawTextDigest = text,
        observedAt = now,
        postTime = now,
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.OfficialProviderApi,
    )
}
