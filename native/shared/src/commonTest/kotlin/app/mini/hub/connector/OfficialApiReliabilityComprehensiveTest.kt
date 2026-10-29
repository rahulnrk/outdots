package app.mini.hub.connector

import app.mini.hub.action.*
import app.mini.hub.domain.*
import app.mini.hub.observation.*
import app.mini.hub.persistence.*
import app.mini.hub.search.SearchQuery
import java.io.File
import org.junit.Test
import kotlin.test.*

class OfficialApiReliabilityComprehensiveTest {
    private val digest = DigestProvider { "hash-$it" }

    @Test
    fun testApprovalExpiryOrRevocationWhileWaitingForAccountOwnership() {
        var now = 1_700_000_000_000L
        val clock = Clock { UtcInstant(now) }
        val controller = ActionController(clock, digest, ActionIdGenerator { "id-$it" })
        val accountId = AccountId(Provider.GMAIL, "g-own")
        val payload = ExactActionPayload(
            accountId = accountId.key,
            conversationId = "c1",
            route = ActionRoute.Api,
            recipients = listOf("r@example.com"),
            subject = null,
            body = "Hello",
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = "g1",
            policyVersion = "p1",
        )
        val (state, command) = controller.approve(ActionState(), payload)

        // Advance time past approval lifetime (120s) while waiting for account ownership/lock
        now += 120_001L
        val checks = LiveDispatchChecks("g1", "p1", emptyList())
        val begun = controller.beginDispatch(state, command.id, payload, checks)
        val outboxItem = begun.outbox.single { it.id == command.id }
        assertEquals(OutboxState.ExpiredOrInvalidated, outboxItem.state)
        assertEquals("approval_expired_or_consumed", outboxItem.failureReason)
    }

    @Test
    fun testRetryAfterPersistsThroughStoreReopen() {
        val file = File.createTempFile("retry-persist", ".db").apply { delete(); deleteOnExit() }
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "g-retry"), "retry@gmail.com", "Retry",
            AccountType.GoogleConsumer, ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy
        )
        val store = JdbcObservationStore(file.path)
        val now = 1_700_000_000_000L
        store.checkpointCursor(IngestCursor(source = "retry", lastProcessedTimeMillis = now, lastProcessedKey = null, processedCount = 0, gapCount = 0, nextAllowedAtMillis = now + 3_600_000L, retryAttempt = 0))
        val cursor = store.getCursor("retry")
        assertNotNull(cursor)
        assertTrue(cursor.nextAllowedAtMillis >= now + 3_600_000L)
        store.connection().close()

        // Reopen store and verify admission block persists
        val reopened = JdbcObservationStore(file.path)
        val reopenedCursor = reopened.getCursor("retry")
        assertNotNull(reopenedCursor)
        assertEquals(cursor.nextAllowedAtMillis, reopenedCursor.nextAllowedAtMillis)
        reopened.connection().close()
    }

    @Test
    fun testApiMessagesAndNotificationsFromSameAccountMergeByReliableIdentityWithoutDiscardingHistory() {
        val store = InMemoryObservationStore()
        val acc = AccountId(Provider.GMAIL, "g-merge")
        val convId = ScopedId(acc, ScopedId.Kind.CONVERSATION, "thread-100")

        val apiObs = ObservedNotification(
            id = ScopedId(acc, ScopedId.Kind.OBSERVATION, "api-1"),
            accountId = acc,
            conversationId = convId,
            packageId = "com.google.android.gm",
            notificationKey = "provider:${acc.key}:m100",
            notificationId = 100,
            tag = null,
            senderHint = "Boss",
            title = "Boss",
            textFragments = listOf("API message body"),
            rawTextDigest = "d-api",
            observedAt = UtcInstant(1000),
            postTime = UtcInstant(1000),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.OfficialProviderApi,
            notificationCategory = NotificationCategory.DirectMessage,
            messagingEvidence = true,
        )
        val notifObs = ObservedNotification(
            id = ScopedId(acc, ScopedId.Kind.OBSERVATION, "notif-1"),
            accountId = acc,
            conversationId = convId,
            packageId = "com.google.android.gm",
            notificationKey = "0|com.google.android.gm|100|thread-100|1000",
            notificationId = 100,
            tag = null,
            senderHint = "Boss",
            title = "Boss",
            textFragments = listOf("Notification banner preview"),
            rawTextDigest = "d-notif",
            observedAt = UtcInstant(1000),
            postTime = UtcInstant(1000),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.DirectMessage,
            messagingEvidence = true,
        )

        store.persistObservation(apiObs)
        store.persistObservation(notifObs)

        val observations = store.getObservationsForConversation(convId)
        assertEquals(2, observations.size, "both API ingestion and notification observations are preserved with distinct provenances, never discarded by text match guesses")
        assertTrue(observations.any { it.provenance == ObservationProvenance.OfficialProviderApi })
        assertTrue(observations.any { it.provenance == ObservationProvenance.AndroidNotificationListener })
    }
}
