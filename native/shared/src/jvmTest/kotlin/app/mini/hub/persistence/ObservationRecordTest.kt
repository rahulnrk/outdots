package app.mini.hub.persistence

import app.mini.hub.action.DigestProvider
import app.mini.hub.connector.ConnectorObservationFactory
import app.mini.hub.connector.MessageDirection
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.observation.isConversationSummary
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.retrieval.ScopedRetrievalQuery
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 2 T2: stored messages keep direction, recipients and reply anchor; timelines order by event time. */
class ObservationRecordTest {
    private val account = AccountId(Provider.GMAIL, "acct-a")
    private val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread-1")
    private val factory = ConnectorObservationFactory(DigestProvider { "d-${it.hashCode()}" })

    private fun stores(): List<Pair<String, ObservationStore>> = listOf(
        "memory" to InMemoryObservationStore(),
        "jdbc" to JdbcObservationStore(File.createTempFile("mini-obs", ".db").apply { delete(); deleteOnExit() }.path),
    )

    private fun msg(id: String, at: Long, dir: MessageDirection = MessageDirection.Inbound, deleted: Boolean = false) = ProviderMessage(
        providerMessageId = id, conversationId = "thread-1", sender = if (dir == MessageDirection.Outbound) "me@example.com" else "bob@example.com",
        subject = "Plan", snippet = "body $id", occurredAtMillis = at, direction = dir, isDeleted = deleted,
        replyAnchor = "<$id@mail>", recipients = listOf("x@example.com", "y@example.com"),
    )

    @Test(timeout = 30_000)
    fun providerFactsSurviveFactoryAndStore() {
        for ((name, store) in stores()) {
            store.persistObservation(factory.fromMessage(account, msg("m1", 1_000, MessageDirection.Outbound), observedAtMillis = 5_000))
            val back = store.getObservationsForConversation(conv).single()
            assertEquals(MessageDirection.Outbound, back.direction, name)
            assertEquals(listOf("x@example.com", "y@example.com"), back.recipients, name)
            assertEquals("<m1@mail>", back.replyAnchor, name)
        }
    }

    @Test(timeout = 30_000)
    fun notificationRowsKeepUnknownAsNull() {
        for ((name, store) in stores()) {
            store.persistObservation(notification("n1", post = 10, observed = 10))
            val back = store.getObservationsForConversation(conv).single()
            assertNull(back.direction, name); assertNull(back.recipients, name); assertNull(back.replyAnchor, name)
        }
    }

    @Test(timeout = 30_000)
    fun outOfOrderBackfillIsOrderedByProviderEventTime() {
        for ((name, store) in stores()) {
            // Newest message fetched first; an older message arrives later in a backfill.
            store.persistObservation(factory.fromMessage(account, msg("new", at = 3_000), observedAtMillis = 10_000))
            store.persistObservation(factory.fromMessage(account, msg("old", at = 1_000), observedAtMillis = 20_000))
            store.persistObservation(factory.fromMessage(account, msg("mid", at = 2_000), observedAtMillis = 30_000))
            assertEquals(listOf("new", "mid", "old"), store.getObservationsForConversation(conv).map { it.notificationKey.substringAfterLast(':') }, name)
        }
    }

    @Test(timeout = 30_000)
    fun identicalTimestampsHaveStableOrder() {
        for ((name, store) in stores()) {
            listOf("b", "c", "a").forEach { store.persistObservation(factory.fromMessage(account, msg(it, at = 1_000), observedAtMillis = 1_000)) }
            val first = store.getObservationsForConversation(conv).map { it.id.key }
            repeat(3) { assertEquals(first, store.getObservationsForConversation(conv).map { it.id.key }, name) }
            assertEquals(first.sortedDescending(), first, "$name: ties break by id")
        }
    }

    @Test(timeout = 30_000)
    fun deletedMessageIsStoredButNotRetrieved() {
        for ((name, store) in stores()) {
            store.persistObservation(factory.fromMessage(account, msg("keep", at = 1_000), observedAtMillis = 1_000))
            store.persistObservation(factory.fromMessage(account, msg("gone", at = 2_000, deleted = true), observedAtMillis = 2_000))
            assertEquals(2, store.getObservationsForConversation(conv).size, "$name: still stored")
            val retrieved = ScopedRetrievalEngine(store).retrieve(ScopedRetrievalQuery(conv, CompartmentId(account.key)))
            assertEquals(listOf("provider:${account.key}:keep"), retrieved.retrievedObservations.map { it.notificationKey }, name)
        }
    }

    @Test(timeout = 30_000)
    fun sentMessageKeepsOutboundDirection() {
        for ((name, store) in stores()) {
            store.persistObservation(factory.fromMessage(account, msg("in", at = 1_000), observedAtMillis = 1_000))
            store.persistObservation(factory.fromMessage(account, msg("out", at = 2_000, dir = MessageDirection.Outbound), observedAtMillis = 2_000))
            val byKey = store.getObservationsForConversation(conv).associateBy { it.notificationKey.substringAfterLast(':') }
            assertEquals(MessageDirection.Outbound, byKey.getValue("out").direction, name)
            assertEquals(MessageDirection.Inbound, byKey.getValue("in").direction, name)
        }
    }

    @Test(timeout = 30_000)
    fun version2DatabaseMigratesInPlaceKeepingRows() {
        val file = File.createTempFile("mini-v2", ".db").apply { delete(); deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(ObservationSchema.CREATE_OBSERVATIONS_V1)
                s.execute(ObservationSchema.CREATE_CONVERSATIONS_V1)
                s.execute(ObservationSchema.CREATE_CURSORS_V1)
                s.execute(
                    "INSERT INTO notification_observations VALUES ('${notification("old", 5, 5).id.key}','${account.key}','${conv.key}'," +
                        "'com.google.android.gm','k-old',1,NULL,'Bob','Plan','hello','dg',5,5,1,0,0,'Complete','High','AndroidNotificationListener')",
                )
                s.execute("PRAGMA user_version = 2")
            }
        }
        val store = JdbcObservationStore(file.path)
        assertEquals(ObservationSchema.VERSION, store.schemaVersion())
        val old = store.getObservationsForConversation(conv).single()
        assertEquals("k-old", old.notificationKey)
        assertNull(old.direction); assertNull(old.recipients); assertNull(old.replyAnchor)

        store.persistObservation(factory.fromMessage(account, msg("new", at = 10, dir = MessageDirection.Outbound), observedAtMillis = 10))
        val reopened = JdbcObservationStore(file.path)
        assertEquals(2, reopened.getObservationsForConversation(conv).size)
        assertTrue(reopened.getObservationsForConversation(conv).any { it.direction == MessageDirection.Outbound && it.replyAnchor == "<new@mail>" })
    }

    @Test(timeout = 30_000)
    fun notificationCategorySurvivesJdbcCloseReopenAndSummaryRebuild() {
        val file = File.createTempFile("mini-cat", ".db").apply { delete(); deleteOnExit() }
        val ig = AccountId(Provider.INSTAGRAM, "local")
        val igConv = ScopedId(ig, ScopedId.Kind.CONVERSATION, "Sam")
        val message = ObservedNotification(
            id = ScopedId(ig, ScopedId.Kind.OBSERVATION, "obs-sam"),
            accountId = ig,
            conversationId = igConv,
            packageId = "com.instagram.android",
            notificationKey = "k-sam",
            notificationId = 7,
            tag = null,
            senderHint = "Sam",
            title = "Sam",
            textFragments = listOf("I liked your post, can you send the file?"),
            rawTextDigest = "dg-sam",
            observedAt = UtcInstant(20),
            postTime = UtcInstant(20),
            revision = 1,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.DirectMessage,
        )
        JdbcObservationStore(file.path).persistObservation(message)
        val reopened = JdbcObservationStore(file.path)
        assertEquals(ObservationSchema.VERSION, reopened.schemaVersion())
        val back = reopened.getObservationsForConversation(igConv).single()
        assertEquals(NotificationCategory.DirectMessage, back.notificationCategory)
        val summary = reopened.getConversationSummaries(ig).single()
        assertEquals(NotificationCategory.DirectMessage, summary.category)
        assertTrue(isConversationSummary(summary))
    }

    @Test(timeout = 30_000)
    fun jdbcReopenReconcilesStoredMisclassifiedDirectMessageRecommendation() {
        val file = File.createTempFile("mini-reconcile", ".db").apply { delete(); deleteOnExit() }
        val fb = AccountId(Provider.FACEBOOK, "local")
        val fbConv = ScopedId(fb, ScopedId.Kind.CONVERSATION, "rec_conv")
        val misclassified = ObservedNotification(
            id = ScopedId(fb, ScopedId.Kind.OBSERVATION, "obs-rec"),
            accountId = fb,
            conversationId = fbConv,
            packageId = "com.facebook.katana",
            notificationKey = "k-rec",
            notificationId = 88,
            tag = null,
            senderHint = "Example Person",
            title = "Example Person",
            textFragments = listOf("You might like Example Person."),
            rawTextDigest = "dg-rec",
            observedAt = UtcInstant(30),
            postTime = UtcInstant(30),
            revision = 1,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.DirectMessage, // Stored incorrectly as DirectMessage
        )
        val store = JdbcObservationStore(file.path)
        store.persistObservation(misclassified)

        // Close and reopen database
        val reopened = JdbcObservationStore(file.path)
        val storedObs = reopened.getObservationsForConversation(fbConv).single()
        // Original observation record is preserved
        assertEquals("You might like Example Person.", storedObs.textFragments.single())

        // But summary projection / conversation eligibility reconciles it out of conversation summaries
        val summaries = reopened.getConversationSummaries(fb)
        val conversationSummaries = summaries.filter { isConversationSummary(it) }
        assertTrue(conversationSummaries.isEmpty(), "Misclassified recommendation must be excluded from conversation projections after reopen")
    }

    @Test(timeout = 30_000)
    fun jdbcSearchExcludesGeneralNotificationAndPreservesGenuineMessage() {
        val file = File.createTempFile("mini-search", ".db").apply { delete(); deleteOnExit() }
        val acct = AccountId(Provider.WHATSAPP, "local")
        val alertConv = ScopedId(acct, ScopedId.Kind.CONVERSATION, "alert_conv")
        val msgConv = ScopedId(acct, ScopedId.Kind.CONVERSATION, "msg_conv")

        val generalAlert = ObservedNotification(
            id = ScopedId(acct, ScopedId.Kind.OBSERVATION, "obs-alert"),
            accountId = acct,
            conversationId = alertConv,
            packageId = "com.example.alert",
            notificationKey = "k-alert",
            notificationId = 10,
            tag = null,
            senderHint = "System",
            title = "Status update",
            textFragments = listOf("Service status operational"),
            rawTextDigest = "dg-alert",
            observedAt = UtcInstant(40),
            postTime = UtcInstant(40),
            revision = 1,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.GeneralNotification,
        )

        val genuineMsg = ObservedNotification(
            id = ScopedId(acct, ScopedId.Kind.OBSERVATION, "obs-msg"),
            accountId = acct,
            conversationId = msgConv,
            packageId = "com.whatsapp",
            notificationKey = "k-msg",
            notificationId = 11,
            tag = null,
            senderHint = "Alice",
            title = "Alice",
            textFragments = listOf("Service status operational discussion"),
            rawTextDigest = "dg-msg",
            observedAt = UtcInstant(50),
            postTime = UtcInstant(50),
            revision = 1,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.DirectMessage,
        )

        val store = JdbcObservationStore(file.path)
        store.persistBatch(listOf(generalAlert, genuineMsg))

        val results = store.search(app.mini.hub.search.SearchQuery(queryText = "operational"))
        assertEquals(1, results.size)
        assertEquals(msgConv, results.single().conversationId)
    }

    private fun notification(key: String, post: Long, observed: Long) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-$key"), accountId = account, conversationId = conv,
        packageId = "com.google.android.gm", notificationKey = "k-$key", notificationId = 1, tag = null,
        senderHint = "Bob", title = "Plan", textFragments = listOf("hello"), rawTextDigest = "dg",
        observedAt = UtcInstant(observed), postTime = UtcInstant(post), revision = 1, isRemoved = false,
        isGroupSummary = false, truncationState = TruncationState.Complete, correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.AndroidNotificationListener,
    )
}
