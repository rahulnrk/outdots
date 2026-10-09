package app.mini.hub.android.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxState
import app.mini.hub.android.intelligence.LocalDraftExperiment
import app.mini.hub.android.intelligence.LocalDraftRequest
import app.mini.hub.android.intelligence.LocalDraftResult
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.AttentionReconciler
import app.mini.hub.attention.AttentionStore
import app.mini.hub.attention.AttentionStoreResult
import app.mini.hub.attention.AttentionView
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.AttentionRules
import app.mini.hub.attention.AttentionEntry
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
    private val attentionStore: AttentionStore,
    private val localDraftExperiment: LocalDraftExperiment? = null,
) {
    // Monotonic token so a late callback from a cancelled or superseded request cannot overwrite
    // the cancellation state or a newer draft.
    private var localDraftGeneration = 0L
    private val _state = mutableStateOf(InboxViewState())
    val state: State<InboxViewState> get() = _state
    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (_: RuntimeException) {
        null
    }

    fun refresh(isPermissionGranted: Boolean, isListenerConnected: Boolean) {
        val summaries = observationStore.getConversationSummaries()
        val loadResult = attentionStore.loadAll()
        
        var failure: String? = null
        var records = _state.value.attentionRecords
        if (loadResult is AttentionStoreResult.Ok) {
            val rec = AttentionReconciler.reconcile(loadResult.value, summaries, clock.now())
            records = rec.records
            if (rec.changed.isNotEmpty()) {
                val saveResult = attentionStore.saveAll(rec.changed)
                if (saveResult is AttentionStoreResult.Failed) {
                    failure = saveResult.message
                }
            }
        } else if (loadResult is AttentionStoreResult.Failed) {
            failure = loadResult.message
        }

        val entries = summaries.map { 
            AttentionEntry(it, records[it.conversationId.key] ?: AttentionRules.default(it.conversationId, clock.now()))
        }
        val view = AttentionView.build(entries)

        _state.value = _state.value.copy(
            isPermissionGranted = isPermissionGranted,
            isListenerConnected = isListenerConnected,
            conversations = summaries,
            attentionRecords = records,
            attentionView = view,
            storageFailure = failure,
        )
    }

    fun applyAttentionAction(conversationId: app.mini.hub.domain.ScopedId, action: AttentionAction) {
        val existing = _state.value.attentionRecords[conversationId.key] ?: AttentionRules.default(conversationId, clock.now())
        val decision = AttentionRules.apply(existing, action, _state.value.conversations.find { it.conversationId == conversationId }?.latestEventAt, clock.now())
        if (decision is app.mini.hub.attention.AttentionDecision.Applied) {
            val saveResult = attentionStore.saveAll(listOf(decision.record))
            if (saveResult is AttentionStoreResult.Failed) {
                _state.value = _state.value.copy(storageFailure = saveResult.message)
            } else {
                refresh(_state.value.isPermissionGranted, _state.value.isListenerConnected)
            }
        } else if (decision is app.mini.hub.attention.AttentionDecision.Rejected) {
            _state.value = _state.value.copy(outboxStatusMessage = decision.reason)
        }
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

        abandonLocalDraft()
        _state.value = _state.value.copy(
            selectedConversation = summary,
            selectedTimeline = observations,
            retrievedContext = retrieved,
            activeStyleProfile = style,
            activeReviewPayload = null,
            outboxStatusMessage = null,
            localDraftText = "",
            localDraftWarning = null,
            localDraftStatus = null,
            isLocalDraftInProgress = false,
        )
    }

    fun clearSelection() {
        abandonLocalDraft()
        _state.value = _state.value.copy(
            selectedConversation = null,
            selectedTimeline = emptyList(),
            retrievedContext = null,
            activeStyleProfile = null,
            activeReviewPayload = null,
            outboxStatusMessage = null,
            localDraftText = "",
            localDraftWarning = null,
            localDraftStatus = null,
            isLocalDraftInProgress = false,
        )
    }

    fun requestLocalDraft(userIntent: String) {
        val selected = _state.value.selectedConversation ?: return
        val retrieved = _state.value.retrievedContext ?: return
        val experiment = localDraftExperiment ?: run {
            _state.value = _state.value.copy(
                localDraftStatus = "Local drafting is unavailable in this build.",
                isLocalDraftInProgress = false,
            )
            return
        }
        val generation = ++localDraftGeneration
        _state.value = _state.value.copy(
            localDraftStatus = "Generating locally on this device…",
            localDraftText = "",
            localDraftWarning = retrieved.coverageWarning.ifBlank { null },
            isLocalDraftInProgress = true,
        )
        experiment.draftAsync(
            LocalDraftRequest(
                conversationLabel = selected.title,
                sender = selected.senderHint,
                compartment = CompartmentId(selected.accountId.key),
                context = retrieved.contextBundle,
                observations = retrieved.retrievedObservations,
                coverageWarning = retrieved.coverageWarning,
                userIntent = userIntent,
            ),
        ) { result ->
            val applyResult = applyResult@{
                if (generation != localDraftGeneration) return@applyResult
                when (result) {
                    is LocalDraftResult.Success -> _state.value = _state.value.copy(
                        localDraftText = result.proposal.text,
                        localDraftWarning = result.warning.ifBlank { null },
                        localDraftStatus = "Local draft ready (${result.latencyMs} ms). Review it before use.",
                        isLocalDraftInProgress = false,
                    )
                    is LocalDraftResult.Refused -> _state.value = _state.value.copy(
                        localDraftWarning = result.warning.ifBlank { null },
                        localDraftStatus = result.reason,
                        isLocalDraftInProgress = false,
                    )
                    is LocalDraftResult.Failed -> _state.value = _state.value.copy(
                        localDraftWarning = result.warning.ifBlank { null },
                        localDraftStatus = "Local draft unavailable: ${result.reason}",
                        isLocalDraftInProgress = false,
                    )
                }
            }
            mainHandler?.post(applyResult) ?: applyResult()
        }
    }

    // Drops any in-flight draft so its result cannot appear in a different conversation.
    private fun abandonLocalDraft() {
        if (_state.value.isLocalDraftInProgress) {
            localDraftGeneration++
            localDraftExperiment?.cancel()
        }
    }

    fun cancelLocalDraft() {
        if (!_state.value.isLocalDraftInProgress) return
        // Invalidate the in-flight request first so its eventual callback is ignored, then ask the
        // runtime to stop. The UI reflects the user's cancellation even if the engine had already
        // finished generating.
        localDraftGeneration++
        localDraftExperiment?.cancel()
        _state.value = _state.value.copy(
            localDraftStatus = "Local draft cancelled.",
            isLocalDraftInProgress = false,
        )
    }

    fun updateLocalDraftText(text: String) {
        _state.value = _state.value.copy(localDraftText = text)
    }

    fun releaseLocalDraftResources() {
        localDraftExperiment?.release()
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
