package app.mini.hub.observation

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

enum class TruncationState {
    Complete,
    Truncated,
    Unknown,
}

enum class CorrelationConfidence {
    High,
    Medium,
    Low,
}

enum class ObservationProvenance {
    AndroidNotificationListener,
    OfficialProviderApi,
    ThirdPartyBridge,
    SyntheticFixture,
    UserImport,
}

fun coverageFor(provenance: ObservationProvenance): ConversationCoverage = when (provenance) {
    // The first Gmail connector is bounded/page-based. A future history-complete adapter may use
    // FullProviderSync, but an official endpoint alone is not proof of complete mailbox history.
    ObservationProvenance.OfficialProviderApi -> ConversationCoverage.PartialApiSync
    ObservationProvenance.ThirdPartyBridge -> ConversationCoverage.PartialApiSync
    ObservationProvenance.UserImport -> ConversationCoverage.UserImportedSnapshot
    ObservationProvenance.AndroidNotificationListener,
    ObservationProvenance.SyntheticFixture -> ConversationCoverage.NotificationOnly
}

fun coverageExplanationFor(provenance: ObservationProvenance, gapCount: Long = 0L): String = when (provenance) {
    ObservationProvenance.OfficialProviderApi ->
        if (gapCount > 0) "Synchronized through the official provider API with bounded coverage; $gapCount recovery gap(s) require another backfill."
        else "Synchronized through the official provider API with bounded/page coverage for this authorized account."
    ObservationProvenance.ThirdPartyBridge ->
        "Synchronized through a third-party bridge; coverage, retention and provider policy limits apply."
    ObservationProvenance.UserImport -> "Imported by explicit user action; it is not a live synchronized inbox."
    ObservationProvenance.AndroidNotificationListener ->
        if (gapCount > 0) "Observed via Android notifications only ($gapCount dropped events due to backpressure). Prior history is not visible."
        else "Observed via Android notifications only. Prior history and out-of-band replies are not visible."
    ObservationProvenance.SyntheticFixture -> "Synthetic test data; not a connected provider account."
}

enum class ConversationCoverage {
    FullProviderSync,
    PartialApiSync,
    NotificationOnly,
    UserImportedSnapshot,
    StaleOrDisconnected,
}

enum class NotificationCategory {
    DirectMessage,
    GroupMessage,
    SocialUpdate,
    SystemStatus,
    OngoingService,
    GeneralNotification,
}

data class NotificationRawPayload(
    val key: String,
    val packageId: String,
    val notificationId: Int,
    val tag: String? = null,
    val postTimeMillis: Long,
    val senderHint: String,
    val title: String? = null,
    val text: String,
    val subText: String? = null,
    val textFragments: List<String> = emptyList(),
    val isGroupSummary: Boolean = false,
    val isGroupConversation: Boolean = false,
    val hasMessagingStyle: Boolean = false,
    val isOngoing: Boolean = false,
    val category: String? = null,
    val appLabel: String? = null,
    val isRemoved: Boolean = false,
    val removedReason: Int? = null,
    /**
     * Optional caller-supplied correlation ID that binds this payload to a specific
     * test operation regardless of what OS key is ultimately assigned to the notification.
     * When present, the coordinator records all pipeline stages under this ID instead of
     * the OS notification key, enabling end-to-end tracing across the broadcast/listener boundary.
     */
    val correlationId: String? = null,
) {
    init {
        require(key.isNotBlank()) { "Notification key must not be blank" }
        require(packageId.isNotBlank()) { "Package ID must not be blank" }
    }
}

data class ObservedNotification(
    val id: ScopedId,
    val accountId: AccountId,
    val conversationId: ScopedId,
    val packageId: String,
    val notificationKey: String,
    val notificationId: Int,
    val tag: String?,
    val senderHint: String,
    val title: String?,
    val textFragments: List<String>,
    val rawTextDigest: String,
    val observedAt: UtcInstant,
    val postTime: UtcInstant,
    val revision: Long,
    val isRemoved: Boolean,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean = false,
    val truncationState: TruncationState,
    val correlationConfidence: CorrelationConfidence,
    val provenance: ObservationProvenance,
    /** Inbound/outbound from the account owner's view. Null = unknown (notification rows, old rows). */
    val direction: app.mini.hub.connector.MessageDirection? = null,
    /** Provider-reported recipients. Null = unknown (notification rows, old rows). */
    val recipients: List<String>? = null,
    /** Provider message reference used to thread a reply (e.g. RFC 822 Message-ID). Null = unknown. */
    val replyAnchor: String? = null,
) {
    init {
        require(id.kind == ScopedId.Kind.OBSERVATION) { "ObservedNotification id must be OBSERVATION kind" }
        require(conversationId.kind == ScopedId.Kind.CONVERSATION) { "ObservedNotification conversationId must be CONVERSATION kind" }
    }
}

data class ConversationObservationSummary(
    val conversationId: ScopedId,
    val accountId: AccountId,
    val title: String,
    val appName: String,
    val senderHint: String,
    val packageId: String,
    val latestSnippet: String,
    val category: NotificationCategory,
    val coverage: ConversationCoverage,
    val coverageExplanation: String,
    val latestEventAt: UtcInstant,
    val observationCount: Int,
    val unreadCount: Int,
    val isRemoved: Boolean,
)

data class IngestCursor(
    val source: String,
    val lastProcessedTimeMillis: Long,
    val lastProcessedKey: String?,
    val processedCount: Long,
    val gapCount: Long,
)
