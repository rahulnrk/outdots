package app.mini.hub.android.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.Clock
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.retrieval.ScopedRetrievalQuery
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.ContextCategory

/**
 * Coordinates UI state between persisted observations, scoped retrieval,
 * style profiles, and exact action approvals.
 */
class InboxViewModel(
    private val observationStore: ObservationStore,
    private val retrievalEngine: ScopedRetrievalEngine,
    private val styleEngine: AdaptiveStyleEngine,
    private val actionController: ActionController,
    private val actionStore: ActionStore,
    private val clock: Clock,
) {
    private val _state = mutableStateOf(InboxViewState())
    val state: State<InboxViewState> get() = _state

    fun refresh(isPermissionGranted: Boolean, isListenerConnected: Boolean) {
        val summaries = observationStore.getConversationSummaries()
        _state.value = _state.value.copy(
            isPermissionGranted = isPermissionGranted,
            isListenerConnected = isListenerConnected,
            conversations = summaries,
        )
    }

    fun selectConversation(summary: ConversationObservationSummary) {
        val observations = observationStore.getObservationsForConversation(summary.conversationId)
        val compartment = CompartmentId(summary.accountId.key)

        val retrieved = retrievalEngine.retrieve(
            ScopedRetrievalQuery(
                conversationId = summary.conversationId,
                compartment = compartment,
                tokenBudget = 650,
            ),
        )

        val style = styleEngine.resolveProfile(
            category = ContextCategory.Professional,
            recipientId = summary.senderHint,
            now = clock.now(),
        )

        _state.value = _state.value.copy(
            selectedConversation = summary,
            selectedTimeline = observations,
            retrievedContext = retrieved,
            activeStyleProfile = style,
            activeReviewPayload = null,
            outboxStatusMessage = null,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            selectedConversation = null,
            selectedTimeline = emptyList(),
            retrievedContext = null,
            activeStyleProfile = null,
            activeReviewPayload = null,
            outboxStatusMessage = null,
        )
    }

    fun prepareDraft(body: String, recipient: String) {
        val selected = _state.value.selectedConversation ?: return

        val payload = ExactActionPayload(
            accountId = selected.accountId.key,
            conversationId = selected.conversationId.key,
            route = ActionRoute.NativeHandoff,
            recipients = if (recipient.isNotBlank()) listOf(recipient) else emptyList(),
            subject = null,
            body = body,
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = "grant-v1-user-initiated",
            policyVersion = "policy-v1-notification-handoff",
        )

        _state.value = _state.value.copy(activeReviewPayload = payload)
    }

    fun clearDraftReview() {
        _state.value = _state.value.copy(activeReviewPayload = null)
    }

    fun approveAndDispatch(handoffLauncher: HandoffLauncher) {
        val payload = _state.value.activeReviewPayload ?: return

        var outboxCmdId: String? = null
        actionStore.transaction { currentActionState ->
            val (approvedState, command) = actionController.approve(currentActionState, payload)
            outboxCmdId = command.id
            approvedState
        }

        val cmdId = outboxCmdId
        if (cmdId != null) {
            val finalActionState = actionStore.transaction { currentActionState ->
                actionController.dispatch(
                    state = currentActionState,
                    commandId = cmdId,
                    payload = payload,
                    gateway = null,
                    handoff = handoffLauncher,
                )
            }

            val finalCommand = finalActionState.outbox.find { it.id == cmdId }
            val statusMessage = when (finalCommand?.state) {
                OutboxState.HandoffOpened -> "Draft opened in native app. Pending your manual send."
                OutboxState.AcceptedByProvider -> "Message accepted by provider."
                OutboxState.Rejected -> "Action rejected: ${finalCommand.failureReason}"
                else -> "Action state: ${finalCommand?.state?.name}"
            }

            _state.value = _state.value.copy(
                activeReviewPayload = null,
                outboxStatusMessage = statusMessage,
            )
        }
    }
}
