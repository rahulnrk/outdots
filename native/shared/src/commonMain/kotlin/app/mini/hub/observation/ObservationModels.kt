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
    SyntheticFixture,
    UserImport,
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
