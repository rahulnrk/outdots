package app.mini.hub.android.ui

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.attention.AttentionReconciliation
import app.mini.hub.attention.AttentionView
import app.mini.hub.attention.ConversationAttention
import app.mini.hub.domain.ScopedId
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.retrieval.ScopedRetrievalResult
import app.mini.hub.style.CompactStyleProfile

data class InboxViewState(
    val isPermissionGranted: Boolean = false,
    val isListenerConnected: Boolean = false,
    val conversations: List<ConversationObservationSummary> = emptyList(),
    val attentionView: AttentionView? = null,
    val attentionRecords: Map<String, ConversationAttention> = emptyMap(),
    val storageFailure: String? = null,
    val selectedConversation: ConversationObservationSummary? = null,
    val selectedTimeline: List<ObservedNotification> = emptyList(),
    val retrievedContext: ScopedRetrievalResult? = null,
    val activeStyleProfile: CompactStyleProfile? = null,
    val activeReviewPayload: ExactActionPayload? = null,
    val outboxStatusMessage: String? = null,
    val isLoading: Boolean = false,
    val localDraftText: String = "",
    val localDraftWarning: String? = null,
    val localDraftStatus: String? = null,
    val isLocalDraftInProgress: Boolean = false,
)
