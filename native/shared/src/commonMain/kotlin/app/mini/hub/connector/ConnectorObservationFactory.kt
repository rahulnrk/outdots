package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState

/** Converts authoritative connector messages without pretending they came from notifications. */
class ConnectorObservationFactory(
    private val digest: DigestProvider,
) {
    fun fromMessage(account: AccountId, message: ProviderMessage, observedAtMillis: Long): ObservedNotification {
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, message.conversationId)
        // Prefer the full body when the adapter fetched one; the snippet is a provider preview.
        val text = message.bodyText?.takeIf { it.isNotBlank() } ?: message.snippet
        return ObservedNotification(
            id = ScopedId(
                account,
                ScopedId.Kind.OBSERVATION,
                "provider-${message.providerMessageId.replace("|", "%7C")}",
            ),
            accountId = account,
            conversationId = conversation,
            packageId = packageFor(account.provider),
            notificationKey = "provider:${account.key}:${message.providerMessageId}",
            notificationId = message.providerMessageId.hashCode(),
            tag = null,
            senderHint = message.sender,
            title = message.subject ?: message.sender,
            textFragments = listOf(text).filter { it.isNotBlank() },
            rawTextDigest = digest.sha256(text),
            observedAt = UtcInstant(observedAtMillis),
            postTime = UtcInstant(message.occurredAtMillis),
            revision = message.revision,
            isRemoved = message.isDeleted,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = if (message.bodyText != null) TruncationState.Complete else TruncationState.Unknown,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.OfficialProviderApi,
        )
    }

    companion object {
        /**
         * Display package for API-sourced rows. Gmail keeps the Gmail app package for continuity
         * with existing stored history; other providers use a non-installable `provider.` prefix so
         * an API row is never confused with a notification from that app.
         */
        fun packageFor(provider: app.mini.hub.domain.Provider): String = when (provider) {
            app.mini.hub.domain.Provider.GMAIL -> "com.google.android.gm"
            else -> "provider.${provider.wire}"
        }
    }
}
