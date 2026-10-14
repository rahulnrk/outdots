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
import app.mini.hub.action.toReviewedSnapshot
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
    private val draftStore: app.mini.hub.persistence.ConversationDraftStore? =
        actionStore as? app.mini.hub.persistence.ConversationDraftStore
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
        val reviewedSnapshots: List<app.mini.hub.action.ReviewedActionSnapshot>,
    )

    /** Store reads and analysis; runs on the refresh worker. */
    private fun loadRefresh(previousRecords: Map<String, app.mini.hub.attention.ConversationAttention>): RefreshResult {
        val policy = CommunicationSourcePolicy(selectedSources = selectedSources)
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
                    observationStore.getObservationsForConversation(summary.conversationId).filterNot { it.isRemoved && it.notificationKey.startsWith("provider:") },
                    record,
                ).also { understandingCache[key] = signature to it })
            }
        }
        return RefreshResult(summaries, records, failure, view, understanding, actionStore.snapshot().reviewedSnapshots)
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
            reviewedSnapshots = result.reviewedSnapshots,
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

    private fun persistCurrentDraft() {
        val selected = _state.value.selectedConversation ?: return
        val store = draftStore ?: return
        val s = _state.value
        val empty = s.localDraftText.isBlank() &&
            s.composeNewTo.orEmpty().isBlank() &&
            s.composeCc.isBlank() &&
            s.composeBcc.isBlank() &&
            s.composeSubject.isBlank()
        if (empty) {
            store.deleteDraft(selected.conversationId.key)
            return
        }
        store.saveDraft(
            app.mini.hub.persistence.ConversationDraft(
                conversationKey = selected.conversationId.key,
                accountId = selected.accountId.key,
                body = s.localDraftText,
                to = s.composeNewTo.orEmpty(),
                cc = s.composeCc,
                bcc = s.composeBcc,
                subject = s.composeSubject,
                isNewEmail = s.composeNewTo != null,
                updatedAtMillis = clock.now().epochMillis,
            ),
        )
    }

    private fun loadedDraft(conversationKey: String): app.mini.hub.persistence.ConversationDraft? =
        draftStore?.loadDraft(conversationKey)

    /**
     * Opens the chat with this person on the chosen account. Uses the existing conversation when
     * there is one; otherwise starts an empty thread so the user can write the first message.
     */
    fun openCompose(accountId: app.mini.hub.domain.AccountId, address: String, name: String) {
        val all = observationStore.getConversationSummaries(accountId)
        val existing = all.firstOrNull { it.conversationId.value.equals(address, ignoreCase = true) }
            ?: all.firstOrNull { it.senderHint.contains(address, ignoreCase = true) }
        if (existing != null) {
            clearSearch()
            selectConversation(existing)
            return
        }
        val isEmail = app.mini.hub.connector.ReplyTargets.usesSubject(accountId.provider)
        val convValue = if (isEmail) "new:$address" else address
        val summary = ConversationObservationSummary(
            conversationId = app.mini.hub.domain.ScopedId(accountId, app.mini.hub.domain.ScopedId.Kind.CONVERSATION, convValue),
            accountId = accountId,
            title = name.ifBlank { address },
            appName = app.mini.hub.observation.SourceAppNames.forPackage(CommunicationSource.API_PACKAGE_PREFIX + accountId.provider.wire),
            senderHint = if (isEmail) "$name <$address>" else name.ifBlank { address },
            packageId = if (accountId.provider == app.mini.hub.domain.Provider.GMAIL) "com.google.android.gm" else CommunicationSource.API_PACKAGE_PREFIX + accountId.provider.wire,
            latestSnippet = "",
            category = app.mini.hub.observation.NotificationCategory.DirectMessage,
            coverage = app.mini.hub.observation.ConversationCoverage.PartialApiSync,
            coverageExplanation = "New conversation",
            latestEventAt = clock.now(),
            observationCount = 0,
            unreadCount = 0,
            isRemoved = false,
        )
        clearSearch()
        persistCurrentDraft()
        abandonLocalDraft()
        val draft = loadedDraft(summary.conversationId.key)
        _state.value = _state.value.copy(
            selectedConversation = summary,
            selectedTimeline = emptyList(),
            retrievedContext = retrievalEngine.retrieve(
                ScopedRetrievalQuery(conversationId = summary.conversationId, compartment = CompartmentId(accountId.key), tokenBudget = 650),
            ),
            activeReviewPayload = null,
            localDraftText = draft?.body.orEmpty(),
            draftVersions = emptyList(),
            localDraftStatus = null,
            conversationLoadStatus = null,
            composeNewTo = if (isEmail) draft?.to?.ifBlank { address } ?: address else null,
            composeCc = draft?.cc.orEmpty(),
            composeBcc = draft?.bcc.orEmpty(),
            composeSubject = draft?.subject.orEmpty(),
        )
    }

    /** Compose button: opens a new email thread (To/Cc/Bcc/Subject) on the chosen account. */
    fun startComposeEmail(accountId: app.mini.hub.domain.AccountId) {
        val summary = ConversationObservationSummary(
            conversationId = app.mini.hub.domain.ScopedId(accountId, app.mini.hub.domain.ScopedId.Kind.CONVERSATION, "compose:${clock.now().epochMillis}"),
            accountId = accountId,
            title = "New email",
            appName = "Gmail",
            senderHint = "",
            packageId = "com.google.android.gm",
            latestSnippet = "",
            category = app.mini.hub.observation.NotificationCategory.DirectMessage,
            coverage = app.mini.hub.observation.ConversationCoverage.PartialApiSync,
            coverageExplanation = "New email",
            latestEventAt = clock.now(),
            observationCount = 0, unreadCount = 0, isRemoved = false,
        )
        clearSearch()
        persistCurrentDraft()
        abandonLocalDraft()
        val draft = loadedDraft(summary.conversationId.key)
        _state.value = _state.value.copy(
            selectedConversation = summary,
            selectedTimeline = emptyList(),
            retrievedContext = retrievalEngine.retrieve(
                ScopedRetrievalQuery(conversationId = summary.conversationId, compartment = CompartmentId(accountId.key), tokenBudget = 650),
            ),
            activeReviewPayload = null,
            localDraftText = draft?.body.orEmpty(),
            draftVersions = emptyList(),
            localDraftStatus = null,
            conversationLoadStatus = null,
            composeNewTo = draft?.to ?: "",
            composeCc = draft?.cc.orEmpty(),
            composeBcc = draft?.bcc.orEmpty(),
            composeSubject = draft?.subject.orEmpty(),
        )
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

        persistCurrentDraft()
        abandonLocalDraft()
        val draft = loadedDraft(summary.conversationId.key)
        _state.value = _state.value.copy(
            selectedConversation = summary,
            selectedTimeline = observations,
            retrievedContext = retrieved,
            activeStyleProfile = style,
            activeReviewPayload = null,
            outboxStatusMessage = null,
            localDraftText = draft?.body.orEmpty(),
            originalGeneratedDraft = null,
            draftVersions = emptyList(),
            composeNewTo = if (draft?.isNewEmail == true) draft.to else null,
            composeCc = draft?.cc.orEmpty(),
            composeBcc = draft?.bcc.orEmpty(),
            composeSubject = draft?.subject.orEmpty(),
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
                            timeline.filterNot { it.isRemoved && it.notificationKey.startsWith("provider:") },
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
        persistCurrentDraft()
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
            composeNewTo = null,
            composeCc = "",
            composeBcc = "",
            composeSubject = "",
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
                persistCurrentDraft()
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
        persistCurrentDraft()
    }

    fun updateLocalDraftText(text: String) {
        _state.value = _state.value.copy(localDraftText = text)
        persistCurrentDraft()
    }

    fun updateComposeTo(value: String) {
        if (_state.value.composeNewTo == null) return
        _state.value = _state.value.copy(composeNewTo = value)
        persistCurrentDraft()
    }

    fun updateComposeCc(value: String) {
        _state.value = _state.value.copy(composeCc = value)
        persistCurrentDraft()
    }

    fun updateComposeBcc(value: String) {
        _state.value = _state.value.copy(composeBcc = value)
        persistCurrentDraft()
    }

    fun updateComposeSubject(value: String) {
        _state.value = _state.value.copy(composeSubject = value)
        persistCurrentDraft()
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

    fun prepareDraft(body: String, recipient: String, subjectOverride: String? = null) {
        if (body.isBlank()) {
            _state.value = _state.value.copy(outboxStatusMessage = "Write a message first.")
            return
        }
        val selected = _state.value.selectedConversation ?: return
        _state.value.composeNewTo?.let { to ->
            prepareNewEmail(selected, to, subjectOverride?.ifBlank { null } ?: "(no subject)", body)
            return
        }
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
        val live = _state.value.selectedTimeline.filter { !(it.isRemoved && it.notificationKey.startsWith("provider:")) }
        val anchor = live.firstOrNull { isInboundMessage(it, connectedAccount) } ?: live.firstOrNull()
        val providerMessageId = anchor?.notificationKey
            ?.takeIf { it.startsWith("provider:${selected.accountId.key}:") }
            ?.removePrefix("provider:${selected.accountId.key}:")
        val replyTarget = app.mini.hub.connector.ReplyTargets.forProvider(provider, providerMessageId, anchor?.replyAnchor)

        // Email needs an address; chat routes address the conversation itself.
        val typed = recipient.trim()
        val recipients: List<String> = if (isEmail) {
            listOfNotNull(app.mini.hub.connector.EmailAddress.extract(typed.ifBlank { anchor?.senderHint ?: selected.senderHint }))
        } else {
            listOfNotNull(typed.ifBlank { selected.title.ifBlank { selected.senderHint } }.takeIf { it.isNotBlank() })
        }

        val apiAllowed = isApiAccount && apiReplyAllowed(connectedAccount!!) && (!isEmail || recipients.isNotEmpty()) &&
            instagramWindowOpen(provider, live, connectedAccount)
        val outsideIgWindow = provider == app.mini.hub.domain.Provider.INSTAGRAM && isApiAccount && !instagramWindowOpen(provider, live, connectedAccount)
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
            outsideIgWindow -> "Instagram only allows apps to reply within 24 hours of their last message. MINI will open this chat in Instagram with your text so you can send it there."
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
                fromAddress = connectedAccount?.providerAccountId ?: connectedAccount?.displayName,
            ),
        )
    }

    private fun prepareNewEmail(selected: ConversationObservationSummary, to: String, subject: String, body: String) {
        prepareComposeEmail(selected.accountId, listOf(to), emptyList(), emptyList(), subject, body)
    }

    /**
     * New email to any address. Sends through Gmail when allowed, otherwise opens Gmail's compose
     * with every field prefilled. The approved payload carries the text exactly as typed.
     */
    fun prepareComposeEmail(
        accountId: app.mini.hub.domain.AccountId,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String,
    ) {
        val clean = { l: List<String> ->
            l.flatMap { it.split(',', ';') }.map { it.trim() }.filter { it.isNotEmpty() }
                .map { app.mini.hub.connector.EmailAddress.extract(it) ?: it }.distinctBy { it.lowercase() }
        }
        val toL = clean(to); val ccL = clean(cc) - toL.toSet(); val bccL = clean(bcc) - (toL + ccL).toSet()
        val bad = (toL + ccL + bccL).firstOrNull { app.mini.hub.connector.EmailAddress.extract(it) == null }
        if (toL.isEmpty() || bad != null || body.isBlank()) {
            _state.value = _state.value.copy(outboxStatusMessage = when {
                toL.isEmpty() -> "Add at least one recipient."
                bad != null -> "\"$bad\" is not a valid email address."
                else -> "Write a message first."
            })
            return
        }
        val account = accountStore?.get(accountId)
        val apiOk = account != null && account.state in USABLE_STATES && apiReplyAllowed(account)
        val route = if (apiOk) ActionRoute.Api else ActionRoute.NativeHandoff
        val enabled = account?.let { capabilitiesFor(it)?.enabled }.orEmpty()
        val payload = ExactActionPayload(
            accountId = accountId.key,
            conversationId = "",
            route = route,
            recipients = toL,
            subject = subject.ifBlank { "(no subject)" },
            body = body,
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = AuthorizationSnapshot.grantVersion(account?.grantedScopes.orEmpty()),
            policyVersion = AuthorizationSnapshot.policyVersion(enabled, route),
            replyTarget = null,
            cc = ccL,
            bcc = bccL,
        )
        val handoffPkg = if (route == ActionRoute.NativeHandoff) {
            CommunicationSource.primaryPackageFor(accountId.provider)
        } else null
        val appName = handoffPkg?.let { app.mini.hub.observation.SourceAppNames.forPackage(it) } ?: "your mail app"
        _state.value = _state.value.copy(
            activeReviewPayload = payload,
            reviewContext = ReviewContext(
                providerName = providerLabel(accountId.provider),
                isReply = false,
                handoffPackage = handoffPkg,
                note = if (route == ActionRoute.Api) null else
                    "MINI will open $appName with To, Cc, Bcc, Subject and your message filled in. You tap Send there. MINI cannot choose which account $appName uses.",
                fromAddress = account?.providerAccountId ?: account?.displayName,
            ),
        )
    }

    private fun prepareNewEmailUnused(selected: ConversationObservationSummary, to: String, subject: String, body: String) {
        val account = accountStore?.get(selected.accountId)
        val apiOk = account != null && account.state in USABLE_STATES && apiReplyAllowed(account)
        val route = if (apiOk) ActionRoute.Api else ActionRoute.NativeHandoff
        val enabled = account?.let { capabilitiesFor(it)?.enabled }.orEmpty()
        val payload = ExactActionPayload(
            accountId = selected.accountId.key,
            conversationId = "",
            route = route,
            recipients = listOf(to),
            subject = subject,
            body = body,
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = AuthorizationSnapshot.grantVersion(account?.grantedScopes.orEmpty()),
            policyVersion = AuthorizationSnapshot.policyVersion(enabled, route),
            replyTarget = null,
        )
        _state.value = _state.value.copy(
            activeReviewPayload = payload,
            reviewContext = ReviewContext(
                providerName = providerLabel(selected.accountId.provider),
                isReply = false,
                handoffPackage = if (route == ActionRoute.NativeHandoff) handoffPackageFor(selected) else null,
                note = if (route == ActionRoute.Api) null else "Sending new email is not enabled for this account; MINI will open the mail app.",
            ),
        )
    }

    /** Meta's 24h standard messaging window for Instagram; other providers always open. */
    private fun instagramWindowOpen(
        provider: app.mini.hub.domain.Provider,
        live: List<app.mini.hub.observation.ObservedNotification>,
        account: app.mini.hub.connector.ConnectedAccount?,
    ): Boolean {
        if (provider != app.mini.hub.domain.Provider.INSTAGRAM) return true
        val lastInbound = live.filter { isInboundMessage(it, account) }.maxOfOrNull { it.postTime.epochMillis }
        return app.mini.hub.connector.InstagramApiConnector.windowOpen(lastInbound, clock.now().epochMillis)
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
            approvedState.copy(reviewedSnapshots = approvedState.reviewedSnapshots + payload.toReviewedSnapshot(command))
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

            if (finalCommand?.state == OutboxState.AcceptedByProvider) {
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
                reviewedSnapshots = actionStore.snapshot().reviewedSnapshots,
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
            .filter { !(it.isRemoved && it.notificationKey.startsWith("provider:")) }
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
