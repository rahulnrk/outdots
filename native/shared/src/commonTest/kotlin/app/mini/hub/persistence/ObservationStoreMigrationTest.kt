package app.mini.hub.persistence

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservationStoreMigrationTest {
    private val account = AccountId(Provider.TWITTER, "user_1")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "X_alerts")

    @Test
    fun testExistingConversationSummaryPopulatesAppNameAndCategory() {
        val store = InMemoryObservationStore()

        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_tw_1"),
            accountId = account,
            conversationId = convId,
            packageId = "com.twitter.android",
            notificationKey = "key_tw_1",
            notificationId = 101,
            tag = null,
            senderHint = "X",
            title = "X Alert",
            textFragments = listOf("You have a new reply."),
            rawTextDigest = "digest_tw",
            observedAt = UtcInstant(1_700_000_000_000L),
            postTime = UtcInstant(1_700_000_000_000L),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )

        val result = store.persistObservation(obs)
        assertTrue(result is PersistResult.Success)

        val summaries = store.getConversationSummaries(account)
        assertEquals(1, summaries.size)

        val summary = summaries.first()
        assertEquals("X", summary.appName)
        assertEquals(NotificationCategory.SocialUpdate, summary.category)
        assertEquals(ConversationCoverage.NotificationOnly, summary.coverage)
        assertEquals("You have a new reply.", summary.latestSnippet)
    }

    @Test
    fun testOngoingServiceConversationClassification() {
        val whoopAccount = AccountId(Provider.WHOOP, "whoop_user")
        val whoopConvId = ScopedId(whoopAccount, ScopedId.Kind.CONVERSATION, "WHOOP_service")
        val store = InMemoryObservationStore()

        val whoopObs = ObservedNotification(
            id = ScopedId(whoopAccount, ScopedId.Kind.OBSERVATION, "obs_wh_1"),
            accountId = whoopAccount,
            conversationId = whoopConvId,
            packageId = "com.noop.whoop.staging",
            notificationKey = "key_wh_1",
            notificationId = 4201,
            tag = null,
            senderHint = "WHOOP",
            title = "Connected to your WHOOP",
            textFragments = listOf("Streaming in the background"),
            rawTextDigest = "digest_wh",
            observedAt = UtcInstant(1_700_000_000_000L),
            postTime = UtcInstant(1_700_000_000_000L),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = true,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )

        store.persistObservation(whoopObs)
        val summaries = store.getConversationSummaries(whoopAccount)
        assertEquals(1, summaries.size)
        assertEquals("WHOOP", summaries.first().appName)
        assertEquals(NotificationCategory.OngoingService, summaries.first().category)
    }
}
