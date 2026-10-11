package app.mini.hub.style

import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.EmailAddress
import app.mini.hub.connector.MessageDirection
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification

/** Account-scoped context used for local personalization without merging contacts across accounts. */
data class StyleSampleContext(
    val category: ContextCategory,
    val recipientId: String?,
)

object StyleContext {
    private val professionalHints = listOf(
        "invoice", "proposal", "meeting", "project", "client", "work", "interview",
        "deadline", "attached", "regards", "please find", "kindly", "contract",
    )
    private val casualHints = listOf(
        "hey", "lol", "haha", "wanna", "gonna", "thx", "brunch", "dinner", "catch up", "btw",
    )

    fun forProvider(provider: Provider, subject: String? = null, text: String? = null): ContextCategory =
        forMessage(provider, subject, text)

    /**
     * Classifies a message from linguistic evidence first, then provider defaults.
     * Contact names are never used as family/relationship proof.
     */
    fun forMessage(provider: Provider, subject: String? = null, text: String? = null): ContextCategory {
        val combined = "${subject.orEmpty()} ${text.orEmpty()}".lowercase()
        val professional = professionalHints.count { combined.contains(it) }
        val casual = casualHints.count { combined.contains(it) } + if (combined.any { it.code > 0x1F300 }) 1 else 0
        if (professional > casual && professional > 0) return ContextCategory.Professional
        if (casual > professional && casual > 0) return ContextCategory.Friends
        if (provider in setOf(Provider.WHATSAPP, Provider.WHATSAPP_BUSINESS, Provider.INSTAGRAM, Provider.TELEGRAM)) {
            return ContextCategory.Friends
        }
        return when (provider) {
            Provider.GMAIL, Provider.OUTLOOK, Provider.TEAMS, Provider.SLACK, Provider.GOOGLE_CHAT, Provider.LINKEDIN ->
                ContextCategory.Professional
            else -> ContextCategory.Custom
        }
    }

    /** Recipient identity is always account-scoped, preventing cross-account profile joins. */
    fun recipientFor(account: ConnectedAccount, message: ProviderMessage): String? {
        val raw = when (message.direction) {
            MessageDirection.Outbound -> message.recipients.firstOrNull()
            MessageDirection.Inbound -> message.sender
            MessageDirection.Unknown -> message.sender.takeIf { it.isNotBlank() }
        } ?: return null
        return "${account.id.key}|$raw"
    }

    fun recipientFor(account: ConnectedAccount, observation: ObservedNotification): String =
        "${account.id.key}|${observation.senderHint}"

    fun sampleId(accountKey: String, providerMessageId: String): String = "$accountKey:$providerMessageId"

    fun bodySampleId(accountKey: String, conversationId: String, text: String): String =
        "$accountKey:body:${nativeConversationId(accountKey, conversationId)}:${fingerprint(text)}"

    fun sampleIds(
        accountKey: String,
        conversationId: String,
        text: String,
        providerMessageId: String? = null,
    ): List<String> = buildList {
        providerMessageId?.takeIf { it.isNotBlank() }?.let { add(sampleId(accountKey, it)) }
        add(bodySampleId(accountKey, conversationId, text))
    }.distinct()

    fun providerMessageIdFromNotificationKey(accountKey: String, notificationKey: String): String? {
        val prefix = "provider:$accountKey:"
        return notificationKey.removePrefix(prefix).takeIf { notificationKey.startsWith(prefix) && it.isNotBlank() }
    }

    fun learn(
        engine: AdaptiveStyleEngine,
        account: ConnectedAccount,
        messages: List<ProviderMessage>,
        now: UtcInstant,
    ): Int {
        var learned = 0
        messages.forEach { message ->
            if (message.direction != MessageDirection.Outbound) return@forEach
            val text = message.bodyText?.takeIf { it.isNotBlank() } ?: message.snippet
            if (text.isBlank()) return@forEach
            val category = forMessage(account.id.provider, message.subject, text)
            val recipient = recipientFor(account, message)
            val ids = sampleIds(account.id.key, message.conversationId, text, message.providerMessageId)
            if (learnOnce(engine, text, category, recipient, now, ids, account.id)) learned++
        }
        return learned
    }

    /**
     * Learns from already stored provider history without requiring a conversation to be selected.
     * Notification-only rows are ignored because they are typically inbound and lack authorship proof.
     */
    fun learnFromObservations(
        engine: AdaptiveStyleEngine,
        account: ConnectedAccount,
        observations: List<ObservedNotification>,
        now: UtcInstant,
        maxSamples: Int = 200,
    ): Int {
        var learned = 0
        observations
            .asSequence()
            .filter { isUserAuthored(account, it) }
            .sortedBy { it.postTime.epochMillis }
            .take(maxSamples)
            .forEach { observation ->
                val text = observation.textFragments.joinToString(" ").trim()
                if (text.isBlank()) return@forEach
                val category = forMessage(account.id.provider, observation.title, text)
                val recipient = "${account.id.key}|${counterparty(account, observation)}"
                val providerId = providerMessageIdFromNotificationKey(account.id.key, observation.notificationKey)
                val ids = sampleIds(account.id.key, observation.conversationId.key, text, providerId)
                if (learnOnce(engine, text, category, recipient, now, ids, account.id)) learned++
            }
        return learned
    }

    fun isUserAuthored(account: ConnectedAccount, observation: ObservedNotification): Boolean {
        if (observation.isRemoved) return false
        if (observation.provenance != ObservationProvenance.OfficialProviderApi &&
            observation.provenance != ObservationProvenance.ThirdPartyBridge
        ) {
            return false
        }
        val me = account.providerAccountId.trim().lowercase()
        if (me.isBlank()) return false
        val sender = observation.senderHint.lowercase()
        val senderAddress = EmailAddress.extract(observation.senderHint)?.lowercase()
        return senderAddress == me || sender == me || sender.contains("<$me>")
    }

    private fun learnOnce(
        engine: AdaptiveStyleEngine,
        text: String,
        category: ContextCategory,
        recipient: String?,
        now: UtcInstant,
        ids: List<String>,
        account: AccountId,
    ): Boolean {
        val primary = ids.first()
        val extra = ids.drop(1)
        val learned = engine.learnFromText(
            text, TextAuthorship.UserAuthoredSentMessage, category, recipient, now, primary, account, extra,
        )
        engine.learnFromText(
            text, TextAuthorship.UserAuthoredSentMessage, category, null, now, "cat:$primary", account, extra.map { "cat:$it" },
        )
        return learned
    }

    private fun nativeConversationId(accountKey: String, conversationId: String): String {
        val prefix = "$accountKey|conversation|"
        return if (conversationId.startsWith(prefix)) conversationId.removePrefix(prefix) else conversationId
    }

    private fun fingerprint(text: String): String {
        var hash = 0xcbf29ce484222325UL
        for (ch in text.trim()) {
            hash = hash xor ch.code.toULong()
            hash *= 0x100000001b3UL
        }
        return hash.toString(16)
    }

    private fun counterparty(account: ConnectedAccount, observation: ObservedNotification): String {
        val me = account.providerAccountId.lowercase()
        val senderAddress = EmailAddress.extract(observation.senderHint)?.lowercase()
        return if (senderAddress == me || observation.senderHint.lowercase().contains("<$me>")) {
            observation.title?.takeIf { it.isNotBlank() && !it.equals(observation.senderHint, ignoreCase = true) }
                ?: observation.senderHint
        } else {
            observation.senderHint
        }
    }
}
