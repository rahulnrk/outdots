package app.mini.hub.search

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.persistence.JdbcObservationStore
import app.mini.hub.persistence.ObservationStore
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedSearchTest {
    private val acctGmail = AccountId(Provider.GMAIL, "work")
    private val acctSlack = AccountId(Provider.SLACK, "team")

    private fun stores(): List<Pair<String, ObservationStore>> = listOf(
        "memory" to InMemoryObservationStore(),
        "jdbc" to JdbcObservationStore(File.createTempFile("mini-search", ".db").apply { delete(); deleteOnExit() }.path),
    )

    private fun makeObs(
        account: AccountId,
        convKey: String,
        id: String,
        title: String,
        text: String,
        pkg: String,
        postAt: Long = 1_700_000_000_000L,
        isRemoved: Boolean = false,
    ) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_$id"),
        accountId = account,
        conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, convKey),
        packageId = pkg,
        notificationKey = "key_$id",
        notificationId = id.hashCode(),
        tag = null,
        senderHint = "Sender_$id",
        title = title,
        textFragments = listOf(text),
        rawTextDigest = "hash_$id",
        observedAt = UtcInstant(postAt),
        postTime = UtcInstant(postAt),
        revision = 1L,
        isRemoved = isRemoved,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.OfficialProviderApi,
    )

    @Test(timeout = 30_000)
    fun searchReturnsResultsFromTwoAccountsGroupedByConversation() {
        for ((name, store) in stores()) {
            store.persistObservation(makeObs(acctGmail, "conv_g", "1", "Project Alpha", "Budget spreadsheet is ready", "com.google.android.gm"))
            store.persistObservation(makeObs(acctSlack, "conv_s", "2", "General", "Review project alpha deliverables", "com.slack"))

            val results = store.search(SearchQuery("alpha"))
            assertEquals(2, results.size, "$name: must find results in both accounts")
            val accountsFound = results.map { it.accountId }.toSet()
            assertTrue(acctGmail in accountsFound, "$name: Gmail account present")
            assertTrue(acctSlack in accountsFound, "$name: Slack account present")
            results.forEach {
                assertTrue(it.packageId.isNotBlank(), "$name: packageId shown")
                assertTrue(it.conversationTitle.isNotBlank(), "$name: title shown")
            }
        }
    }

    @Test(timeout = 30_000)
    fun sourceFilterNarrowsResults() {
        for ((name, store) in stores()) {
            store.persistObservation(makeObs(acctGmail, "conv_g", "10", "Quarterly Plan", "Q3 targets", "com.google.android.gm"))
            store.persistObservation(makeObs(acctSlack, "conv_s", "11", "Planning Channel", "Q3 targets discussed", "com.slack"))

            val filtered = store.search(SearchQuery("targets", sourceFilter = "com.slack"))
            assertEquals(1, filtered.size, "$name: only filtered source returned")
            assertEquals("com.slack", filtered.single().packageId, name)
        }
    }

    @Test(timeout = 30_000)
    fun removedMessagesNeverAppearInSearchResults() {
        for ((name, store) in stores()) {
            store.persistObservation(makeObs(acctGmail, "conv_live", "20", "Live Meeting", "Confidential notes", "com.google.android.gm", isRemoved = false))
            store.persistObservation(makeObs(acctGmail, "conv_del", "21", "Deleted Meeting", "Confidential notes deleted", "com.google.android.gm", isRemoved = true))

            val results = store.search(SearchQuery("confidential"))
            assertEquals(1, results.size, "$name: removed messages must not appear")
            assertEquals("conv_live", results.single().conversationId.value, name)
        }
    }

    @Test(timeout = 30_000)
    fun performance10000MessagesAnsweredInUnder300MsOnJdbcStore() {
        val dbFile = File.createTempFile("mini-perf-search", ".db").apply { delete(); deleteOnExit() }
        val store = JdbcObservationStore(dbFile.path)

        // Seed 10,000 messages across 1,000 conversations
        val batch = mutableListOf<ObservedNotification>()
        for (i in 1..10_000) {
            val convIdx = i % 1000
            val hasKeyword = (i == 42 || i == 5000 || i == 9999)
            val text = if (hasKeyword) "Special kaleidoscope finding here" else "Routine update number $i about daily activity"
            batch.add(
                makeObs(
                    account = acctGmail,
                    convKey = "conv_$convIdx",
                    id = "msg_$i",
                    title = "Thread $convIdx",
                    text = text,
                    pkg = "com.google.android.gm",
                    postAt = 1_700_000_000_000L + i,
                )
            )
        }
        store.persistBatch(batch)

        // Benchmark query time
        val elapsedMs = measureTimeMillis {
            val results = store.search(SearchQuery("kaleidoscope"))
            assertEquals(3, results.size, "must find all 3 matching conversations")
        }

        assertTrue(elapsedMs < 300, "10,000 messages search must complete under 300ms, took ${elapsedMs}ms")
    }
}
