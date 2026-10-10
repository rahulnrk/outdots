package app.mini.hub.observation

import app.mini.hub.domain.Provider

/** A notification source, not an authenticated provider account. */
enum class CommunicationSource(
    val displayName: String,
    val packageIds: Set<String>,
    val provider: Provider,
) {
    Gmail("Gmail", setOf("com.google.android.gm"), Provider.GMAIL),
    Outlook("Outlook", setOf("com.microsoft.office.outlook"), Provider.OUTLOOK),
    Teams("Teams", setOf("com.microsoft.teams", "com.microsoft.skype.teams"), Provider.TEAMS),
    WhatsApp("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b"), Provider.WHATSAPP),
    FacebookMessenger("Facebook/Messenger", setOf("com.facebook.orca", "com.facebook.katana", "com.facebook.lite"), Provider.FACEBOOK),
    Instagram("Instagram", setOf("com.instagram.android"), Provider.INSTAGRAM),
    LinkedIn("LinkedIn", setOf("com.linkedin.android"), Provider.LINKEDIN),
    Slack("Slack", setOf("com.Slack"), Provider.SLACK),
    GoogleChat("Google Chat", setOf("com.google.android.apps.dynamite"), Provider.GOOGLE_CHAT),
    GoogleMessages("Google Messages", setOf("com.google.android.apps.messaging"), Provider.GOOGLE_MESSAGES),
    Discord("Discord", setOf("com.discord"), Provider.DISCORD),
    Line("LINE", setOf("jp.naver.line.android"), Provider.LINE),
    Signal("Signal", setOf("org.thoughtcrime.securesms"), Provider.SIGNAL),
    Telegram("Telegram", setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"), Provider.TELEGRAM),
    X("X", setOf("com.twitter.android"), Provider.TWITTER),
    ;

    companion object {
        val all: Set<CommunicationSource> = entries.toSet()

        /**
         * First-run selection. Sources added later are opt-in: a user who never chose sources
         * keeps the original set, and a stored selection is never widened automatically.
         */
        val defaultSelection: Set<CommunicationSource> =
            setOf(Gmail, Outlook, Teams, WhatsApp, FacebookMessenger, Instagram, LinkedIn)

        fun fromId(id: String): CommunicationSource? = entries.firstOrNull { it.name == id }

        fun forExactPackage(packageId: String): CommunicationSource? =
            entries.firstOrNull { packageId in it.packageIds }

        /** Primary installed app package for a provider, used for native handoff targeting. */
        fun primaryPackageFor(provider: app.mini.hub.domain.Provider): String? =
            entries.firstOrNull { it.provider == provider }?.packageIds?.first()

        /** API-synced rows use a non-installable "provider.<wire>" package (see ConnectorObservationFactory). */
        const val API_PACKAGE_PREFIX = "provider."
    }
}

/** Human app label for a stored row. API rows say which provider and that they are API-synced. */
object SourceAppNames {
    private val API_NAMES = mapOf(
        "outlook" to "Outlook", "teams" to "Teams", "slack" to "Slack", "google_chat" to "Google Chat",
        "telegram" to "Telegram bot", "twitter" to "X DMs", "gmail" to "Gmail",
    )

    fun forPackage(packageId: String): String {
        if (packageId.startsWith(CommunicationSource.API_PACKAGE_PREFIX)) {
            val wire = packageId.removePrefix(CommunicationSource.API_PACKAGE_PREFIX)
            return API_NAMES[wire] ?: wire.replaceFirstChar { it.uppercase() }
        }
        CommunicationSource.forExactPackage(packageId)?.let { source ->
            return if (packageId == "com.facebook.orca") "Messenger" else source.displayName
        }
        return when {
            packageId.contains("twitter", ignoreCase = true) -> "X"
            packageId.contains("whoop", ignoreCase = true) -> "WHOOP"
            packageId.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            packageId.contains("android.gm", ignoreCase = true) -> "Gmail"
            packageId.contains("facebook", ignoreCase = true) -> "Facebook"
            packageId.contains("instagram", ignoreCase = true) -> "Instagram"
            packageId.contains("linkedin", ignoreCase = true) -> "LinkedIn"
            packageId.contains("slack", ignoreCase = true) -> "Slack"
            packageId == "android" -> "Android System"
            packageId.startsWith("com.android.systemui") -> "System UI"
            else -> packageId.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

/** Exact package allow-list used before notification normalization and persistence. */
class CommunicationSourcePolicy(
    private val selectedSources: () -> Set<CommunicationSource>,
) {
    fun sourceForPackage(packageId: String): CommunicationSource? =
        CommunicationSource.forExactPackage(packageId)

    fun isEligible(payload: NotificationRawPayload): Boolean =
        payload.tag == "synthetic_test" || isEligiblePackage(payload.packageId)

    fun isEligiblePackage(packageId: String): Boolean {
        val source = sourceForPackage(packageId) ?: return false
        return source in selectedSources()
    }

    /**
     * Inbox visibility. Notification rows follow the user's source choices. Rows synced from a
     * connected provider account are always shown: the user explicitly authorized that account,
     * and disconnecting it (not a notification toggle) is how they stop it.
     */
    fun isVisibleInInbox(packageId: String): Boolean =
        packageId.startsWith(CommunicationSource.API_PACKAGE_PREFIX) || isEligiblePackage(packageId)

    fun selected(): Set<CommunicationSource> = selectedSources()
}
