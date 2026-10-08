package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.persistence.PersistResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive delivery correctness tests covering:
 * - Failed writes rejected by PersistResult.Error
 * - Transaction rollback: no partial state on batch failure
 * - Retry: failed writes are NOT cached in deduplicator and can succeed on retry
 * - Readback verification: ReadbackVerified stage is recorded after successful writes
 * - Updates: revision increments and deduplicator cache is updated on success
 * - Removals: markRemoved observed by readback, ledger records DbCommitted
 * - Actual timestamps: each stage event has a non-decreasing timestamp
 */
class DeliveryCorrectnessTest {

    private var tickMs = 1_000_000L
    private val clock = Clock { UtcInstant(tickMs++) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }
    private val ledger = DeliveryAccountingLedger(4000)

    private fun makeCoordinator(store: InMemoryObservationStore): ObservationIngestCoordinator {
        val normalizer = NotificationNormalizer(idGen, digest)
        val deduplicator = NotificationDeduplicator()
        return ObservationIngestCoordinator(
            queue = BoundedIngestQueue(512),
            normalizer = normalizer,
            deduplicator = deduplicator,
            store = store,
            clock = clock,
            ledger = ledger,
        )
    }

    private fun payload(
        key: String,
        pkg: String = "com.whatsapp",
        id: Int = 1,
        timeMs: Long = 1_700_000_000_000L,
        sender: String = "Alice",
        title: String = "Chat",
        text: String = "Hello",
        isRemoved: Boolean = false,
        fragments: List<String> = emptyList(),
    ) = NotificationRawPayload(
        key = key,
        packageId = pkg,
        notificationId = id,
        postTimeMillis = timeMs,
        senderHint = sender,
        title = title,
        text = text,
        isRemoved = isRemoved,
        textFragments = fragments,
    )

    // -------------------------------------------------------------------------
    // 1. Failed writes: persistObservation returns Error -> coordinator returns
    //    IngestProcessResult.Error, ledger records PipelineStage.Error,
    //    and NO DbCommitted is emitted.
    // -------------------------------------------------------------------------
    @Test
    fun testFailedWriteReturnsErrorAndRecordsLedgerError() {
        val store = InMemoryObservationStore()
        store.simulatedPersistError = "Disk full"
        val coord = makeCoordinator(store)
        ledger.clear()

        coord.enqueue(payload("fail_key_1"))
        val result = coord.processNext()

        assertTrue(result is IngestProcessResult.Error, "Expected Error, got $result")
        assertTrue(result.error.contains("Disk full") || result.error.contains("Persist failed"),
            "Error message should reference the underlying cause: ${result.error}")

        val events = ledger.getEvents("fail_key_1")
        assertTrue(events.any { it.stage == PipelineStage.Error },
            "Expected PipelineStage.Error to be recorded")
        assertFalse(events.any { it.stage == PipelineStage.DbCommitted },
            "DbCommitted must NOT be recorded when write fails")
        assertFalse(events.any { it.stage == PipelineStage.ReadbackVerified },
            "ReadbackVerified must NOT be recorded when write fails")

        // Store must be empty
        assertEquals(0, store.recoverFromRestart().totalObservations)
    }

    // -------------------------------------------------------------------------
    // 2. Transaction rollback: after a batch failure, no observations or cursors
    //    are written (store is clean), and deduplicator does not cache entries.
    // -------------------------------------------------------------------------
    @Test
    fun testBatchRollbackLeavesNoPartialState() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        // Enqueue 3 items that will succeed, then enable error for the 4th
        for (i in 1..3) {
            coord.enqueue(payload("batch_ok_$i", id = i, timeMs = 1_700_000_000_000L + i * 1000L))
        }
        // Enable error before draining
        store.simulatedPersistError = "IO Error"

        val processed = coord.drainAndProcessAll(100)
        // All 3 were queued; processed count counts deduplication stage completions
        assertEquals(3, processed)

        // After rollback, nothing should be in the store
        assertEquals(0, store.recoverFromRestart().totalObservations,
            "Batch rollback must leave store empty")
        assertEquals(0, store.recoverFromRestart().totalConversations)

        // Cursors must also be absent (atomic rollback)
        assertNull(store.getCursor("com.whatsapp"), "Cursor must not be written on failed batch")

        // All 3 keys should have Error, not DbCommitted
        for (i in 1..3) {
            val events = ledger.getEvents("batch_ok_$i")
            assertFalse(events.any { it.stage == PipelineStage.DbCommitted },
                "batch_ok_$i must not have DbCommitted after rollback")
        }
    }

    // -------------------------------------------------------------------------
    // 3. Retry: after a write failure, the deduplicator has NOT cached the key,
    //    so a second enqueue of the same notification succeeds on retry.
    // -------------------------------------------------------------------------
    @Test
    fun testFailedWriteIsRetryableNotDroppedAsDuplicate() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val p = payload("retry_key_1", timeMs = 1_700_000_000_000L)

        // First attempt fails
        store.simulatedPersistError = "Transient error"
        coord.enqueue(p)
        val res1 = coord.processNext()
        assertTrue(res1 is IngestProcessResult.Error)

        // Store still empty
        assertEquals(0, store.recoverFromRestart().totalObservations)

        // Clear error and retry with same payload
        store.simulatedPersistError = null
        coord.enqueue(p)
        val res2 = coord.processNext()
        assertTrue(res2 is IngestProcessResult.Created,
            "Retry should produce Created, not DuplicateIgnored. Got: $res2")

        // Store now has 1 observation
        assertEquals(1, store.recoverFromRestart().totalObservations)

        // Deduplicator now caches the entry; a third enqueue is correctly dropped
        coord.enqueue(p)
        val res3 = coord.processNext()
        assertTrue(res3 is IngestProcessResult.DuplicateIgnored,
            "After successful write, same payload should be DuplicateIgnored. Got: $res3")
    }

    // -------------------------------------------------------------------------
    // 4. Readback verification: after a successful write, ReadbackVerified is
    //    recorded, and getObservationByNotificationKey returns the record.
    // -------------------------------------------------------------------------
    @Test
    fun testReadbackVerifiedRecordedAfterSuccessfulWrite() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        coord.enqueue(payload("readback_key_1"))
        val res = coord.processNext()
        assertTrue(res is IngestProcessResult.Created)

        val events = ledger.getEvents("readback_key_1")
        assertTrue(events.any { it.stage == PipelineStage.DbCommitted },
            "DbCommitted must be present after successful write")
        assertTrue(events.any { it.stage == PipelineStage.ReadbackVerified },
            "ReadbackVerified must be present after successful write")

        // Independent readback
        val readback = store.getObservationByNotificationKey("readback_key_1")
        assertNotNull(readback, "getObservationByNotificationKey must return the persisted record")
        assertEquals("readback_key_1", readback.notificationKey)
    }

    // -------------------------------------------------------------------------
    // 5. Update flow: revision increments, deduplicator cache updates on success.
    // -------------------------------------------------------------------------
    @Test
    fun testUpdateFlowIncrementsRevisionAndRecordsCorrectly() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val initial = payload("update_key_1", timeMs = 1_700_000_000_000L, text = "Initial text")
        val updated = payload("update_key_1", timeMs = 1_700_000_010_000L, text = "Updated text",
            fragments = listOf("Initial text", "Updated text"))

        coord.enqueue(initial)
        val res1 = coord.processNext()
        assertTrue(res1 is IngestProcessResult.Created)
        assertEquals(1L, res1.observation.revision)

        coord.enqueue(updated)
        val res2 = coord.processNext()
        assertTrue(res2 is IngestProcessResult.Updated,
            "Second distinct payload with same key should be Updated")
        assertEquals(1L, res2.priorRevision)
        assertEquals(2L, res2.observation.revision)
        assertEquals(listOf("Initial text", "Updated text"), res2.observation.textFragments)

        // Store has 1 observation (same ID updated)
        assertEquals(1, store.recoverFromRestart().totalObservations)

        // ReadbackVerified for the update
        val updateEvents = ledger.getEvents("update_key_1")
        val dbCommits = updateEvents.count { it.stage == PipelineStage.DbCommitted }
        assertTrue(dbCommits >= 2, "Both insert and update should record DbCommitted: $dbCommits")
    }

    // -------------------------------------------------------------------------
    // 6. Removal flow: markRemoved is called, DbCommitted recorded, observation
    //    is marked removed and NOT returned by getObservationByNotificationKey.
    // -------------------------------------------------------------------------
    @Test
    fun testRemovalFlowMarksObservationRemovedAndRecordsDbCommitted() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val post = payload("remove_key_1", id = 901, timeMs = 1_700_000_000_000L, text = "Message")
        val removal = payload("remove_key_1", id = 901, timeMs = 1_700_000_005_000L,
            text = "", isRemoved = true)

        coord.enqueue(post)
        coord.processNext()

        coord.enqueue(removal)
        val removeRes = coord.processNext()
        assertTrue(removeRes is IngestProcessResult.Removed)
        assertEquals("remove_key_1", removeRes.notificationKey)
        assertEquals(1, removeRes.count)

        // After removal, readback by notification key returns null (is_removed = true)
        val readback = store.getObservationByNotificationKey("remove_key_1")
        assertNull(readback, "Removed observation must not be returned by readback")

        // Removal records DbCommitted
        val events = ledger.getEvents("remove_key_1")
        assertTrue(events.any { it.stage == PipelineStage.DbCommitted },
            "Removal must record DbCommitted")
    }

    // -------------------------------------------------------------------------
    // 7. Actual timestamps: stage events for a single notification have
    //    non-decreasing timestamps in pipeline order.
    // -------------------------------------------------------------------------
    @Test
    fun testStageTimestampsAreNonDecreasingInPipelineOrder() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        coord.enqueue(payload("ts_key_1"))
        val res = coord.processNext()
        assertTrue(res is IngestProcessResult.Created)

        val events = ledger.getEvents("ts_key_1")
            .sortedWith(compareBy({ pipelineOrder(it.stage) }, { it.timestampMillis }))

        // Verify timestamps are non-decreasing
        for (i in 1 until events.size) {
            assertTrue(events[i].timestampMillis >= events[i - 1].timestampMillis,
                "Timestamp at ${events[i].stage} (${events[i].timestampMillis}) should be >= " +
                    "${events[i - 1].stage} (${events[i - 1].timestampMillis})")
        }

        // Key stages must all be present
        val stages = events.map { it.stage }.toSet()
        assertTrue(PipelineStage.Normalized in stages)
        assertTrue(PipelineStage.DeduplicatedInsert in stages)
        assertTrue(PipelineStage.DbCommitted in stages)
        assertTrue(PipelineStage.ReadbackVerified in stages)
    }

    // -------------------------------------------------------------------------
    // 8. Batch with cursors: atomic success persists all observations and cursors
    //    together.
    // -------------------------------------------------------------------------
    @Test
    fun testAtomicBatchPersistsCursorsWithObservations() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        for (i in 1..5) {
            coord.enqueue(payload("batch_cur_$i", id = i, timeMs = 1_700_000_000_000L + i * 1000L,
                text = "Message $i"))
        }

        val processed = coord.drainAndProcessAll(10)
        assertEquals(5, processed)

        // All observations persisted
        assertEquals(5, store.recoverFromRestart().totalObservations)

        // Cursor was checkpointed
        val cursor = store.getCursor("com.whatsapp")
        assertNotNull(cursor, "Cursor must be persisted atomically with batch")
        assertTrue(cursor.processedCount >= 5,
            "Processed count should reflect batch: ${cursor.processedCount}")

        // ReadbackVerified was recorded for first and last
        val firstEvents = ledger.getEvents("batch_cur_1")
        val lastEvents = ledger.getEvents("batch_cur_5")
        assertTrue(firstEvents.any { it.stage == PipelineStage.ReadbackVerified },
            "First batch item should have ReadbackVerified")
        assertTrue(lastEvents.any { it.stage == PipelineStage.ReadbackVerified },
            "Last batch item should have ReadbackVerified")
    }

    // -------------------------------------------------------------------------
    // 9. Batch rollback also rolls back cursors atomically.
    // -------------------------------------------------------------------------
    @Test
    fun testBatchFailureRollsBackCursorsToo() {
        val store = InMemoryObservationStore()
        store.simulatedPersistError = "Storage unavailable"
        val coord = makeCoordinator(store)
        ledger.clear()

        for (i in 1..3) {
            coord.enqueue(payload("cursor_rb_$i", id = i))
        }
        coord.drainAndProcessAll(10)

        // Cursor must NOT be written
        assertNull(store.getCursor("com.whatsapp"),
            "Cursor must not be written when batch rolls back")
    }

    // -------------------------------------------------------------------------
    // 10. InMemoryObservationStore.persistBatchWithCursors rollback contract.
    // -------------------------------------------------------------------------
    @Test
    fun testInMemoryStoreRollbackOnError() {
        val store = InMemoryObservationStore()

        val obs = makeObservation("rollback_obs_key_1")
        val cursor = IngestCursor("com.whatsapp", 1_000L, "rollback_obs_key_1", 1L, 0L)

        // Persist once successfully
        val r1 = store.persistBatchWithCursors(listOf(obs), listOf(cursor))
        assertTrue(r1.isFullySuccessful)
        assertEquals(1, store.recoverFromRestart().totalObservations)

        // Now inject an error and try again with a new observation
        store.simulatedPersistError = "Injected error"
        val obs2 = makeObservation("rollback_obs_key_2")
        val cursor2 = IngestCursor("com.whatsapp", 2_000L, "rollback_obs_key_2", 2L, 0L)

        val r2 = store.persistBatchWithCursors(listOf(obs2), listOf(cursor2))
        assertFalse(r2.isFullySuccessful)
        assertEquals(1, r2.failedCount)
        assertTrue(r2.errors.isNotEmpty())

        // Store must still have only the first observation
        assertEquals(1, store.recoverFromRestart().totalObservations,
            "Rollback must leave store with only the pre-existing observation")
        // Cursor must still be at its original state
        val c = store.getCursor("com.whatsapp")
        assertNotNull(c)
        assertEquals(1L, c.processedCount, "Cursor must not have been advanced after rollback")
    }

    // -------------------------------------------------------------------------
    // 11. getObservationByNotificationKey returns null for removed observations.
    // -------------------------------------------------------------------------
    @Test
    fun testGetObservationByKeyExcludesRemoved() {
        val store = InMemoryObservationStore()
        val obs = makeObservation("gone_key_1")
        store.persistObservation(obs)

        // Confirm it's readable before removal
        assertNotNull(store.getObservationByNotificationKey("gone_key_1"))

        // Mark removed
        store.markRemoved("gone_key_1", UtcInstant(1_700_000_099_000L))

        // Must now return null
        assertNull(store.getObservationByNotificationKey("gone_key_1"),
            "getObservationByNotificationKey must exclude removed observations")
    }

    // -------------------------------------------------------------------------
    // 12. PipelineAccountingReport reflects ReadbackVerified counts.
    // -------------------------------------------------------------------------
    @Test
    fun testLedgerReportCountsReadbackVerifiedStage() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        for (i in 1..4) {
            coord.enqueue(payload("rept_key_$i", id = i, timeMs = 1_700_000_000_000L + i))
            coord.processNext()
        }

        val report = ledger.generateReport()
        assertEquals(4, report.fullyDeliveredCount,
            "All 4 events should be fully delivered")
        val readbackCount = report.countsByStage[PipelineStage.ReadbackVerified] ?: 0
        assertTrue(readbackCount >= 4,
            "ReadbackVerified should be recorded for all 4 events, got $readbackCount")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun pipelineOrder(stage: PipelineStage): Int = when (stage) {
        PipelineStage.SourcePublished -> 0
        PipelineStage.ListenerReceived -> 1
        PipelineStage.QueueAdmitted -> 2
        PipelineStage.QueueDropped -> 2
        PipelineStage.Normalized -> 3
        PipelineStage.DeduplicatedInsert -> 4
        PipelineStage.DeduplicatedUpdate -> 4
        PipelineStage.DeduplicatedDrop -> 4
        PipelineStage.DeduplicatedRemove -> 4
        PipelineStage.DbCommitted -> 5
        PipelineStage.ReadbackVerified -> 6
        PipelineStage.UiNotified -> 7
        PipelineStage.Error -> 8
    }

    private fun makeObservation(notifKey: String): app.mini.hub.observation.ObservedNotification {
        val acct = app.mini.hub.domain.AccountId(
            app.mini.hub.domain.Provider.WHATSAPP, "local_user")
        val convId = app.mini.hub.domain.ScopedId(
            acct, app.mini.hub.domain.ScopedId.Kind.CONVERSATION, "Chat")
        val obsId = app.mini.hub.domain.ScopedId(
            acct, app.mini.hub.domain.ScopedId.Kind.OBSERVATION, notifKey)
        return ObservedNotification(
            id = obsId,
            accountId = acct,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = notifKey,
            notificationId = 1,
            tag = null,
            senderHint = "Alice",
            title = "Chat",
            textFragments = listOf("Hello"),
            rawTextDigest = "digest_$notifKey",
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
    }
}
