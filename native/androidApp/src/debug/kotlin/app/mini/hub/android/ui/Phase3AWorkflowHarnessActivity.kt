package app.mini.hub.android.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.android.intelligence.LocalDraftExperiment
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.InferenceEngine
import app.mini.hub.intelligence.InferenceTask
import app.mini.hub.intelligence.ModelProposal
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.persistence.SqlCipherActionStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DEBUG-ONLY Phase 3A on-device workflow harness. Registered only in the debug manifest.
 *
 * Drives the production [InboxViewModel], production Compose screens ([ThreadView],
 * [WhyThisDraftView], [ExactActionReviewDialog]), the real Android main looper and a real
 * [SqlCipherActionStore] backed by an ISOLATED database file ([HARNESS_DB]) so production drafts,
 * accounts and tokens are never read or written.
 *
 * Inference is a FAKE gated engine ([GatedFakeEngine]); this harness does not qualify a real model.
 * No account store or gateway is supplied, so no provider send path exists; the review dialog is
 * shown and dismissed, never confirmed.
 *
 * Logs (tag [TAG]) contain only synthetic IDs, counts, booleans and SHA-256 prefixes.
 */
class Phase3AWorkflowHarnessActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val vmState = mutableStateOf<InboxViewModel?>(null)
    private val banner = mutableStateOf("Phase 3A harness (debug, fake inference)")
    private val engine = GatedFakeEngine()
    private var experiment: LocalDraftExperiment? = null
    private var store: SqlCipherActionStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HarnessScreen() }
        // After process death Android recreates the activity and redelivers the old launch intent.
        // Running it again would mutate the harness DB before verification, so only fresh launches run.
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handle(intent) else log("SKIP_REDELIVERED_INTENT restored=${savedInstanceState != null}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val step = intent?.getStringExtra(EXTRA_STEP).orEmpty()
        worker.execute {
            runCatching { runStep(step) }.onFailure { log("STEP=$step ERROR=${it.javaClass.simpleName}") }
        }
    }

    // ---------------------------------------------------------------- scenario steps

    private fun runStep(step: String) {
        log("STEP_BEGIN step=$step pid=${android.os.Process.myPid()}")
        // Each interactive scenario starts from an empty isolated harness DB so earlier steps cannot leak in.
        // "verify" and the read-only probes must NOT clear it.
        val isolated = step in setOf("edits", "clear", "discard", "versions", "switch", "evidence")
        if (isolated) freshVm(clearDb = true)
        when (step) {
            "edits" -> stepEditsDuringGeneration()
            "edits-restored" -> { freshVm(clearDb = true); stepEditsOnRestoredDraft() }
            "clear" -> stepClearDuringGeneration()
            "discard" -> stepDiscardThenLateResult()
            "versions" -> stepVersionSelection()
            "switch" -> stepAccountSwitch()
            "evidence" -> stepOpenEvidence()
            "cited" -> stepOpenCitedMessage()
            "review" -> stepManualReview()
            "review-dismiss" -> onMain { vm().clearDraftReview() }
            "interactive-arm" -> stepInteractiveArm()
            "interactive-release" -> stepInteractiveRelease()
            "seed" -> stepSeedForRestart()
            "verify" -> stepVerifyAfterRestart()
            "cleanup" -> stepCleanup()
            else -> log("STEP_UNKNOWN step=$step")
        }
        log("STEP_END step=$step")
    }

    private fun stepEditsOnRestoredDraft() {
        // Seed a stored AI draft first, then edit during a new generation (the case that exposed stale provenance).
        onMain { vm().selectConversation(summaryA) }
        engine.arm("Harness stored AI draft")
        onMain { vm().requestLocalDraft("stored instruction") }
        engine.awaitStarted(); engine.release(); awaitIdle()
        freshVm()
        onMain { vm().selectConversation(summaryA) }
        val restoredHadProv = onMain { vm().state.value.localDraftProvenance != null }
        engine.arm("Harness newer AI draft")
        onMain { vm().requestLocalDraft("shorter") }
        engine.awaitStarted()
        onMain { vm().updateLocalDraftText("Harness manual rewrite during generation") }
        engine.release(); awaitIdle()
        val s = onMain { vm().state.value }
        log(
            "RESULT edits-restored restoredHadProvenance=$restoredHadProv editorKeptUserText=${s.localDraftText == "Harness manual rewrite during generation"} " +
                "activeIsManual=${s.draftVersions.getOrNull(s.selectedVersionIndex)?.isAiGenerated == false} activeProvenanceNull=${s.localDraftProvenance == null}",
        )
    }

    /**
     * Starts a real in-flight generation and leaves it blocked so the operator can type or clear
     * the editor through the on-screen keyboard. Release with step=interactive-release.
     */
    private fun stepInteractiveArm() {
        freshVm(clearDb = true)
        onMain { vm().selectConversation(summaryA); vm().updateLocalDraftText("Harness baseline I") }
        engine.arm("Harness AI proposal I")
        onMain { vm().requestLocalDraft("confirm the meeting") }
        engine.awaitStarted()
        log("ARMED inFlight=${onMain { vm().state.value.isLocalDraftInProgress }} editorSha=${sha(onMain { vm().state.value.localDraftText })}")
    }

    private fun stepInteractiveRelease() {
        val before = onMain { vm().state.value }
        engine.release()
        awaitIdle()
        val s = onMain { vm().state.value }
        log(
            "RESULT interactive editorBeforeReleaseSha=${sha(before.localDraftText)} editorAfterReleaseSha=${sha(s.localDraftText)} " +
                "editorUnchangedByResult=${before.localDraftText == s.localDraftText} editorEmpty=${s.localDraftText.isEmpty()} " +
                "selectedIndex=${s.selectedVersionIndex} versions=${s.draftVersions.size} aiVersions=${s.draftVersions.count { it.isAiGenerated }} " +
                "activeProvenanceNull=${s.localDraftProvenance == null}",
        )
    }

    private fun stepEditsDuringGeneration() {
        onMain { vm().selectConversation(summaryA) ; vm().updateLocalDraftText("Harness baseline A") }
        engine.arm("Harness AI proposal E")
        onMain { vm().requestLocalDraft("confirm the meeting") }
        engine.awaitStarted()
        val inFlight = onMain { vm().state.value.isLocalDraftInProgress }
        onMain { vm().updateLocalDraftText("Harness user edit typed during generation") }
        engine.release()
        awaitIdle()
        val s = onMain { vm().state.value }
        val active = s.draftVersions.getOrNull(s.selectedVersionIndex)
        log(
            "RESULT edits inFlightBeforeEdit=$inFlight editorKeptUserText=${s.localDraftText == "Harness user edit typed during generation"} " +
                "versions=${s.draftVersions.size} aiVersions=${s.draftVersions.count { it.isAiGenerated }} " +
                "activeIsManual=${active?.isAiGenerated == false} activeProvenanceNull=${s.localDraftProvenance == null} " +
                "statusMentionsPreserved=${s.localDraftStatus?.contains("preserved") == true}",
        )
    }

    private fun stepClearDuringGeneration() {
        onMain { vm().selectConversation(summaryA); vm().updateLocalDraftText("Harness baseline C") }
        engine.arm("Harness AI proposal C")
        onMain { vm().requestLocalDraft("confirm the meeting") }
        engine.awaitStarted()
        onMain { vm().updateLocalDraftText("") }
        engine.release()
        awaitIdle()
        val s = onMain { vm().state.value }
        log(
            "RESULT clear editorEmpty=${s.localDraftText.isEmpty()} selectedIndex=${s.selectedVersionIndex} " +
                "offeredVersions=${s.draftVersions.size} offeredIsAi=${s.draftVersions.lastOrNull()?.isAiGenerated == true} " +
                "activeProvenanceNull=${s.localDraftProvenance == null}",
        )
    }

    private fun stepDiscardThenLateResult() {
        onMain { vm().selectConversation(summaryA) }
        engine.arm("Harness late proposal D")
        onMain { vm().requestLocalDraft("write a reply") }
        engine.awaitStarted()
        onMain { vm().discardDraft() }
        val savedAfterDiscard = store!!.loadDraft(convA.key) != null
        engine.release()
        awaitIdle()
        Thread.sleep(500)
        val s = onMain { vm().state.value }
        val savedAfterLate = store!!.loadDraft(convA.key) != null
        log(
            "RESULT discard editorEmpty=${s.localDraftText.isEmpty()} versions=${s.draftVersions.size} " +
                "provenanceNull=${s.localDraftProvenance == null} status=${s.localDraftStatus == "Draft discarded."} " +
                "storedAfterDiscard=$savedAfterDiscard storedAfterLateResult=$savedAfterLate",
        )
    }

    private fun stepVersionSelection() {
        onMain { vm().selectConversation(summaryA) }
        // Two real async generations through LocalDraftExperiment with IDENTICAL text and different instructions.
        for (instruction in listOf("formal instruction", "concise instruction")) {
            engine.arm("Harness identical proposal")
            onMain { vm().requestLocalDraft(instruction) }
            engine.awaitStarted()
            engine.release()
            awaitIdle()
        }
        val before = onMain { vm().state.value }
        onMain { vm().selectDraftVersion(0) }
        val v0 = onMain { vm().state.value }
        onMain { vm().selectDraftVersion(1) }
        val v1 = onMain { vm().state.value }
        log(
            "RESULT versions count=${before.draftVersions.size} distinctIds=${before.draftVersions.map { it.id }.toSet().size} " +
                "sameText=${before.draftVersions.map { it.text }.toSet().size == 1} " +
                "v0Instruction=${v0.localDraftProvenance?.userInstruction} v0Index=${v0.selectedVersionIndex} " +
                "v1Instruction=${v1.localDraftProvenance?.userInstruction} v1Index=${v1.selectedVersionIndex} " +
                "v0MatchesStored=${v0.localDraftProvenance == before.draftVersions[0].provenance} " +
                "v1MatchesStored=${v1.localDraftProvenance == before.draftVersions[1].provenance}",
        )
    }

    private fun stepAccountSwitch() {
        onMain { vm().selectConversation(summaryA) }
        engine.arm("Harness proposal for account A")
        onMain { vm().requestLocalDraft("reply for A") }
        engine.awaitStarted(); engine.release(); awaitIdle()
        onMain { vm().openWhyThisDraft() }
        val a = onMain { vm().state.value }
        onMain { vm().selectConversation(summaryB) }
        val b = onMain { vm().state.value }
        log(
            "RESULT switch aHadProvenance=${a.localDraftProvenance != null} aEvidenceOpen=${a.showWhyThisDraft} " +
                "bAccount=${b.selectedConversation?.accountId?.provider?.wire} bEditorEmpty=${b.localDraftText.isEmpty()} " +
                "bProvenanceNull=${b.localDraftProvenance == null} bVersions=${b.draftVersions.size} " +
                "bEvidenceOpen=${b.showWhyThisDraft} bHighlightNull=${b.targetMessageIdToHighlight == null}",
        )
    }

    /** Leaves the populated "Why this draft?" screen on display for UI inspection and taps. */
    private fun stepOpenEvidence() {
        onMain { vm().selectConversation(summaryA) }
        engine.arm("Harness evidence proposal")
        onMain { vm().requestLocalDraft("agree and confirm Friday") }
        engine.awaitStarted(); engine.release(); awaitIdle()
        onMain { vm().openWhyThisDraft() }
        val s = onMain { vm().state.value }
        log(
            "RESULT evidence shown=${s.showWhyThisDraft} sourceRefs=${s.localDraftProvenance?.sourceMessageReferences?.size} " +
                "accountMatches=${s.localDraftProvenance?.accountId == accountA.key}",
        )
    }

    /** Read-only probe after the operator taps "Open original message" on the device. */
    private fun stepOpenCitedMessage() {
        val s = onMain { vm().state.value }
        val cited = s.localDraftProvenance?.sourceMessageReferences?.firstOrNull()?.messageId
        log(
            "RESULT cited evidenceClosed=${!s.showWhyThisDraft} highlightSet=${s.targetMessageIdToHighlight != null} " +
                "highlightMatchesCited=${cited != null && s.targetMessageIdToHighlight == cited} " +
                "citedInTimeline=${s.selectedTimeline.any { it.notificationKey == cited }}",
        )
    }

    /** Read-only probe after the operator taps "Review reply". Never confirms. */
    private fun stepManualReview() {
        val s = onMain { vm().state.value }
        val p = s.activeReviewPayload
        log(
            "RESULT review dialogOpen=${p != null} route=${p?.route} bodyMatchesEditor=${p?.body == s.localDraftText} " +
                "accountMatches=${p?.accountId == accountA.key} sending=${s.isSending} outboxStatusNull=${s.outboxStatusMessage == null}",
        )
    }

    private fun stepSeedForRestart() {
        freshVm(clearDb = true)
        onMain { vm().selectConversation(summaryA) }
        for (instruction in listOf("seed formal", "seed concise")) {
            engine.arm("Harness seed proposal $instruction")
            onMain { vm().requestLocalDraft(instruction) }
            engine.awaitStarted(); engine.release(); awaitIdle()
        }
        onMain { vm().selectDraftVersion(0) }
        val s = onMain { vm().state.value }
        log("SEED ${fingerprint(s)}")
        val stored = store!!.loadDraft(convA.key)
        log("SEED_STORED bodySha=${sha(stored?.body)} versionIds=${stored?.versions?.joinToString("|") { it.id }} provSha=${sha(stored?.provenance?.toString())}")
    }

    private fun stepVerifyAfterRestart() {
        freshVm()
        onMain { vm().selectConversation(summaryA) }
        val s = onMain { vm().state.value }
        log("VERIFY ${fingerprint(s)}")
    }

    private fun stepCleanup() {
        freshVm()
        store!!.deleteDraft(convA.key)
        store!!.deleteDraft(convB.key)
        log("CLEANUP remaining=${store!!.listDrafts().size}")
    }

    private fun fingerprint(s: InboxViewState): String {
        val active = s.draftVersions.getOrNull(s.selectedVersionIndex)
        return "bodySha=${sha(s.localDraftText)} versions=${s.draftVersions.size} " +
            "versionIds=${s.draftVersions.joinToString("|") { it.id }} activeIndex=${s.selectedVersionIndex} " +
            "activeId=${active?.id} provSha=${sha(s.localDraftProvenance?.toString())} " +
            "provInstruction=${s.localDraftProvenance?.userInstruction} " +
            "versionProvShas=${s.draftVersions.joinToString("|") { sha(it.provenance?.toString()) }} " +
            "baselineSha=${sha(s.originalGeneratedDraft)}"
    }

    // ---------------------------------------------------------------- wiring

    private fun freshVm(clearDb: Boolean = false) {
        if (store == null) store = SqlCipherActionStore(applicationContext, HARNESS_DB)
        if (clearDb) store!!.listDrafts().forEach { store!!.deleteDraft(it.conversationKey) }
        if (experiment == null) {
            experiment = LocalDraftExperiment(
                engine = engine,
                modelPath = "debug-harness-fake-engine",
                executor = Executors.newSingleThreadExecutor(),
            ).also { it.setForeground(true) }
        }
        val obs = InMemoryObservationStore().apply {
            persistObservation(obs(accountA, convA, "a1", "Kiran (synthetic)", "Synthetic: can we confirm Friday?"))
            persistObservation(obs(accountB, convB, "b1", "Alice (synthetic)", "Synthetic: hello from B"))
        }
        val created = onMain {
            InboxViewModel(
                observationStore = obs,
                retrievalEngine = ScopedRetrievalEngine(obs),
                styleEngine = AdaptiveStyleEngine(),
                actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
                actionStore = store!!,
                clock = clock,
                attentionStore = InMemoryAttentionStore(),
                localDraftExperiment = experiment,
            )
        }
        onMain { vmState.value = created }
    }

    private fun vm(): InboxViewModel = vmState.value ?: error("harness view model not created")

    private fun awaitIdle() {
        repeat(100) {
            if (!onMain { vm().state.value.isLocalDraftInProgress }) return
            Thread.sleep(50)
        }
        log("WARN awaitIdle timeout")
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val out = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post { out.set(runCatching(block)); latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "main thread timeout" }
        return out.get().getOrThrow()
    }

    @androidx.compose.runtime.Composable
    private fun HarnessScreen() {
        val vm = vmState.value
        MiniTheme {
            Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding),
                ) {
                    Text(banner.value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                    if (vm == null) return@Column
                    val state = vm.state.value
                    val selected = state.selectedConversation ?: return@Column
                    if (state.showWhyThisDraft) {
                        WhyThisDraftView(
                            provenance = state.localDraftProvenance,
                            selected = selected,
                            account = null,
                            onBack = { vm.closeWhyThisDraft() },
                            onOpenMessage = { vm.openSourceMessageFromEvidence(it) },
                        )
                    } else {
                        ThreadView(
                            summary = selected,
                            timeline = state.selectedTimeline,
                            label = null,
                            onLabel = {},
                            accountLabel = selected.accountId.localId,
                            targetMessageKey = state.targetMessageIdToHighlight,
                            draftText = state.localDraftText,
                            draftStatus = state.localDraftStatus,
                            draftVersions = state.draftVersions,
                            selectedVersionIndex = state.selectedVersionIndex,
                            onSelectVersion = { vm.selectDraftVersion(it) },
                            isDrafting = state.isLocalDraftInProgress,
                            hasProvenance = state.localDraftProvenance != null,
                            onWhyThisDraft = { vm.openWhyThisDraft() },
                            onDiscardDraft = { vm.discardDraft() },
                            onBack = {},
                            onDraft = { i, r -> vm.requestLocalDraft(i, r) },
                            onCancelDraft = { vm.cancelLocalDraft() },
                            onDraftChanged = { vm.updateLocalDraftText(it) },
                            onApprove = { body -> vm.prepareDraft(body, "") },
                        )
                    }
                    state.activeReviewPayload?.let { payload ->
                        ExactActionReviewDialog(
                            payload = payload,
                            context = state.reviewContext,
                            isSending = false,
                            // Harness never dispatches: confirming only closes the review.
                            onConfirm = { log("REVIEW_CONFIRM_BLOCKED harness never dispatches"); vm.clearDraftReview() },
                            onDismiss = { vm.clearDraftReview() },
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- synthetic fixtures

    private val clock = Clock { UtcInstant(System.currentTimeMillis()) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-harness-${idCounter++}" }
    private val digest = DigestProvider { payload -> sha(payload) }
    private val accountA = AccountId(Provider.GMAIL, "harness-a")
    private val accountB = AccountId(Provider.WHATSAPP, "harness-b")
    private val convA = ScopedId(accountA, ScopedId.Kind.CONVERSATION, "synthetic-thread-a")
    private val convB = ScopedId(accountB, ScopedId.Kind.CONVERSATION, "synthetic-thread-b")
    private val summaryA by lazy { summary(accountA, convA, "Kiran (synthetic)") }
    private val summaryB by lazy { summary(accountB, convB, "Alice (synthetic)") }

    private fun summary(account: AccountId, conv: ScopedId, title: String) = ConversationObservationSummary(
        conversationId = conv,
        accountId = account,
        title = title,
        appName = if (account.provider == Provider.GMAIL) "Gmail" else "WhatsApp",
        senderHint = title,
        packageId = if (account.provider == Provider.GMAIL) "com.google.android.gm" else "com.whatsapp",
        latestSnippet = "synthetic",
        category = NotificationCategory.DirectMessage,
        coverage = ConversationCoverage.FullProviderSync,
        coverageExplanation = "Synthetic harness history",
        latestEventAt = clock.now(),
        observationCount = 1,
        unreadCount = 0,
        isRemoved = false,
    )

    private fun obs(account: AccountId, conv: ScopedId, key: String, sender: String, text: String) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, key),
        accountId = account,
        conversationId = conv,
        packageId = if (account.provider == Provider.GMAIL) "com.google.android.gm" else "com.whatsapp",
        notificationKey = "harness_$key",
        notificationId = key.hashCode(),
        tag = null,
        senderHint = sender,
        title = sender,
        textFragments = listOf(text),
        rawTextDigest = "digest_$key",
        observedAt = clock.now(),
        postTime = clock.now(),
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.AndroidNotificationListener,
    )

    private fun log(msg: String) = Log.i(TAG, msg)

    /**
     * FAKE inference engine for the debug harness. Blocks inside run() until released so the
     * operator/harness can edit, clear or discard while generation is genuinely in flight.
     */
    private class GatedFakeEngine : InferenceEngine {
        @Volatile private var nextText = "Harness proposal"
        @Volatile private var started = CountDownLatch(1)
        @Volatile private var gate = CountDownLatch(1)

        fun arm(text: String) { nextText = text; started = CountDownLatch(1); gate = CountDownLatch(1) }
        fun awaitStarted() { check(started.await(10, TimeUnit.SECONDS)) { "fake engine never started" } }
        fun release() { gate.countDown() }

        override fun load(modelId: String): Boolean = true
        override fun run(task: InferenceTask): ModelProposal {
            started.countDown()
            gate.await(15, TimeUnit.SECONDS)
            return ModelProposal(taskId = task.id, compartment = task.compartment, text = nextText, evidence = task.context.evidence)
        }
        override fun cancel(taskId: String): Boolean { gate.countDown(); return true }
        override fun unload() {}
    }

    companion object {
        const val TAG = "MiniPhase3AHarness"
        const val EXTRA_STEP = "step"
        const val HARNESS_DB = "phase3a-harness.db"

        fun sha(value: String?): String {
            if (value == null) return "null"
            val d = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return d.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
