package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.IngestCursor
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservedNotification

sealed class PersistResult {
    data class Success(val id: ScopedId, val revision: Long) : PersistResult()
    data class Error(val message: String) : PersistResult()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
}

data class BatchPersistResult(
    val successCount: Int,
    val failedCount: Int,
    val errors: List<String>,
) {
    val isFullySuccessful: Boolean get() = failedCount == 0
}

data class RestartRecoveryReport(
    val totalObservations: Int,
    val totalConversations: Int,
    val lastCursor: IngestCursor?,
)

interface ObservationStore {
    val qualification: StorageQualification
    fun persistObservation(observation: ObservedNotification): PersistResult
    fun persistBatch(observations: List<ObservedNotification>): Int
    fun getObservation(id: ScopedId): ObservedNotification?
    fun getObservationsForConversation(conversationId: ScopedId, limit: Int = 100): List<ObservedNotification>
    fun getConversationSummaries(accountId: AccountId? = null): List<ConversationObservationSummary>
    fun markRemoved(notificationKey: String, removedAt: UtcInstant): Int
    fun checkpointCursor(cursor: IngestCursor)
    fun getCursor(source: String): IngestCursor?
    fun recoverFromRestart(): RestartRecoveryReport

    /**
     * Atomically persist a batch of observations and checkpoint cursors in a single transaction.
     * If any write fails, the entire batch is rolled back and no cursors are updated.
     */
    fun persistBatchWithCursors(
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult

    /**
     * Atomically persist one observation and checkpoint its cursor in a single transaction.
     * Returns PersistResult.Error if either write fails (neither is committed).
     */
    fun persistObservationWithCursor(
        observation: ObservedNotification,
        cursor: IngestCursor,
    ): PersistResult

    /**
     * Read back a specific observation by its notification key.
     * Used for independent verification after writes.
     */
    fun getObservationByNotificationKey(notificationKey: String): ObservedNotification?
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
        simulatedPersistError?.let { return PersistResult.Error(it) }
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

        val category = when {
            observation.isOngoing -> NotificationCategory.OngoingService
            observation.isGroupSummary -> NotificationCategory.GroupMessage
            observation.packageId.contains("twitter") || observation.packageId.contains("instagram") -> NotificationCategory.SocialUpdate
            observation.packageId == "android" || observation.packageId.startsWith("com.android.systemui") -> NotificationCategory.SystemStatus
            observation.packageId.contains("whatsapp") || observation.packageId.contains("orca") -> NotificationCategory.DirectMessage
            else -> NotificationCategory.GeneralNotification
        }

        val appName = when {
            observation.packageId.contains("twitter", ignoreCase = true) -> "X"
            observation.packageId.contains("whoop", ignoreCase = true) -> "WHOOP"
            observation.packageId.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            observation.packageId.contains("android.gm", ignoreCase = true) -> "Gmail"
            observation.packageId.contains("facebook.orca", ignoreCase = true) -> "Messenger"
            observation.packageId.contains("facebook", ignoreCase = true) -> "Facebook"
            observation.packageId.contains("instagram", ignoreCase = true) -> "Instagram"
            observation.packageId.contains("linkedin", ignoreCase = true) -> "LinkedIn"
            observation.packageId.contains("slack", ignoreCase = true) -> "Slack"
            observation.packageId == "android" -> "Android System"
            observation.packageId.startsWith("com.android.systemui") -> "System UI"
            else -> observation.packageId.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        val summary = ConversationObservationSummary(
            conversationId = observation.conversationId,
            accountId = observation.accountId,
            title = observation.title ?: existingConv?.title ?: observation.senderHint,
            appName = appName,
            senderHint = observation.senderHint,
            packageId = observation.packageId,
            latestSnippet = snippet,
            category = category,
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

    override fun persistBatch(observations: List<ObservedNotification>): Int = synchronized(lock) {
        var count = 0
        for (obs in observations) {
            val result = persistObservation(obs)
            if (result.isSuccess) count++
        }
        count
    }

    override fun persistBatchWithCursors(
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult = synchronized(lock) {
        // Snapshot for rollback
        val obsSnapshot = LinkedHashMap(this.observations)
        val convSnapshot = LinkedHashMap(conversations)
        val cursorSnapshot = this.cursors.toMutableMap()

        val errors = mutableListOf<String>()
        var successCount = 0

        try {
            for (obs in observations) {
                val result = persistObservation(obs)
                if (result.isError) {
                    errors.add((result as PersistResult.Error).message)
                } else {
                    successCount++
                }
            }

            if (errors.isNotEmpty()) {
                // Rollback all changes
                this.observations.clear()
                this.observations.putAll(obsSnapshot)
                conversations.clear()
                conversations.putAll(convSnapshot)
                this.cursors.clear()
                this.cursors.putAll(cursorSnapshot)
                return BatchPersistResult(0, observations.size, errors)
            }

            for (cursor in cursors) {
                checkpointCursor(cursor)
            }

            BatchPersistResult(successCount, 0, emptyList())
        } catch (e: Throwable) {
            // Rollback on any exception
            this.observations.clear()
            this.observations.putAll(obsSnapshot)
            conversations.clear()
            conversations.putAll(convSnapshot)
            this.cursors.clear()
            this.cursors.putAll(cursorSnapshot)
            BatchPersistResult(0, observations.size, listOf(e.message ?: "Unknown error"))
        }
    }

    override fun persistObservationWithCursor(
        observation: ObservedNotification,
        cursor: IngestCursor,
    ): PersistResult = synchronized(lock) {
        val obsSnapshot = LinkedHashMap(this.observations)
        val convSnapshot = LinkedHashMap(conversations)
        val cursorSnapshot = this.cursors.toMutableMap()

        val result = persistObservation(observation)
        if (result.isError) {
            // Rollback
            this.observations.clear()
            this.observations.putAll(obsSnapshot)
            conversations.clear()
            conversations.putAll(convSnapshot)
            this.cursors.clear()
            this.cursors.putAll(cursorSnapshot)
            return result
        }

        checkpointCursor(cursor)
        result
    }

    override fun getObservationByNotificationKey(notificationKey: String): ObservedNotification? = synchronized(lock) {
        observations.values.find { it.notificationKey == notificationKey && !it.isRemoved }
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

    /** Injected error simulation for testing: set to non-null to force persistObservation failures. */
    var simulatedPersistError: String? = null

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
