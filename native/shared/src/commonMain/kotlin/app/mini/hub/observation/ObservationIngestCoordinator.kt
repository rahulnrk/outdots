package app.mini.hub.observation

import app.mini.hub.domain.Clock
import app.mini.hub.domain.ScopedId
import app.mini.hub.persistence.ObservationStore

fun interface ObservationUiListener {
    fun onConversationUpdated(summary: ConversationObservationSummary)
}

/**
 * Coordinates non-blocking notification ingestion, deduplication, encrypted persistence,
 * and UI notification dispatch. Supports atomic batch persistence for burst workloads.
 */
class ObservationIngestCoordinator(
    val queue: BoundedIngestQueue<NotificationRawPayload> = BoundedIngestQueue(),
    private val normalizer: NotificationNormalizer,
    private val deduplicator: NotificationDeduplicator = NotificationDeduplicator(),
    val store: ObservationStore,
    private val clock: Clock,
    private var uiListener: ObservationUiListener? = null,
) {
    fun setUiListener(listener: ObservationUiListener?) {
        uiListener = listener
    }

    fun enqueue(payload: NotificationRawPayload): IngestOfferResult {
        val result = queue.offer(payload)
        if (result == IngestOfferResult.QueueFull) {
            recordDroppedGap(payload.packageId)
        }
        return result
    }

    fun processNext(): IngestProcessResult {
        val payload = queue.poll() ?: return IngestProcessResult.QueueEmpty
        val now = clock.now()

        return try {
            val normalized = normalizer.normalize(payload, now)
            val action = deduplicator.evaluate(normalized)

            when (action) {
                is NotificationDeduplicator.DedupeAction.Insert -> {
                    store.persistObservation(action.observation)
                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                    }
                    updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                    IngestProcessResult.Created(action.observation)
                }

                is NotificationDeduplicator.DedupeAction.Update -> {
                    store.persistObservation(action.observation)
                    val summary = store.getConversationSummaries(action.observation.accountId)
                        .find { it.conversationId == action.observation.conversationId }
                    if (summary != null) {
                        uiListener?.onConversationUpdated(summary)
                    }
                    updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                    IngestProcessResult.Updated(action.observation, action.priorRevision)
                }

                is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                    IngestProcessResult.DuplicateIgnored(action.notificationKey)
                }

                is NotificationDeduplicator.DedupeAction.Remove -> {
                    val count = store.markRemoved(action.notificationKey, now)
                    IngestProcessResult.Removed(action.notificationKey, count)
                }
            }
        } catch (e: Throwable) {
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

        val observationsToPersist = mutableListOf<ObservedNotification>()
        val affectedAccounts = mutableSetOf<app.mini.hub.domain.AccountId>()
        var processedCount = 0

        for (payload in batch) {
            try {
                val normalized = normalizer.normalize(payload, now)
                val action = deduplicator.evaluate(normalized)

                when (action) {
                    is NotificationDeduplicator.DedupeAction.Insert -> {
                        observationsToPersist.add(action.observation)
                        affectedAccounts.add(action.observation.accountId)
                        updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Update -> {
                        observationsToPersist.add(action.observation)
                        affectedAccounts.add(action.observation.accountId)
                        updateCursor(action.observation.packageId, now.epochMillis, action.observation.notificationKey)
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.DropDuplicate -> {
                        processedCount++
                    }

                    is NotificationDeduplicator.DedupeAction.Remove -> {
                        store.markRemoved(action.notificationKey, now)
                        processedCount++
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (observationsToPersist.isNotEmpty()) {
            store.persistBatch(observationsToPersist)
            for (acc in affectedAccounts) {
                val summaries = store.getConversationSummaries(acc)
                summaries.firstOrNull()?.let { uiListener?.onConversationUpdated(it) }
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
