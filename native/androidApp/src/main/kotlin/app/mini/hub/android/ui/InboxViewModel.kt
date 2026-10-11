package app.mini.hub.android.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.mini.hub.android.intelligence.LocalAiTrace
import app.mini.hub.android.intelligence.RecoveryResult
import app.mini.hub.android.intelligence.ReleaseStatus
import app.mini.hub.android.intelligence.onAndroidMainThread
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.LiveDispatchChecks
import app.mini.hub.action.OutboxState
import app.mini.hub.action.SourceVersion
import app.mini.hub.connector.AuthorizationSnapshot
import app.mini.hub.connector.CapabilitySnapshot
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
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.CommunicationSourcePolicy
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.retrieval.ScopedRetrievalQuery
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.StyleContext
import app.mini.hub.understanding.MessageUnderstanding
import app.mini.hub.understanding.MessageUnderstandingEngine

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
    private val selectedSources: () -> Set<CommunicationSource> = { CommunicationSource.defaultSelection },
    private val accountStore: app.mini.hub.connector.ConnectedAccountStore? = null,
    private val gateway: app.mini.hub.action.ProviderGateway? = null,
    /** True only when the account may reply via API right now (scopes granted, gates cleared). */
    private val apiReplyAllowed: (app.mini.hub.connector.ConnectedAccount) -> Boolean = { false },
    /** Live capability snapshot used to bind and re-check grant/policy versions. */
    private val capabilitiesFor: (app.mini.hub.connector.ConnectedAccount) -> CapabilitySnapshot? = { null },
    /**
     * Called after the user reviews and approves text they authored or edited inside MINI.
     * Unedited AI drafts are not passed here.
     */
    private val onLearnFromApprovedText: ((ConversationObservationSummary, String, String, app.mini.hub.style.TextAuthorship) -> Unit)? = null,
    /**
     * Fetches the full provider conversation, persists it, and returns the refreshed timeline.
     * Network I/O: always invoked off the main thread. Null when the provider has no reader.
     */
    private val conversationLoader: ((ConversationObservationSummary) -> List<app.mini.hub.observation.ObservedNotification>?)? = null,
    /** Runs provider sends off the main thread. Tests inject a deterministic runner. */
    private val sendRunner: (Runnable) -> Unit = { r -> Thread(r, "mini-send").start() },
    /** Runs refresh store reads off the main thread. Tests inject a deterministic runner; null = default. */
    refreshRunner: ((Runnable) -> Unit)? = null,
) {
    /** Monotonic token: a refresh result older than the latest request is dropped. */
    private var refreshGeneration = 0L
    /** Understanding cache keyed by conversation, valid while its change signature is unchanged. */
    private val understandingCache = HashMap<String, Pair<String, MessageUnderstanding>>()
    /** Main-thread guard: true from the first tap until the send result is applied. */
    private var sendInFlight = false
    private var conversationLoadGeneration = 0L
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
    /** Background executor on Android; inline on a plain JVM (no main Looper), like [postToMain]. */
    private val refreshRunner: (Runnable) -> Unit = refreshRunner
        ?: if (mainHandler != null) { r -> REFRESH_EXECUTOR.execute(r) } else { r -> r.run() }
    /** Monotonic token: late search results are dropped when a newer query was typed. */
    private var searchGeneration = 0L

    init {
        localDraftExperiment?.setReleaseListener { ticket ->
            postToMain("release") {
                _state.value = _state.value.copy(localAiReleaseStatus = releaseLabel(ticket.status))
            }
        }
    }

    /**
     * Delivers worker results on the main thread and records the real main-thread cost of
     * applying them (dispatch delay and handler duration).
     */
    private fun postToMain(name: String, block: () -> Unit) {
        val handler = mainHandler ?: run { block(); return }
        val postedAt = SystemClock.elapsedRealtimeNanos()
        handler.post {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            val end = SystemClock.elapsedRealtimeNanos()
            LocalAiTrace.log(
                "MAIN_CALLBACK name=ui-$name mainThread=${onAndroidMainThread()} " +
                    "dispatchDelayUs=${(start - postedAt) / 1_000} durationUs=${(end - start) / 1_000}",
            )
        }
    }

    private fun releaseLabel(status: ReleaseStatus): String = when (status) {
        ReleaseStatus.Queued -> "Local AI cleanup queued."
        ReleaseStatus.Released -> "Local AI resources released."
        is ReleaseStatus.Quarantined -> "Local AI could not stop cleanly. Restart the app to free it."
        is ReleaseStatus.Failed -> "Local AI cleanup failed. Restart the app before drafting."
    }

    fun refresh(isPermissionGranted: Boolean, isListenerConnected: Boolean) {
        val generation = ++refreshGeneration
        val previousRecords = _state.value.attentionRecords
        refreshRunner(Runnable {
            val result = loadRefresh(previousRecords)
            postToMain("refresh") {
                if (generation != refreshGeneration) return@postToMain // a newer refresh superseded this one
                applyRefresh(result, isPermissionGranted, isListenerConnected)
            }
        })
    }

    private class RefreshResult(
        val summaries: List<ConversationObservationSummary>,
        val records: Map<String, app.mini.hub.attention.ConversationAttention>,
        val failure: String?,
        val view: AttentionView,
        val understanding: Map<String, MessageUnderstanding>,
    )

    /** Store reads and analysis; runs on the refresh worker. */
    private fun loadRefresh(previousRecords: Map<String, app.mini.hub.attention.ConversationAttention>): RefreshResult {
        val policy = CommunicationSourcePolicy(selectedSources)
        val summaries = observationStore.getConversationSummaries()
            .filter { policy.isVisibleInInbox(it.packageId) }
            .filterNot { isHiddenWhatsAppChat(it.conversationId.value) }
            .let { all ->
                // When a provider account is linked (WhatsApp, Gmail...), its notification-only copies
                // are duplicates of the synced chats. Show only the synced ones.
                val linked = all.filter { it.coverage != app.mini.hub.observation.ConversationCoverage.NotificationOnly }
                    .map { it.accountId.provider }.toSet()
                all.filterNot { it.coverage == app.mini.hub.observation.ConversationCoverage.NotificationOnly && it.accountId.provider in linked }
            }
            .filterNot { it.latestSnippet.isBlank() && it.observationCount <= 1 }
        val loadResult = attentionStore.loadAll()

        var failure: String? = null
        var records = previousRecords
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
        // Re-read a conversation's messages only when its summary or attention record changed.
        val understanding = synchronized(understandingCache) {
            understandingCache.keys.retainAll(summaries.map { it.conversationId.key }.toSet())
            summaries.associate { summary ->
                val key = summary.conversationId.key
                val record = records[key] ?: AttentionRules.default(summary.conversationId, clock.now())
                val signature = "${summary.latestEventAt.epochMillis}|${summary.observationCount}|${summary.latestSnippet.hashCode()}|${summary.isRemoved}|$record"
                val cached = understandingCache[key]?.takeIf { it.first == signature }?.second
                key to (cached ?: MessageUnderstandingEngine.analyze(
                    summary,
                    observationStore.getObservationsForConversation(summary.conversationId).filterNot { it.isRemoved },
                    record,
                ).also { understandingCache[key] = signature to it })
            }
        }
        return RefreshResult(summaries, records, failure, view, understanding)
    }

    private fun applyRefresh(result: RefreshResult, isPermissionGranted: Boolean, isListenerConnected: Boolean) {
        val summaries = result.summaries
        val selected = _state.value.selectedConversation?.takeIf { current ->
            summaries.any { it.conversationId == current.conversationId }
        }
        _state.value = _state.value.copy(
            isPermissionGranted = isPermissionGranted,
            isListenerConnected = isListenerConnected,
            conversations = summaries,
            attentionRecords = result.records,
            understanding = result.understanding,
            attentionView = result.view,
            storageFailure = result.failure,
            selectedConversation = selected,
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

    fun onSearchQueryChanged(text: String) {
        val filter = _state.value.searchSourceFilter
        _state.value = _state.value.copy(searchQuery = text)
        executeSearch(text, filter)
    }

    fun onSearchSourceFilterChanged(packageId: String?) {
        val query = _state.value.searchQuery
        _state.value = _state.value.copy(searchSourceFilter = packageId)
        executeSearch(query, packageId)
    }

    fun clearSearch() {
        searchGeneration++
        _state.value = _state.value.copy(searchQuery = "", searchResults = emptyList(), searchSourceFilter = null, isSearching = false)
    }

    private fun executeSearch(queryText: String, sourceFilter: String?) {
        val trimmed = queryText.trim()
        if (trimmed.isBlank()) {
            searchGeneration++
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        val gen = ++searchGeneration
        _state.value = _state.value.copy(isSearching = true)
        val enabledSources = selectedSources().flatMap { it.packageIds }.toSet()
        refreshRunner(Runnable {
            // Search runs off the main thread; logs contain no query text or message content.
            val query = app.mini.hub.search.SearchQuery(queryText = trimmed, sourceFilter = sourceFilter, limit = 50)
            val rawResults = observationStore.search(query)
            // Filter out sources that are disabled in settings
            val filtered = rawResults.filter { it.packageId in enabledSources || it.packageId.startsWith("provider.") }
            postToMain("search-result") {
                if (gen != searchGeneration) return@postToMain
                _state.value = _state.value.copy(searchResults = filtered, isSearching = false)
            }
        })
    }

    fun selectConversationFromSearch(result: app.mini.hub.search.SearchResult) {
        val conv = _state.value.conversations.firstOrNull { it.conversationId == result.conversationId }
            ?: observationStore.getConversationSummaries(result.accountId).firstOrNull { it.conversationId == result.conversationId }
        if (conv != null) {
            selectConversation(conv)
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

        val styleCategory = StyleContext.forMessage(summary.accountId.provider, summary.title, summary.latestSnippet)
        val style = styleEngine.resolveProfile(
            category = styleCategory,
            recipientId = styleRecipient(summary),
            now = clock.now(),
            account = summary.accountId,
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
            originalGeneratedDraft = null,
            draftVersions = emptyList(),
            localDraftWarning = null,
            localDraftStatus = null,
            localDraftFeedback = null,
            isLocalDraftInProgress = false,
            // The failure pause belongs to the runtime, not the conversation; keep offering retry.
            isLocalAiRetryAvailable = localDraftExperiment?.needsRecovery == true,
            conversationLoadStatus = null,
        )
        loadFullConversation(summary)
    }

    private fun loadFullConversation(summary: ConversationObservationSummary) {
        val loader = conversationLoader ?: return
        val account = accountStore?.get(summary.accountId) ?: return
        if (account.state !in USABLE_STATES) return
        val generation = ++conversationLoadGeneration
        _state.value = _state.value.copy(conversationLoadStatus = "Loading the full conversation from ${account.id.provider.wire}…")
        Thread({
            val loaded = runCatching { loader(summary) }
            postToMain("conversation-load") {
                if (generation != conversationLoadGeneration ||
                    _state.value.selectedConversation?.conversationId != summary.conversationId
                ) return@postToMain
                val timeline = loaded.getOrNull()
                _state.value = when {
                    loaded.isFailure -> _state.value.copy(
                        conversationLoadStatus = "Full conversation unavailable right now; showing messages already in MINI.",
                    )
                    timeline == null -> _state.value.copy(conversationLoadStatus = null)
                    else -> _state.value.copy(
                        selectedTimeline = timeline,
                        understanding = _state.value.understanding + (summary.conversationId.key to MessageUnderstandingEngine.analyze(
                            summary,
                            timeline.filterNot { it.isRemoved },
                            _state.value.attentionRecords[summary.conversationId.key]
                                ?: AttentionRules.default(summary.conversationId, clock.now()),
                        )),
                        retrievedContext = retrievalEngine.retrieve(
                            ScopedRetrievalQuery(
                                conversationId = summary.conversationId,
                                compartment = CompartmentId(summary.accountId.key),
                                tokenBudget = 650,
                            ),
                        ),
                        conversationLoadStatus = "Full conversation loaded (${timeline.size} message(s)).",
                    )
                }
            }
        }, "mini-conversation-load").start()
    }

    fun clearSelection() {
        abandonLocalDraft()
        conversationLoadGeneration++
        _state.value = _state.value.copy(
            selectedConversation = null,
            selectedTimeline = emptyList(),
            retrievedContext = null,
            activeStyleProfile = null,
            activeReviewPayload = null,
            outboxStatusMessage = null,
            localDraftText = "",
            originalGeneratedDraft = null,
            draftVersions = emptyList(),
            localDraftWarning = null,
            localDraftStatus = null,
            localDraftFeedback = null,
            isLocalDraftInProgress = false,
        )
    }

    fun requestLocalDraft(userIntent: String, revise: Boolean = false) {
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
        val previous = if (revise) _state.value.localDraftText.takeIf { it.isNotBlank() } else null
        _state.value = _state.value.copy(
            localDraftStatus = if (previous != null) "Rewriting on this device…" else "Generating locally on this device…",
            localDraftText = previous ?: "",
            localDraftWarning = retrieved.coverageWarning.ifBlank { null },
            isLocalDraftInProgress = true,
            localDraftFeedback = null,
            isLocalAiRetryAvailable = false,
            // A new draft reloads the model, so an earlier "released" line would be stale.
            localAiReleaseStatus = null,
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
                styleProfile = _state.value.activeStyleProfile,
                understandingNote = _state.value.understanding[selected.conversationId.key]?.explanation,
                previousDraft = previous,
            ),
        ) { result ->
            val applyResult = applyResult@{
                if (generation != localDraftGeneration) return@applyResult
                when (result) {
                    is LocalDraftResult.Success -> _state.value = _state.value.copy(
                        localDraftText = result.proposal.text,
                        originalGeneratedDraft = result.proposal.text,
                        draftVersions = (_state.value.draftVersions + result.proposal.text).takeLast(5),
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
                        localDraftStatus = when {
                            result.cancelled -> "Local draft cancelled."
                            result.needsRecovery || result.restartRequired -> result.reason
                            else -> "Local draft unavailable: ${result.reason}"
                        },
                        isLocalDraftInProgress = false,
                        isLocalAiRetryAvailable = result.needsRecovery,
                    )
                }
            }
            postToMain("draft-result", applyResult)
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

    /** Called by the host when Android backgrounds the app; typed/generated text remains visible. */
    fun onAppBackgrounded() {
        if (!_state.value.isLocalDraftInProgress) return
        localDraftGeneration++
        localDraftExperiment?.cancel()
        _state.value = _state.value.copy(
            localDraftStatus = "Draft paused when MINI left the foreground. Your text was kept; tap Draft on device to try again.",
            isLocalDraftInProgress = false,
        )
    }

    fun updateLocalDraftText(text: String) {
        _state.value = _state.value.copy(localDraftText = text)
    }

    fun recordDraftUsefulness(feedback: DraftUsefulnessFeedback) {
        if (_state.value.localDraftText.isBlank()) return
        _state.value = _state.value.copy(
            localDraftFeedback = feedback,
            localDraftStatus = when (feedback) {
                DraftUsefulnessFeedback.Useful -> "Marked useful. Review and approve before opening the native app."
                DraftUsefulnessFeedback.NeedsEditing -> "Marked needs editing. Your edits stay local and are not learned automatically."
                DraftUsefulnessFeedback.NotUseful -> "Marked not useful. Try a clearer intent or reply manually."
            },
        )
    }

    /**
     * "Retry local AI" after repeated failures. Recovery runs on the inference worker; the
     * model is not loaded until the next valid draft request.
     */
    fun retryLocalAi() {
        val experiment = localDraftExperiment ?: return
        if (_state.value.isLocalAiRetryInProgress) return
        _state.value = _state.value.copy(isLocalAiRetryInProgress = true)
        experiment.retryLocalAi { result ->
            postToMain("retry-result") {
                val message = when (result) {
                    RecoveryResult.Recovered -> "Local AI is ready. The model loads when you draft again."
                    RecoveryResult.NotNeeded -> "Local AI is already available."
                    RecoveryResult.Background -> "Open the app to retry local AI."
                    RecoveryResult.MemoryPressure -> "The device is low on memory. Try again shortly."
                    RecoveryResult.RestartRequired -> "Local AI did not stop cleanly. Restart the app before drafting again."
                }
                _state.value = _state.value.copy(
                    isLocalAiRetryInProgress = false,
                    isLocalAiRetryAvailable = result == RecoveryResult.MemoryPressure ||
                        result == RecoveryResult.Background,
                    localDraftStatus = message,
                )
            }
        }
    }

    fun releaseLocalDraftResources() {
        localDraftExperiment?.release()
    }

    fun prepareDraft(body: String, recipient: String) {
        val selected = _state.value.selectedConversation ?: return
        val provider = selected.accountId.provider
        val connectedAccount = accountStore?.get(selected.accountId)
        val isApiAccount = connectedAccount != null &&
            connectedAccount.route in setOf(
                app.mini.hub.connector.ConnectorRoute.OfficialProviderApi,
                app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge,
            ) &&
            connectedAccount.state in USABLE_STATES
        val isEmail = app.mini.hub.connector.ReplyTargets.usesSubject(provider)

        // Reply to the newest inbound message; fall back to the newest non-own message, then newest overall.
        // Timeline is ordered newest-first (TIMELINE_ORDER: post_time DESC, observed_at DESC, id DESC),
        // so firstOrNull gives the newest message matching the condition.
        val live = _state.value.selectedTimeline.filter { !it.isRemoved }
        val anchor = live.firstOrNull { isInboundMessage(it, connectedAccount) } ?: live.firstOrNull()
        val providerMessageId = anchor?.notificationKey
            ?.takeIf { it.startsWith("provider:${selected.accountId.key}:") }
            ?.removePrefix("provider:${selected.accountId.key}:")
        val replyTarget = app.mini.hub.connector.ReplyTargets.forProvider(provider, providerMessageId)

        // Email needs an address; chat routes address the conversation itself.
        val typed = recipient.trim()
        val recipients: List<String> = if (isEmail) {
            listOfNotNull(app.mini.hub.connector.EmailAddress.extract(typed.ifBlank { anchor?.senderHint ?: selected.senderHint }))
        } else {
            listOfNotNull(typed.ifBlank { selected.title.ifBlank { selected.senderHint } }.takeIf { it.isNotBlank() })
        }

        val apiAllowed = isApiAccount && apiReplyAllowed(connectedAccount!!) && (!isEmail || recipients.isNotEmpty())
        val route = if (apiAllowed) ActionRoute.Api else ActionRoute.NativeHandoff
        val subject = if (isEmail) selected.title.takeIf { it.isNotBlank() }?.let { s ->
            if (replyTarget != null && !s.startsWith("Re:", ignoreCase = true)) "Re: $s" else s
        } else null

        val sourceVersions = live.map { SourceVersion(it.id.key, it.revision) }
        val enabled = connectedAccount?.let { capabilitiesFor(it)?.enabled }.orEmpty()
        val grantVersion = AuthorizationSnapshot.grantVersion(connectedAccount?.grantedScopes.orEmpty())
        val policyVersion = AuthorizationSnapshot.policyVersion(enabled, route)

        val payload = ExactActionPayload(
            accountId = selected.accountId.key,
            conversationId = selected.conversationId.key,
            route = route,
            recipients = if (route == ActionRoute.Api || recipients.isNotEmpty()) recipients else listOfNotNull(selected.senderHint.takeIf { it.isNotBlank() }),
            subject = subject,
            body = body,
            attachments = emptyList(),
            sourceVersions = sourceVersions,
            grantVersion = grantVersion,
            policyVersion = policyVersion,
            replyTarget = if (route == ActionRoute.Api) replyTarget else null,
        )

        val note = when {
            route == ActionRoute.Api -> null
            isApiAccount -> "API sending is not enabled for this account yet (permission not granted or send qualification not recorded). " +
                "MINI will open ${app.mini.hub.observation.SourceAppNames.forPackage(selected.packageId)} with your text so you send it yourself."
            else -> null
        }
        _state.value = _state.value.copy(
            activeReviewPayload = payload,
            reviewContext = ReviewContext(
                providerName = providerLabel(provider),
                isReply = route == ActionRoute.Api && replyTarget != null,
                handoffPackage = if (route == ActionRoute.NativeHandoff) handoffPackageFor(selected) else null,
                note = note,
            ),
        )
    }

    private fun isInboundMessage(obs: app.mini.hub.observation.ObservedNotification, account: app.mini.hub.connector.ConnectedAccount?): Boolean = when (obs.direction) {
        app.mini.hub.connector.MessageDirection.Inbound -> true
        app.mini.hub.connector.MessageDirection.Outbound -> false
        null, app.mini.hub.connector.MessageDirection.Unknown -> !isOwnMessage(obs, account)
    }

    private fun isOwnMessage(obs: app.mini.hub.observation.ObservedNotification, account: app.mini.hub.connector.ConnectedAccount?): Boolean {
        val me = account?.providerAccountId?.lowercase() ?: return false
        return obs.senderHint.lowercase().let { it == me || it.contains("<$me>") }
    }

    /** Notification rows open their own app; API rows open the provider's primary app. */
    private fun handoffPackageFor(summary: ConversationObservationSummary): String? =
        summary.packageId.takeUnless { it.startsWith(CommunicationSource.API_PACKAGE_PREFIX) }
            ?: CommunicationSource.primaryPackageFor(summary.accountId.provider)

    private fun providerLabel(provider: app.mini.hub.domain.Provider): String =
        app.mini.hub.observation.SourceAppNames.forPackage(CommunicationSource.API_PACKAGE_PREFIX + provider.wire)

    fun clearDraftReview() {
        _state.value = _state.value.copy(activeReviewPayload = null, reviewContext = null)
    }

    fun approveAndDispatch(handoffLauncher: HandoffLauncher) {
        if (sendInFlight) return // a second tap while a send is in flight does nothing
        val payload = _state.value.activeReviewPayload ?: return
        val context = _state.value.reviewContext
        val launcher = (handoffLauncher as? AndroidNativeHandoffLauncher)?.forPackage(context?.handoffPackage) ?: handoffLauncher

        var outboxCmdId: String? = null
        actionStore.transaction { currentActionState ->
            val (approvedState, command) = actionController.approve(currentActionState, payload)
            outboxCmdId = command.id
            approvedState
        }
        val cmdId = outboxCmdId ?: return

        if (payload.route == ActionRoute.NativeHandoff) {
            // Handoff opens an activity: it must stay on the main thread and makes no network call.
            val finalState = actionStore.transaction {
                actionController.dispatch(it, cmdId, payload, gateway, launcher, liveDispatchChecks(payload))
            }
            applyDispatchResult(payload, context, finalState.outbox.find { it.id == cmdId })
            return
        }

        // API send in three durable steps. The provider call runs off the main thread and outside
        // any database transaction; Dispatching and the consumed approval are committed first, so a
        // crash mid-call is recovered at startup as OutcomeUnknown and never re-sent.
        sendInFlight = true
        _state.value = _state.value.copy(isSending = true, outboxStatusMessage = "Sending through ${context?.providerName ?: "the provider"}…")
        sendRunner(Runnable {
            val finalCommand = runCatching {
                val liveChecks = liveDispatchChecks(payload)
                val begun = actionStore.transaction { actionController.beginDispatch(it, cmdId, payload, liveChecks) }
                val command = begun.outbox.first { it.id == cmdId }
                if (command.state != OutboxState.Dispatching) {
                    command
                } else {
                    val result = actionController.executeGateway(command, payload, gateway)
                    actionStore.transaction { actionController.completeDispatch(it, cmdId, result) }
                        .outbox.find { it.id == cmdId }
                }
            }.getOrNull() ?: actionStore.snapshot().outbox.find { it.id == cmdId }
            postToMain("send-result") {
                sendInFlight = false
                applyDispatchResult(payload, context, finalCommand)
            }
        })
    }

    private fun applyDispatchResult(payload: ExactActionPayload, context: ReviewContext?, finalCommand: app.mini.hub.action.OutboxCommand?) {
        run {
            val statusMessage = when (finalCommand?.state) {
                OutboxState.HandoffOpened -> "Draft opened in ${context?.handoffPackage?.let { app.mini.hub.observation.SourceAppNames.forPackage(it) } ?: "another app"}. Nothing is sent until you send it there."
                OutboxState.AcceptedByProvider -> "${context?.providerName ?: "Provider"} accepted the ${if (context?.isReply == true) "reply" else "message"} (receipt ${finalCommand.receipt ?: "accepted"}). Accepted means the provider took it; it does not confirm the recipient received it."
                OutboxState.Rejected -> "Not sent: ${finalCommand.failureReason}"
                OutboxState.OutcomeUnknown -> "Send outcome unknown (${finalCommand.failureReason ?: "timeout/network failure"}). Check the conversation before trying again so it is not sent twice."
                else -> "Action state: ${finalCommand?.state?.name}"
            }

            if (finalCommand?.state == OutboxState.AcceptedByProvider || finalCommand?.state == OutboxState.HandoffOpened) {
                authorshipForApprovedBody(payload.body)?.let { authorship ->
                    onLearnFromApprovedText?.invoke(
                        selectedConversationForLearning(),
                        payload.body,
                        payload.recipients.firstOrNull().orEmpty(),
                        authorship,
                    )
                }
            }

            _state.value = _state.value.copy(
                activeReviewPayload = null,
                reviewContext = null,
                outboxStatusMessage = statusMessage,
                isSending = false,
            )
        }
    }

    /** Test hook: puts an exact payload under review without going through route selection. */
    internal fun setReviewPayloadForTest(payload: ExactActionPayload, context: ReviewContext? = null) {
        _state.value = _state.value.copy(activeReviewPayload = payload, reviewContext = context)
    }

    private fun liveDispatchChecks(payload: ExactActionPayload): LiveDispatchChecks {
        val accountId = runCatching { app.mini.hub.domain.AccountId.parse(payload.accountId) }.getOrNull()
        val account = accountId?.let { accountStore?.get(it) }
        val grant: String
        val policy: String
        if (account != null) {
            val enabled = capabilitiesFor(account)?.enabled.orEmpty()
            grant = AuthorizationSnapshot.grantVersion(account.grantedScopes)
            policy = AuthorizationSnapshot.policyVersion(enabled, payload.route)
        } else {
            grant = payload.grantVersion
            policy = payload.policyVersion
        }
        return LiveDispatchChecks(grant, policy, liveSourceVersions(payload))
    }

    private fun liveSourceVersions(payload: ExactActionPayload): List<SourceVersion> {
        val conversationId = runCatching { app.mini.hub.domain.ScopedId.parse(payload.conversationId) }.getOrNull()
            ?: return payload.sourceVersions
        return observationStore.getObservationsForConversation(conversationId)
            .filter { !it.isRemoved }
            .map { SourceVersion(it.id.key, it.revision) }
    }

    private fun selectedConversationForLearning(): ConversationObservationSummary =
        requireNotNull(_state.value.selectedConversation)

    private fun authorshipForApprovedBody(body: String): app.mini.hub.style.TextAuthorship? {
        val original = _state.value.originalGeneratedDraft
        return when {
            original == null -> app.mini.hub.style.TextAuthorship.UserAuthoredSentMessage
            original == body -> null
            else -> app.mini.hub.style.TextAuthorship.LocallyEditedDraft
        }
    }

    private fun styleRecipient(summary: ConversationObservationSummary): String =
        "${summary.accountId.key}|${summary.senderHint}"

    companion object {
        /** One background thread for inbox reads keeps refreshes ordered and off the main thread. */
        private val REFRESH_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mini-inbox-refresh").apply { isDaemon = true }
        }
        private val USABLE_STATES = setOf(
            app.mini.hub.connector.ConnectionState.Authorized,
            app.mini.hub.connector.ConnectionState.Healthy,
            app.mini.hub.connector.ConnectionState.Syncing,
        )
    }
}
