package app.mini.hub.android.notification

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.BoundedIngestQueue
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.IngestOfferResult
import app.mini.hub.observation.NotificationDeduplicator
import app.mini.hub.observation.NotificationNormalizer
import app.mini.hub.observation.NotificationRawPayload
import app.mini.hub.observation.ObservationIngestCoordinator
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadlessNotificationLifecycleTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    @Test
    fun testHeadlessBackgroundIngestionWithoutUi() {
        val store = InMemoryObservationStore()
        val queue = BoundedIngestQueue<NotificationRawPayload>(capacity = 100)
        val normalizer = NotificationNormalizer(idGen, digest)
        val deduplicator = NotificationDeduplicator()

        val coordinator = ObservationIngestCoordinator(
            queue = queue,
            normalizer = normalizer,
            deduplicator = deduplicator,
            store = store,
            clock = clock,
        )

        // Simulate 5 notifications arriving from background OS service without UI
        for (i in 1..5) {
            val payload = NotificationRawPayload(
                key = "wa_bg_key_$i",
                packageId = "com.whatsapp",
                notificationId = i,
                postTimeMillis = 1_700_000_000_000L + i * 1000L,
                senderHint = "Alice",
                title = "Alice",
                text = "Background message $i",
            )
            val res = coordinator.enqueue(payload)
            assertEquals(IngestOfferResult.Accepted, res)
        }

        assertEquals(5, queue.size)

        // Background worker drains
        val processed = coordinator.drainAndProcessAll(10)
        assertEquals(5, processed)
        assertEquals(0, queue.size)

        // Verify observations in store
        val convs = store.getConversationSummaries()
        assertEquals(1, convs.size)
        assertEquals(5, convs.first().observationCount)
        assertEquals("Background message 5", convs.first().latestSnippet)
    }

    @Test
    fun testQueueOverflowRecordsGapsInCursorAndExplanation() {
        val store = InMemoryObservationStore()
        val smallQueue = BoundedIngestQueue<NotificationRawPayload>(capacity = 3)
        val normalizer = NotificationNormalizer(idGen, digest)
        val deduplicator = NotificationDeduplicator()

        val coordinator = ObservationIngestCoordinator(
            queue = smallQueue,
            normalizer = normalizer,
            deduplicator = deduplicator,
            store = store,
            clock = clock,
        )

        // Enqueue 3 items -> Accepted
        for (i in 1..3) {
            coordinator.enqueue(
                NotificationRawPayload(
                    key = "msg_$i",
                    packageId = "com.whatsapp",
                    notificationId = i,
                    postTimeMillis = 1000L * i,
                    senderHint = "Sender",
                    text = "Text $i",
                )
            )
        }

        // 4th and 5th items overflow queue -> triggers recordDroppedGap
        val res4 = coordinator.enqueue(
            NotificationRawPayload(
                key = "msg_4",
                packageId = "com.whatsapp",
                notificationId = 4,
                postTimeMillis = 4000L,
                senderHint = "Sender",
                text = "Text 4",
            )
        )
        val res5 = coordinator.enqueue(
            NotificationRawPayload(
                key = "msg_5",
                packageId = "com.whatsapp",
                notificationId = 5,
                postTimeMillis = 5000L,
                senderHint = "Sender",
                text = "Text 5",
            )
        )

        assertEquals(IngestOfferResult.QueueFull, res4)
        assertEquals(IngestOfferResult.QueueFull, res5)

        // Drain pending queue
        coordinator.drainAndProcessAll(10)

        // Verify gap count is recorded in cursor
        val cursor = store.getCursor("com.whatsapp")
        assertNotNull(cursor)
        assertEquals(2L, cursor.gapCount)

        // Ingest one more message to check updated coverageExplanation
        coordinator.enqueue(
            NotificationRawPayload(
                key = "msg_6",
                packageId = "com.whatsapp",
                notificationId = 6,
                postTimeMillis = 6000L,
                senderHint = "Sender",
                text = "Text 6",
            )
        )
        coordinator.drainAndProcessAll(10)

        val conv = store.getConversationSummaries().first()
        assertTrue(conv.coverageExplanation.contains("2 dropped events"))
    }

    @Test
    fun testReactiveUiListenerDispatchesOnBackgroundIngest() {
        val store = InMemoryObservationStore()
        val queue = BoundedIngestQueue<NotificationRawPayload>(capacity = 10)
        val normalizer = NotificationNormalizer(idGen, digest)
        val deduplicator = NotificationDeduplicator()

        val coordinator = ObservationIngestCoordinator(
            queue = queue,
            normalizer = normalizer,
            deduplicator = deduplicator,
            store = store,
            clock = clock,
        )

        var notifiedSummary: ConversationObservationSummary? = null
        coordinator.setUiListener { summary ->
            notifiedSummary = summary
        }

        coordinator.enqueue(
            NotificationRawPayload(
                key = "reactive_key_1",
                packageId = "com.whatsapp",
                notificationId = 101,
                postTimeMillis = 1000L,
                senderHint = "Charlie",
                title = "Team Sync",
                text = "Status update ready",
            )
        )

        coordinator.processNext()

        assertNotNull(notifiedSummary)
        assertEquals("Team Sync", notifiedSummary!!.title)
        assertEquals("Status update ready", notifiedSummary!!.latestSnippet)
    }
}
