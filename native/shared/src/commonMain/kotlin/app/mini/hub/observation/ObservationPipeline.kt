package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

enum class IngestOfferResult {
    Accepted,
    QueueFull,
    Coalesced,
}

sealed class IngestProcessResult {
    data class Created(val observation: ObservedNotification) : IngestProcessResult()
    data class Updated(val observation: ObservedNotification, val priorRevision: Long) : IngestProcessResult()
    data class DuplicateIgnored(val notificationKey: String) : IngestProcessResult()
    data class Removed(val notificationKey: String, val count: Int) : IngestProcessResult()
    data object QueueEmpty : IngestProcessResult()
    data class Error(val error: String) : IngestProcessResult()
}

/**
 * Thread-safe, bounded, non-blocking queue for inbound notification events.
 * Callbacks from Android NotificationListenerService offer items here and return immediately.
 */
class BoundedIngestQueue<T>(
    val capacity: Int = 256,
) {
    init {
        require(capacity in 1..1024) { "Capacity out of bounds (1..1024): $capacity" }
    }

    private val lock = Any()
    private val deque = ArrayDeque<T>(capacity)

    val size: Int get() = synchronized(lock) { deque.size }
    val isEmpty: Boolean get() = synchronized(lock) { deque.isEmpty() }

    fun offer(item: T): IngestOfferResult = synchronized(lock) {
        if (deque.size >= capacity) {
            IngestOfferResult.QueueFull
        } else {
            deque.addLast(item)
            IngestOfferResult.Accepted
        }
    }

    fun poll(): T? = synchronized(lock) {
        if (deque.isEmpty()) null else deque.removeFirst()
    }

    fun drain(maxItems: Int = 100): List<T> = synchronized(lock) {
        val result = mutableListOf<T>()
        var count = 0
        while (count < maxItems && deque.isNotEmpty()) {
            result.add(deque.removeFirst())
            count++
        }
        result
    }

    fun clear(): Unit = synchronized(lock) {
        deque.clear()
    }
}

/**
 * Maps Android package identifiers to MINI Providers and constructs normalized models.
 */
class NotificationNormalizer(
    private val idGenerator: IdGenerator,
    private val digestProvider: DigestProvider,
) {
    fun normalize(
        payload: NotificationRawPayload,
        observedAt: UtcInstant,
    ): ObservedNotification {
        val provider = mapPackageToProvider(payload.packageId)
        val account = AccountId(provider, "local_user")

        val conversationKey = deriveConversationKey(payload)
        val conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, conversationKey)
        val observationId = ScopedId(account, ScopedId.Kind.OBSERVATION, idGenerator.next("obs"))

        val fragments = if (payload.textFragments.isNotEmpty()) {
            payload.textFragments
        } else if (payload.text.isNotBlank()) {
            listOf(payload.text)
        } else {
            emptyList()
        }

        val digest = computeContentDigest(payload, fragments)
        val truncation = detectTruncation(payload)

        return ObservedNotification(
            id = observationId,
            accountId = account,
            conversationId = conversationId,
            packageId = payload.packageId,
            notificationKey = payload.key,
            notificationId = payload.notificationId,
            tag = payload.tag,
            senderHint = payload.senderHint.ifBlank { payload.title ?: "Unknown" },
            title = payload.title,
            textFragments = fragments,
            rawTextDigest = digest,
            observedAt = observedAt,
            postTime = UtcInstant(payload.postTimeMillis),
            revision = 1L,
            isRemoved = payload.isRemoved,
            isGroupSummary = payload.isGroupSummary,
            truncationState = truncation,
            correlationConfidence = if (payload.isGroupSummary) CorrelationConfidence.Medium else CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
    }

    private fun mapPackageToProvider(packageId: String): Provider = when {
        packageId.contains("whatsapp", ignoreCase = true) -> Provider.WHATSAPP
        packageId.contains("android.gm", ignoreCase = true) -> Provider.GMAIL
        packageId.contains("outlook", ignoreCase = true) -> Provider.OUTLOOK
        packageId.contains("teams", ignoreCase = true) -> Provider.TEAMS
        packageId.contains("facebook.orca", ignoreCase = true) -> Provider.FACEBOOK
        packageId.contains("instagram", ignoreCase = true) -> Provider.INSTAGRAM
        packageId.contains("linkedin", ignoreCase = true) -> Provider.LINKEDIN
        else -> Provider.WHATSAPP
    }

    private fun deriveConversationKey(payload: NotificationRawPayload): String {
        val titleClean = payload.title?.trim()?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (!titleClean.isNullOrBlank()) return titleClean
        val tagClean = payload.tag?.trim()?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (!tagClean.isNullOrBlank()) return tagClean
        return "notif_${payload.notificationId}"
    }

    private fun computeContentDigest(payload: NotificationRawPayload, fragments: List<String>): String {
        val raw = buildString {
            append(payload.packageId).append('|')
            append(payload.key).append('|')
            append(payload.title ?: "").append('|')
            append(payload.senderHint).append('|')
            append(payload.text).append('|')
            fragments.forEach { append("f:").append(it).append('|') }
        }
        return digestProvider.sha256(raw)
    }

    private fun detectTruncation(payload: NotificationRawPayload): TruncationState {
        val text = payload.text.trim()
        return if (text.endsWith("…") || text.endsWith("...") || text.endsWith("[...]")) {
            TruncationState.Truncated
        } else {
            TruncationState.Complete
        }
    }
}

/**
 * Deduplicator that handles:
 * 1. Exact repeated notification posts -> drops duplicate.
 * 2. Distinct messages with identical text -> preserves both if postTime/key differs.
 * 3. Updates to existing notification -> increments revision.
 * 4. Removals -> handles dismissal.
 * 5. Group summaries vs child messages.
 */
class NotificationDeduplicator(
    private val maxCacheEntries: Int = 1000,
) {
    private val lock = Any()
    private val recentNotifications = LinkedHashMap<String, ObservedNotification>()

    sealed class DedupeAction {
        data class Insert(val observation: ObservedNotification) : DedupeAction()
        data class Update(val observation: ObservedNotification, val priorRevision: Long) : DedupeAction()
        data class DropDuplicate(val notificationKey: String) : DedupeAction()
        data class Remove(val notificationKey: String) : DedupeAction()
    }

    fun evaluate(normalized: ObservedNotification): DedupeAction = synchronized(lock) {
        if (normalized.isRemoved) {
            recentNotifications.remove(normalized.notificationKey)
            return DedupeAction.Remove(normalized.notificationKey)
        }

        val existing = recentNotifications[normalized.notificationKey]
        if (existing == null) {
            put(normalized)
            return DedupeAction.Insert(normalized)
        }

        // Check if exact duplicate (same key, postTime, and content digest)
        if (existing.postTime == normalized.postTime && existing.rawTextDigest == normalized.rawTextDigest) {
            return DedupeAction.DropDuplicate(normalized.notificationKey)
        }

        // Notification was updated (e.g. new message appended or edit)
        val updated = normalized.copy(
            id = existing.id,
            revision = existing.revision + 1L,
        )
        put(updated)
        return DedupeAction.Update(updated, existing.revision)
    }

    private fun put(obs: ObservedNotification) {
        recentNotifications[obs.notificationKey] = obs
        while (recentNotifications.size > maxCacheEntries) {
            recentNotifications.remove(recentNotifications.entries.first().key)
        }
    }

    fun clear() = synchronized(lock) {
        recentNotifications.clear()
    }
}
