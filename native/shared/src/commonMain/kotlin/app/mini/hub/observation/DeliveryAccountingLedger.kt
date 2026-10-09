package app.mini.hub.observation

import app.mini.hub.domain.monotonicMillis

enum class PipelineStage {
    SourcePublished,
    ListenerReceived,
    QueueAdmitted,
    QueueDropped,
    Normalized,
    DeduplicatedInsert,
    DeduplicatedUpdate,
    DeduplicatedDrop,
    DeduplicatedRemove,
    DbCommitted,
    ReadbackVerified,
    UiNotified,
    Error,
}

data class StageEvent(
    val correlationId: String,
    val stage: PipelineStage,
    val timestampMillis: Long,
    /** Monotonic milliseconds at the time this event was recorded. Used for latency computation. */
    val monotonicMs: Long = timestampMillis,
    val details: String? = null,
)

data class EventDeliverySummary(
    val correlationId: String,
    val stagesPassed: List<PipelineStage>,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val totalLatencyMillis: Long,
    val isFullyDelivered: Boolean,
)

data class PipelineAccountingReport(
    val totalTrackedEvents: Int,
    val countsByStage: Map<PipelineStage, Int>,
    val p50LatencyMillis: Double,
    val p95LatencyMillis: Double,
    val p99LatencyMillis: Double,
    val fullyDeliveredCount: Int,
    val droppedCount: Int,
)

/**
 * Thread-safe, bounded telemetry ledger tracking synthetic events through all
 * pipeline stages to independently reconcile delivery without recording sensitive content.
 */
class DeliveryAccountingLedger(
    private val maxCapacity: Int = 2000,
) {
    private val lock = Any()
    private val stageEvents = mutableListOf<StageEvent>()

    fun record(
        correlationId: String,
        stage: PipelineStage,
        timestampMillis: Long,
        details: String? = null,
        monotonicMs: Long = monotonicMillis(),
    ): Unit = synchronized(lock) {
        if (correlationId.isBlank()) return
        if (stageEvents.size >= maxCapacity) {
            stageEvents.removeAt(0)
        }
        stageEvents.add(StageEvent(correlationId, stage, timestampMillis, monotonicMs, details))
    }

    fun getEvents(correlationId: String): List<StageEvent> = synchronized(lock) {
        stageEvents.filter { it.correlationId == correlationId }
    }

    fun summarizeEvent(correlationId: String): EventDeliverySummary? = synchronized(lock) {
        val evs = stageEvents.filter { it.correlationId == correlationId }
        if (evs.isEmpty()) return null
        val stages = evs.map { it.stage }
        val first = evs.minOf { it.timestampMillis }
        val last = evs.maxOf { it.timestampMillis }
        val isDelivered = (PipelineStage.DbCommitted in stages || PipelineStage.DeduplicatedDrop in stages)
        EventDeliverySummary(
            correlationId = correlationId,
            stagesPassed = stages,
            firstSeenMillis = first,
            lastSeenMillis = last,
            totalLatencyMillis = last - first,
            isFullyDelivered = isDelivered,
        )
    }

    fun generateReport(): PipelineAccountingReport = synchronized(lock) {
        val distinctIds = stageEvents.map { it.correlationId }.distinct()
        val stageCounts = mutableMapOf<PipelineStage, Int>()
        for (stage in PipelineStage.entries) {
            stageCounts[stage] = stageEvents.count { it.stage == stage }
        }

        val latencies = mutableListOf<Long>()
        var fullyDelivered = 0

        for (id in distinctIds) {
            val evs = stageEvents.filter { it.correlationId == id }
            val stages = evs.map { it.stage }
            if (PipelineStage.DbCommitted in stages || PipelineStage.DeduplicatedDrop in stages) {
                fullyDelivered++
                // Use monotonic timestamps for latency to avoid wall-clock jumps.
                val first = evs.minOf { it.monotonicMs }
                val commit = evs.find { it.stage == PipelineStage.DbCommitted }?.monotonicMs
                    ?: evs.maxOf { it.monotonicMs }
                latencies.add((commit - first).coerceAtLeast(0L))
            }
        }

        val sorted = latencies.sorted()
        val p50 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.50).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0

        PipelineAccountingReport(
            totalTrackedEvents = distinctIds.size,
            countsByStage = stageCounts,
            p50LatencyMillis = p50,
            p95LatencyMillis = p95,
            p99LatencyMillis = p99,
            fullyDeliveredCount = fullyDelivered,
            droppedCount = stageCounts[PipelineStage.QueueDropped] ?: 0,
        )
    }

    fun clear() = synchronized(lock) {
        stageEvents.clear()
    }
}
