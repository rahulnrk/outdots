package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.android.intelligence.LocalDraftExperiment
import app.mini.hub.android.intelligence.LocalDraftResult
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
import app.mini.hub.persistence.ConversationDraft
import app.mini.hub.persistence.DraftProvenance
import app.mini.hub.persistence.DraftProvenanceSerializer
import app.mini.hub.persistence.DraftSourceReference
import app.mini.hub.persistence.DraftVersion
import app.mini.hub.persistence.DraftVersionSerializer
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase3AConversationComposerTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    private val accountA = AccountId(Provider.GMAIL, "work")
    private val accountB = AccountId(Provider.WHATSAPP, "personal")

    private val convA = ScopedId(accountA, ScopedId.Kind.CONVERSATION, "kiran@example.com")
    private val convB = ScopedId(accountB, ScopedId.Kind.CONVERSATION, "alice")

    private class ManualQueuedExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        var shutdownFlag = false

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runNext(): Boolean {
            if (tasks.isEmpty()) return false
            val task = tasks.removeAt(0)
            task.run()
            return true
        }

        fun runAll() {
            while (runNext()) {}
        }

        override fun shutdown() { shutdownFlag = true }
        override fun shutdownNow(): List<Runnable> { shutdownFlag = true; return emptyList() }
        override fun isShutdown(): Boolean = shutdownFlag
        override fun isTerminated(): Boolean = shutdownFlag && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class TestInferenceEngine(
        val generateText: (InferenceTask) -> String = { "Generated AI response for ${it.id}" },
    ) : InferenceEngine {
        var lastTask: InferenceTask? = null
        override fun load(modelId: String): Boolean = true
        override fun run(task: InferenceTask): ModelProposal {
            lastTask = task
            return ModelProposal(
                taskId = task.id,
                compartment = task.compartment,
                text = generateText(task),
                evidence = task.context.evidence,
            )
        }
        override fun cancel(taskId: String): Boolean = true
        override fun unload() {}
    }

    private fun createObs(
        account: AccountId,
        conv: ScopedId,
        idStr: String,
        sender: String,
        text: String,
    ): ObservedNotification = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, idStr),
        accountId = account,
        conversationId = conv,
        packageId = if (account.provider == Provider.GMAIL) "com.google.android.gm" else "com.whatsapp",
        notificationKey = "key_$idStr",
        notificationId = idCounter.toInt(),
        tag = null,
        senderHint = sender,
        title = sender,
        textFragments = listOf(text),
        rawTextDigest = "digest_$idStr",
        observedAt = clock.now(),
        postTime = clock.now(),
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.AndroidNotificationListener,
    )

    private fun createSummary(
        account: AccountId,
        conv: ScopedId,
        title: String,
        snippet: String,
    ): ConversationObservationSummary = ConversationObservationSummary(
        conversationId = conv,
        accountId = account,
        title = title,
        appName = if (account.provider == Provider.GMAIL) "Gmail" else "WhatsApp",
        senderHint = title,
        packageId = if (account.provider == Provider.GMAIL) "com.google.android.gm" else "com.whatsapp",
        latestSnippet = snippet,
        category = NotificationCategory.DirectMessage,
        coverage = ConversationCoverage.FullProviderSync,
        coverageExplanation = "Full history",
        latestEventAt = clock.now(),
        observationCount = 1,
        unreadCount = 0,
        isRemoved = false,
    )

    @Test
    fun accountIsolation_retrievalAndProvenanceUseOnlySelectedAccount() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        // Ingest into Account A (Gmail)
        val msgA = createObs(accountA, convA, "msg_A1", "Kiran Shah", "The revised total is 42,000 INR.")
        obsStore.persistObservation(msgA)

        // Ingest into Account B (WhatsApp)
        val msgB = createObs(accountB, convB, "msg_B1", "Alice", "Hey, do not forget the party tonight!")
        obsStore.persistObservation(msgB)

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        val summaryA = createSummary(accountA, convA, "Kiran Shah", "The revised total is 42,000 INR.")
        vm.selectConversation(summaryA)

        val state = vm.state.value
        assertNotNull(state.selectedConversation)
        assertEquals(accountA, state.selectedConversation?.accountId)

        // Verify retrieval context contains only Account A messages
        val retrievedObs = state.retrievedContext?.retrievedObservations.orEmpty()
        assertTrue(retrievedObs.isNotEmpty())
        assertTrue(retrievedObs.all { it.accountId == accountA }, "Retrieved observations must belong strictly to Account A")
        assertFalse(retrievedObs.any { it.textFragments.any { f -> f.contains("party") } }, "Account B content must not leak into Account A")
    }

    @Test
    fun productionPath_staleCallbackDiscardsResultAfterSwitchingConversations() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Need updated quote"))
        obsStore.persistObservation(createObs(accountB, convB, "b1", "Alice", "Hello from Alice"))

        val manualExecutor = ManualQueuedExecutor()
        val testEngine = TestInferenceEngine { "AI generated draft for Kiran" }
        val experiment = LocalDraftExperiment(
            engine = testEngine,
            modelPath = "test-model.bin",
            executor = manualExecutor,
            nowMillis = { 1000L },
            isMainThread = { true },
        )

        val vm = InboxViewModel(
            obsStore, retrieval, style, controller, actionStore, clock, attentionStore,
            localDraftExperiment = experiment,
        )
        val summaryA = createSummary(accountA, convA, "Kiran", "Need updated quote")
        val summaryB = createSummary(accountB, convB, "Alice", "Hello from Alice")

        // 1. Select Conversation A and start AI drafting
        vm.selectConversation(summaryA)
        vm.requestLocalDraft("confirm quote")
        assertTrue(vm.state.value.isLocalDraftInProgress)
        assertEquals(1, manualExecutor.tasks.size)

        // 2. User switches to Conversation B while draftAsync task is still queued
        vm.selectConversation(summaryB)
        vm.updateLocalDraftText("My handwritten draft for Alice")
        assertEquals("My handwritten draft for Alice", vm.state.value.localDraftText)
        assertFalse(vm.state.value.isLocalDraftInProgress)

        // 3. Now let the background AI generation task for Conversation A finish and deliver its callback
        manualExecutor.runAll()

        // 4. Assert: Stale result for Conversation A was discarded and DID NOT overwrite Conversation B!
        assertEquals("My handwritten draft for Alice", vm.state.value.localDraftText, "Late callback must not overwrite Conversation B")
        assertEquals(summaryB, vm.state.value.selectedConversation)
        assertNull(vm.state.value.localDraftProvenance)
    }

    @Test
    fun productionPath_discardCancelsPendingGenerationAndLateResultCannotRecreateDraft() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Hello"))

        val manualExecutor = ManualQueuedExecutor()
        val testEngine = TestInferenceEngine { "Late AI proposal text" }
        val experiment = LocalDraftExperiment(
            engine = testEngine,
            modelPath = "test-model.bin",
            executor = manualExecutor,
            nowMillis = { 1000L },
            isMainThread = { true },
        )

        val vm = InboxViewModel(
            obsStore, retrieval, style, controller, actionStore, clock, attentionStore,
            localDraftExperiment = experiment,
        )
        val summaryA = createSummary(accountA, convA, "Kiran", "Hello")
        vm.selectConversation(summaryA)

        // Request draft
        vm.requestLocalDraft("write reply")
        assertTrue(vm.state.value.isLocalDraftInProgress)

        // User clicks Discard while generation is still in flight
        vm.discardDraft()
        assertEquals("", vm.state.value.localDraftText)
        assertFalse(vm.state.value.isLocalDraftInProgress)

        // Now background execution completes and posts callback
        manualExecutor.runAll()

        // Late result must NOT recreate or revive the discarded draft!
        assertEquals("", vm.state.value.localDraftText, "Late result must not recreate discarded draft")
        assertNull(vm.state.value.localDraftProvenance)
        assertEquals("Draft discarded.", vm.state.value.localDraftStatus)
    }

    @Test
    fun productionPath_editsDuringGenerationAreRetainedAndOfferedAsVersion() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Meeting tomorrow"))

        val manualExecutor = ManualQueuedExecutor()
        val testEngine = TestInferenceEngine { "AI generated meeting confirmation" }
        val experiment = LocalDraftExperiment(
            engine = testEngine,
            modelPath = "test-model.bin",
            executor = manualExecutor,
            nowMillis = { 1000L },
            isMainThread = { true },
        )
        experiment.setForeground(true)

        val vm = InboxViewModel(
            obsStore, retrieval, style, controller, actionStore, clock, attentionStore,
            localDraftExperiment = experiment,
        )
        val summaryA = createSummary(accountA, convA, "Kiran", "Meeting tomorrow")
        vm.selectConversation(summaryA)

        // Request draft
        vm.requestLocalDraft("reply")
        assertTrue(vm.state.value.isLocalDraftInProgress)

        // User starts typing manual changes while AI is generating
        val userManualLiveText = "I started typing my own response while waiting"
        vm.updateLocalDraftText(userManualLiveText)

        // Background generation resolves and posts result
        manualExecutor.runAll()

        // Assert: User's live writing is preserved in the editor!
        assertEquals(userManualLiveText, vm.state.value.localDraftText, "User manual edits must not be overwritten by incoming AI draft")
        assertTrue(vm.state.value.localDraftStatus?.contains("live edits are preserved") == true)

        // Both versions are available in draftVersions: user manual and AI version
        val versions = vm.state.value.draftVersions
        assertEquals(2, versions.size)
        assertTrue(versions.any { it.text == userManualLiveText && !it.isAiGenerated })
        assertTrue(versions.any { it.text == "AI generated meeting confirmation" && it.isAiGenerated })

        // User can switch to the AI version
        val aiIndex = versions.indexOfFirst { it.isAiGenerated }
        vm.selectDraftVersion(aiIndex)
        assertEquals("AI generated meeting confirmation", vm.state.value.localDraftText)
        assertNotNull(vm.state.value.localDraftProvenance)

        // User can switch back to their manual version without fallback to AI provenance
        val manualIndex = versions.indexOfFirst { !it.isAiGenerated }
        vm.selectDraftVersion(manualIndex)
        assertEquals(userManualLiveText, vm.state.value.localDraftText)
        assertNull(vm.state.value.localDraftProvenance, "Manual version must not inherit AI provenance")
    }

    @Test
    fun versionAndEvidenceAlignment_neverFallsBackAndProtectsBaselineAuthorship() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Pricing"))
        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        val summaryA = createSummary(accountA, convA, "Kiran", "Pricing")
        vm.selectConversation(summaryA)

        val prov1 = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "first instruction",
            sourceMessageReferences = listOf(DraftSourceReference("key_a1", "Kiran", 1000L, "Pricing")),
            generatedAtMillis = 1000L,
        )
        val prov2 = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "second instruction",
            sourceMessageReferences = listOf(DraftSourceReference("key_a1", "Kiran", 1000L, "Pricing")),
            generatedAtMillis = 2000L,
        )

        // Simulate Version 1 (AI)
        vm.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("t1", app.mini.hub.intelligence.CompartmentId(accountA.key), "AI Version 1", emptyList()), "", 100L, false),
            prov1,
        )

        // Simulate Version 2 (AI)
        vm.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("t2", app.mini.hub.intelligence.CompartmentId(accountA.key), "AI Version 2", emptyList()), "", 100L, false),
            prov2,
        )

        // Verify Version 2 is active
        assertEquals("AI Version 2", vm.state.value.localDraftText)
        assertEquals(prov2, vm.state.value.localDraftProvenance)
        assertEquals("AI Version 2", vm.state.value.originalGeneratedDraft)

        // Switch to Version 1
        vm.selectDraftVersion(0)
        assertEquals("AI Version 1", vm.state.value.localDraftText)
        assertEquals(prov1, vm.state.value.localDraftProvenance, "Must bind Version 1's exact provenance")
        assertEquals("AI Version 1", vm.state.value.originalGeneratedDraft, "Must restore original AI text baseline so unedited version is not learned as user writing")

        // Switch back to Version 2
        vm.selectDraftVersion(1)
        assertEquals("AI Version 2", vm.state.value.localDraftText)
        assertEquals(prov2, vm.state.value.localDraftProvenance)
        assertEquals("AI Version 2", vm.state.value.originalGeneratedDraft)
    }

    @Test
    fun standaloneDraft_accountSwitchingClearsAndIsolatesEvidence() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)

        // 1. Open new email compose on Account A and attach provenance
        vm.startComposeEmail(accountA)
        val provA = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "compose for A",
            generatedAtMillis = 1000L,
        )
        vm.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("t1", app.mini.hub.intelligence.CompartmentId(accountA.key), "Body for Account A", emptyList()), "", 100L, false),
            provA,
        )
        vm.openWhyThisDraft()
        assertTrue(vm.state.value.showWhyThisDraft)
        assertEquals(provA, vm.state.value.localDraftProvenance)

        // 2. Open new email compose on Account B
        vm.startComposeEmail(accountB)
        val stateB = vm.state.value
        assertEquals(accountB, stateB.selectedConversation?.accountId)
        assertEquals("", stateB.localDraftText, "New compose on Account B must not retain Account A draft")
        assertNull(stateB.localDraftProvenance, "Account B must not retain Account A provenance")
        assertFalse(stateB.showWhyThisDraft, "Evidence sheet must be closed on account switch")
        assertNull(stateB.targetMessageIdToHighlight)
    }

    @Test
    fun openSavedDraft_restoresVersionsAndValidatesAccount() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        val provA = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "saved draft instruction",
            generatedAtMillis = 1000L,
        )
        val savedDraftA = ConversationDraft(
            conversationKey = convA.key,
            accountId = accountA.key,
            body = "Saved draft body for Kiran",
            subject = "Re: Project",
            updatedAtMillis = 1000L,
            provenance = provA,
            versions = listOf(
                DraftVersion(text = "Saved draft body for Kiran", provenance = provA, originalAiText = "Saved draft body for Kiran", isAiGenerated = true),
            ),
        )
        actionStore.saveDraft(savedDraftA)

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        vm.openSavedDraft(savedDraftA)

        val state = vm.state.value
        assertEquals("Saved draft body for Kiran", state.localDraftText)
        assertEquals(provA, state.localDraftProvenance)
        assertEquals(1, state.draftVersions.size)
        assertTrue(state.draftVersions.first().isAiGenerated)
        assertFalse(state.showWhyThisDraft)
        assertNull(state.targetMessageIdToHighlight)
    }

    @Test
    fun clearedEditorDuringGeneration_preservesEmptyEditorAndOffersGeneratedResultSeparately() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Meeting tomorrow"))

        val manualExecutor = ManualQueuedExecutor()
        val testEngine = TestInferenceEngine { "AI generated meeting confirmation" }
        val experiment = LocalDraftExperiment(
            engine = testEngine,
            modelPath = "test-model.bin",
            executor = manualExecutor,
            nowMillis = { 1000L },
            isMainThread = { true },
        )
        experiment.setForeground(true)

        val vm = InboxViewModel(
            obsStore, retrieval, style, controller, actionStore, clock, attentionStore,
            localDraftExperiment = experiment,
        )
        val summaryA = createSummary(accountA, convA, "Kiran", "Meeting tomorrow")
        vm.selectConversation(summaryA)
        vm.updateLocalDraftText("Initial draft before generation")

        // 1. Request draft
        vm.requestLocalDraft("confirm meeting")
        assertTrue(vm.state.value.isLocalDraftInProgress)

        // 2. User deliberately clears the editor while generation is in flight
        vm.updateLocalDraftText("")
        assertEquals("", vm.state.value.localDraftText)

        // 3. Generation completes and delivers result
        manualExecutor.runAll()

        // 4. Assert: Empty editor is preserved, not overwritten by incoming AI draft!
        assertEquals("", vm.state.value.localDraftText, "User-cleared editor must be preserved")
        assertNull(vm.state.value.localDraftProvenance, "Cleared editor must have null active provenance")
        assertEquals(-1, vm.state.value.selectedVersionIndex, "Cleared editor should have no version chip selected")
        assertTrue(vm.state.value.localDraftStatus?.contains("cleared editor was preserved") == true)

        // 5. Generated AI version is offered separately in draftVersions
        assertEquals(1, vm.state.value.draftVersions.size)
        val aiVersion = vm.state.value.draftVersions.first()
        assertEquals("AI generated meeting confirmation", aiVersion.text)
        assertTrue(aiVersion.isAiGenerated)
        assertNotNull(aiVersion.provenance)

        // 6. User can explicitly tap the version chip to load the AI proposal
        vm.selectDraftVersion(0)
        assertEquals("AI generated meeting confirmation", vm.state.value.localDraftText)
        assertEquals(aiVersion.provenance, vm.state.value.localDraftProvenance)
        assertEquals(0, vm.state.value.selectedVersionIndex)
    }

    @Test
    fun identicalTextGenerations_preserveDistinctVersionIdentityAndProvenance() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Pricing request"))
        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        val summaryA = createSummary(accountA, convA, "Kiran", "Pricing request")
        vm.selectConversation(summaryA)

        val provFormal = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "formal instruction",
            sourceMessageReferences = listOf(DraftSourceReference("key_a1", "Kiran", 1000L, "Pricing request")),
            generatedAtMillis = 1000L,
        )
        val provConcise = DraftProvenance(
            accountId = accountA.key,
            accountLabel = "Gmail · Work",
            userInstruction = "concise instruction",
            sourceMessageReferences = listOf(DraftSourceReference("key_a1", "Kiran", 1000L, "Pricing request")),
            generatedAtMillis = 2000L,
        )

        // Both generations produce the exact same proposal text
        val sharedText = "Understood, thank you for the update."

        // Apply Generation 1 (formal)
        vm.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("task_formal", app.mini.hub.intelligence.CompartmentId(accountA.key), sharedText, emptyList()), "", 100L, false),
            provFormal,
            baselineTextAtStart = "",
        )

        // Apply Generation 2 (concise)
        vm.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("task_concise", app.mini.hub.intelligence.CompartmentId(accountA.key), sharedText, emptyList()), "", 100L, false),
            provConcise,
            baselineTextAtStart = sharedText,
        )

        // Verify both versions exist and were NOT collapsed by text-only distinct
        val versions = vm.state.value.draftVersions
        assertEquals(2, versions.size, "Versions with identical text must not be collapsed by distinctBy text")
        assertEquals(sharedText, versions[0].text)
        assertEquals(sharedText, versions[1].text)
        assertEquals("formal instruction", versions[0].provenance?.userInstruction)
        assertEquals("concise instruction", versions[1].provenance?.userInstruction)

        // Active version is Version 2 (concise)
        assertEquals(1, vm.state.value.selectedVersionIndex)
        assertEquals("concise instruction", vm.state.value.localDraftProvenance?.userInstruction)

        // Switch to Version 1 (formal)
        vm.selectDraftVersion(0)
        assertEquals(0, vm.state.value.selectedVersionIndex)
        assertEquals(sharedText, vm.state.value.localDraftText)
        assertEquals("formal instruction", vm.state.value.localDraftProvenance?.userInstruction)

        // Switch back to Version 2 (concise)
        vm.selectDraftVersion(1)
        assertEquals(1, vm.state.value.selectedVersionIndex)
        assertEquals(sharedText, vm.state.value.localDraftText)
        assertEquals("concise instruction", vm.state.value.localDraftProvenance?.userInstruction)
    }

    @Test
    fun editingAndRestoringTextDuringGeneration_detectsEditRevisionAndPreservesUserWriting() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Meeting time"))

        val manualExecutor = ManualQueuedExecutor()
        val testEngine = TestInferenceEngine { "AI generated meeting time confirmation" }
        val experiment = LocalDraftExperiment(
            engine = testEngine,
            modelPath = "test-model.bin",
            executor = manualExecutor,
            nowMillis = { 1000L },
            isMainThread = { true },
        )
        experiment.setForeground(true)

        val vm = InboxViewModel(
            obsStore, retrieval, style, controller, actionStore, clock, attentionStore,
            localDraftExperiment = experiment,
        )
        val summaryA = createSummary(accountA, convA, "Kiran", "Meeting time")
        vm.selectConversation(summaryA)

        val initialText = "I will attend at 3pm"
        vm.updateLocalDraftText(initialText)

        // Request draft
        vm.requestLocalDraft("confirm time")
        assertTrue(vm.state.value.isLocalDraftInProgress)

        // User edits the text during generation, and then restores the exact same text
        vm.updateLocalDraftText("I will attend at 4pm instead")
        vm.updateLocalDraftText(initialText)

        // Generation resolves
        manualExecutor.runAll()

        // Assert: Edit revision token detected that edits occurred; user live text is preserved
        assertEquals(initialText, vm.state.value.localDraftText)
        assertTrue(vm.state.value.localDraftStatus?.contains("live edits are preserved") == true)

        // Both manual version and AI version are available
        assertEquals(2, vm.state.value.draftVersions.size)
    }

    @Test
    fun reopenedDraft_restoresActiveVersionByStableIdEvenWhenTextsMatch() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Pricing"))
        val summaryA = createSummary(accountA, convA, "Kiran", "Pricing")
        fun newVm() = InboxViewModel(
            obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(),
            ActionController(clock, digest, DomainActionIdGenerator(idGen)), actionStore, clock, InMemoryAttentionStore(),
        )
        val shared = "Same text from both generations."
        val provFirst = DraftProvenance(accountA.key, "Gmail", "first instruction", generatedAtMillis = 1L)
        val provSecond = DraftProvenance(accountA.key, "Gmail", "second instruction", generatedAtMillis = 2L)

        val vm1 = newVm()
        vm1.selectConversation(summaryA)
        vm1.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("t_first", app.mini.hub.intelligence.CompartmentId(accountA.key), shared, emptyList()), "", 1L, false),
            provFirst, baselineTextAtStart = "",
        )
        vm1.applyGenerationResultForTest(
            LocalDraftResult.Success(ModelProposal("t_second", app.mini.hub.intelligence.CompartmentId(accountA.key), shared, emptyList()), "", 1L, false),
            provSecond, baselineTextAtStart = shared,
        )
        vm1.selectDraftVersion(1)
        val savedIds = vm1.state.value.draftVersions.map { it.id }
        assertEquals(savedIds[1], actionStore.loadDraft(convA.key)?.activeVersionId)

        // Fresh view model (same store) restores the second version, not the first text match.
        val vm2 = newVm()
        vm2.selectConversation(summaryA)
        val restored = vm2.state.value
        assertEquals(savedIds, restored.draftVersions.map { it.id })
        assertEquals(1, restored.selectedVersionIndex)
        assertEquals("second instruction", restored.localDraftProvenance?.userInstruction)
    }

    @Test
    fun editsDuringGenerationOnRestoredAiDraft_clearStaleProvenanceForManualVersion() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        obsStore.persistObservation(createObs(accountA, convA, "a1", "Kiran", "Pricing"))
        val prov = DraftProvenance(accountA.key, "Gmail", "earlier instruction", generatedAtMillis = 1L)
        actionStore.saveDraft(
            ConversationDraft(
                conversationKey = convA.key, accountId = accountA.key, body = "Earlier AI text",
                originalGeneratedDraft = "Earlier AI text", updatedAtMillis = 1L, provenance = prov,
                versions = listOf(DraftVersion(id = "ai-old", text = "Earlier AI text", provenance = prov, originalAiText = "Earlier AI text", isAiGenerated = true)),
                activeVersionId = "ai-old",
            ),
        )
        val executor = ManualQueuedExecutor()
        val experiment = LocalDraftExperiment(TestInferenceEngine { "New AI text" }, "test-model.bin", executor, { 1000L }, { true })
        experiment.setForeground(true)
        val vm = InboxViewModel(
            obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(),
            ActionController(clock, digest, DomainActionIdGenerator(idGen)), actionStore, clock, InMemoryAttentionStore(),
            localDraftExperiment = experiment,
        )
        vm.selectConversation(createSummary(accountA, convA, "Kiran", "Pricing"))
        assertEquals(prov, vm.state.value.localDraftProvenance)

        vm.requestLocalDraft("shorter")
        vm.updateLocalDraftText("My own rewrite")
        executor.runAll()

        val s = vm.state.value
        assertEquals("My own rewrite", s.localDraftText)
        assertFalse(s.draftVersions[s.selectedVersionIndex].isAiGenerated)
        assertNull(s.localDraftProvenance, "Manual active version must not carry an older AI version's evidence")
        assertNull(s.originalGeneratedDraft)
    }
}
