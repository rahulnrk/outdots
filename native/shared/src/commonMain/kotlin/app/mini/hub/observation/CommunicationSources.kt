package app.mini.hub.observation

/** A notification source, not an authenticated provider account. */
enum class CommunicationSource(
    val displayName: String,
    val packageIds: Set<String>,
) {
    Gmail("Gmail", setOf("com.google.android.gm")),
    Outlook("Outlook", setOf("com.microsoft.office.outlook")),
    Teams("Teams", setOf("com.microsoft.teams", "com.microsoft.skype.teams")),
    WhatsApp("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b")),
    FacebookMessenger("Facebook/Messenger", setOf("com.facebook.katana", "com.facebook.orca", "com.facebook.lite")),
    Instagram("Instagram", setOf("com.instagram.android")),
    LinkedIn("LinkedIn", setOf("com.linkedin.android")),
    Slack("Slack", setOf("com.Slack")),
    GoogleChat("Google Chat", setOf("com.google.android.apps.dynamite")),
    GoogleMessages("Google Messages", setOf("com.google.android.apps.messaging")),
    Discord("Discord", setOf("com.discord")),
    Line("LINE", setOf("jp.naver.line.android")),
    Signal("Signal", setOf("org.thoughtcrime.securesms")),
    Telegram("Telegram", setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")),
    X("X", setOf("com.twitter.android")),
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

    fun selected(): Set<CommunicationSource> = selectedSources()
}
