package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservationPipelineTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private val idGen = IdGenerator { prefix -> "$prefix-${counter++}" }
    private var counter = 1
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    private fun createCoordinator(store: InMemoryObservationStore = InMemoryObservationStore(), capacity: Int = 256): ObservationIngestCoordinator {
        val normalizer = NotificationNormalizer(idGen, digest)
        val deduplicator = NotificationDeduplicator()
        return ObservationIngestCoordinator(
            queue = BoundedIngestQueue(capacity),
            normalizer = normalizer,
            deduplicator = deduplicator,
            store = store,
            clock = clock,
        )
    }

    @Test
    fun test1000SyntheticEventsIngestionAndPersistence() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store, capacity = 1024)

        val startTime = 1_700_000_000_000L
        for (i in 1..1000) {
            val pkg = when (i % 4) {
                0 -> "com.whatsapp"
                1 -> "com.google.android.gm"
                2 -> "com.instagram.android"
                else -> "com.slack"
            }
            val payload = NotificationRawPayload(
                key = "${pkg}_key_$i",
                packageId = pkg,
                notificationId = i,
                tag = "chat_${i % 50}",
                postTimeMillis = startTime + i * 1000L,
                senderHint = "Sender $i",
                title = "Conversation ${i % 50}",
                text = "Synthetic message body for event $i",
            )
            val res = coordinator.enqueue(payload)
            assertEquals(IngestOfferResult.Accepted, res)
        }

        assertEquals(1000, coordinator.queue.size)
        val processed = coordinator.drainAndProcessAll(maxBatch = 1000)
        assertEquals(1000, processed)
        assertEquals(0, coordinator.queue.size)

        val recovery = store.recoverFromRestart()
        assertEquals(1000, recovery.totalObservations)
        assertTrue(recovery.totalConversations in 1..200)

        // Verify conversations have correct coverage
        val conversations = coordinator.getRecentConversations()
        assertTrue(conversations.isNotEmpty())
        conversations.forEach { conv ->
            assertEquals(ConversationCoverage.NotificationOnly, conv.coverage)
            assertTrue(conv.coverageExplanation.contains("notifications only"))
        }
    }

    @Test
    fun testExactDuplicateDroppedIdempotently() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store)

        val payload = NotificationRawPayload(
            key = "wa_msg_100",
            packageId = "com.whatsapp",
            notificationId = 100,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Alice",
            title = "Project Chat",
            text = "Are you available for the sync?",
        )

        coordinator.enqueue(payload)
        val res1 = coordinator.processNext()
        assertTrue(res1 is IngestProcessResult.Created)

        // Post exact duplicate
        coordinator.enqueue(payload)
        val res2 = coordinator.processNext()
        assertTrue(res2 is IngestProcessResult.DuplicateIgnored)
        assertEquals("wa_msg_100", res2.notificationKey)

        // Total observations in store must be 1
        assertEquals(1, store.recoverFromRestart().totalObservations)
    }

    @Test
    fun testIdenticalContentWithNewerNotificationTimestampIsUpdate() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store)

        val first = NotificationRawPayload(
            key = "wa_repost_timestamp",
            packageId = "com.whatsapp",
            notificationId = 110,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Alice",
            title = "Project Chat",
            text = "Same content",
        )
        val repost = first.copy(postTimeMillis = first.postTimeMillis + 1_000L)

        coordinator.enqueue(first)
        assertTrue(coordinator.processNext() is IngestProcessResult.Created)

        coordinator.enqueue(repost)
        val result = coordinator.processNext()
        assertTrue(result is IngestProcessResult.Updated,
            "A repost with a newer OS post timestamp must be an Update, not a Drop")
        assertEquals(2L, result.observation.revision)
        assertEquals(repost.postTimeMillis, result.observation.postTime.epochMillis)
        assertEquals(1, store.recoverFromRestart().totalObservations)
    }

    @Test
    fun testDistinctMessagesWithIdenticalTextPreserved() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store)

        // Two distinct messages sent at different times with the same text "Hello"
        val payload1 = NotificationRawPayload(
            key = "wa_msg_201",
            packageId = "com.whatsapp",
            notificationId = 201,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Bob",
            title = "Bob",
            text = "Hello",
        )
        val payload2 = NotificationRawPayload(
            key = "wa_msg_202",
            packageId = "com.whatsapp",
            notificationId = 202,
            postTimeMillis = 1_700_000_005_000L,
            senderHint = "Bob",
            title = "Bob",
            text = "Hello",
        )

        coordinator.enqueue(payload1)
        coordinator.enqueue(payload2)

        val res1 = coordinator.processNext()
        val res2 = coordinator.processNext()

        assertTrue(res1 is IngestProcessResult.Created)
        assertTrue(res2 is IngestProcessResult.Created)
        assertEquals(2, store.recoverFromRestart().totalObservations)
    }

    @Test
    fun testNotificationUpdateIncrementsRevision() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store)

        val initial = NotificationRawPayload(
            key = "wa_update_key",
            packageId = "com.whatsapp",
            notificationId = 301,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Charlie",
            title = "Design Discussion",
            text = "Drafting specs",
        )
        val updated = NotificationRawPayload(
            key = "wa_update_key",
            packageId = "com.whatsapp",
            notificationId = 301,
            postTimeMillis = 1_700_000_010_000L,
            senderHint = "Charlie",
            title = "Design Discussion",
            text = "Drafting specs, please review",
            textFragments = listOf("Drafting specs", "Drafting specs, please review"),
        )

        coordinator.enqueue(initial)
        val res1 = coordinator.processNext()
        assertTrue(res1 is IngestProcessResult.Created)
        assertEquals(1L, res1.observation.revision)

        coordinator.enqueue(updated)
        val res2 = coordinator.processNext()
        assertTrue(res2 is IngestProcessResult.Updated)
        assertEquals(1L, res2.priorRevision)
        assertEquals(2L, res2.observation.revision)
        assertEquals(listOf("Drafting specs", "Drafting specs, please review"), res2.observation.textFragments)
    }

    @Test
    fun testNotificationRemovalHandlesDismissal() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store)

        val post = NotificationRawPayload(
            key = "dismiss_key_1",
            packageId = "com.whatsapp",
            notificationId = 401,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Dave",
            title = "Direct Message",
            text = "Quick question",
        )
        val removal = NotificationRawPayload(
            key = "dismiss_key_1",
            packageId = "com.whatsapp",
            notificationId = 401,
            postTimeMillis = 1_700_000_005_000L,
            senderHint = "Dave",
            text = "",
            isRemoved = true,
        )

        coordinator.enqueue(post)
        coordinator.processNext()

        coordinator.enqueue(removal)
        val removeRes = coordinator.processNext()
        assertTrue(removeRes is IngestProcessResult.Removed)
        assertEquals("dismiss_key_1", removeRes.notificationKey)
        assertEquals(1, removeRes.count)

        // Historical record is kept but marked removed
        val obs = store.getObservationsForConversation(ScopedId(AccountId(Provider.WHATSAPP, "local_user"), ScopedId.Kind.CONVERSATION, "Direct_Message"))
        assertEquals(1, obs.size)
        assertTrue(obs.first().isRemoved)
    }

    @Test
    fun testTruncatedNotificationState() {
        val normalizer = NotificationNormalizer(idGen, digest)
        val payload = NotificationRawPayload(
            key = "trunc_key_1",
            packageId = "com.google.android.gm",
            notificationId = 501,
            postTimeMillis = 1_700_000_000_000L,
            senderHint = "Newsletter",
            title = "Weekly Digest",
            text = "Here is the top summary for this week…",
        )
        val obs = normalizer.normalize(payload, UtcInstant(1_700_000_000_000L))
        assertEquals(TruncationState.Truncated, obs.truncationState)
        assertEquals(Provider.GMAIL, obs.accountId.provider)
    }

    @Test
    fun testBoundedQueueRejectsOverflow() {
        val queue = BoundedIngestQueue<NotificationRawPayload>(capacity = 5)
        for (i in 1..5) {
            val res = queue.offer(
                NotificationRawPayload(
                    key = "k_$i",
                    packageId = "com.whatsapp",
                    notificationId = i,
                    postTimeMillis = 1000L * i,
                    senderHint = "User",
                    text = "Text",
                )
            )
            assertEquals(IngestOfferResult.Accepted, res)
        }
        // 6th offer must be rejected with QueueFull without crashing
        val overflowRes = queue.offer(
            NotificationRawPayload(
                key = "k_6",
                packageId = "com.whatsapp",
                notificationId = 6,
                postTimeMillis = 6000L,
                senderHint = "User",
                text = "Text",
            )
        )
        assertEquals(IngestOfferResult.QueueFull, overflowRes)
        assertEquals(5, queue.size)
    }

    @Test
    fun testAppLabelAndProviderResolution() {
        val normalizer = NotificationNormalizer(idGen, digest)

        assertEquals("X", normalizer.deriveHumanAppName("com.twitter.android", null))
        assertEquals("WHOOP", normalizer.deriveHumanAppName("com.noop.whoop.staging", null))
        assertEquals("WhatsApp", normalizer.deriveHumanAppName("com.whatsapp", null))
        assertEquals("Gmail", normalizer.deriveHumanAppName("com.google.android.gm", null))
        assertEquals("Android System", normalizer.deriveHumanAppName("android", null))

        // Custom app label from PackageManager takes precedence
        assertEquals("Custom App Title", normalizer.deriveHumanAppName("com.custom.app", "Custom App Title"))

        assertEquals(Provider.TWITTER, normalizer.mapPackageToProvider("com.twitter.android"))
        assertEquals(Provider.WHOOP, normalizer.mapPackageToProvider("com.noop.whoop.staging"))
        assertEquals(Provider.SYSTEM, normalizer.mapPackageToProvider("android"))
        assertEquals(Provider.SLACK, normalizer.mapPackageToProvider("com.Slack"))
    }

    @Test
    fun testNotificationCategoryClassification() {
        val normalizer = NotificationNormalizer(idGen, digest)

        val dmPayload = NotificationRawPayload(
            key = "dm_1",
            packageId = "com.whatsapp",
            notificationId = 1,
            postTimeMillis = 1000L,
            senderHint = "Alice",
            text = "Hello",
            category = "msg",
        )
        assertEquals(NotificationCategory.DirectMessage, normalizer.deriveNotificationCategory(dmPayload))

        val groupPayload = NotificationRawPayload(
            key = "grp_1",
            packageId = "com.whatsapp",
            notificationId = 2,
            postTimeMillis = 2000L,
            senderHint = "Dev Team",
            text = "3 new messages",
            category = "msg",
            isGroupSummary = true,
        )
        assertEquals(NotificationCategory.GroupMessage, normalizer.deriveNotificationCategory(groupPayload))

        val socialPayload = NotificationRawPayload(
            key = "soc_1",
            packageId = "com.twitter.android",
            notificationId = 3,
            postTimeMillis = 3000L,
            senderHint = "X",
            text = "New tweet from @developer",
            category = "social",
        )
        assertEquals(NotificationCategory.SocialUpdate, normalizer.deriveNotificationCategory(socialPayload))

        val servicePayload = NotificationRawPayload(
            key = "srv_1",
            packageId = "com.noop.whoop.staging",
            notificationId = 4,
            postTimeMillis = 4000L,
            senderHint = "WHOOP",
            text = "Streaming in background",
            isOngoing = true,
        )
        assertEquals(NotificationCategory.OngoingService, normalizer.deriveNotificationCategory(servicePayload))
    }

    @Test
    fun testCrashAndRestartRecovery() {
        val store = InMemoryObservationStore()
        val coordinator = createCoordinator(store = store, capacity = 1000)

        // Phase 1: Ingest 500 events
        for (i in 1..500) {
            coordinator.enqueue(
                NotificationRawPayload(
                    key = "restart_key_$i",
                    packageId = "com.whatsapp",
                    notificationId = i,
                    postTimeMillis = 1000L * i,
                    senderHint = "User $i",
                    title = "Chat $i",
                    text = "Message $i",
                )
            )
        }
        coordinator.drainAndProcessAll(500)

        // Verify Phase 1 recovery snapshot
        val report1 = store.recoverFromRestart()
        assertEquals(500, report1.totalObservations)
        assertNotNull(report1.lastCursor)
        assertEquals(500L, report1.lastCursor?.processedCount)

        // Phase 2: Ingest next 500 events after simulated restart
        for (i in 501..1000) {
            coordinator.enqueue(
                NotificationRawPayload(
                    key = "restart_key_$i",
                    packageId = "com.whatsapp",
                    notificationId = i,
                    postTimeMillis = 1000L * i,
                    senderHint = "User $i",
                    title = "Chat $i",
                    text = "Message $i",
                )
            )
        }
        coordinator.drainAndProcessAll(500)

        val report2 = store.recoverFromRestart()
        assertEquals(1000, report2.totalObservations)
        assertEquals(1000L, report2.lastCursor?.processedCount)
    }
}
