package app.mini.hub.observation

/**
 * Whether a notification is a real conversation, optional LinkedIn activity, or noise.
 *
 * Classification prefers Android category, MessagingStyle, group-summary flags and
 * source-specific title patterns over broad body keywords so a legitimate message that
 * mentions "job" or "post" is not discarded.
 */
enum class CommunicationKind {
    ConversationMessage,
    LinkedInActivity,
    NotCommunication;

    /** Whether this kind of content belongs in the primary Inbox. */
    val isInboxEligible: Boolean get() = this == ConversationMessage
}

private val NON_MESSAGE_ANDROID_CATEGORIES = setOf(
    "promo",
    "recommendation",
    "service",
    "status",
    "sys",
    "transport",
    "err",
    "error",
    "progress",
    "call",
    "alarm",
    "reminder",
    "event",
    "navigation",
    "location_sharing",
)

fun classifyCommunication(payload: NotificationRawPayload): CommunicationKind {
    if (payload.tag == "synthetic_test") return CommunicationKind.ConversationMessage
    if (payload.isOngoing) return CommunicationKind.NotCommunication
    val androidCat = payload.category?.lowercase()
    if (androidCat in NON_MESSAGE_ANDROID_CATEGORIES) return CommunicationKind.NotCommunication
    if (payload.isGroupSummary) return CommunicationKind.NotCommunication

    val source = CommunicationSource.forExactPackage(payload.packageId)
    val messagingEvidence = payload.hasMessagingStyle ||
        androidCat == "msg" ||
        androidCat == "message" ||
        androidCat == "email"

    return when (source) {
        CommunicationSource.LinkedIn ->
            if (messagingEvidence || isLinkedInMessageEvidence(payload)) CommunicationKind.ConversationMessage
            else if (classifyLinkedInActivity(payload) != null) CommunicationKind.LinkedInActivity
            else CommunicationKind.NotCommunication
        CommunicationSource.Gmail, CommunicationSource.Outlook ->
            if (messagingEvidence || hasPersonAndBody(payload)) CommunicationKind.ConversationMessage
            else CommunicationKind.NotCommunication
        CommunicationSource.WhatsApp,
        CommunicationSource.GoogleMessages,
        CommunicationSource.Signal,
        CommunicationSource.Telegram,
        CommunicationSource.Line,
        CommunicationSource.Slack,
        CommunicationSource.Teams,
        CommunicationSource.GoogleChat,
        CommunicationSource.Discord,
        -> when {
            isMessagingServiceStatus(payload) -> CommunicationKind.NotCommunication
            messagingEvidence || hasPersonAndBody(payload) -> CommunicationKind.ConversationMessage
            else -> CommunicationKind.NotCommunication
        }
        CommunicationSource.Instagram, CommunicationSource.FacebookMessenger, CommunicationSource.X -> when {
            messagingEvidence -> CommunicationKind.ConversationMessage
            looksLikeSocialActivity(payload.title, payload.text, payload.subText) -> CommunicationKind.NotCommunication
            hasPersonAndBody(payload) -> CommunicationKind.ConversationMessage
            else -> CommunicationKind.NotCommunication
        }
        null -> if (messagingEvidence) CommunicationKind.ConversationMessage else CommunicationKind.NotCommunication
    }
}

/** Classifies only known LinkedIn non-message families. Unknown activity is ignored. */
fun classifyLinkedInActivity(payload: NotificationRawPayload): LinkedInActivityType? {
    if (payload.packageId != "com.linkedin.android") return null
    if (isLinkedInMessageEvidence(payload)) return null
    val title = payload.title.orEmpty()
    val sub = payload.subText.orEmpty()
    val text = payload.text
    val head = "$title $sub".lowercase()
    val body = text.lowercase()
    return when {
        matchesAny(head, CONNECTION_TITLE) || matchesAny(body, CONNECTION_BODY) -> LinkedInActivityType.ConnectionRequests
        matchesAny(head, JOB_TITLE) || matchesAny(body, JOB_BODY) -> LinkedInActivityType.Jobs
        matchesAny(head, POST_TITLE) || matchesAny(body, POST_BODY) -> LinkedInActivityType.Posts
        matchesAny(head, REACTION_TITLE) || matchesAny(body, REACTION_BODY) -> LinkedInActivityType.Reactions
        matchesAny(head, PROFILE_TITLE) || matchesAny(body, PROFILE_BODY) -> LinkedInActivityType.ProfileAlerts
        else -> null
    }
}

fun linkedInActivityConversationValue(title: String?, text: String): String {
    val basis = "${title.orEmpty().trim().lowercase()}|${text.trim().lowercase()}"
    return "li_activity_${basis.hashCode().toUInt().toString(16)}"
}

fun categoryForStoredObservation(obs: ObservedNotification): NotificationCategory {
    if (obs.isOngoing) return NotificationCategory.OngoingService
    obs.notificationCategory?.let { return it }

    // API-synced items are always messages (direct or group)
    if (obs.provenance == ObservationProvenance.OfficialProviderApi ||
        obs.provenance == ObservationProvenance.ThirdPartyBridge ||
        obs.provenance == ObservationProvenance.UserImport ||
        obs.packageId.startsWith(CommunicationSource.API_PACKAGE_PREFIX)
    ) {
        return if (obs.isGroupSummary) NotificationCategory.GroupMessage else NotificationCategory.DirectMessage
    }

    if (obs.isGroupSummary) return NotificationCategory.GroupMessage

    val reconstructed = NotificationRawPayload(
        key = obs.notificationKey,
        packageId = obs.packageId,
        notificationId = obs.notificationId,
        tag = obs.tag,
        postTimeMillis = obs.postTime.epochMillis,
        senderHint = obs.senderHint,
        title = obs.title,
        text = obs.textFragments.lastOrNull().orEmpty(),
        textFragments = obs.textFragments,
        isGroupSummary = obs.isGroupSummary,
        isOngoing = obs.isOngoing,
    )

    return when (classifyCommunication(reconstructed)) {
        CommunicationKind.ConversationMessage ->
            if (obs.isGroupSummary) NotificationCategory.GroupMessage else NotificationCategory.DirectMessage
        CommunicationKind.LinkedInActivity -> NotificationCategory.SocialUpdate
        CommunicationKind.NotCommunication -> {
            val source = CommunicationSource.forExactPackage(obs.packageId)
            when (source) {
                CommunicationSource.Instagram, CommunicationSource.X -> NotificationCategory.SocialUpdate
                CommunicationSource.LinkedIn -> NotificationCategory.GeneralNotification
                null -> when {
                    obs.packageId == "android" || obs.packageId.startsWith("com.android.systemui") ->
                        NotificationCategory.SystemStatus
                    else -> NotificationCategory.GeneralNotification
                }
                else -> NotificationCategory.GeneralNotification
            }
        }
    }
}

fun isConversationSummary(summary: ConversationObservationSummary): Boolean {
    // Non-message categories are never in the Inbox.
    if (summary.category == NotificationCategory.GeneralNotification ||
        summary.category == NotificationCategory.OngoingService ||
        summary.category == NotificationCategory.SystemStatus ||
        summary.category == NotificationCategory.SocialUpdate
    ) return false

    // API-sourced items are always genuine messages.
    if (summary.coverage != ConversationCoverage.NotificationOnly) return true
    if (summary.packageId.startsWith(CommunicationSource.API_PACKAGE_PREFIX)) return true

    // Direct/Group messages from notification-only sources: verify via classification
    // to catch misclassified recommendations and social activity stored as DirectMessage.
    if (summary.category == NotificationCategory.DirectMessage ||
        summary.category == NotificationCategory.GroupMessage
    ) {
        val source = CommunicationSource.forExactPackage(summary.packageId)
        // Social platforms may have wrongly-categorized recommendations.
        if (source == CommunicationSource.Instagram || source == CommunicationSource.X ||
            source == CommunicationSource.FacebookMessenger
        ) {
            if (looksLikeNonMessageActivity(summary)) return false
            return classifyCommunication(summaryAsPayload(summary)) == CommunicationKind.ConversationMessage
        }
        if (source == CommunicationSource.LinkedIn) return isLinkedInMessageSummary(summary)
        return true
    }

    // NULL category: re-classify from stored text.
    if (summary.category == null) {
        return classifyCommunication(summaryAsPayload(summary)) == CommunicationKind.ConversationMessage
    }

    return false
}

fun isLinkedInActivitySummary(summary: ConversationObservationSummary): Boolean =
    summary.category == NotificationCategory.SocialUpdate && summary.packageId == "com.linkedin.android"

fun isLinkedInMessageSummary(summary: ConversationObservationSummary): Boolean {
    if (summary.category == NotificationCategory.DirectMessage ||
        summary.category == NotificationCategory.GroupMessage
    ) return true
    return classifyCommunication(summaryAsPayload(summary)) == CommunicationKind.ConversationMessage
}

private fun looksLikeNonMessageActivity(summary: ConversationObservationSummary): Boolean {
    val source = CommunicationSource.forExactPackage(summary.packageId)
    if (source == CommunicationSource.Instagram ||
        source == CommunicationSource.X ||
        source == CommunicationSource.FacebookMessenger
    ) {
        return looksLikeSocialActivity(summary.title, summary.latestSnippet, null)
    }
    return false
}

    private fun classifyLinkedInKind(payload: NotificationRawPayload, messagingEvidence: Boolean): CommunicationKind {
        if (messagingEvidence || isLinkedInMessageEvidence(payload)) return CommunicationKind.ConversationMessage
        if (classifyLinkedInActivity(payload) != null) return CommunicationKind.LinkedInActivity
        return CommunicationKind.NotCommunication
    }

private fun isLinkedInMessageEvidence(payload: NotificationRawPayload): Boolean {
    val androidCat = payload.category?.lowercase()
    if (payload.hasMessagingStyle || androidCat == "msg" || androidCat == "message") return true
    val title = payload.title?.lowercase().orEmpty()
    if (title.startsWith("new message") ||
        title.startsWith("new inmail") ||
        "sent you a message" in title ||
        "replied to your message" in title ||
        "inmail" in title
    ) return true
    if (classifyLinkedInActivityFromText(payload) != null) return false
    return hasPersonAndBody(payload) && !payload.title.equals("LinkedIn", ignoreCase = true)
}

private fun classifyLinkedInActivityFromText(payload: NotificationRawPayload): LinkedInActivityType? {
    val title = payload.title.orEmpty()
    val sub = payload.subText.orEmpty()
    val text = payload.text
    val head = "$title $sub".lowercase()
    val body = text.lowercase()
    return when {
        matchesAny(head, CONNECTION_TITLE) || matchesAny(body, CONNECTION_BODY) -> LinkedInActivityType.ConnectionRequests
        matchesAny(head, JOB_TITLE) || matchesAny(body, JOB_BODY) -> LinkedInActivityType.Jobs
        matchesAny(head, POST_TITLE) || matchesAny(body, POST_BODY) -> LinkedInActivityType.Posts
        matchesAny(head, REACTION_TITLE) || matchesAny(body, REACTION_BODY) -> LinkedInActivityType.Reactions
        matchesAny(head, PROFILE_TITLE) || matchesAny(body, PROFILE_BODY) -> LinkedInActivityType.ProfileAlerts
        else -> null
    }
}

private fun hasPersonAndBody(payload: NotificationRawPayload): Boolean {
    val title = payload.title?.trim().orEmpty().ifBlank { payload.senderHint.trim() }
    val body = payload.text.trim().ifBlank { payload.textFragments.firstOrNull()?.trim().orEmpty() }
    if (title.isBlank() || body.isBlank()) return false
    if (title.equals(payload.packageId, ignoreCase = true)) return false
    val source = CommunicationSource.forExactPackage(payload.packageId)
    if (source != null && title.equals(source.displayName, ignoreCase = true)) return false
    return title.length <= 80
}

private fun isMessagingServiceStatus(payload: NotificationRawPayload): Boolean {
    if (payload.hasMessagingStyle) return false
    val title = payload.title?.lowercase().orEmpty()
    val text = payload.text.lowercase()
    val hay = "$title $text"
    val appTitle = title in setOf(
        "whatsapp", "whatsapp business", "telegram", "signal", "slack", "teams",
        "google chat", "discord", "line", "messages",
    )
    if (!appTitle && title.isNotBlank()) return false
    return listOf(
        "backup",
        "checking for new messages",
        "logged in",
        "web is open",
        "web is active",
        "restoring messages",
        "updating",
        "connected to",
    ).any { it in hay }
}

private fun looksLikeSocialActivity(title: String?, text: String, subText: String?): Boolean {
    val hay = listOf(title, subText, text).filterNotNull().joinToString(" ").lowercase()
    return matchesAny(
        hay,
        listOf(
            "liked your",
            "likes your",
            "reacted to your",
            "commented on your",
            "started following",
            "began following",
            "mentioned you in a comment",
            "tagged you",
            "reposted your",
            "shared your post",
            "new follower",
            "story mention",
            "took a screenshot",
            "new reply",
            "replied to your",
            "replied to you",
            "people you may know",
            "suggested for you",
            "might know",
            "you might like",
            "you may like",
            "people you might like",
            "suggested friend",
            "friend suggestion",
            "connection suggestion",
            "recommendation",
        ),
    )
}

private fun summaryAsPayload(summary: ConversationObservationSummary) = NotificationRawPayload(
    key = "summary:${summary.conversationId.key}",
    packageId = summary.packageId,
    notificationId = 0,
    postTimeMillis = summary.latestEventAt.epochMillis,
    senderHint = summary.senderHint,
    title = summary.title,
    text = summary.latestSnippet,
)

private fun matchesAny(haystack: String, needles: List<String>): Boolean =
    needles.any { it in haystack }

private val CONNECTION_TITLE = listOf(
    "connection request",
    "invited you to connect",
    "invitation to connect",
    "wants to connect",
    "connect with",
)
private val CONNECTION_BODY = listOf(
    "invited you to connect",
    "wants to connect",
    "accept this invitation",
)
private val JOB_TITLE = listOf(
    "job alert",
    "jobs for you",
    "posted a job",
    "is hiring",
    "job recommendation",
    "new jobs",
    "applicant",
)
private val JOB_BODY = listOf(
    "job alert",
    "jobs for you",
    "posted a job",
    "is hiring for",
    "job recommendation",
)
private val POST_TITLE = listOf(
    "commented on",
    "shared a post",
    "published a post",
    "posted:",
)
private val POST_BODY = listOf(
    "commented on your post",
    "commented on your article",
    "shared a post",
)
private val REACTION_TITLE = listOf(
    "liked your",
    "reacted to",
    "reposted your",
    "reposted this",
)
private val REACTION_BODY = listOf(
    "liked your post",
    "liked your comment",
    "reacted to your",
    "reposted your",
)
private val PROFILE_TITLE = listOf(
    "viewed your profile",
    "birthday",
    "started following",
    "new follower",
)
private val PROFILE_BODY = listOf(
    "viewed your profile",
    "started following you",
    "new follower",
)
