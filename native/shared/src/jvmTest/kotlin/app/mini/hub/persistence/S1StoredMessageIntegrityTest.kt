package app.mini.hub.persistence

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.IngestProcessResult
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.NotificationDeduplicator
import app.mini.hub.observation.NotificationNormalizer
import app.mini.hub.observation.NotificationRawPayload
import app.mini.hub.observation.ObservationIngestCoordinator
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.observation.isConversationSummary
import app.mini.hub.search.SearchQuery
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1 / WP01 regressions (audit A1, A3, A4, A5, A10). Every case runs against a real sqlite file,
 * with genuine-message controls next to each noise case so a fix cannot pass by hiding everything.
 */
class S1StoredMessageIntegrityTest {
    private val fb = AccountId(Provider.FACEBOOK, "local_user")
    private val wa = AccountId(Provider.WHATSAPP, "local_user")

    @Test fun structuralMessageEvidenceSurvivesReopenAndDefeatsActivityLikeBodyWords() {
        val file = dbFile("s1-structure")
        val store = JdbcObservationStore(file.path)
        var ids = 0
        val normalizer = NotificationNormalizer(IdGenerator { "synthetic-${ids++}" }, DigestProvider { it })
        val obs = normalizer.normalize(NotificationRawPayload(key = "structured-message", packageId = "com.instagram.android",
            notificationId = 1, postTimeMillis = 1_700_000_000_000L, senderHint = "Synthetic Contact", title = "Synthetic Contact",
            text = "You might like this recommendation; can we discuss it tomorrow?", hasMessagingStyle = true,
            sourceMessageTimeMillis = 1_700_000_000_000L), UtcInstant(1_700_000_000_000L))
        assertTrue(store.persistObservation(obs).isSuccess)
        close(store)
        val reopened = JdbcObservationStore(file.path)
        assertTrue(reopened.getObservation(obs.id)!!.messagingEvidence)
        assertTrue(isConversationSummary(reopened.getConversationSummaries().single()))
        assertEquals(listOf(obs.conversationId), reopened.search(SearchQuery("recommendation")).map { it.conversationId })
        close(reopened)
    }

    @Test fun reusedLiveNotificationKeyKeepsMessageOccurrencesAndLateReplaysAreIdempotent() {
        val file = dbFile("s1-occurrence")
        val store = JdbcObservationStore(file.path)
        var ids = 0
        fun coordinator() = ObservationIngestCoordinator(store = store,
            normalizer = NotificationNormalizer(IdGenerator { "occurrence-${ids++}" }, DigestProvider { it }),
            clock = Clock { UtcInstant(1_700_000_000_000L) })
        fun payload(time: Long, text: String) = NotificationRawPayload(key = "reused-live-key", packageId = "com.whatsapp",
            notificationId = 1, postTimeMillis = time, sourceMessageTimeMillis = time, senderHint = "Synthetic Contact",
            title = "Synthetic Contact", text = text, hasMessagingStyle = true)
        val first = coordinator()
        first.enqueue(payload(1000, "First message")); assertTrue(first.processNext() is IngestProcessResult.Created)
        first.enqueue(payload(1000, "First message edited")); assertTrue(first.processNext() is IngestProcessResult.Updated)
        first.enqueue(payload(2000, "Second message")); assertTrue(first.processNext() is IngestProcessResult.Created)
        // New coordinator has no cache and replays an older occurrence after a newer one.
        val restarted = coordinator()
        restarted.enqueue(payload(1000, "First message edited")); assertTrue(restarted.processNext() is IngestProcessResult.DuplicateIgnored)
        listOf(payload(3000, "Third message"), payload(4000, "Fourth message"), payload(3000, "Third message")).forEach { restarted.enqueue(it) }
        restarted.drainAndProcessAll()
        assertEquals(4, store.recoverFromRestart().totalObservations)
        close(store)
    }

    @Test fun openingExistingDatabaseDoesNotRecreateTheSearchIndex() {
        val file = dbFile("s1-no-rebuild")
        val first = JdbcObservationStore(file.path)
        first.connection().createStatement().use { it.execute("CREATE TRIGGER fts_open_guard AFTER INSERT ON obs_fts_data BEGIN SELECT 1; END") }
        close(first)
        val reopened = JdbcObservationStore(file.path)
        reopened.connection().createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name = 'fts_open_guard'").use {
                it.next(); assertEquals(1, it.getInt(1), "a normal open must retain the existing FTS table")
            }
        }
        close(reopened)
    }

    private fun dbFile(prefix: String) = File.createTempFile(prefix, ".db").apply { delete(); deleteOnExit() }

    private fun close(store: JdbcObservationStore) = store.connection().close()

    private fun ftsRows(store: JdbcObservationStore): Int =
        store.connection().createStatement().use { s -> s.executeQuery("SELECT count(*) FROM obs_fts").use { it.next(); it.getInt(1) } }

    private fun row(
        account: AccountId,
        label: String,
        pkg: String,
        title: String,
        text: String,
        category: NotificationCategory?,
        at: Long = 1_700_000_000_000L,
        conversation: String = label,
        provenance: ObservationProvenance = ObservationProvenance.AndroidNotificationListener,
        removed: Boolean = false,
        key: String = "key-$label",
    ) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-$label"),
        accountId = account,
        conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, conversation),
        packageId = pkg,
        notificationKey = key,
        notificationId = label.hashCode(),
        tag = null,
        senderHint = title,
        title = title,
        textFragments = listOf(text),
        rawTextDigest = "dg-$label",
        observedAt = UtcInstant(at),
        postTime = UtcInstant(at),
        revision = 1L,
        isRemoved = removed,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = provenance,
        notificationCategory = category,
    )

    private fun nullCategory(store: JdbcObservationStore, obs: ObservedNotification) {
        store.connection().prepareStatement("UPDATE notification_observations SET notification_category = NULL WHERE id = ?").use {
            it.setString(1, obs.id.key); assertEquals(1, it.executeUpdate())
        }
    }

    // A10: fixture-name filter hides genuine conversations; recommendations hidden in summaries stay searchable.
    @Test(timeout = 60_000)
    fun fixtureNamedGenuineConversationIsVisibleAndStoredRecommendationIsNotSearchable() {
        val file = dbFile("s1-a10")
        val first = JdbcObservationStore(file.path)
        val recommendation = row(fb, "rec", "com.facebook.katana", "Example Person", "You might like Example Person. reviewneedle", NotificationCategory.DirectMessage)
        val genuine = row(fb, "genuine", "com.facebook.katana", "Example Person", "Please review the document. reviewneedle", NotificationCategory.DirectMessage, at = 1_700_000_001_000L)
        val unrelated = row(fb, "unrelated", "com.facebook.katana", "Unrelated Name", "Please review the document. reviewneedle", NotificationCategory.DirectMessage, at = 1_700_000_002_000L)
        listOf(recommendation, genuine, unrelated).forEach { assertTrue(first.persistObservation(it).isSuccess) }
        close(first)

        val reopened = JdbcObservationStore(file.path)
        val conversations = reopened.getConversationSummaries(fb).filter(::isConversationSummary).map { it.conversationId }
        assertTrue(genuine.conversationId in conversations, "genuine conversation with a fixture-like name must be visible")
        assertTrue(unrelated.conversationId in conversations, "control: unrelated name visible")
        assertFalse(recommendation.conversationId in conversations, "stored recommendation must stay out of the Inbox")
        val hits = reopened.search(SearchQuery("reviewneedle")).map { it.conversationId }
        assertFalse(recommendation.conversationId in hits, "recommendation hidden from Inbox must not be searchable")
        assertTrue(genuine.conversationId in hits, "genuine message searchable")
        // Source history is kept even when it is not eligible for the Inbox.
        assertEquals("You might like Example Person. reviewneedle", reopened.getObservation(recommendation.id)!!.textFragments.single())
        close(reopened)
    }

    // A4: SQL inequality drops legitimate NULL-category history; noise with NULL category stays out.
    @Test(timeout = 60_000)
    fun legacyNullCategoryGenuineMessageIsSearchableAndLegacyNoiseIsNot() {
        val file = dbFile("s1-a4")
        val first = JdbcObservationStore(file.path)
        val legacy = row(wa, "legacy", "com.whatsapp", "Independent Contact", "Please review the document. legacyneedle", NotificationCategory.DirectMessage)
        val legacyNoise = row(wa, "noise", "com.whatsapp", "WhatsApp", "Backup in progress legacyneedle", NotificationCategory.DirectMessage, conversation = "WhatsApp_alerts")
        assertTrue(first.persistObservation(legacy).isSuccess)
        assertTrue(first.persistObservation(legacyNoise).isSuccess)
        nullCategory(first, legacy)
        nullCategory(first, legacyNoise)
        close(first)

        val reopened = JdbcObservationStore(file.path)
        val hits = reopened.search(SearchQuery("legacyneedle")).map { it.conversationId }
        assertTrue(legacy.conversationId in hits, "legitimate NULL-category history must be searchable")
        assertFalse(legacyNoise.conversationId in hits, "NULL-category status noise must not be admitted")
        close(reopened)
    }

    // A1: every open re-populates the FTS table, duplicating unchanged rows.
    @Test(timeout = 60_000)
    fun repeatedOpensKeepSearchIndexCardinalityStable() {
        val file = dbFile("s1-a1")
        val first = JdbcObservationStore(file.path)
        assertTrue(first.persistObservation(row(wa, "a", "com.whatsapp", "Asha", "first message", NotificationCategory.DirectMessage)).isSuccess)
        assertTrue(first.persistObservation(row(wa, "b", "com.whatsapp", "Ravi", "second message", NotificationCategory.DirectMessage)).isSuccess)
        val baseline = ftsRows(first)
        close(first)
        repeat(3) {
            val reopened = JdbcObservationStore(file.path)
            assertEquals(baseline, ftsRows(reopened), "open #${it + 1} must not add search-index rows")
            assertEquals(1, reopened.search(SearchQuery("first")).size)
            close(reopened)
        }
    }

    // A4: ASCII-only query splitting loses Hindi/Telugu and breaks accented names.
    @Test(timeout = 60_000)
    fun unicodeQueriesFindHindiTeluguAndAccentedNames() {
        val file = dbFile("s1-unicode")
        val store = JdbcObservationStore(file.path)
        val hindi = row(wa, "hi", "com.whatsapp", "Priya", "नमस्ते दोस्त, कल मिलते हैं", NotificationCategory.DirectMessage)
        val telugu = row(wa, "te", "com.whatsapp", "Kiran", "నమస్కారం మిత్రమా రేపు కలుద్దాం", NotificationCategory.DirectMessage)
        val accented = row(wa, "acc", "com.whatsapp", "José Müller", "Invoice attached", NotificationCategory.DirectMessage)
        val decomposed = row(wa, "dec", "com.whatsapp", "Rene\u0301e", "Decomposed accent", NotificationCategory.DirectMessage)
        listOf(hindi, telugu, accented, decomposed).forEach { assertTrue(store.persistObservation(it).isSuccess) }
        close(store)
        val reopened = JdbcObservationStore(file.path)
        assertEquals(listOf(hindi.conversationId), reopened.search(SearchQuery("नमस्ते")).map { it.conversationId }, "Hindi word")
        assertEquals(listOf(hindi.conversationId), reopened.search(SearchQuery("मिलते")).map { it.conversationId }, "Hindi word with vowel sign")
        assertEquals(listOf(telugu.conversationId), reopened.search(SearchQuery("నమస్కారం")).map { it.conversationId }, "Telugu word")
        assertEquals(listOf(accented.conversationId), reopened.search(SearchQuery("Müller")).map { it.conversationId }, "accented surname")
        assertEquals(listOf(accented.conversationId), reopened.search(SearchQuery("José")).map { it.conversationId }, "accented given name")
        assertEquals(listOf(decomposed.conversationId), reopened.search(SearchQuery("Renée")).map { it.conversationId }, "precomposed query vs decomposed text")
        assertTrue(reopened.search(SearchQuery("\"")).isEmpty(), "quote-only query is safe")
        close(reopened)
    }

    // A3: dedupe lives only in a bounded memory map; restart or eviction duplicates replays.
    @Test(timeout = 60_000)
    fun notificationReplayAfterRestartAndCacheEvictionIsNotDuplicated() {
        val file = dbFile("s1-a3")
        var counter = 0
        fun coordinator(store: ObservationStore, cache: Int = 1000) = ObservationIngestCoordinator(
            normalizer = NotificationNormalizer(IdGenerator { "$it-${counter++}" }, DigestProvider { "d-${it.hashCode()}" }),
            deduplicator = NotificationDeduplicator(maxCacheEntries = cache),
            store = store,
            clock = Clock { UtcInstant(1_700_000_100_000L) },
        )
        fun payload(key: String, text: String, at: Long) = NotificationRawPayload(
            key = key, packageId = "com.whatsapp", notificationId = key.hashCode(), postTimeMillis = at,
            senderHint = "Asha", title = "Asha", text = text, hasMessagingStyle = true, category = "msg",
        )
        val a = payload("0|com.whatsapp|1|a|10", "See you at 5", 1_700_000_000_000L)
        val b = payload("0|com.whatsapp|2|b|10", "See you at 5", 1_700_000_001_000L) // same text, distinct message

        val first = JdbcObservationStore(file.path)
        val c1 = coordinator(first)
        c1.enqueue(a); assertTrue(c1.processNext() is IngestProcessResult.Created)
        close(first)

        // Restart: new store + new coordinator (empty memory cache) replays the same post.
        val second = JdbcObservationStore(file.path)
        val c2 = coordinator(second, cache = 1)
        c2.enqueue(a)
        val replay = c2.processNext()
        assertTrue(replay is IngestProcessResult.DuplicateIgnored, "replay after restart must be a duplicate, got $replay")
        c2.enqueue(b); assertTrue(c2.processNext() is IngestProcessResult.Created, "same-text distinct message preserved")
        // Cache size 1: b evicted a. Replay a again (batch path) must still be a duplicate.
        c2.enqueue(a); c2.drainAndProcessAll()
        assertEquals(2, second.recoverFromRestart().totalObservations, "exactly two genuine rows")
        close(second)
    }

    @Test(timeout = 60_000)
    fun reusedNotificationKeyAfterDismissalCreatesNewOccurrence() {
        val file = dbFile("s1-occurrence")
        var counter = 0
        fun payload(at: Long, text: String) = NotificationRawPayload(
            key = "reused-key", packageId = "com.whatsapp", notificationId = 7,
            postTimeMillis = at, senderHint = "Asha", title = "Asha", text = text,
            hasMessagingStyle = true, category = "msg",
        )
        fun coordinator(store: ObservationStore) = ObservationIngestCoordinator(
            normalizer = NotificationNormalizer(IdGenerator { "occ-${counter++}" }, DigestProvider { "d-${it.hashCode()}" }),
            store = store,
            clock = Clock { UtcInstant(1_700_000_100_000L) },
        )
        val store = JdbcObservationStore(file.path)
        val first = coordinator(store)
        assertTrue(first.enqueue(payload(1_700_000_000_000L, "first occurrence")) != app.mini.hub.observation.IngestOfferResult.Filtered)
        assertTrue(first.processNext() is IngestProcessResult.Created)
        assertEquals(1, store.markRemoved("reused-key", UtcInstant(1_700_000_001_000L)))

        val second = coordinator(store)
        assertTrue(second.enqueue(payload(1_700_000_002_000L, "second occurrence")) != app.mini.hub.observation.IngestOfferResult.Filtered)
        assertTrue(second.processNext() is IngestProcessResult.Created)
        val rows = store.getObservationsForConversation(ScopedId(wa, ScopedId.Kind.CONVERSATION, "Asha"))
        assertEquals(2, rows.size, "key reuse after dismissal must preserve both occurrences")
        assertTrue(rows.any { it.isRemoved && it.textFragments.single() == "first occurrence" })
        assertTrue(rows.any { !it.isRemoved && it.textFragments.single() == "second occurrence" })
        close(store)
    }

    // A5: Android permits dismissed notification rows in search; JDBC excludes them. Provider deletion differs.
    @Test(timeout = 60_000)
    fun dismissalKeepsHistorySearchableButProviderDeletionDoesNot() {
        val file = dbFile("s1-a5")
        val store = JdbcObservationStore(file.path)
        val dismissed = row(wa, "dis", "com.whatsapp", "Asha", "dismissneedle hello", NotificationCategory.DirectMessage)
        val gmail = AccountId(Provider.GMAIL, "work")
        val deleted = row(gmail, "del", "com.google.android.gm", "Boss", "dismissneedle deleted at source", null,
            provenance = ObservationProvenance.OfficialProviderApi, key = "provider:${gmail.key}:m1")
        assertTrue(store.persistObservation(dismissed).isSuccess)
        assertTrue(store.persistObservation(deleted).isSuccess)
        assertEquals(1, store.markRemoved(dismissed.notificationKey, UtcInstant(1_700_000_005_000L)))
        assertTrue(store.persistObservation(deleted.copy(isRemoved = true, revision = 2)).isSuccess)
        close(store)
        val reopened = JdbcObservationStore(file.path)
        val hits = reopened.search(SearchQuery("dismissneedle")).map { it.conversationId }
        assertTrue(dismissed.conversationId in hits, "clearing an OS notification does not erase message history")
        assertFalse(deleted.conversationId in hits, "provider-deleted message is not searchable")
        assertTrue(reopened.getObservation(dismissed.id)!!.isRemoved, "dismissal is still recorded")
        close(reopened)
    }

    // A5: newest matching message per conversation must be chosen deterministically.
    @Test(timeout = 60_000)
    fun searchReturnsNewestMatchingMessagePerConversation() {
        val store = JdbcObservationStore(dbFile("s1-newest").path)
        val newest = row(wa, "n3", "com.whatsapp", "Asha", "orderneedle newest", NotificationCategory.DirectMessage, at = 3_000, conversation = "Asha")
        val middle = row(wa, "n2", "com.whatsapp", "Asha", "orderneedle middle", NotificationCategory.DirectMessage, at = 2_000, conversation = "Asha")
        val oldest = row(wa, "n1", "com.whatsapp", "Asha", "orderneedle oldest", NotificationCategory.DirectMessage, at = 1_000, conversation = "Asha")
        listOf(newest, middle, oldest).forEach { assertTrue(store.persistObservation(it).isSuccess) }
        val hit = store.search(SearchQuery("orderneedle")).single()
        assertEquals(3_000, hit.eventAt.epochMillis)
        assertTrue("newest" in hit.snippet, "snippet must come from the newest match, got '${hit.snippet}'")
    }

    // Eligibility must apply before LIMIT: noise newer than a genuine match cannot crowd it out.
    @Test(timeout = 60_000)
    fun noiseHeavyMatchesDoNotCrowdOutGenuineMessageBeforeLimit() {
        val store = JdbcObservationStore(dbFile("s1-limit").path)
        val ig = AccountId(Provider.INSTAGRAM, "local_user")
        val genuine = row(wa, "real", "com.whatsapp", "Asha", "crowdneedle can you call me", NotificationCategory.DirectMessage, at = 1_000)
        assertTrue(store.persistObservation(genuine).isSuccess)
        val noise = (1..60).map {
            row(ig, "act$it", "com.instagram.android", "Instagram", "user$it liked your photo crowdneedle", NotificationCategory.SocialUpdate, at = 10_000L + it)
        }
        assertEquals(60, store.persistBatch(noise))
        val hits = store.search(SearchQuery("crowdneedle", limit = 10))
        assertTrue(hits.any { it.conversationId == genuine.conversationId }, "genuine conversation must survive the limit")
        val conversations = store.getConversationSummaries().filter(::isConversationSummary).map { it.conversationId.key }.toSet()
        assertTrue(hits.all { it.conversationId.key in conversations }, "search returns only Inbox-eligible conversations")
    }

    @Test(timeout = 60_000)
    fun jdbcVersion7To8MigrationPreservesPopulatedObservationsSummariesAndSearchIndex() {
        val file = dbFile("jdbc-v6-v7")
        val obsId = ScopedId(wa, ScopedId.Kind.OBSERVATION, "mig1").key
        val convId = ScopedId(wa, ScopedId.Kind.CONVERSATION, "Asha").key
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(ObservationSchema.CREATE_OBSERVATIONS_V1)
                s.execute(ObservationSchema.CREATE_CONVERSATIONS_V1)
                s.execute(ObservationSchema.CREATE_CURSORS_V1)
                s.execute(ObservationSchema.CREATE_PENDING_INVALIDATIONS)
                ObservationSchema.version3Statements(emptySet()).forEach { s.execute(it) }
                s.execute("ALTER TABLE notification_observations ADD COLUMN notification_category TEXT")
                s.execute("ALTER TABLE notification_observations ADD COLUMN messaging_evidence INTEGER NOT NULL DEFAULT 0")
                s.execute("ALTER TABLE conversation_summaries ADD COLUMN messaging_evidence INTEGER NOT NULL DEFAULT 0")
                s.execute("ALTER TABLE notification_observations ADD COLUMN source_message_time INTEGER")
                s.execute("ALTER TABLE notification_observations ADD COLUMN is_dismissed INTEGER NOT NULL DEFAULT 0")
                s.execute("PRAGMA user_version = 7")
                s.execute(
                    "INSERT INTO notification_observations (id, account_key, conversation_key, package_id, notification_key, notification_id, tag, sender_hint, title, text_fragments_json, raw_text_digest, observed_at, post_time, revision, is_removed, is_group_summary, truncation_state, correlation_confidence, provenance, notification_category, messaging_evidence, source_message_time, is_dismissed) " +
                        "VALUES ('$obsId','${wa.key}','$convId','com.whatsapp','key-mig',1,NULL,'Asha','Asha','Migrated message migrationneedle','dg-mig',1000,1000,1,0,0,'Complete','High','AndroidNotificationListener','DirectMessage',0,NULL,0)"
                )
                s.execute(
                    "INSERT INTO conversation_summaries (conversation_key, account_key, title, app_name, sender_hint, package_id, latest_snippet, category, coverage, coverage_explanation, latest_event_at, observation_count, unread_count, is_removed, messaging_evidence) " +
                        "VALUES ('$convId','${wa.key}','Asha','WhatsApp','Asha','com.whatsapp','Migrated message migrationneedle','DirectMessage','NotificationOnly','n',1000,1,1,0,0)"
                )
            }
        }
        val store = JdbcObservationStore(file.path)
        assertEquals(8, store.schemaVersion())
        val obs = store.getObservation(ScopedId.parse(obsId))
        kotlin.test.assertNotNull(obs)
        kotlin.test.assertFalse(obs.messagingEvidence, "legacy v6 rows default to unproven evidence rather than fabricated metadata")
        assertEquals("Migrated message migrationneedle", obs.textFragments.single())

        val summaries = store.getConversationSummaries(wa)
        assertEquals(1, summaries.size)
        kotlin.test.assertFalse(summaries.single().messagingEvidence)

        val hits = store.search(SearchQuery("migrationneedle"))
        assertEquals(1, hits.size)
        assertEquals(convId, hits.single().conversationId.key)

        // Verify pending invalidations table is active and functional
        store.persistProviderDeletionWithEvent(obs, UtcInstant(2000L))
        val pending = store.listPendingInvalidations(wa)
        assertEquals(1, pending.size)
        val eventId = pending.single().eventId
        store.acknowledgeInvalidations(listOf(eventId))
        assertTrue(store.listPendingInvalidations(wa).isEmpty())

        close(store)

        // Second close/reopen verification
        val reopened = JdbcObservationStore(file.path)
        assertEquals(8, reopened.schemaVersion())
        assertEquals("Migrated message migrationneedle", reopened.getObservation(ScopedId.parse(obsId))!!.textFragments.single())
        assertEquals(1, reopened.search(SearchQuery("migrationneedle")).size)
        close(reopened)
    }

    @Test(timeout = 60_000)
    fun populatedVersion4DatabaseWithDuplicateIndexRowsIsRepaired() {
        val file = dbFile("s1-v4")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(ObservationSchema.CREATE_OBSERVATIONS_V1)
                s.execute(ObservationSchema.CREATE_CONVERSATIONS_V1)
                s.execute(ObservationSchema.CREATE_CURSORS_V1)
                ObservationSchema.version3Statements(emptySet()).forEach { s.execute(it) }
                s.execute("ALTER TABLE notification_observations ADD COLUMN notification_category TEXT")
                s.execute("CREATE VIRTUAL TABLE obs_fts USING fts5(obs_id UNINDEXED, conversation_key UNINDEXED, account_key UNINDEXED, package_id UNINDEXED, title, body)")
                for (i in 1..5) {
                    s.execute(
                        "INSERT INTO notification_observations (id, account_key, conversation_key, package_id, notification_key, notification_id, tag, sender_hint, title, text_fragments_json, raw_text_digest, observed_at, post_time, revision, is_removed, is_group_summary, truncation_state, correlation_confidence, provenance, notification_category) " +
                            "VALUES ('${wa.key}:observation:old$i','${wa.key}','${wa.key}:conversation:Contact$i','com.whatsapp','k$i',$i,NULL,'Contact $i','Contact $i','v4needle message $i','dg$i',$i,$i,1,0,0,'Complete','High','AndroidNotificationListener',NULL)",
                    )
                    s.execute(
                        "INSERT INTO conversation_summaries VALUES ('${wa.key}:conversation:Contact$i','${wa.key}','Contact $i','WhatsApp','Contact $i','com.whatsapp','v4needle message $i','DirectMessage','NotificationOnly','n',$i,1,1,0)",
                    )
                }
                // Three earlier population passes left three copies of every row.
                repeat(3) {
                    s.execute("INSERT INTO obs_fts(obs_id, conversation_key, account_key, package_id, title, body) SELECT id, conversation_key, account_key, package_id, coalesce(title,''), text_fragments_json FROM notification_observations")
                }
                s.execute("PRAGMA user_version = 4")
            }
        }
        val store = JdbcObservationStore(file.path)
        assertEquals(5, store.recoverFromRestart().totalObservations, "source rows preserved")
        assertEquals(5, ftsRows(store), "duplicate derived rows repaired")
        assertEquals(5, store.search(SearchQuery("v4needle")).size)
        close(store)
        val again = JdbcObservationStore(file.path)
        assertEquals(5, ftsRows(again), "stable on the next open")
        close(again)
    }
}
