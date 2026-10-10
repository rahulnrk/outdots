package app.mini.hub.connector

import app.mini.hub.domain.Provider

/**
 * Converts MINI's stored provider message key into the provider-native reply target each sender
 * expects. Null means the provider has no reply anchor MINI can use, so the reviewed message is
 * sent as a new message in the same conversation (and the review screen says so).
 */
object ReplyTargets {
    fun forProvider(provider: Provider, providerMessageId: String?): String? {
        val id = providerMessageId?.takeIf { it.isNotBlank() } ?: return null
        return when (provider) {
            // Gmail and Outlook senders resolve threading from the message ID themselves.
            Provider.GMAIL, Provider.OUTLOOK -> id
            // Stored as "<channel>:<ts>"; Slack threads by the parent ts.
            Provider.SLACK -> id.substringAfter(':').takeIf { it.isNotBlank() && it != id }
            // Stored as "<chat>:<message_id>"; TelegramBotSender reads the part after the last ':'.
            Provider.TELEGRAM -> id.takeIf { ':' in it }
            // Teams chats are flat, X DMs have no reply anchor, Chat thread names are not stored.
            Provider.TEAMS, Provider.TWITTER, Provider.GOOGLE_CHAT -> null
            else -> null
        }
    }

    /** True when replies are written into an email thread with a subject line. */
    fun usesSubject(provider: Provider): Boolean = provider == Provider.GMAIL || provider == Provider.OUTLOOK
}

/** Extracts the bare address from "Name <user@example.com>" for providers that compare addresses. */
object EmailAddress {
    private val ANGLE = Regex("<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>")
    private val BARE = Regex("^[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+$")

    fun extract(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ANGLE.find(text)?.let { return it.groupValues[1] }
        return text.takeIf { BARE.matches(it) }
    }
}

/**
 * Derives which release gates are cleared for an account. Configuration gates are cleared only by
 * real configuration (a client ID compiled into the build, a stored bot token). Qualification
 * gates (send, X entitlement, tenant consent, Telegram AI terms) are cleared only by evidence the
 * project owner records explicitly, never by code defaults or passing fixtures.
 */
class GatePolicy(
    private val configuredClients: Set<Provider>,
    private val recordedEvidence: (Provider) -> Collection<GateEvidence>,
) : GateEvidenceSource {
    override fun cleared(account: ConnectedAccount): Collection<GateEvidence> = buildList {
        val provider = account.id.provider
        if (provider in configuredClients) {
            when (provider) {
                Provider.OUTLOOK, Provider.TEAMS -> add(GateEvidence(ReleaseGate.MicrosoftAppRegistration, "Entra client ID configured in this build"))
                Provider.SLACK -> add(GateEvidence(ReleaseGate.SlackAppInstallation, "Slack PKCE client ID configured in this build"))
                Provider.GOOGLE_CHAT -> add(GateEvidence(ReleaseGate.GoogleChatAppConfiguration, "Google client ID configured in this build"))
                else -> Unit
            }
        }
        if (provider == Provider.TELEGRAM && ConnectorCatalog.TELEGRAM_BOT_SCOPE in account.grantedScopes) {
            add(GateEvidence(ReleaseGate.TelegramBotToken, "Bot token stored for this account"))
        }
        addAll(recordedEvidence(provider))
    }

    companion object {
        /** Gates that only a recorded human decision or live test can clear. */
        val QUALIFICATION_GATES = setOf(
            ReleaseGate.SendQualification,
            ReleaseGate.TenantAdminConsent,
            ReleaseGate.XApiEntitlementAndBudget,
            ReleaseGate.TelegramContentUseTerms,
            ReleaseGate.LiveAccountQualification,
        )
    }
}
