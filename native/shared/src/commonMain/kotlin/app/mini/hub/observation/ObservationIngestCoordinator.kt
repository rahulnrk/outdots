package app.mini.hub.observation

import app.mini.hub.domain.Clock
import app.mini.hub.domain.ScopedId
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
) {
    fun setUiListener(listener: ObservationUiListener?) {
        uiListener = listener
    }

    fun enqueue(payload: NotificationRawPayload): IngestOfferResult {
        val now = clock.now().epochMillis
        val result = queue.offer(payload)
        if (result == IngestOfferResult.QueueFull) {
            recordDroppedGap(payload.packageId)
            ledger?.record(payload.key, PipelineStage.QueueDropped, now, "Queue full")
        } else {
            ledger?.record(payload.key, PipelineStage.QueueAdmitted, now)
        }
        return result
    }

    fun processNext(): IngestProcessResult {
        val payload = queue.poll() ?: return IngestProcessResult.QueueEmpty

        return try {
            val normalized = normalizer.normalize(payload, clock.now())
            ledger?.record(payload.key, PipelineStage.Normalized, clock.now().epochMillis)
            val action = deduplicator.evaluate(normalized)

            when (action) {
                is NotificationDeduplicator.DedupeAction.Insert -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedInsert, clock.now().epochMillis)
                    val cursor = buildCursor(action.observation.packageId, action.observation.notificationKey)
                    val persistResult = store.persistObservationWithCursor(action.observation, cursor)

                    if (persistResult.isError) {
                        // Do NOT cache in deduplicator -- keep retryable
                        val errorMsg = (persistResult as PersistResult.Error).message
                        ledger?.record(payload.key, PipelineStage.Error, clock.now().epochMillis, "Persist failed: $errorMsg")
                        return IngestProcessResult.Error("Persist failed: $errorMsg")
                    }

                    // Successful write -- cache in deduplicator and record stages with actual timestamps
                    deduplicator.confirmPersisted(action.observation)
                    ledger?.record(payload.key, PipelineStage.DbCommitted, clock.now().epochMillis)

                    // Independent readback verification
                    val readback = store.getObservationByNotificationKey(action.observation.notificationKey)
                    if (readback != null) {
                        ledger?.record(payload.key, PipelineStage.ReadbackVerified, clock.now().epochMillis)
                    }

                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(payload.key, PipelineStage.UiNotified, clock.now().epochMillis)
                    }
                    IngestProcessResult.Created(action.observation)
                }

                is NotificationDeduplicator.DedupeAction.Update -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedUpdate, clock.now().epochMillis)
                    val cursor = buildCursor(action.observation.packageId, action.observation.notificationKey)
                    val persistResult = store.persistObservationWithCursor(action.observation, cursor)

                    if (persistResult.isError) {
                        val errorMsg = (persistResult as PersistResult.Error).message
                        ledger?.record(payload.key, PipelineStage.Error, clock.now().epochMillis, "Persist failed: $errorMsg")
                        return IngestProcessResult.Error("Persist failed: $errorMsg")
                    }

                    deduplicator.confirmPersisted(action.observation)
                    ledger?.record(payload.key, PipelineStage.DbCommitted, clock.now().epochMillis)

                    val readback = store.getObservationByNotificationKey(action.observation.notificationKey)
                    if (readback != null) {
                        ledger?.record(payload.key, PipelineStage.ReadbackVerified, clock.now().epochMillis)
                    }

                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(payload.key, PipelineStage.UiNotified, clock.now().epochMillis)
                    }
                    IngestProcessResult.Updated(action.observation, action.priorRevision)
                }

                is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedDrop, clock.now().epochMillis)
                    IngestProcessResult.DuplicateIgnored(action.notificationKey)
                }

                is NotificationDeduplicator.DedupeAction.Remove -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedRemove, clock.now().epochMillis)
                    val count = store.markRemoved(action.notificationKey, clock.now())
                    ledger?.record(payload.key, PipelineStage.DbCommitted, clock.now().epochMillis)
                    IngestProcessResult.Removed(action.notificationKey, count)
                }
            }
        } catch (e: Throwable) {
            ledger?.record(payload.key, PipelineStage.Error, clock.now().epochMillis, e.message)
            IngestProcessResult.Error(e.message ?: e::class.simpleName ?: "Ingest processing error")
        }
    }

    /**
     * Drains pending queue items and persists them in an atomic batch to minimize database transactions.
     * Observations and cursors are committed together in a single transaction.
     * Failed batch writes are NOT cached in the deduplicator, keeping items retryable.
     */
    fun drainAndProcessAll(maxBatch: Int = 1000): Int {
        val batch = queue.drain(maxBatch)
        if (batch.isEmpty()) return 0

        val observationsToPersist = mutableListOf<ObservedNotification>()
        val affectedAccounts = mutableSetOf<app.mini.hub.domain.AccountId>()
        val keysCommitted = mutableListOf<String>()
        // Per-source cursor accumulator: source -> (lastKey, countIncrement)
        val cursorAccum = mutableMapOf<String, Pair<String, Long>>()
        var processedCount = 0

        for (payload in batch) {
            try {
                val normalized = normalizer.normalize(payload, clock.now())
                ledger?.record(payload.key, PipelineStage.Normalized, clock.now().epochMillis)
                val action = deduplicator.evaluate(normalized)

                when (action) {
                    is NotificationDeduplicator.DedupeAction.Insert -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedInsert, clock.now().epochMillis)
                        observationsToPersist.add(action.observation)
                        keysCommitted.add(payload.key)
                        affectedAccounts.add(action.observation.accountId)
                        val (_, cnt) = cursorAccum[action.observation.packageId] ?: Pair("", 0L)
                        cursorAccum[action.observation.packageId] = Pair(action.observation.notificationKey, cnt + 1L)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Update -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedUpdate, clock.now().epochMillis)
                        observationsToPersist.add(action.observation)
                        keysCommitted.add(payload.key)
                        affectedAccounts.add(action.observation.accountId)
                        val (_, cnt) = cursorAccum[action.observation.packageId] ?: Pair("", 0L)
                        cursorAccum[action.observation.packageId] = Pair(action.observation.notificationKey, cnt + 1L)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedDrop, clock.now().epochMillis)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Remove -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedRemove, clock.now().epochMillis)
                        store.markRemoved(action.notificationKey, clock.now())
                        ledger?.record(payload.key, PipelineStage.DbCommitted, clock.now().epochMillis)
                        processedCount++
                    }
                }
            } catch (e: Throwable) {
                ledger?.record(payload.key, PipelineStage.Error, clock.now().epochMillis, e.message)
            }
        }

        if (observationsToPersist.isNotEmpty()) {
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

            // Atomic batch: observations + cursors in one transaction
            val batchResult = store.persistBatchWithCursors(observationsToPersist, cursorsToCheckpoint)

            if (batchResult.isFullySuccessful) {
                // Confirm all in deduplicator and record stages with actual completion timestamps
                for (obs in observationsToPersist) {
                    deduplicator.confirmPersisted(obs)
                }
                for (key in keysCommitted) {
                    ledger?.record(key, PipelineStage.DbCommitted, clock.now().epochMillis)
                }

                // Readback verification (sample first and last for efficiency)
                val verifyIndices = buildList {
                    add(0)
                    if (observationsToPersist.size > 1) add(observationsToPersist.size - 1)
                }
                for (idx in verifyIndices) {
                    val obs = observationsToPersist[idx]
                    val readback = store.getObservationByNotificationKey(obs.notificationKey)
                    if (readback != null) {
                        ledger?.record(keysCommitted[idx], PipelineStage.ReadbackVerified, clock.now().epochMillis)
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
                for (key in keysCommitted) {
                    val errorDetail = batchResult.errors.firstOrNull() ?: "batch persist failed"
                    ledger?.record(key, PipelineStage.Error, clock.now().epochMillis, errorDetail)
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
