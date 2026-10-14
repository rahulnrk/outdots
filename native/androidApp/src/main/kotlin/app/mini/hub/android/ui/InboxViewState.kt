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
import app.mini.hub.understanding.MessageUnderstanding

enum class DraftUsefulnessFeedback { Useful, NeedsEditing, NotUseful }

data class InboxViewState(
    val isPermissionGranted: Boolean = false,
    val isListenerConnected: Boolean = false,
    val conversations: List<ConversationObservationSummary> = emptyList(),
    val linkedinActivity: List<ConversationObservationSummary> = emptyList(),
    val attentionView: AttentionView? = null,
    val attentionRecords: Map<String, ConversationAttention> = emptyMap(),
    val understanding: Map<String, MessageUnderstanding> = emptyMap(),
    val storageFailure: String? = null,
    val selectedConversation: ConversationObservationSummary? = null,
    val selectedTimeline: List<ObservedNotification> = emptyList(),
    val retrievedContext: ScopedRetrievalResult? = null,
    val activeStyleProfile: CompactStyleProfile? = null,
    val activeReviewPayload: ExactActionPayload? = null,
    val outboxStatusMessage: String? = null,
    val isLoading: Boolean = false,
    val localDraftText: String = "",
    val originalGeneratedDraft: String? = null,
    /** Recent AI versions for this conversation, newest last. */
    val draftVersions: List<String> = emptyList(),
    val localDraftWarning: String? = null,
    val localDraftStatus: String? = null,
    val localDraftFeedback: DraftUsefulnessFeedback? = null,
    val isLocalDraftInProgress: Boolean = false,
    /** Local AI is paused after repeated failures; show "Retry local AI". */
    val isLocalAiRetryAvailable: Boolean = false,
    val isLocalAiRetryInProgress: Boolean = false,
    /** Latest asynchronous resource release state, e.g. "Queued", "Released". */
    val localAiReleaseStatus: String? = null,
    /** What the review dialog must say about the pending action besides the payload itself. */
    val reviewContext: ReviewContext? = null,
    /** Progress or failure of loading the full provider conversation. */
    val conversationLoadStatus: String? = null,
    /** An approved API send is in flight; the review dialog shows progress and cannot be re-tapped. */
    val isSending: Boolean = false,
    /** Unified search query text entered in the inbox search box. */
    val searchQuery: String = "",
    /** Unified search results from all enabled sources, grouped by conversation. */
    val searchResults: List<app.mini.hub.search.SearchResult> = emptyList(),
    /** Optional source package filter for search results; null = all sources. */
    val searchSourceFilter: String? = null,
    /** True while a search query is being processed on the worker thread. */
    val isSearching: Boolean = false,
    /** Set when starting a brand-new email (no thread yet); the send goes to this address. */
    val composeNewTo: String? = null,
    val composeCc: String = "",
    val composeBcc: String = "",
    val composeSubject: String = "",
    /** Durable reviewed actions, including unknowns recovered after restart. */
    val reviewedSnapshots: List<app.mini.hub.action.ReviewedActionSnapshot> = emptyList(),
    /** Composer drafts that can be reopened after leaving a thread or restarting. */
    val savedDrafts: List<app.mini.hub.persistence.ConversationDraft> = emptyList(),
    val canUndoAttention: Boolean = false,
    val inboxShowArchived: Boolean = false,
    val inboxAccountKey: String? = null,
)

data class ReviewContext(
    val providerName: String,
    /** True: threaded reply to a specific message. False: a new message in the conversation. */
    val isReply: Boolean,
    /** Native handoff target app, when the route is a handoff. */
    val handoffPackage: String?,
    /** Why the route is handoff even though an API account exists. */
    val note: String?,
    /** Visible sending identity for exact review. */
    val fromAddress: String? = null,
)
