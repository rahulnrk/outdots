package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.IngestCursor
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.coverageExplanationFor
import app.mini.hub.observation.coverageFor

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
     * Atomically apply removals, persist observations, and checkpoint cursors in one transaction.
     * Removals are applied first (in order); if any removal or write fails, the entire batch is
     * rolled back and no cursors are updated.  This is the correct primitive for drainAndProcessAll
     * so that removals and inserts/updates within the same drain call are committed atomically.
     */
    fun persistAtomicBatch(
        removals: List<Pair<String, UtcInstant>>,
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

    /** Debug/test readback that includes removed rows, unlike the user-facing lookup above. */
    fun getObservationByNotificationKeyIncludingRemoved(notificationKey: String): ObservedNotification?

    /** Debug/test lookup by synthetic notification ID, including removed rows. */
    fun getObservationByNotificationIdIncludingRemoved(packageId: String, notificationId: Int): ObservedNotification?

    /**
     * Unified search across all stored messages.
     * Searches title and text fragments. Excludes removed messages.
     * Returns matching conversations with snippet, account and package metadata.
     */
    fun search(query: app.mini.hub.search.SearchQuery): List<app.mini.hub.search.SearchResult> = emptyList()
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
        if (simulatedPersistFailuresRemaining > 0) {
            simulatedPersistFailuresRemaining--
            return PersistResult.Error(simulatedPersistError ?: "Transient simulated error")
        }
        simulatedPersistError?.let { return PersistResult.Error(it) }
        observations[observation.id.key] = observation

        // Update conversation summary
        val convKey = observation.conversationId.key
        val existingConv = conversations[convKey]
        val snippet = observation.textFragments.lastOrNull() ?: observation.title ?: ""
        val unread = (existingConv?.unreadCount ?: 0) + if (observation.isRemoved) 0 else 1
        val totalObs = (existingConv?.observationCount ?: 0) + 1

        val gapCount = cursors[observation.packageId]?.gapCount ?: 0L
        val coverageExplanation = coverageExplanationFor(observation.provenance, gapCount)

        val category = when {
            observation.isOngoing -> NotificationCategory.OngoingService
            observation.isGroupSummary -> NotificationCategory.GroupMessage
            observation.packageId.contains("twitter") || observation.packageId.contains("instagram") -> NotificationCategory.SocialUpdate
            observation.packageId == "android" || observation.packageId.startsWith("com.android.systemui") -> NotificationCategory.SystemStatus
            observation.packageId.contains("whatsapp") || observation.packageId.contains("orca") -> NotificationCategory.DirectMessage
            else -> NotificationCategory.GeneralNotification
        }

        val appName = app.mini.hub.observation.SourceAppNames.forPackage(observation.packageId)

        val summary = ConversationObservationSummary(
            conversationId = observation.conversationId,
            accountId = observation.accountId,
            title = observation.title ?: existingConv?.title ?: observation.senderHint,
            appName = appName,
            senderHint = observation.senderHint,
            packageId = observation.packageId,
            latestSnippet = snippet,
            category = category,
            coverage = coverageFor(observation.provenance),
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

    override fun persistAtomicBatch(
        removals: List<Pair<String, UtcInstant>>,
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult = synchronized(lock) {
        // Snapshot for atomic rollback
        val obsSnapshot = LinkedHashMap(this.observations)
        val convSnapshot = LinkedHashMap(conversations)
        val cursorSnapshot = this.cursors.toMutableMap()

        val errors = mutableListOf<String>()

        try {
            // Persist observations first, then apply removals. This preserves arrival-order
            // semantics for insert/update followed by removal in one atomic batch.
            var successCount = 0
            for (obs in observations) {
                val result = persistObservation(obs)
                if (result.isError) {
                    errors.add((result as PersistResult.Error).message)
                } else {
                    successCount++
                }
            }

            for ((key, removedAt) in removals) {
                markRemoved(key, removedAt)
            }

            if (errors.isNotEmpty()) {
                // Rollback everything — removals and writes
                this.observations.clear()
                this.observations.putAll(obsSnapshot)
                conversations.clear()
                conversations.putAll(convSnapshot)
                this.cursors.clear()
                this.cursors.putAll(cursorSnapshot)
                return BatchPersistResult(0, removals.size + observations.size, errors)
            }

            for (cursor in cursors) {
                checkpointCursor(cursor)
            }

            BatchPersistResult(removals.size + successCount, 0, emptyList())
        } catch (e: Throwable) {
            this.observations.clear()
            this.observations.putAll(obsSnapshot)
            conversations.clear()
            conversations.putAll(convSnapshot)
            this.cursors.clear()
            this.cursors.putAll(cursorSnapshot)
            BatchPersistResult(0, removals.size + observations.size, listOf(e.message ?: "Unknown error"))
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

    override fun getObservationByNotificationKeyIncludingRemoved(notificationKey: String): ObservedNotification? = synchronized(lock) {
        observations.values.find { it.notificationKey == notificationKey }
    }

    override fun getObservationByNotificationIdIncludingRemoved(packageId: String, notificationId: Int): ObservedNotification? = synchronized(lock) {
        observations.values
            .filter { it.packageId == packageId && it.notificationId == notificationId }
            .maxByOrNull { it.observedAt.epochMillis }
    }

    override fun getObservation(id: ScopedId): ObservedNotification? = synchronized(lock) {
        observations[id.key]
    }

    override fun getObservationsForConversation(conversationId: ScopedId, limit: Int): List<ObservedNotification> = synchronized(lock) {
        observations.values
            .filter { it.conversationId == conversationId }
            .sortedWith(TIMELINE_ORDER)
            .take(limit)
    }

    companion object {
        /** Same order as [ObservationSchema.TIMELINE_ORDER]: event time, fetch time, id; newest first. */
        val TIMELINE_ORDER: Comparator<ObservedNotification> =
            compareByDescending<ObservedNotification> { it.postTime.epochMillis }
                .thenByDescending { it.observedAt.epochMillis }
                .thenByDescending { it.id.key }
    }

    override fun getConversationSummaries(accountId: AccountId?): List<ConversationObservationSummary> = synchronized(lock) {
        conversations.values
            .filter { accountId == null || it.accountId == accountId }
            .sortedByDescending { it.latestEventAt.epochMillis }
    }

    /** Injected error simulation for testing: set to non-null to force persistObservation failures. */
    var simulatedPersistError: String? = null

    /** Fail this many individual persistence attempts, then allow later attempts to succeed. */
    var simulatedPersistFailuresRemaining: Int = 0

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

    override fun search(query: app.mini.hub.search.SearchQuery): List<app.mini.hub.search.SearchResult> = synchronized(lock) {
        val q = query.queryText.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Find matching non-removed observations
        val matches = observations.values
            .filter { !it.isRemoved }
            .filter { query.sourceFilter == null || it.packageId == query.sourceFilter }
            .filter { obs ->
                val fullText = (listOfNotNull(obs.title, obs.senderHint) + obs.textFragments).joinToString(" ").lowercase()
                terms.all { term -> fullText.contains(term) }
            }

        // Group by conversation
        val grouped = matches.groupBy { it.conversationId }
        return grouped.map { (convId, obsList) ->
            val summary = conversations[convId.key]
            val newest = obsList.maxByOrNull { it.postTime.epochMillis } ?: obsList.first()
            val snippet = newest.textFragments.firstOrNull { f -> terms.any { f.lowercase().contains(it) } }
                ?: newest.textFragments.firstOrNull() ?: newest.title ?: ""
            app.mini.hub.search.SearchResult(
                conversationId = convId,
                accountId = newest.accountId,
                packageId = newest.packageId,
                conversationTitle = summary?.title ?: newest.title ?: newest.senderHint,
                senderHint = newest.senderHint,
                snippet = snippet,
                eventAt = newest.postTime,
                messageCount = summary?.observationCount ?: obsList.size,
            )
        }.sortedByDescending { it.eventAt.epochMillis }.take(query.limit)
    }
}
