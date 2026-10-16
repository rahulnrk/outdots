package app.mini.hub.observation

import app.mini.hub.domain.Clock
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.persistence.PersistResult

fun interface ObservationUiListener {
    fun onConversationUpdated(summary: ConversationObservationSummary)
}

/**
 * Coordinates non-blocking notification ingestion, deduplication, encrypted persistence,
 * and UI notification dispatch. Supports atomic batch persistence, delivery accounting,
 * readback verification, and retryable write failures.
 *
 * Correctness invariants:
 * 1. Database write results are checked before reporting DbCommitted.
 * 2. Observations and cursor checkpoints are committed atomically.
 * 3. Failed writes are NOT cached in the deduplicator, keeping them retryable.
 * 4. ReadbackVerified stage confirms the observation is independently readable.
 * 5. Each pipeline stage records its actual wall-clock completion timestamp.
 */
class ObservationIngestCoordinator(
    val queue: BoundedIngestQueue<NotificationRawPayload> = BoundedIngestQueue(),
    private val normalizer: NotificationNormalizer,
    private val deduplicator: NotificationDeduplicator = NotificationDeduplicator(),
    val store: ObservationStore,
    private val clock: Clock,
    private var uiListener: ObservationUiListener? = null,
    val ledger: DeliveryAccountingLedger? = null,
    private val isPayloadEligible: (NotificationRawPayload) -> Boolean = { true },
    /**
     * Maximum number of automatic retry attempts for transient write failures in processNext().
     * Set to 0 to disable automatic retry. Default is 2 (3 total attempts).
     */
    val maxWriteRetries: Int = 2,
) {
    private val processingLock = Any()

    fun setUiListener(listener: ObservationUiListener?) {
        uiListener = listener
    }

    fun enqueue(payload: NotificationRawPayload): IngestOfferResult {
        if (!isPayloadEligible(payload)) return IngestOfferResult.Filtered
        val now = clock.now().epochMillis
        val lk = payload.correlationId ?: payload.key
        val result = queue.offer(payload)
        if (result == IngestOfferResult.QueueFull) {
            recordDroppedGap(payload.packageId)
            ledger?.record(lk, PipelineStage.QueueDropped, now, "Queue full")
        } else {
            ledger?.record(lk, PipelineStage.QueueAdmitted, now)
        }
        return result
    }

    fun processNext(): IngestProcessResult = synchronized(processingLock) {
        processNextInternal()
    }

    private fun processNextInternal(): IngestProcessResult {
        val payload = queue.poll() ?: return IngestProcessResult.QueueEmpty
        val lk = payload.correlationId ?: payload.key

        return try {
            val normalized = normalizer.normalize(payload, clock.now())
            ledger?.record(lk, PipelineStage.Normalized, clock.now().epochMillis)
            if (!payload.isRemoved && classifyCommunication(payload) == CommunicationKind.LinkedInActivity) {
                val existingActivity = store.getObservationsForConversation(normalized.conversationId)
                if (existingActivity.any { !it.isRemoved }) {
                    ledger?.record(lk, PipelineStage.DeduplicatedDrop, clock.now().epochMillis)
                    return IngestProcessResult.DuplicateIgnored(normalized.notificationKey)
                }
            }
            val action = deduplicator.evaluate(normalized, store)

            when (action) {
                is NotificationDeduplicator.DedupeAction.Insert -> {
                    ledger?.record(lk, PipelineStage.DeduplicatedInsert, clock.now().epochMillis)
                    val cursor = buildCursor(action.observation.packageId, action.observation.notificationKey)
                    var persistResult = store.persistObservationWithCursor(action.observation, cursor)
                    var attempt = 0
                    while (persistResult.isError && attempt < maxWriteRetries) {
                        attempt++
                        ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis,
                            "Persist failed (attempt $attempt/${maxWriteRetries + 1}): ${(persistResult as PersistResult.Error).message} -- retrying")
                        persistResult = store.persistObservationWithCursor(action.observation, cursor)
                    }

                    if (persistResult.isError) {
                        // Do NOT cache in deduplicator -- keep retryable
                        val errorMsg = (persistResult as PersistResult.Error).message
                        ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis, "Persist failed after ${attempt + 1} attempt(s): $errorMsg")
                        return IngestProcessResult.Error("Persist failed: $errorMsg")
                    }

                    // Successful write -- cache in deduplicator and record stages with actual timestamps
                    deduplicator.confirmPersisted(action.observation)
                    ledger?.record(lk, PipelineStage.DbCommitted, clock.now().epochMillis)

                    // Independent readback verification
                    val readback = store.getObservationByNotificationKey(action.observation.notificationKey)
                    if (readback != null) {
                        ledger?.record(lk, PipelineStage.ReadbackVerified, clock.now().epochMillis)
                    }

                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(lk, PipelineStage.UiNotified, clock.now().epochMillis)
                    }
                    IngestProcessResult.Created(action.observation)
                }

                is NotificationDeduplicator.DedupeAction.Update -> {
                    ledger?.record(lk, PipelineStage.DeduplicatedUpdate, clock.now().epochMillis)
                    val cursor = buildCursor(action.observation.packageId, action.observation.notificationKey)
                    var persistResult = store.persistObservationWithCursor(action.observation, cursor)
                    var attempt = 0
                    while (persistResult.isError && attempt < maxWriteRetries) {
                        attempt++
                        ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis,
                            "Persist failed (attempt $attempt/${maxWriteRetries + 1}): ${(persistResult as PersistResult.Error).message} -- retrying")
                        persistResult = store.persistObservationWithCursor(action.observation, cursor)
                    }

                    if (persistResult.isError) {
                        val errorMsg = (persistResult as PersistResult.Error).message
                        ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis, "Persist failed after ${attempt + 1} attempt(s): $errorMsg")
                        return IngestProcessResult.Error("Persist failed: $errorMsg")
                    }

                    deduplicator.confirmPersisted(action.observation)
                    ledger?.record(lk, PipelineStage.DbCommitted, clock.now().epochMillis)

                    val readback = store.getObservationByNotificationKey(action.observation.notificationKey)
                    if (readback != null) {
                        ledger?.record(lk, PipelineStage.ReadbackVerified, clock.now().epochMillis)
                    }

                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(lk, PipelineStage.UiNotified, clock.now().epochMillis)
                    }
                    IngestProcessResult.Updated(action.observation, action.priorRevision)
                }

                is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                    ledger?.record(lk, PipelineStage.DeduplicatedDrop, clock.now().epochMillis)
                    IngestProcessResult.DuplicateIgnored(action.notificationKey)
                }

                is NotificationDeduplicator.DedupeAction.Remove -> {
                    ledger?.record(lk, PipelineStage.DeduplicatedRemove, clock.now().epochMillis)
                    val count = store.markRemoved(action.notificationKey, clock.now())
                    ledger?.record(lk, PipelineStage.DbCommitted, clock.now().epochMillis)
                    IngestProcessResult.Removed(action.notificationKey, count)
                }
            }
        } catch (e: Throwable) {
            ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis, e.message)
            IngestProcessResult.Error(e.message ?: e::class.simpleName ?: "Ingest processing error")
        }
    }

    /**
     * Resolve the deduplication action for [normalized] within a single batch, consulting
     * the intra-batch [pending] cache before falling through to the committed deduplicator.
     *
     * [pending] maps notificationKey -> the ObservedNotification that will be committed for
     * that key once the batch succeeds.  [removed] tracks keys that have been removed in
     * this batch already.
     */
    private fun resolveBatchAction(
        normalized: ObservedNotification,
        pending: Map<String, ObservedNotification>,
        removed: Set<String>,
    ): NotificationDeduplicator.DedupeAction {
        if (normalized.isRemoved) {
            // Always honour a removal, regardless of pending state.
            return NotificationDeduplicator.DedupeAction.Remove(normalized.notificationKey)
        }

        // Check the intra-batch state first.
        val batchExisting = pending[normalized.notificationKey]
        if (batchExisting != null) {
            // Key already processed earlier in this same batch.
            if (batchExisting.postTime == normalized.postTime &&
                batchExisting.rawTextDigest == normalized.rawTextDigest) {
                return NotificationDeduplicator.DedupeAction.DropDuplicate(normalized.notificationKey)
            }
            val updated = normalized.copy(
                id = batchExisting.id,
                revision = batchExisting.revision + 1L,
            )
            return NotificationDeduplicator.DedupeAction.Update(updated, batchExisting.revision)
        }

        // Key was removed earlier in this same batch — treat as a fresh insert if it arrives
        // again (Android can re-post a notification after removal in the same delivery window).
        if (normalized.notificationKey in removed) {
            return NotificationDeduplicator.DedupeAction.Insert(normalized)
        }

        // Fall through to the committed deduplicator for cross-batch state.
        return deduplicator.evaluate(normalized, store)
    }

    /**
     * Drains pending queue items and persists them in an atomic batch to minimize database transactions.
     * Observations and cursors are committed together in a single transaction.
     * Failed batch writes are NOT cached in the deduplicator, keeping items retryable.
     *
     * Operation order within a batch is preserved: if the same notification key appears as both
     * an insert and a subsequent update (or removal), each operation is evaluated in arrival order.
     * For removals, markRemoved is called immediately so ordering relative to inserts/updates of
     * the same key within one drain call is correct.
     */
    fun drainAndProcessAll(maxBatch: Int = 1000): Int = synchronized(processingLock) {
        drainAndProcessAllInternal(maxBatch)
    }

    private fun drainAndProcessAllInternal(maxBatch: Int): Int {
        val batch = queue.drain(maxBatch)
        if (batch.isEmpty()) return 0

        val observationsToPersist = mutableListOf<ObservedNotification>()
        val removalsToApply = mutableListOf<Pair<String, UtcInstant>>()
        val affectedAccounts = mutableSetOf<app.mini.hub.domain.AccountId>()
        // Parallel list of ledger keys (correlationId or payload.key) for each observation slot
        val ledgerKeysForBatch = mutableListOf<String>()
        val removalLedgerKeys = mutableListOf<String>()
        // Per-source cursor accumulator: source -> (lastKey, countIncrement)
        val cursorAccum = mutableMapOf<String, Pair<String, Long>>()
        var processedCount = 0

        // Intra-batch deduplication state: mirrors what will be cached in the real deduplicator
        // once the batch commits.  This allows Update/Drop/Remove to resolve correctly for
        // subsequent payloads targeting the same notificationKey within a single drain call.
        // Keys removed within the batch are explicitly tracked so Remove following an Insert
        // (in the same batch) correctly sees the key as absent on restart.
        val batchPending = mutableMapOf<String, ObservedNotification>()
        val batchRemoved = mutableSetOf<String>()

        for (payload in batch) {
            val lk = payload.correlationId ?: payload.key
            try {
                val normalized = normalizer.normalize(payload, clock.now())
                ledger?.record(lk, PipelineStage.Normalized, clock.now().epochMillis)

                // Resolve deduplication against intra-batch state first, then fall through to the
                // committed deduplicator (which has no knowledge of earlier items in this batch).
                val action = resolveBatchAction(normalized, batchPending, batchRemoved)

                when (action) {
                    is NotificationDeduplicator.DedupeAction.Insert -> {
                        ledger?.record(lk, PipelineStage.DeduplicatedInsert, clock.now().epochMillis)
                        observationsToPersist.add(action.observation)
                        ledgerKeysForBatch.add(lk)
                        affectedAccounts.add(action.observation.accountId)
                        val (_, cnt) = cursorAccum[action.observation.packageId] ?: Pair("", 0L)
                        cursorAccum[action.observation.packageId] = Pair(action.observation.notificationKey, cnt + 1L)
                        batchPending[action.observation.notificationKey] = action.observation
                        // A re-post after a removal in this same batch is active again. Remove
                        // the earlier pending removal so the final state follows arrival order.
                        batchRemoved.remove(action.observation.notificationKey)
                        removalsToApply.removeAll { it.first == action.observation.notificationKey }
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Update -> {
                        ledger?.record(lk, PipelineStage.DeduplicatedUpdate, clock.now().epochMillis)
                        // Replace existing slot in observationsToPersist with the updated version
                        // (same notificationKey) so the batch upsert produces the final state.
                        val existingIdx = observationsToPersist.indexOfLast { it.notificationKey == action.observation.notificationKey }
                        if (existingIdx >= 0) {
                            observationsToPersist[existingIdx] = action.observation
                            ledgerKeysForBatch[existingIdx] = lk
                        } else {
                            observationsToPersist.add(action.observation)
                            ledgerKeysForBatch.add(lk)
                        }
                        affectedAccounts.add(action.observation.accountId)
                        val (_, cnt) = cursorAccum[action.observation.packageId] ?: Pair("", 0L)
                        cursorAccum[action.observation.packageId] = Pair(action.observation.notificationKey, cnt + 1L)
                        batchPending[action.observation.notificationKey] = action.observation
                        batchRemoved.remove(action.observation.notificationKey)
                        removalsToApply.removeAll { it.first == action.observation.notificationKey }
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                        ledger?.record(lk, PipelineStage.DeduplicatedDrop, clock.now().epochMillis)
                        val (_, cnt) = cursorAccum[normalized.packageId] ?: Pair("", 0L)
                        cursorAccum[normalized.packageId] = Pair(normalized.notificationKey, cnt + 1L)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Remove -> {
                        // Defer removals into the same atomic store operation as writes and
                        // checkpoints. The store applies observations first, then removals, so
                        // insert/update followed by removal in one drain call is committed as
                        // removed without exposing an intermediate state.
                        ledger?.record(lk, PipelineStage.DeduplicatedRemove, clock.now().epochMillis)
                        batchPending.remove(action.notificationKey)
                        batchRemoved.add(action.notificationKey)
                        removalsToApply.removeAll { it.first == action.notificationKey }
                        removalsToApply.add(action.notificationKey to clock.now())
                        removalLedgerKeys.add(lk)
                        val (_, cnt) = cursorAccum[normalized.packageId] ?: Pair("", 0L)
                        cursorAccum[normalized.packageId] = Pair(normalized.notificationKey, cnt + 1L)
                        processedCount++
                    }
                }
            } catch (e: Throwable) {
                ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis, e.message)
            }
        }

        if (observationsToPersist.isNotEmpty() || removalsToApply.isNotEmpty()) {
            // Build one merged cursor per source, adding to any pre-existing cursor count
            val cursorsToCheckpoint = cursorAccum.map { (source, pair) ->
                val (lastKey, increment) = pair
                val existing = store.getCursor(source)
                IngestCursor(
                    source = source,
                    lastProcessedTimeMillis = clock.now().epochMillis,
                    lastProcessedKey = lastKey,
                    processedCount = (existing?.processedCount ?: 0L) + increment,
                    gapCount = existing?.gapCount ?: 0L,
                )
            }

            // Atomic batch: observations + removals + cursors in one transaction. Retry the
            // complete transaction, not individual rows, so a transient failure cannot leave
            // removals, writes, and checkpoints at different commit points.
            var batchResult = store.persistAtomicBatch(
                removals = removalsToApply,
                observations = observationsToPersist,
                cursors = cursorsToCheckpoint,
            )
            var batchRetry = 0
            while (!batchResult.isFullySuccessful && batchRetry < maxWriteRetries) {
                batchRetry++
                val detail = batchResult.errors.firstOrNull() ?: "batch persist failed"
                for (lk in ledgerKeysForBatch + removalLedgerKeys) {
                    ledger?.record(
                        lk,
                        PipelineStage.Error,
                        clock.now().epochMillis,
                        "Batch persist failed (attempt ${batchRetry + 1}/${maxWriteRetries + 1}): $detail -- retrying",
                    )
                }
                batchResult = store.persistAtomicBatch(
                    removals = removalsToApply,
                    observations = observationsToPersist,
                    cursors = cursorsToCheckpoint,
                )
            }

            if (batchResult.isFullySuccessful) {
                // Confirm all in deduplicator and record stages with actual completion timestamps
                for (obs in observationsToPersist) {
                    deduplicator.confirmPersisted(obs)
                }
                for ((notificationKey, _) in removalsToApply) {
                    deduplicator.forget(notificationKey)
                }
                for (lk in ledgerKeysForBatch) {
                    ledger?.record(lk, PipelineStage.DbCommitted, clock.now().epochMillis)
                }
                for (lk in removalLedgerKeys) {
                    ledger?.record(lk, PipelineStage.DbCommitted, clock.now().epochMillis)
                }

                // Readback verification (sample first and last for efficiency). A removal-only
                // batch has no observation slots to verify.
                if (observationsToPersist.isNotEmpty()) {
                    val verifyIndices = buildList {
                        add(0)
                        if (observationsToPersist.size > 1) add(observationsToPersist.size - 1)
                    }
                    for (idx in verifyIndices) {
                        val obs = observationsToPersist[idx]
                        val readback = store.getObservationByNotificationKey(obs.notificationKey)
                        if (readback != null) {
                            ledger?.record(ledgerKeysForBatch[idx], PipelineStage.ReadbackVerified, clock.now().epochMillis)
                        }
                    }
                }

                for (acc in affectedAccounts) {
                    val summaries = store.getConversationSummaries(acc)
                    summaries.firstOrNull()?.let {
                        uiListener?.onConversationUpdated(it)
                    }
                }
            } else {
                // Batch failed -- do NOT confirm in deduplicator (keep retryable)
                val errorDetail = batchResult.errors.firstOrNull() ?: "batch persist failed"
                for (lk in ledgerKeysForBatch + removalLedgerKeys) {
                    ledger?.record(lk, PipelineStage.Error, clock.now().epochMillis, errorDetail)
                }
            }
        }

        return processedCount
    }

    fun getRecentConversations(): List<ConversationObservationSummary> {
        return store.getConversationSummaries()
    }

    fun getConversationTimeline(conversationId: ScopedId): List<ObservedNotification> {
        return store.getObservationsForConversation(conversationId)
    }

    fun recordDroppedGap(source: String) {
        val existing = store.getCursor(source)
        val cursor = IngestCursor(
            source = source,
            lastProcessedTimeMillis = existing?.lastProcessedTimeMillis ?: clock.now().epochMillis,
            lastProcessedKey = existing?.lastProcessedKey,
            processedCount = existing?.processedCount ?: 0L,
            gapCount = (existing?.gapCount ?: 0L) + 1L,
        )
        store.checkpointCursor(cursor)
    }

    private fun buildCursor(source: String, key: String): IngestCursor {
        val existing = store.getCursor(source)
        return IngestCursor(
            source = source,
            lastProcessedTimeMillis = clock.now().epochMillis,
            lastProcessedKey = key,
            processedCount = (existing?.processedCount ?: 0L) + 1L,
            gapCount = existing?.gapCount ?: 0L,
        )
    }
}
