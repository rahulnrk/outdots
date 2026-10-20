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
    /**
     * Local MINI seen watermark. Opening a chat marks it seen here and never as a provider read.
     */
    private val markConversationSeen: (String, Long) -> Unit = { _, _ -> },
    /** Runs refresh store reads off the main thread. Tests inject a deterministic runner; null = default. */
    refreshRunner: ((Runnable) -> Unit)? = null,
    /**
     * Extra packages shown in Inbox and search. Debug builds pass MINI's own package so synthetic
     * acceptance probes are visible. Release builds pass nothing.
     */
    private val extraVisiblePackages: Set<String> = emptySet(),
) {
    private var refreshGeneration = 0L
    /** Understanding cache keyed by conversation, valid while its change signature is unchanged. */
    private val understandingCache = HashMap<String, Pair<String, MessageUnderstanding>>()
    /** Main-thread guard: true from the first tap until the send result is applied. */
    private var sendInFlight = false
    private var conversationLoadGeneration = 0L
    // Monotonic token so a late callback from a cancelled or superseded request cannot overwrite
    // the cancellation state or a newer draft.
    private var localDraftGeneration = 0L
    private var localDraftEditRevision = 0L
    private var loadingDraftFor: app.mini.hub.domain.ScopedId? = null
    private var loadingDraftEditRevision = 0L
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
    private val storageRunner: (Runnable) -> Unit = refreshRunner
        ?: if (mainHandler != null) { r -> REFRESH_EXECUTOR.execute(r) } else { r -> r.run() }
    private val refreshRunner: (Runnable) -> Unit = { work ->
        storageRunner(Runnable {
            try { work.run() } catch (_: Exception) {
                postToMain("storage-work-failed") {
                    _state.value = _state.value.copy(storageFailure = "Could not read saved messages. Your writing is kept; try again.", isSearching = false)
                }
            }
        })
    }
    /** Monotonic token: late search results are dropped when a newer query was typed. */
    private var searchGeneration = 0L
    private val draftSaveLock = Any()
    private val pendingDraftSaves = LinkedHashMap<String, Pair<ConversationObservationSummary, InboxViewState>>()
    private var draftSaveScheduled = false

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
        val activity: List<ConversationObservationSummary>,
        val records: Map<String, app.mini.hub.attention.ConversationAttention>,
        val failure: String?,
        val view: AttentionView,
        val understanding: Map<String, MessageUnderstanding>,
        val reviewedSnapshots: List<app.mini.hub.action.ReviewedActionSnapshot>,
        val savedDrafts: List<app.mini.hub.persistence.ConversationDraft>,
    )

    /** Store reads and analysis; runs on the refresh worker. */
    private fun loadRefresh(previousRecords: Map<String, app.mini.hub.attention.ConversationAttention>): RefreshResult {
        val policy = CommunicationSourcePolicy(selectedSources = selectedSources)
        val allSummaries = observationStore.getConversationSummaries()
        val instagramCandidates = allSummaries.filter {
            it.accountId.provider == app.mini.hub.domain.Provider.INSTAGRAM || it.packageId.contains("instagram", ignoreCase = true)
        }
        android.util.Log.i(
            "MiniInboxRefresh",
            "instagram_candidates=${instagramCandidates.size} total=${allSummaries.size} details=" +
                instagramCandidates.joinToString(";") { summary ->
                    "pkg=${summary.packageId},visible=${policy.isVisibleInInbox(summary.packageId) || summary.packageId in extraVisiblePackages},cat=${summary.category},cov=${summary.coverage},msgs=${summary.observationCount},evidence=${summary.messagingEvidence},snippetBlank=${summary.latestSnippet.isBlank()},isConversation=${app.mini.hub.observation.isConversationSummary(summary)}"
                },
        )
        val summaries = allSummaries
            .filter { policy.isVisibleInInbox(it.packageId) || it.packageId in extraVisiblePackages }
            .filterNot { isHiddenWhatsAppChat(it.conversationId.value) }
            .let { all ->
                // When a provider account is linked (WhatsApp, Gmail...), its notification-only copies
                // are duplicates of the synced chats. Show only the synced ones.
                val linked = all.filter { it.coverage != app.mini.hub.observation.ConversationCoverage.NotificationOnly }
                    .map { it.accountId.provider }.toSet()
                all.filterNot { it.coverage == app.mini.hub.observation.ConversationCoverage.NotificationOnly && it.accountId.provider in linked }
            }
            .filterNot { it.latestSnippet.isBlank() && it.observationCount <= 1 }
        val conversations = summaries.filter { app.mini.hub.observation.isConversationSummary(it) }
        val activity = summaries.filter { app.mini.hub.observation.isLinkedInActivitySummary(it) }
        val loadResult = attentionStore.loadAll()

        var failure: String? = null
        var records = previousRecords
        if (loadResult is AttentionStoreResult.Ok) {
            val rec = AttentionReconciler.reconcile(loadResult.value, summaries, clock.now()) {
                app.mini.hub.observation.isConversationSummary(it)
            }
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

        val entries = conversations.map {
            AttentionEntry(it, records[it.conversationId.key] ?: AttentionRules.default(it.conversationId, clock.now()))
        }
        val view = AttentionView.build(entries)
        // Re-read a conversation's messages only when its summary or attention record changed.
        val understanding = synchronized(understandingCache) {
            understandingCache.keys.retainAll(conversations.map { it.conversationId.key }.toSet())
            conversations.associate { summary ->
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
        return RefreshResult(
            conversations, activity, records, failure, view, understanding,
            actionStore.snapshot().reviewedSnapshots,
            draftStore?.listDrafts().orEmpty(),
        )
    }

    private fun applyRefresh(result: RefreshResult, isPermissionGranted: Boolean, isListenerConnected: Boolean) {
        val summaries = result.summaries
        val selected = _state.value.selectedConversation?.takeIf { current ->
            summaries.any { it.conversationId == current.conversationId } ||
                result.activity.any { it.conversationId == current.conversationId }
        }
        _state.value = _state.value.copy(
            isPermissionGranted = isPermissionGranted,
            isListenerConnected = isListenerConnected,
            conversations = summaries,
            linkedinActivity = result.activity,
            attentionRecords = result.records,
            understanding = result.understanding,
            attentionView = result.view,
            storageFailure = result.failure,
            selectedConversation = selected,
            reviewedSnapshots = result.reviewedSnapshots,
            savedDrafts = result.savedDrafts,
        )
    }

    private var attentionUndo: app.mini.hub.attention.ConversationAttention? = null

    fun applyAttentionAction(conversationId: app.mini.hub.domain.ScopedId, action: AttentionAction) {
        val existing = _state.value.attentionRecords[conversationId.key] ?: AttentionRules.default(conversationId, clock.now())
        val latest = _state.value.conversations.find { it.conversationId == conversationId }?.latestEventAt
            ?: _state.value.linkedinActivity.find { it.conversationId == conversationId }?.latestEventAt
        val decision = AttentionRules.apply(existing, action, latest, clock.now())
        if (decision is app.mini.hub.attention.AttentionDecision.Applied) {
            val saveResult = attentionStore.saveAll(listOf(decision.record))
            if (saveResult is AttentionStoreResult.Failed) {
                _state.value = _state.value.copy(storageFailure = saveResult.message)
            } else {
                attentionUndo = existing
                _state.value = _state.value.copy(canUndoAttention = true)
                refresh(_state.value.isPermissionGranted, _state.value.isListenerConnected)
            }
        } else if (decision is app.mini.hub.attention.AttentionDecision.Rejected) {
            _state.value = _state.value.copy(outboxStatusMessage = decision.reason)
        }
    }

    fun undoLastAttention() {
        val previous = attentionUndo ?: return
        val saveResult = attentionStore.saveAll(listOf(previous))
        attentionUndo = null
        if (saveResult is AttentionStoreResult.Failed) {
            _state.value = _state.value.copy(storageFailure = saveResult.message, canUndoAttention = false)
        } else {
            _state.value = _state.value.copy(canUndoAttention = false)
            refresh(_state.value.isPermissionGranted, _state.value.isListenerConnected)
        }
    }

    fun reportUserMessage(message: String) {
        _state.value = _state.value.copy(outboxStatusMessage = message)
    }

    fun laterPresets(): List<Pair<String, app.mini.hub.domain.UtcInstant>> {
        val now = clock.now().epochMillis
        fun atHour(daysAhead: Int, hour: Int): Long {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = now
            c.add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
            c.set(java.util.Calendar.HOUR_OF_DAY, hour)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        return listOf(
            "In 1 hour" to app.mini.hub.domain.UtcInstant(now + 60 * 60_000L),
            "This evening" to app.mini.hub.domain.UtcInstant(atHour(0, 20)),
            "Tomorrow morning" to app.mini.hub.domain.UtcInstant(atHour(1, 9)),
            "In 2 days" to app.mini.hub.domain.UtcInstant(now + 2 * 24 * 60 * 60_000L),
        )
    }

    fun setShowArchived(showArchived: Boolean) {
        _state.value = _state.value.copy(inboxShowArchived = showArchived)
        executeSearch(_state.value.searchQuery, _state.value.searchSourceFilter)
    }

    fun setInboxAccountKey(accountKey: String?) {
        _state.value = _state.value.copy(inboxAccountKey = accountKey)
        executeSearch(_state.value.searchQuery, _state.value.searchSourceFilter)
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
        _state.value = _state.value.copy(isSearching = true, searchError = null)
        val enabledSources = selectedSources().flatMap { it.packageIds }.toSet() + extraVisiblePackages
        refreshRunner(Runnable {
            // Search runs off the main thread; logs contain no query text or message content.
            val query = app.mini.hub.search.SearchQuery(queryText = trimmed, sourceFilter = sourceFilter, limit = 50)
            val rawResults = try { observationStore.search(query) } catch (_: Exception) {
                postToMain("search-unavailable") {
                    if (gen == searchGeneration) _state.value = _state.value.copy(isSearching = false,
                        searchResults = emptyList(), searchError = "Search is unavailable right now. Try again.")
                }
                return@Runnable
            }
            val scope = app.mini.hub.inbox.InboxScope(
                accountKey = _state.value.inboxAccountKey,
                showDone = _state.value.inboxShowArchived,
            )
            val filtered = rawResults.filter { result ->
                (result.packageId in enabledSources || result.packageId.startsWith("provider.")) &&
                    app.mini.hub.inbox.InboxScopeFilter.matchesSearchResult(
                        result = result,
                        labels = emptyMap(),
                        records = _state.value.attentionRecords,
                        unseen = { _, _ -> true },
                        scope = scope,
                        conversations = _state.value.conversations,
                    )
            }
            postToMain("search-result") {
                if (gen != searchGeneration) return@postToMain
                _state.value = _state.value.copy(searchResults = filtered, isSearching = false)
            }
        })
    }

    private fun persistCurrentDraft() {
        val selected = _state.value.selectedConversation ?: return
        // A blank loading composer is not a user instruction to delete an existing saved draft.
        if (loadingDraftFor == selected.conversationId && loadingDraftEditRevision == localDraftEditRevision) return
        val snapshot = _state.value
        val schedule = synchronized(draftSaveLock) {
            pendingDraftSaves[selected.conversationId.key] = selected to snapshot
            if (draftSaveScheduled) false else { draftSaveScheduled = true; true }
        }
        if (schedule) refreshRunner(Runnable {
            while (true) {
                val pending = synchronized(draftSaveLock) {
                    val first = pendingDraftSaves.entries.firstOrNull()
                    if (first == null) { draftSaveScheduled = false; null }
                    else pendingDraftSaves.remove(first.key)
                } ?: break
                try {
                    persistDraftSnapshot(pending.first, pending.second)
                } catch (_: Exception) {
                    postToMain("draft-save-failed") {
                        _state.value = _state.value.copy(localDraftStatus = "Could not save this draft. Keep MINI open and try again.")
                    }
                }
            }
        })
    }

    private fun persistDraftSnapshot(selected: ConversationObservationSummary, s: InboxViewState) {
        val store = draftStore ?: return
        val empty = s.localDraftText.isBlank() &&
            s.composeNewTo.orEmpty().isBlank() &&
            s.composeCc.isBlank() &&
            s.composeBcc.isBlank() &&
            s.composeSubject.isBlank()
        if (empty) {
            if (!s.isLocalDraftInProgress) store.deleteDraft(selected.conversationId.key)
            publishDraftsFromWorker(store)
            return
        }

        var effectiveVersions = if (s.draftVersions.isNotEmpty()) {
            s.draftVersions
        } else if (s.localDraftText.isNotBlank()) {
            listOf(
                app.mini.hub.persistence.DraftVersion(
                    text = s.localDraftText,
                    provenance = s.localDraftProvenance,
                    originalAiText = s.originalGeneratedDraft,
                ),
            )
        } else {
            emptyList()
        }
        if (s.draftVersions.isEmpty()) {
            val existing = store.loadDraft(selected.conversationId.key)?.takeIf { it.accountId == selected.accountId.key }
            if (!existing?.body.isNullOrBlank() && existing?.body != s.localDraftText) {
                val prior = existing!!.versions.ifEmpty { listOf(app.mini.hub.persistence.DraftVersion(
                    text = existing.body, provenance = existing.provenance, originalAiText = existing.originalGeneratedDraft)) }
                effectiveVersions = (prior + effectiveVersions).takeLast(5)
            }
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
                originalGeneratedDraft = s.originalGeneratedDraft,
                updatedAtMillis = clock.now().epochMillis,
                provenance = s.localDraftProvenance,
                versions = effectiveVersions,
                activeVersionId = effectiveVersions.getOrNull(s.selectedVersionIndex)?.id?.ifEmpty { null },
            ),
        )
        publishDraftsFromWorker(store)
    }

    private fun publishDraftsFromWorker(store: app.mini.hub.persistence.ConversationDraftStore) {
        val drafts = store.listDrafts()
        postToMain("drafts") {
            _state.value = _state.value.copy(savedDrafts = drafts)
        }
    }

    private fun restoreDraftState(
        draft: app.mini.hub.persistence.ConversationDraft?,
        accountId: app.mini.hub.domain.AccountId,
    ): RestoredDraftState {
        val validProvenance = draft?.provenance?.takeIf { it.accountId == accountId.key }
        val restoredVersions = draft?.versions?.filter { v ->
            val prov = v.provenance
            prov == null || prov.accountId == accountId.key
        } ?: emptyList()
        val effectiveVersions = if (restoredVersions.isNotEmpty()) {
            restoredVersions
        } else if (!draft?.body.isNullOrBlank()) {
            listOf(app.mini.hub.persistence.DraftVersion(text = draft!!.body, provenance = validProvenance, originalAiText = draft.originalGeneratedDraft))
        } else {
            emptyList()
        }
        val selectedIdx = if (effectiveVersions.isNotEmpty()) {
            // Prefer the persisted stable ID; fall back to body match only for legacy rows without one.
            val byId = draft?.activeVersionId?.let { id -> effectiveVersions.indexOfFirst { it.id.isNotEmpty() && it.id == id } } ?: -1
            when {
                byId >= 0 -> byId
                draft?.activeVersionId == null && draft?.body?.isEmpty() == true && draft.versions.isNotEmpty() -> -1
                else -> effectiveVersions.indexOfFirst { it.text == draft?.body }.coerceAtLeast(0)
            }
        } else 0

        return RestoredDraftState(
            body = draft?.body.orEmpty(),
            originalGeneratedDraft = draft?.originalGeneratedDraft,
            provenance = validProvenance,
            versions = effectiveVersions,
            selectedIndex = selectedIdx,
        )
    }

    private data class RestoredDraftState(
        val body: String,
        val originalGeneratedDraft: String?,
        val provenance: app.mini.hub.persistence.DraftProvenance?,
        val versions: List<app.mini.hub.persistence.DraftVersion>,
        val selectedIndex: Int,
    )

    private fun publishDrafts() {
        val store = draftStore ?: return
        refreshRunner(Runnable { publishDraftsFromWorker(store) })
    }

    private fun loadedDraft(conversationKey: String): app.mini.hub.persistence.ConversationDraft? =
        draftStore?.loadDraft(conversationKey)

    /**
     * Opens the chat with this person on the chosen account. Uses the existing conversation when
     * there is one; otherwise starts an empty thread so the user can write the first message.
     */
    fun openCompose(accountId: app.mini.hub.domain.AccountId, address: String, name: String) {
        val generation = ++conversationLoadGeneration
        refreshRunner(Runnable {
            val all = observationStore.getConversationSummaries(accountId)
            postToMain("compose-selection") {
                if (generation == conversationLoadGeneration) openComposeLoaded(accountId, address, name, all)
            }
        })
    }

    private fun openComposeLoaded(accountId: app.mini.hub.domain.AccountId, address: String, name: String,
        all: List<ConversationObservationSummary>,
    ) {
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
        openConversation(summary, if (isEmail) address else null)
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
        openConversation(summary, composeTo = "")
    }

    /** Reopens a persisted composer draft, including new emails that have no inbox thread yet. */
    fun openSavedDraft(draft: app.mini.hub.persistence.ConversationDraft) {
        val conversationId = runCatching { app.mini.hub.domain.ScopedId.parse(draft.conversationKey) }.getOrNull() ?: return
        val existing = _state.value.conversations.firstOrNull { it.conversationId == conversationId }

        if (existing != null && !draft.isNewEmail) {
            selectConversation(existing)
            return
        }
        val accountId = conversationId.account
        val pkg = CommunicationSource.primaryPackageFor(accountId.provider)
            ?: if (accountId.provider == app.mini.hub.domain.Provider.GMAIL) "com.google.android.gm"
            else CommunicationSource.API_PACKAGE_PREFIX + accountId.provider.wire
        val summary = ConversationObservationSummary(
            conversationId = conversationId,
            accountId = accountId,
            title = draft.subject.ifBlank { if (draft.to.isBlank()) "Draft" else "Draft to ${draft.to}" },
            appName = app.mini.hub.observation.SourceAppNames.forPackage(pkg),
            senderHint = draft.to,
            packageId = pkg,
            latestSnippet = draft.body,
            category = app.mini.hub.observation.NotificationCategory.DirectMessage,
            coverage = app.mini.hub.observation.ConversationCoverage.PartialApiSync,
            coverageExplanation = "Saved draft",
            latestEventAt = app.mini.hub.domain.UtcInstant(draft.updatedAtMillis),
            observationCount = 0, unreadCount = 0, isRemoved = false,
        )
        clearSearch()
        openConversation(summary, if (draft.isNewEmail) draft.to else null, draft)
    }

    fun selectConversationFromSearch(result: app.mini.hub.search.SearchResult) {
        val generation = ++conversationLoadGeneration
        refreshRunner(Runnable {
            val conv = _state.value.conversations.firstOrNull { it.conversationId == result.conversationId }
                ?: observationStore.getConversationSummaries(result.accountId).firstOrNull { it.conversationId == result.conversationId }
            postToMain("search-selection") {
                if (generation != conversationLoadGeneration) return@postToMain
                conv?.let { selectConversation(it) }
            }
        })
    }

    fun selectConversation(summary: ConversationObservationSummary) = openConversation(summary)

    private fun openConversation(summary: ConversationObservationSummary, composeTo: String? = null,
        suppliedDraft: app.mini.hub.persistence.ConversationDraft? = null,
    ) {
        val generation = ++conversationLoadGeneration
        val previousState = _state.value
        val previous = previousState.selectedConversation
        val previousWasDifferent = previous?.conversationId != summary.conversationId
        persistCurrentDraft()
        abandonLocalDraft()
        val editRevision = localDraftEditRevision
        loadingDraftFor = summary.conversationId
        loadingDraftEditRevision = editRevision
        if (previousWasDifferent) {
            // Snapshot persistence and all durable reads happen on the worker below. The UI gets a
            // selection immediately, while this generation prevents a slow store from winning.
            _state.value = _state.value.copy(
                selectedConversation = summary,
                selectedTimeline = emptyList(),
                retrievedContext = null,
                activeStyleProfile = null,
                activeReviewPayload = null,
                reviewContext = null,
                localDraftText = "",
                originalGeneratedDraft = null,
                localDraftProvenance = null,
                draftVersions = emptyList(),
                selectedVersionIndex = 0,
                composeNewTo = composeTo,
                composeCc = "", composeBcc = "", composeSubject = "",
                conversationLoadStatus = "Loading this conversation…",
            )
        }
        refreshRunner(Runnable {
            try {
            val observations = observationStore.getObservationsForConversation(summary.conversationId)
            val retrieved = retrievalEngine.retrieve(
                ScopedRetrievalQuery(
                    conversationId = summary.conversationId,
                    compartment = CompartmentId(summary.accountId.key),
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
            markConversationSeen(summary.conversationId.key, summary.latestEventAt.epochMillis)
            val draft = suppliedDraft ?: loadedDraft(summary.conversationId.key)
            val restored = restoreDraftState(draft, summary.accountId)
            postToMain("conversation-selection") {
                if (generation != conversationLoadGeneration) return@postToMain
                loadingDraftFor = null
                var next = _state.value.copy(
                    selectedConversation = summary,
                    selectedTimeline = observations,
                    retrievedContext = retrieved,
                    activeStyleProfile = style,
                    conversationLoadStatus = null,
                )
                // Storage may finish after typing or selecting a newer draft version. Restore
                // only if the composer has not changed since this selection started.
                if (editRevision == localDraftEditRevision) next = next.copy(
                    activeReviewPayload = null,
                    outboxStatusMessage = null,
                    localDraftText = restored.body,
                    originalGeneratedDraft = restored.originalGeneratedDraft,
                    localDraftProvenance = restored.provenance,
                    draftVersions = restored.versions,
                    selectedVersionIndex = restored.selectedIndex,
                    showWhyThisDraft = false,
                    targetMessageIdToHighlight = null,
                    composeNewTo = if (draft?.isNewEmail == true) draft.to else composeTo,
                    composeCc = draft?.cc.orEmpty(),
                    composeBcc = draft?.bcc.orEmpty(),
                    composeSubject = draft?.subject.orEmpty(),
                    localDraftWarning = null,
                    localDraftStatus = null,
                    localDraftFeedback = null,
                    isLocalDraftInProgress = false,
                    isLocalAiRetryAvailable = localDraftExperiment?.needsRecovery == true,
                    conversationLoadStatus = null,
                )
                if (editRevision != localDraftEditRevision && next.draftVersions.isEmpty()) {
                    val retained = restored.versions
                    val current = app.mini.hub.persistence.DraftVersion(text = next.localDraftText,
                        provenance = next.localDraftProvenance, originalAiText = next.originalGeneratedDraft)
                    val versions = (retained + listOf(current).filter { next.localDraftText.isNotBlank() && retained.none { old -> old.text == current.text } }).takeLast(5)
                    next = next.copy(draftVersions = versions,
                        selectedVersionIndex = versions.indexOfLast { it.text == next.localDraftText })
                }
                _state.value = next
                loadFullConversation(summary)
            }
            } catch (_: Exception) {
                postToMain("conversation-selection-failed") {
                    if (generation == conversationLoadGeneration) _state.value = _state.value.copy(
                        conversationLoadStatus = "Could not open this conversation. Your writing is kept; try again.")
                }
            }
        })
    }

    private fun loadFullConversation(summary: ConversationObservationSummary) {
        val loader = conversationLoader ?: return
        val account = accountStore?.get(summary.accountId) ?: return
        if (account.state !in USABLE_STATES) return
        val generation = ++conversationLoadGeneration
        _state.value = _state.value.copy(conversationLoadStatus = "Loading the full conversation from ${account.id.provider.wire}…")
        Thread({
            val loaded = runCatching {
                val timeline = loader(summary)
                val context = if (timeline == null) null else retrievalEngine.retrieve(
                    ScopedRetrievalQuery(summary.conversationId, CompartmentId(summary.accountId.key), tokenBudget = 650))
                timeline to context
            }
            postToMain("conversation-load") {
                if (generation != conversationLoadGeneration ||
                    _state.value.selectedConversation?.conversationId != summary.conversationId
                ) return@postToMain
                val timeline = loaded.getOrNull()?.first
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
                        retrievedContext = loaded.getOrNull()?.second,
                        conversationLoadStatus = "Full conversation loaded (${timeline.size} message(s)).",
                    )
                }
            }
        }, "mini-conversation-load").start()
    }

    fun clearSelection() {
        persistCurrentDraft()
        loadingDraftFor = null
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
            selectedVersionIndex = 0,
            localDraftProvenance = null,
            showWhyThisDraft = false,
            targetMessageIdToHighlight = null,
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
        val requestedConversationId = selected.conversationId
        val requestedAccountId = selected.accountId
        val retrieved = _state.value.retrievedContext ?: return
        val experiment = localDraftExperiment ?: run {
            _state.value = _state.value.copy(
                localDraftStatus = "Local drafting is unavailable in this build.",
                isLocalDraftInProgress = false,
            )
            return
        }
        persistCurrentDraft()
        val generation = ++localDraftGeneration
        val baselineEditRevisionAtStart = localDraftEditRevision
        val baselineTextAtStart = _state.value.localDraftText
        val retained = baselineTextAtStart
        _state.value = _state.value.copy(
            localDraftStatus = if (revise || retained.isNotBlank()) "Rewriting on this device…" else "Generating locally on this device…",
            localDraftText = retained,
            localDraftWarning = retrieved.coverageWarning.ifBlank { null },
            isLocalDraftInProgress = true,
            localDraftFeedback = null,
            isLocalAiRetryAvailable = false,
            // A new draft reloads the model, so an earlier "released" line would be stale.
            localAiReleaseStatus = null,
        )

        val account = accountStore?.get(selected.accountId)
        val accountLabel = account?.displayName?.ifBlank { null }
            ?: account?.providerAccountId?.ifBlank { null }
            ?: selected.accountId.localId

        val sourceReferences = retrieved.retrievedObservations.map { obs ->
            app.mini.hub.persistence.DraftSourceReference(
                messageId = obs.notificationKey,
                senderHint = obs.senderHint,
                timestampMillis = obs.postTime.epochMillis,
                snippet = obs.textFragments.joinToString(" ").take(160),
                isIncludedAsText = true,
            )
        }

        val allMediaAttachments = retrieved.retrievedObservations
            .flatMap { obs ->
                obs.textFragments.flatMap { frag ->
                    allMedia(frag).map { it.fileName.ifBlank { "Attachment (${it.kind})" } }
                }
            }
            .distinct()

        val provenance = app.mini.hub.persistence.DraftProvenance(
            accountId = selected.accountId.key,
            accountLabel = accountLabel,
            userInstruction = userIntent.ifBlank { "write a short, natural reply" },
            sourceMessageReferences = sourceReferences,
            appliedStyleSummary = _state.value.activeStyleProfile?.let {
                val formality = if (it.traits.formality > 0.6) "formal" else if (it.traits.formality < 0.4) "casual" else "balanced"
                val brevity = if (it.traits.brevity > 0.6) "concise" else if (it.traits.brevity < 0.4) "detailed" else "moderate"
                "Style: ${it.category.name} ($brevity, $formality)"
            },
            coverageWarning = retrieved.coverageWarning.ifBlank { null },
            excludedAttachments = allMediaAttachments,
            previousDraft = retained.takeIf { it.isNotBlank() },
            understandingNote = _state.value.understanding[selected.conversationId.key]?.explanation,
            generatedAtMillis = clock.now().epochMillis,
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
                previousDraft = retained.takeIf { it.isNotBlank() },
            ),
        ) { result ->
            postToMain("draft-result") {
                if (generation != localDraftGeneration) return@postToMain
                if (_state.value.selectedConversation?.conversationId != requestedConversationId ||
                    _state.value.selectedConversation?.accountId != requestedAccountId
                ) {
                    return@postToMain
                }
                applyGenerationResult(result, provenance, baselineTextAtStart, baselineEditRevisionAtStart)
            }
        }
    }

    private fun applyGenerationResult(
        result: LocalDraftResult,
        provenance: app.mini.hub.persistence.DraftProvenance? = null,
        baselineTextAtStart: String = "",
        baselineEditRevisionAtStart: Long = -1L,
    ) {
        when (result) {
            is LocalDraftResult.Success -> {
                val newAiVersion = app.mini.hub.persistence.DraftVersion(
                    id = "ai-${clock.now().epochMillis}-${result.proposal.taskId.takeLast(6)}",
                    text = result.proposal.text,
                    provenance = provenance,
                    originalAiText = result.proposal.text,
                    isAiGenerated = true,
                )
                val currentLiveText = _state.value.localDraftText
                val userEditedWhileGenerating = if (baselineEditRevisionAtStart >= 0L) {
                    (localDraftEditRevision != baselineEditRevisionAtStart) || (currentLiveText != baselineTextAtStart)
                } else {
                    currentLiveText != baselineTextAtStart
                }

                if (userEditedWhileGenerating) {
                    if (currentLiveText.isNotBlank()) {
                        val userManualVersion = app.mini.hub.persistence.DraftVersion(
                            id = "manual-${clock.now().epochMillis}",
                            text = currentLiveText,
                            provenance = null,
                            originalAiText = null,
                            isAiGenerated = false,
                        )
                        val updatedVersions = (_state.value.draftVersions + userManualVersion + newAiVersion).takeLast(5)
                        val userIndex = updatedVersions.indexOfLast { it.id == userManualVersion.id }.coerceAtLeast(0)
                        _state.value = _state.value.copy(
                            localDraftText = currentLiveText,
                            draftVersions = updatedVersions,
                            selectedVersionIndex = userIndex,
                            // The active version is the user's manual text: never show an older AI version's evidence for it.
                            localDraftProvenance = null,
                            originalGeneratedDraft = null,
                            localDraftWarning = result.warning.ifBlank { null },
                            localDraftStatus = "New AI version generated (${result.latencyMs} ms). Your live edits are preserved.",
                            isLocalDraftInProgress = false,
                        )
                    } else {
                        // User cleared / deleted the draft while generation was running.
                        // Preserve the empty editor and offer the generated result separately in draftVersions.
                        val updatedVersions = (_state.value.draftVersions + newAiVersion).takeLast(5)
                        _state.value = _state.value.copy(
                            localDraftText = "",
                            draftVersions = updatedVersions,
                            selectedVersionIndex = -1,
                            localDraftProvenance = null,
                            originalGeneratedDraft = null,
                            localDraftWarning = result.warning.ifBlank { null },
                            localDraftStatus = "New AI version generated (${result.latencyMs} ms). Your cleared editor was preserved; tap a version above to view it.",
                            isLocalDraftInProgress = false,
                        )
                    }
                } else {
                    val updatedVersions = (_state.value.draftVersions + newAiVersion).takeLast(5)
                    val newIndex = updatedVersions.lastIndex.coerceAtLeast(0)
                    _state.value = _state.value.copy(
                        localDraftText = result.proposal.text,
                        originalGeneratedDraft = result.proposal.text,
                        draftVersions = updatedVersions,
                        selectedVersionIndex = newIndex,
                        localDraftProvenance = provenance,
                        localDraftWarning = result.warning.ifBlank { null },
                        localDraftStatus = "Local draft ready (${result.latencyMs} ms). Review it before use.",
                        isLocalDraftInProgress = false,
                    )
                }
            }
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

    internal fun applyGenerationResultForTest(
        result: LocalDraftResult,
        provenance: app.mini.hub.persistence.DraftProvenance? = null,
        baselineTextAtStart: String = _state.value.localDraftText,
        baselineEditRevisionAtStart: Long = localDraftEditRevision,
    ) = applyGenerationResult(result, provenance, baselineTextAtStart, baselineEditRevisionAtStart)

    fun selectDraftVersion(index: Int) {
        localDraftEditRevision++
        val s = _state.value
        val version = s.draftVersions.getOrNull(index) ?: return
        _state.value = s.copy(
            selectedVersionIndex = index,
            localDraftText = version.text,
            originalGeneratedDraft = version.originalAiText,
            localDraftProvenance = version.provenance?.takeIf { it.accountId == s.selectedConversation?.accountId?.key },
        )
        persistCurrentDraft()
    }

    fun discardDraft() {
        localDraftEditRevision++
        val selected = _state.value.selectedConversation ?: return
        abandonLocalDraft()
        _state.value = _state.value.copy(
            localDraftText = "",
            originalGeneratedDraft = null,
            draftVersions = emptyList(),
            selectedVersionIndex = 0,
            localDraftProvenance = null,
            showWhyThisDraft = false,
            targetMessageIdToHighlight = null,
            localDraftStatus = "Draft discarded.",
            isLocalDraftInProgress = false,
        )
        persistCurrentDraft()
    }

    fun openWhyThisDraft() {
        _state.value = _state.value.copy(showWhyThisDraft = true)
    }

    fun closeWhyThisDraft() {
        _state.value = _state.value.copy(showWhyThisDraft = false, targetMessageIdToHighlight = null)
    }

    fun openSourceMessageFromEvidence(messageId: String) {
        _state.value = _state.value.copy(
            showWhyThisDraft = false,
            targetMessageIdToHighlight = messageId,
        )
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
        persistCurrentDraft()
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
        localDraftEditRevision++
        _state.value = _state.value.copy(localDraftText = text)
        persistCurrentDraft()
    }

    fun updateComposeTo(value: String) {
        localDraftEditRevision++
        if (_state.value.composeNewTo == null) return
        _state.value = _state.value.copy(composeNewTo = value)
        persistCurrentDraft()
    }

    fun updateComposeCc(value: String) {
        localDraftEditRevision++
        _state.value = _state.value.copy(composeCc = value)
        persistCurrentDraft()
    }

    fun updateComposeBcc(value: String) {
        localDraftEditRevision++
        _state.value = _state.value.copy(composeBcc = value)
        persistCurrentDraft()
    }

    fun updateComposeSubject(value: String) {
        localDraftEditRevision++
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
        val replyWindowLastInboundMillis = if (provider == app.mini.hub.domain.Provider.INSTAGRAM) {
            live.filter { isInboundMessage(it, connectedAccount) }.maxOfOrNull { it.postTime.epochMillis }
        } else null

        // Email needs an address; chat routes address the conversation itself.
        val typed = recipient.trim()
        val recipients: List<String> = if (isEmail) {
            listOfNotNull(app.mini.hub.connector.EmailAddress.extract(typed.ifBlank { anchor?.senderHint ?: selected.senderHint }))
        } else {
            listOfNotNull(typed.ifBlank { selected.title.ifBlank { selected.senderHint } }.takeIf { it.isNotBlank() })
        }

        val apiAllowed = isApiAccount && apiReplyAllowed(connectedAccount!!) && (!isEmail || recipients.isNotEmpty()) &&
            instagramWindowOpen(provider, live, connectedAccount) &&
            !(provider == app.mini.hub.domain.Provider.INSTAGRAM &&
                connectedAccount.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge)
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
            replyWindowLastInboundMillis = if (route == ActionRoute.Api) replyWindowLastInboundMillis else null,
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
        if (sendInFlight) return
        val payload = _state.value.activeReviewPayload ?: return
        val approvedAt = clock.now()
        val context = _state.value.reviewContext
        val learningSummary = _state.value.selectedConversation?.takeIf { it.accountId.key == payload.accountId && it.conversationId.key == payload.conversationId }
        val authorship = authorshipForApprovedBody(payload.body)
        val launcher = (handoffLauncher as? AndroidNativeHandoffLauncher)?.forPackage(context?.handoffPackage) ?: handoffLauncher
        sendInFlight = true
        _state.value = _state.value.copy(isSending = true, outboxStatusMessage = "Preparing your reviewed action…")
        val runner = if (payload.route == ActionRoute.NativeHandoff) refreshRunner else sendRunner
        runner(Runnable {
            var commandId: String? = null
            fun finish() {
                val snapshot = runCatching { actionStore.snapshot() }.getOrNull()
                val command = snapshot?.outbox?.find { it.id == commandId }
                if (command?.state == OutboxState.AcceptedByProvider && learningSummary != null && authorship != null) {
                    runCatching { onLearnFromApprovedText?.invoke(learningSummary, payload.body, payload.recipients.firstOrNull().orEmpty(), authorship) }
                }
                postToMain("send-result") {
                    sendInFlight = false
                    applyDispatchResult(payload, context, command, snapshot?.reviewedSnapshots.orEmpty(), learningSummary, authorship)
                }
            }
            try {
                actionStore.transaction { current ->
                    val (approved, command) = actionController.approve(current, payload, approvedAt)
                    commandId = command.id
                    approved.copy(reviewedSnapshots = approved.reviewedSnapshots + payload.toReviewedSnapshot(command))
                }
                val id = requireNotNull(commandId)
                val checks = liveDispatchChecks(payload)
                val begun = actionStore.transaction { actionController.beginDispatch(it, id, payload, checks) }
                val command = begun.outbox.first { it.id == id }
                if (command.state != OutboxState.Dispatching) { finish(); return@Runnable }
                if (payload.route == ActionRoute.NativeHandoff) {
                    val approval = begun.approvals.first { it.id == command.approvalId }
                    // Only Activity launch is on main; approval, consumption and completion are durable worker writes.
                    postToMain("native-handoff") {
                        val valid = clock.now() >= approval.approvedAt && clock.now() <= approval.expiresAt
                        val opened = if (valid) runCatching { launcher.open(command, payload) }.getOrNull() else null
                        refreshRunner(Runnable {
                            try {
                                actionStore.transaction {
                                    if (valid) actionController.completeHandoff(it, id, opened)
                                    else actionController.rejectBeforeExecution(it, id, "approval_expired_before_handoff")
                                }
                            } finally { finish() }
                        })
                    }
                } else {
                    val result = actionController.executeGateway(command, payload, gateway)
                    actionStore.transaction { actionController.completeDispatch(it, id, result) }
                    finish()
                }
            } catch (_: Exception) {
                // A committed Dispatching state stays uncertain; never show an unrelated command's result.
                finish()
            }
        })
    }
    private fun applyDispatchResult(payload: ExactActionPayload, context: ReviewContext?, finalCommand: app.mini.hub.action.OutboxCommand?,
        snapshots: List<app.mini.hub.action.ReviewedActionSnapshot>, learningSummary: ConversationObservationSummary?,
        authorship: app.mini.hub.style.TextAuthorship?,
    ) {
        run {
            val statusMessage = when (finalCommand?.state) {
                OutboxState.HandoffOpened -> "Draft opened in ${context?.handoffPackage?.let { app.mini.hub.observation.SourceAppNames.forPackage(it) } ?: "another app"}. Nothing is sent until you send it there."
                OutboxState.AcceptedByProvider -> "${context?.providerName ?: "Provider"} accepted the ${if (context?.isReply == true) "reply" else "message"} (receipt ${finalCommand.receipt ?: "accepted"}). Accepted means the provider took it; it does not confirm the recipient received it."
                OutboxState.Rejected -> "Not sent: ${finalCommand.failureReason}"
                OutboxState.OutcomeUnknown -> "Send outcome unknown (${finalCommand.failureReason ?: "timeout/network failure"}). Check the conversation before trying again so it is not sent twice."
                null -> "Could not record the action. Your draft is kept; try again."
                OutboxState.Dispatching -> "Send outcome is not confirmed. Check the source conversation before trying again."
                else -> "Action state: ${finalCommand.state.name}"
            }

            _state.value = _state.value.copy(
                activeReviewPayload = _state.value.activeReviewPayload.takeUnless { it == payload },
                reviewContext = _state.value.reviewContext.takeUnless { _state.value.activeReviewPayload == payload },
                outboxStatusMessage = statusMessage,
                isSending = false,
                reviewedSnapshots = snapshots,
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
