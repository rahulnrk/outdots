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
    // 2b. Atomic rollback against an already-persisted notification: a batch that
    //    removes the existing row and inserts a new row must leave both the old
    //    row and cursor unchanged when the batch transaction fails.
    // -------------------------------------------------------------------------
    @Test
    fun testAtomicBatchRollbackPreservesAlreadyPersistedNotification() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        coord.enqueue(payload("already_persisted", id = 41, text = "Original"))
        assertTrue(coord.processNext() is IngestProcessResult.Created)
        val cursorBefore = store.getCursor("com.whatsapp")
        assertNotNull(cursorBefore)

        store.simulatedPersistError = "batch disk failure"
        coord.enqueue(payload("already_persisted", id = 41, text = "", isRemoved = true))
        coord.enqueue(payload("new_batch_row", id = 42, text = "Should rollback"))
        assertEquals(2, coord.drainAndProcessAll(10))

        val old = store.getObservationByNotificationKey("already_persisted")
        assertNotNull(old, "Existing active row must survive a failed atomic batch")
        assertEquals("Original", old.textFragments.single())
        assertFalse(old.isRemoved)
        assertNull(store.getObservationByNotificationKey("new_batch_row"),
            "New row must not survive a failed atomic batch")
        assertEquals(cursorBefore.processedCount, store.getCursor("com.whatsapp")?.processedCount,
            "Cursor must roll back with the failed removal/write batch")
    }

    // -------------------------------------------------------------------------
    // 2c. Batch writes use bounded automatic retry of the complete transaction.
    //    A transient first transaction failure recovers without re-enqueueing.
    // -------------------------------------------------------------------------
    @Test
    fun testBatchWriteRetriesAutomaticallyWithoutManualReenqueue() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        store.simulatedPersistFailuresRemaining = 1
        store.simulatedPersistError = null
        coord.enqueue(payload("batch_retry_1", id = 51, text = "Retry me"))
        coord.enqueue(payload("batch_retry_2", id = 52, text = "Retry me too"))

        assertEquals(2, coord.drainAndProcessAll(10))
        assertEquals(2, store.recoverFromRestart().totalObservations,
            "Batch retry must recover without manually re-enqueueing events")
        assertNotNull(store.getObservationByNotificationKey("batch_retry_1"))
        assertNotNull(store.getObservationByNotificationKey("batch_retry_2"))

        val retryEvents = ledger.getEvents("batch_retry_1")
        assertTrue(retryEvents.any { it.stage == PipelineStage.Error },
            "Transient batch failure must be visible as Error before recovery")
        assertTrue(retryEvents.any { it.stage == PipelineStage.DbCommitted },
            "Recovered batch must record DbCommitted")
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

    // -------------------------------------------------------------------------
    // 13. Bounded retry: transient failure clears after N attempts and the item
    //     succeeds automatically -- no manual re-enqueue required.
    // -------------------------------------------------------------------------
    @Test
    fun testBoundedRetryRecoverWithoutManualResubmit() {
        val store = InMemoryObservationStore()
        var callCount = 0
        // Intercept by wrapping with an error on the first 2 calls only
        val wrappedStore = object : app.mini.hub.persistence.ObservationStore by store {
            override fun persistObservationWithCursor(
                observation: ObservedNotification,
                cursor: app.mini.hub.observation.IngestCursor,
            ): app.mini.hub.persistence.PersistResult {
                callCount++
                return if (callCount <= 2) {
                    store.simulatedPersistError = "Transient I/O error"
                    val r = store.persistObservationWithCursor(observation, cursor)
                    store.simulatedPersistError = null
                    r
                } else {
                    store.persistObservationWithCursor(observation, cursor)
                }
            }
        }
        val coord = ObservationIngestCoordinator(
            queue = BoundedIngestQueue(512),
            normalizer = NotificationNormalizer(idGen, digest),
            deduplicator = NotificationDeduplicator(),
            store = wrappedStore,
            clock = clock,
            ledger = ledger,
            maxWriteRetries = 2, // allow up to 2 automatic retries (3 total attempts)
        )
        ledger.clear()

        coord.enqueue(payload("retry_auto_key_1"))
        val result = coord.processNext()

        // Should succeed on 3rd attempt automatically without manual re-enqueue
        assertTrue(result is IngestProcessResult.Created,
            "Expected Created after automatic retry, got: $result")
        assertEquals(1, store.recoverFromRestart().totalObservations,
            "Observation must be persisted after automatic retry")

        val events = ledger.getEvents("retry_auto_key_1")
        // First 2 attempts logged as Error with "retrying", final as DbCommitted
        val errorEvents = events.filter { it.stage == PipelineStage.Error }
        assertTrue(errorEvents.size >= 2 && errorEvents.all { it.details?.contains("retrying") == true },
            "Should have retry error events: $errorEvents")
        assertTrue(events.any { it.stage == PipelineStage.DbCommitted },
            "DbCommitted must be present after successful retry")
        assertTrue(events.any { it.stage == PipelineStage.ReadbackVerified },
            "ReadbackVerified must be present after successful retry")
    }

    // -------------------------------------------------------------------------
    // 14. Bounded retry exhausted: after maxWriteRetries + 1 all-fail attempts
    //     returns Error and does NOT cache in deduplicator.
    // -------------------------------------------------------------------------
    @Test
    fun testBoundedRetryExhaustedReturnsErrorAndIsStillRetryable() {
        val store = InMemoryObservationStore()
        store.simulatedPersistError = "Permanent error"
        val coord = ObservationIngestCoordinator(
            queue = BoundedIngestQueue(512),
            normalizer = NotificationNormalizer(idGen, digest),
            deduplicator = NotificationDeduplicator(),
            store = store,
            clock = clock,
            ledger = ledger,
            maxWriteRetries = 2,
        )
        ledger.clear()

        coord.enqueue(payload("retry_exhaust_key_1"))
        val result = coord.processNext()
        assertTrue(result is IngestProcessResult.Error, "Expected Error after exhausted retries")

        // Deduplicator must NOT have cached the entry
        store.simulatedPersistError = null
        coord.enqueue(payload("retry_exhaust_key_1"))
        val retryResult = coord.processNext()
        assertTrue(retryResult is IngestProcessResult.Created,
            "After clearing error, same key must succeed as Created, not DuplicateIgnored. Got: $retryResult")
    }

    // -------------------------------------------------------------------------
    // 15. Genuine same-batch multi-op: Insert, Update, Duplicate, Remove for the
    //     same notification key and one distinct key, all enqueued BEFORE a single
    //     drainAndProcessAll() call.  Verifies intra-batch dedup resolution, final
    //     DB state, and full ledger stage coverage without any sequential processNext.
    // -------------------------------------------------------------------------
    @Test
    fun testMultiOpBatchPreservesOrderAndFinalState() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val baseTime = 1_700_000_000_000L

        // Enqueue 5 operations for the same batch (no processNext between them):
        // op1: Insert key A (first post, v1)
        coord.enqueue(payload("batch_key_A", id = 1, timeMs = baseTime, text = "v1"))
        // op2: Update key A (same key, distinct postTime and text -> Update within batch)
        coord.enqueue(payload("batch_key_A", id = 1, timeMs = baseTime + 10_000L, text = "v2"))
        // op3: Duplicate of op2 (same postTime, same text -> Drop within batch)
        coord.enqueue(payload("batch_key_A", id = 1, timeMs = baseTime + 10_000L, text = "v2"))
        // op4: Insert distinct key B
        coord.enqueue(payload("batch_key_B", id = 2, timeMs = baseTime, text = "distinct"))
        // op5: Remove key A
        coord.enqueue(payload("batch_key_A", id = 1, timeMs = baseTime + 20_000L, text = "", isRemoved = true))

        val processed = coord.drainAndProcessAll(10)
        assertEquals(5, processed, "All 5 operations should be reported as processed")

        // Final DB state: key A is removed (not present in readback), key B exists
        val readbackA = store.getObservationByNotificationKey("batch_key_A")
        assertNull(readbackA, "batch_key_A must be absent (removed) from readback")

        val readbackB = store.getObservationByNotificationKey("batch_key_B")
        assertNotNull(readbackB, "batch_key_B must exist in readback")
        assertEquals("batch_key_B", readbackB.notificationKey)
        assertFalse(readbackB.isRemoved, "batch_key_B must not be marked removed")

        // Ledger for key A must include all expected stages
        val stagesA = ledger.getEvents("batch_key_A").map { it.stage }.toSet()
        assertTrue(PipelineStage.DeduplicatedInsert in stagesA,
            "Insert stage missing for batch_key_A: $stagesA")
        assertTrue(PipelineStage.DeduplicatedUpdate in stagesA,
            "Update stage missing for batch_key_A: $stagesA")
        assertTrue(PipelineStage.DeduplicatedDrop in stagesA,
            "Drop stage missing for batch_key_A: $stagesA")
        assertTrue(PipelineStage.DeduplicatedRemove in stagesA,
            "Remove stage missing for batch_key_A: $stagesA")
        // DbCommitted: Remove writes to DB immediately; the Insert+Update result was cancelled by
        // Remove removing it from the batch list before commit.
        assertTrue(PipelineStage.DbCommitted in stagesA,
            "DbCommitted missing for batch_key_A: $stagesA")
    }

    // -------------------------------------------------------------------------
    // 16. Genuine same-batch 3 updates: Insert + 2 Updates all in one
    //     drainAndProcessAll() call.  Final record in the DB must reflect the last
    //     update's content (revision 2, because Insert+Update collapses to one
    //     persistent record: the batch upsert replaces the slot).
    // -------------------------------------------------------------------------
    @Test
    fun testThreeUpdatesInOneBatchProduceCorrectFinalRevision() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val base = 1_700_000_000_000L
        // All three enqueued before any processNext -- genuine batch.
        coord.enqueue(payload("brev_key_1", id = 10, timeMs = base, text = "rev1"))
        coord.enqueue(payload("brev_key_1", id = 10, timeMs = base + 1_000L, text = "rev2"))
        coord.enqueue(payload("brev_key_1", id = 10, timeMs = base + 2_000L, text = "rev3"))

        val processed = coord.drainAndProcessAll(10)
        assertEquals(3, processed, "Three enqueued items should all be processed")

        // The batch collapses Insert + 2 Updates to a single slot in the persistent list.
        // Store should have exactly 1 observation for this key.
        assertEquals(1, store.recoverFromRestart().totalObservations,
            "Three batch updates to same key must result in exactly one persistent record")

        // Readback shows final state
        val obs = store.getObservationByNotificationKey("brev_key_1")
        assertNotNull(obs, "Observation must exist after 3 batch updates")
        assertFalse(obs.isRemoved)
        // revision: Insert sets 1, each Update increments by 1 via intra-batch tracking -> 3
        assertEquals(3L, obs.revision,
            "Final revision must be 3 after Insert+2 Updates in one batch, got ${obs.revision}")

        // Ledger stages must include all three dedup events
        val stages = ledger.getEvents("brev_key_1").map { it.stage }.toSet()
        assertTrue(PipelineStage.DeduplicatedInsert in stages, "Insert missing: $stages")
        assertTrue(PipelineStage.DeduplicatedUpdate in stages, "Update missing: $stages")
        assertTrue(PipelineStage.DbCommitted in stages, "DbCommitted missing: $stages")
        assertTrue(PipelineStage.ReadbackVerified in stages, "ReadbackVerified missing: $stages")
    }

    // -------------------------------------------------------------------------
    // 17. Full DB readback: all 3 operations in separate drainAndProcessAll batches.
    //     Verifies exact notificationKey, textFragment content, revision, isRemoved,
    //     and that removed keys return null from getObservationByNotificationKey().
    // -------------------------------------------------------------------------
    @Test
    fun testFullDbReadbackVerifiesEveryExpectedRecord() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val base = 1_700_000_000_000L
        val textA1 = "Hello from A"
        val textA2 = "Updated A message"
        val textB  = "Hello from B"
        val textC  = "Hello from C"

        // Batch 1: Insert 3 distinct keys
        coord.enqueue(payload("verify_A", id = 1, timeMs = base, text = textA1))
        coord.enqueue(payload("verify_B", id = 2, timeMs = base, text = textB))
        coord.enqueue(payload("verify_C", id = 3, timeMs = base, text = textC))
        val b1 = coord.drainAndProcessAll(10)
        assertEquals(3, b1, "Batch 1 must process 3 items")

        // Batch 2: Update verify_A with new content (distinct postTime + text)
        coord.enqueue(payload("verify_A", id = 1, timeMs = base + 5_000L, text = textA2))
        val b2 = coord.drainAndProcessAll(10)
        assertEquals(1, b2, "Batch 2 must process 1 item")

        // Batch 3: Remove verify_C
        coord.enqueue(payload("verify_C", id = 3, timeMs = base + 10_000L, text = "", isRemoved = true))
        val b3 = coord.drainAndProcessAll(10)
        assertEquals(1, b3, "Batch 3 must process 1 item")

        // --- verify_A: present, revision=2, correct content, not removed ---
        val obsA = store.getObservationByNotificationKey("verify_A")
        assertNotNull(obsA, "verify_A must exist in readback")
        assertEquals("verify_A", obsA.notificationKey)
        assertFalse(obsA.isRemoved, "verify_A must not be removed")
        assertEquals(2L, obsA.revision, "verify_A must be at revision 2 after update")
        assertTrue(obsA.textFragments.contains(textA2) || obsA.textFragments.any { it.contains(textA2) },
            "verify_A readback must contain updated text '$textA2', got: ${obsA.textFragments}")

        // --- verify_B: present, revision=1, correct content, not removed ---
        val obsB = store.getObservationByNotificationKey("verify_B")
        assertNotNull(obsB, "verify_B must exist in readback")
        assertEquals("verify_B", obsB.notificationKey)
        assertFalse(obsB.isRemoved, "verify_B must not be removed")
        assertEquals(1L, obsB.revision, "verify_B must be at revision 1 (no updates)")
        assertTrue(obsB.textFragments.contains(textB) || obsB.textFragments.any { it.contains(textB) },
            "verify_B readback must contain text '$textB', got: ${obsB.textFragments}")

        // --- verify_C: removed, readback returns null ---
        val obsC = store.getObservationByNotificationKey("verify_C")
        assertNull(obsC, "verify_C must return null from readback after removal")

        // --- Total store size: 3 (store retains removed records; only readback hides them) ---
        val all = store.recoverFromRestart()
        assertEquals(3, all.totalObservations,
            "Store must hold 3 records (A active, B active, C removed)")
    }

    // -------------------------------------------------------------------------
    // 18. CorrelationId threading: stages recorded under correlationId only, never
    //     also under the raw OS notification key.
    // -------------------------------------------------------------------------
    @Test
    fun testCorrelationIdThreadingRecordsStagesUnderCorrelationIdOnly() {
        val store = InMemoryObservationStore()
        val coord = makeCoordinator(store)
        ledger.clear()

        val p = payload("notif_os_key_1").copy(correlationId = "my_corr_id_abc")
        coord.enqueue(p)
        val result = coord.processNext()
        assertTrue(result is IngestProcessResult.Created,
            "Expected Created, got $result")

        // All pipeline stages must be under correlationId
        val byCorr = ledger.getEvents("my_corr_id_abc")
        assertTrue(byCorr.any { it.stage == PipelineStage.QueueAdmitted },
            "QueueAdmitted must be under correlationId")
        assertTrue(byCorr.any { it.stage == PipelineStage.DbCommitted },
            "DbCommitted must be under correlationId")
        assertTrue(byCorr.any { it.stage == PipelineStage.ReadbackVerified },
            "ReadbackVerified must be under correlationId")

        // Nothing must appear under the raw OS key for these ingest-pipeline stages.
        // (The raw key has no entry at all when correlationId is set.)
        val byKey = ledger.getEvents("notif_os_key_1")
        val pipelineStagesUnderRawKey = byKey.filter { it.stage in listOf(
            PipelineStage.QueueAdmitted, PipelineStage.Normalized,
            PipelineStage.DeduplicatedInsert, PipelineStage.DbCommitted,
            PipelineStage.ReadbackVerified,
        )}
        assertTrue(pipelineStagesUnderRawKey.isEmpty(),
            "No ingest-pipeline stages must be recorded under raw OS key when correlationId is set; " +
                "found: $pipelineStagesUnderRawKey")
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
