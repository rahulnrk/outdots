package app.mini.hub.observation

import app.mini.hub.domain.Clock
import app.mini.hub.domain.ScopedId
import app.mini.hub.persistence.ObservationStore

fun interface ObservationUiListener {
    fun onConversationUpdated(summary: ConversationObservationSummary)
}

/**
 * Coordinates non-blocking notification ingestion, deduplication, encrypted persistence,
 * and UI notification dispatch. Supports atomic batch persistence and delivery accounting.
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
        val now = clock.now()
        val nowMs = now.epochMillis

        return try {
            val normalized = normalizer.normalize(payload, now)
            ledger?.record(payload.key, PipelineStage.Normalized, nowMs)
            val action = deduplicator.evaluate(normalized)

            when (action) {
                is NotificationDeduplicator.DedupeAction.Insert -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedInsert, nowMs)
                    store.persistObservation(action.observation)
                    ledger?.record(payload.key, PipelineStage.DbCommitted, nowMs)
                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(payload.key, PipelineStage.UiNotified, nowMs)
                    }
                    updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                    IngestProcessResult.Created(action.observation)
                }

                is NotificationDeduplicator.DedupeAction.Update -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedUpdate, nowMs)
                    store.persistObservation(action.observation)
                    ledger?.record(payload.key, PipelineStage.DbCommitted, nowMs)
                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                        ledger?.record(payload.key, PipelineStage.UiNotified, nowMs)
                    }
                    updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                    IngestProcessResult.Updated(action.observation, action.priorRevision)
                }

                is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedDrop, nowMs)
                    IngestProcessResult.DuplicateIgnored(action.notificationKey)
                }

                is NotificationDeduplicator.DedupeAction.Remove -> {
                    ledger?.record(payload.key, PipelineStage.DeduplicatedRemove, nowMs)
                    val count = store.markRemoved(action.notificationKey, now)
                    ledger?.record(payload.key, PipelineStage.DbCommitted, nowMs)
                    IngestProcessResult.Removed(action.notificationKey, count)
                }
            }
        } catch (e: Throwable) {
            ledger?.record(payload.key, PipelineStage.Error, nowMs, e.message)
            IngestProcessResult.Error(e.message ?: e::class.simpleName ?: "Ingest processing error")
        }
    }

    /**
     * Drains pending queue items and persists them in an atomic batch to minimize database transactions.
     */
    fun drainAndProcessAll(maxBatch: Int = 1000): Int {
        val batch = queue.drain(maxBatch)
        if (batch.isEmpty()) return 0
        val now = clock.now()
        val nowMs = now.epochMillis

        val observationsToPersist = mutableListOf<ObservedNotification>()
        val affectedAccounts = mutableSetOf<app.mini.hub.domain.AccountId>()
        val keysCommitted = mutableListOf<String>()
        var processedCount = 0

        for (payload in batch) {
            try {
                val normalized = normalizer.normalize(payload, now)
                ledger?.record(payload.key, PipelineStage.Normalized, nowMs)
                val action = deduplicator.evaluate(normalized)

                when (action) {
                    is NotificationDeduplicator.DedupeAction.Insert -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedInsert, nowMs)
                        observationsToPersist.add(action.observation)
                        keysCommitted.add(payload.key)
                        affectedAccounts.add(action.observation.accountId)
                        updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Update -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedUpdate, nowMs)
                        observationsToPersist.add(action.observation)
                        keysCommitted.add(payload.key)
                        affectedAccounts.add(action.observation.accountId)
                        updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedDrop, nowMs)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Remove -> {
                        ledger?.record(payload.key, PipelineStage.DeduplicatedRemove, nowMs)
                        store.markRemoved(action.notificationKey, now)
                        ledger?.record(payload.key, PipelineStage.DbCommitted, nowMs)
                        processedCount++
                    }
                }
            } catch (e: Throwable) {
                ledger?.record(payload.key, PipelineStage.Error, nowMs, e.message)
            }
        }

        if (observationsToPersist.isNotEmpty()) {
            store.persistBatch(observationsToPersist)
            for (key in keysCommitted) {
                ledger?.record(key, PipelineStage.DbCommitted, nowMs)
            }
            for (acc in affectedAccounts) {
                val summaries = store.getConversationSummaries(acc)
                summaries.firstOrNull()?.let {
                    uiListener?.onConversationUpdated(it)
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

    private fun updateCursor(source: String, timeMillis: Long, key: String) {
        val existing = store.getCursor(source)
        val cursor = IngestCursor(
            source = source,
            lastProcessedTimeMillis = timeMillis,
            lastProcessedKey = key,
            processedCount = (existing?.processedCount ?: 0L) + 1L,
            gapCount = existing?.gapCount ?: 0L,
        )
        store.checkpointCursor(cursor)
    }
}
