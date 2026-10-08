package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.IngestCursor
import app.mini.hub.observation.ObservedNotification

sealed class PersistResult {
    data class Success(val id: ScopedId, val revision: Long) : PersistResult()
    data class Error(val message: String) : PersistResult()
}

data class RestartRecoveryReport(
    val totalObservations: Int,
    val totalConversations: Int,
    val lastCursor: IngestCursor?,
)

interface ObservationStore {
    val qualification: StorageQualification
    fun persistObservation(observation: ObservedNotification): PersistResult
    fun getObservation(id: ScopedId): ObservedNotification?
    fun getObservationsForConversation(conversationId: ScopedId, limit: Int = 100): List<ObservedNotification>
    fun getConversationSummaries(accountId: AccountId? = null): List<ConversationObservationSummary>
    fun markRemoved(notificationKey: String, removedAt: UtcInstant): Int
    fun checkpointCursor(cursor: IngestCursor)
    fun getCursor(source: String): IngestCursor?
    fun recoverFromRestart(): RestartRecoveryReport
}

/**
 * Deterministic in-memory implementation of [ObservationStore] for JVM test runs.
 */
class InMemoryObservationStore : ObservationStore {
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly

    private val lock = Any()
    private val observations = LinkedHashMap<String, ObservedNotification>()
    private val conversations = LinkedHashMap<String, ConversationObservationSummary>()
    private val cursors = mutableMapOf<String, IngestCursor>()

    override fun persistObservation(observation: ObservedNotification): PersistResult = synchronized(lock) {
        observations[observation.id.key] = observation

        // Update conversation summary
        val convKey = observation.conversationId.key
        val existingConv = conversations[convKey]
        val snippet = observation.textFragments.lastOrNull() ?: observation.title ?: ""
        val unread = (existingConv?.unreadCount ?: 0) + if (observation.isRemoved) 0 else 1
        val totalObs = (existingConv?.observationCount ?: 0) + 1

        val gapCount = cursors[observation.packageId]?.gapCount ?: 0L
        val coverageExplanation = if (gapCount > 0L) {
            "Observed via Android notifications only ($gapCount dropped events due to backpressure). Prior history is not visible."
        } else {
            "Observed via Android notifications only. Prior history and out-of-band replies are not visible."
        }

        val summary = ConversationObservationSummary(
            conversationId = observation.conversationId,
            accountId = observation.accountId,
            title = observation.title ?: existingConv?.title ?: observation.senderHint,
            senderHint = observation.senderHint,
            packageId = observation.packageId,
            latestSnippet = snippet,
            coverage = ConversationCoverage.NotificationOnly,
            coverageExplanation = coverageExplanation,
            latestEventAt = observation.observedAt,
            observationCount = totalObs,
            unreadCount = unread,
            isRemoved = observation.isRemoved,
        )
        conversations[convKey] = summary

        PersistResult.Success(observation.id, observation.revision)
    }

    override fun getObservation(id: ScopedId): ObservedNotification? = synchronized(lock) {
        observations[id.key]
    }

    override fun getObservationsForConversation(conversationId: ScopedId, limit: Int): List<ObservedNotification> = synchronized(lock) {
        observations.values
            .filter { it.conversationId == conversationId }
            .sortedByDescending { it.observedAt.epochMillis }
            .take(limit)
    }

    override fun getConversationSummaries(accountId: AccountId?): List<ConversationObservationSummary> = synchronized(lock) {
        conversations.values
            .filter { accountId == null || it.accountId == accountId }
            .sortedByDescending { it.latestEventAt.epochMillis }
    }

    override fun markRemoved(notificationKey: String, removedAt: UtcInstant): Int = synchronized(lock) {
        var count = 0
        val matchingKeys = observations.values
            .filter { it.notificationKey == notificationKey && !it.isRemoved }
            .map { it.id.key }

        for (key in matchingKeys) {
            val current = observations[key] ?: continue
            val updated = current.copy(isRemoved = true, revision = current.revision + 1L)
            observations[key] = updated
            count++

            // Also update conversation summary
            val convKey = updated.conversationId.key
            val conv = conversations[convKey]
            if (conv != null) {
                conversations[convKey] = conv.copy(isRemoved = true)
            }
        }
        count
    }

    override fun checkpointCursor(cursor: IngestCursor) = synchronized(lock) {
        cursors[cursor.source] = cursor
    }

    override fun getCursor(source: String): IngestCursor? = synchronized(lock) {
        cursors[source]
    }

    override fun recoverFromRestart(): RestartRecoveryReport = synchronized(lock) {
        RestartRecoveryReport(
            totalObservations = observations.size,
            totalConversations = conversations.size,
            lastCursor = cursors.values.maxByOrNull { it.lastProcessedTimeMillis },
        )
    }
}
