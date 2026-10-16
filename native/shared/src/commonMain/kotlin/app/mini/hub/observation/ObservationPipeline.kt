package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.ObservationStore

enum class IngestOfferResult {
    Accepted,
    QueueFull,
    Coalesced,
    Filtered,
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
        val appName = deriveHumanAppName(payload.packageId, payload.appLabel)
        val category = deriveNotificationCategory(payload)

        val conversationKey = deriveConversationKey(payload, appName, category)
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

        val titleDisplay = deriveDisplayTitle(payload, appName, category)
        val senderDisplay = deriveDisplaySender(payload, appName, category)

        return ObservedNotification(
            id = observationId,
            accountId = account,
            conversationId = conversationId,
            packageId = payload.packageId,
            notificationKey = payload.key,
            notificationId = payload.notificationId,
            tag = payload.tag,
            senderHint = senderDisplay,
            title = titleDisplay,
            textFragments = fragments,
            rawTextDigest = digest,
            observedAt = observedAt,
            postTime = UtcInstant(payload.postTimeMillis),
            revision = 1L,
            isRemoved = payload.isRemoved,
            isGroupSummary = payload.isGroupSummary,
            isOngoing = payload.isOngoing,
            truncationState = truncation,
            correlationConfidence = if (payload.isGroupSummary) CorrelationConfidence.Medium else CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = category,
        )
    }

    fun mapPackageToProvider(packageId: String): Provider = when {
        packageId == "com.whatsapp" || packageId == "com.whatsapp.w4b" || packageId.contains("whatsapp", ignoreCase = true) -> Provider.WHATSAPP
        packageId == "com.google.android.gm" || packageId.contains("android.gm", ignoreCase = true) -> Provider.GMAIL
        packageId == "com.microsoft.office.outlook" || packageId.contains("outlook", ignoreCase = true) -> Provider.OUTLOOK
        packageId == "com.microsoft.teams" || packageId == "com.microsoft.skype.teams" || packageId.contains("teams", ignoreCase = true) -> Provider.TEAMS
        packageId == "com.facebook.orca" -> Provider.FACEBOOK
        packageId == "com.facebook.katana" || packageId == "com.facebook.lite" || packageId.contains("facebook", ignoreCase = true) -> Provider.FACEBOOK
        packageId == "com.instagram.android" || packageId.contains("instagram", ignoreCase = true) -> Provider.INSTAGRAM
        packageId == "com.linkedin.android" || packageId.contains("linkedin", ignoreCase = true) -> Provider.LINKEDIN
        packageId == "com.twitter.android" || packageId == "com.twitter.android.lite" || packageId.contains("twitter", ignoreCase = true) -> Provider.TWITTER
        packageId == "com.Slack" || packageId == "com.slack" || packageId.contains("slack", ignoreCase = true) -> Provider.SLACK
        packageId == "com.noop.whoop.staging" || packageId == "com.whoop" || packageId.contains("whoop", ignoreCase = true) -> Provider.WHOOP
        packageId == "com.discord" -> Provider.DISCORD
        packageId == "com.google.android.apps.dynamite" -> Provider.GOOGLE_CHAT
        packageId == "com.google.android.apps.messaging" -> Provider.GOOGLE_MESSAGES
        packageId == "jp.naver.line.android" -> Provider.LINE
        packageId == "org.thoughtcrime.securesms" -> Provider.SIGNAL
        packageId.startsWith("org.telegram.messenger") || packageId == "org.thunderdog.challegram" -> Provider.TELEGRAM
        packageId == "android" || packageId.startsWith("com.android.systemui") -> Provider.SYSTEM
        else -> Provider.APP
    }

    fun deriveHumanAppName(packageId: String, appLabel: String?): String {
        if (!appLabel.isNullOrBlank() && appLabel != packageId) return appLabel
        return when {
            packageId.contains("twitter", ignoreCase = true) -> "X"
            packageId.contains("whoop", ignoreCase = true) -> "WHOOP"
            packageId.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            packageId.contains("android.gm", ignoreCase = true) -> "Gmail"
            packageId.contains("facebook.orca", ignoreCase = true) -> "Messenger"
            packageId.contains("facebook", ignoreCase = true) -> "Facebook"
            packageId.contains("instagram", ignoreCase = true) -> "Instagram"
            packageId.contains("linkedin", ignoreCase = true) -> "LinkedIn"
            packageId.contains("slack", ignoreCase = true) -> "Slack"
            packageId == "android" -> "Android System"
            packageId.startsWith("com.android.systemui") -> "System UI"
            else -> packageId.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun deriveNotificationCategory(payload: NotificationRawPayload): NotificationCategory {
        if (payload.isOngoing || payload.category == "service" || payload.packageId.contains("whoop", ignoreCase = true)) {
            return NotificationCategory.OngoingService
        }
        if (payload.isGroupSummary) return NotificationCategory.GroupMessage
        if (payload.category == "sys" || payload.packageId == "android" || payload.packageId.startsWith("com.android.systemui")) {
            return NotificationCategory.SystemStatus
        }
        val kind = classifyCommunication(payload)
        val grouped = payload.isGroupSummary || payload.isGroupConversation
        return when (kind) {
            CommunicationKind.ConversationMessage ->
                if (grouped) NotificationCategory.GroupMessage else NotificationCategory.DirectMessage
            CommunicationKind.LinkedInActivity -> NotificationCategory.SocialUpdate
            CommunicationKind.NotCommunication -> when {
                payload.category == "social" ||
                    payload.packageId.contains("twitter", ignoreCase = true) ||
                    payload.packageId.contains("instagram", ignoreCase = true) ||
                    payload.packageId.contains("facebook", ignoreCase = true) ->
                    NotificationCategory.SocialUpdate
                else -> NotificationCategory.GeneralNotification
            }
        }
    }

    private fun deriveDisplayTitle(payload: NotificationRawPayload, appName: String, category: NotificationCategory): String {
        val rawTitle = payload.title?.trim()
        if (!rawTitle.isNullOrBlank() && rawTitle != payload.packageId) {
            return rawTitle
        }
        return when (category) {
            NotificationCategory.OngoingService -> "$appName Service"
            NotificationCategory.SystemStatus -> "$appName Status"
            NotificationCategory.SocialUpdate -> "$appName Alert"
            else -> appName
        }
    }

    private fun deriveDisplaySender(payload: NotificationRawPayload, appName: String, category: NotificationCategory): String {
        val hint = payload.senderHint.trim()
        if (hint.isNotBlank() && hint != payload.packageId) return hint
        val title = payload.title?.trim()
        if (!title.isNullOrBlank() && title != payload.packageId) return title
        return appName
    }

    private fun deriveConversationKey(payload: NotificationRawPayload, appName: String, category: NotificationCategory): String {
        if (classifyCommunication(payload) == CommunicationKind.LinkedInActivity) {
            return linkedInActivityConversationValue(payload.title, payload.text)
        }
        // Direct messages and group messages group by thread / sender
        if (category == NotificationCategory.DirectMessage || category == NotificationCategory.GroupMessage) {
            val titleSource = if (providerForPayload(payload) == Provider.LINKEDIN) {
                payload.title?.trim()
                    ?.replace(Regex("^(new\\s+)?(message|inmail|conversation)\\s+(from|with)\\s+", RegexOption.IGNORE_CASE), "")
                    ?.trim()
            } else payload.title?.trim()
            val titleClean = titleSource?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            if (!titleClean.isNullOrBlank() && titleClean != payload.packageId) return titleClean
            val tagClean = payload.tag?.trim()?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            if (!tagClean.isNullOrBlank()) return tagClean
        }

        // Social, Service, and System notifications group under the app topic
        val appClean = appName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val subClean = payload.subText?.trim()?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (!subClean.isNullOrBlank()) return "${appClean}_${subClean}"
        return "${appClean}_alerts"
    }

    private fun providerForPayload(payload: NotificationRawPayload): Provider = mapPackageToProvider(payload.packageId)

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
/**
 * Deduplicator that handles:
 * 1. Exact repeated notification posts -> drops duplicate.
 * 2. Distinct messages with identical text -> preserves both if postTime/key differs.
 * 3. Updates to existing notification -> increments revision.
 * 4. Removals -> handles dismissal.
 * 5. Group summaries vs child messages.
 *
 * Key correctness property: `evaluate()` returns a candidate action without caching.
 * The caller MUST call `confirmPersisted()` after successful database writes
 * so the entry is cached and future duplicates are correctly identified.
 * On write failure, the caller does NOT call `confirmPersisted()`, so the same
 * notification key remains eligible for retry (not incorrectly marked as a duplicate).
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

    /**
     * Evaluate a normalized notification and return the appropriate action.
     * Does NOT cache the entry. The caller must call [confirmPersisted] after a successful write.
     */
    fun evaluate(normalized: ObservedNotification, store: ObservationStore? = null): DedupeAction = synchronized(lock) {
        if (normalized.isRemoved) {
            recentNotifications.remove(normalized.notificationKey)
            return DedupeAction.Remove(normalized.notificationKey)
        }

        val existing = recentNotifications[normalized.notificationKey]
            ?: store?.getObservationByNotificationKeyIncludingRemoved(normalized.notificationKey)

        if (existing == null) {
            // Do NOT cache here -- wait for confirmPersisted
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
        // Do NOT cache here -- wait for confirmPersisted
        return DedupeAction.Update(updated, existing.revision)
    }

    /**
     * Confirm that the observation was successfully persisted to the database.
     * Only after this call will the entry be cached for future deduplication.
     */
    fun confirmPersisted(obs: ObservedNotification): Unit = synchronized(lock) {
        put(obs)
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

    /** Remove a key after an atomic removal has committed successfully. */
    fun forget(notificationKey: String) = synchronized(lock) {
        recentNotifications.remove(notificationKey)
    }
}
