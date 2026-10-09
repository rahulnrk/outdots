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
        val text = message.snippet
        return ObservedNotification(
            id = ScopedId(
                account,
                ScopedId.Kind.OBSERVATION,
                "provider-${message.providerMessageId.replace("|", "%7C")}",
            ),
            accountId = account,
            conversationId = conversation,
            packageId = when (account.provider) {
                app.mini.hub.domain.Provider.GMAIL -> "com.google.android.gm"
                else -> "provider.${account.provider.wire}"
            },
            notificationKey = "provider:${account.key}:${message.providerMessageId}",
            notificationId = message.providerMessageId.hashCode(),
            tag = null,
            senderHint = message.sender,
            title = message.subject ?: message.sender,
            textFragments = listOf(text).filter { it.isNotBlank() },
            rawTextDigest = digest.sha256(text),
            observedAt = UtcInstant(observedAtMillis),
            postTime = UtcInstant(message.occurredAtMillis),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Unknown,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.OfficialProviderApi,
        )
    }
}
