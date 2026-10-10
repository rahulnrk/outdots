package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxViewModelTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    private val account = AccountId(Provider.WHATSAPP, "local_user")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")

    @Test
    fun testRefreshAndSelectConversation() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_1"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "notif_key_1",
            notificationId = 1,
            tag = null,
            senderHint = "Alice",
            title = "Alice",
            textFragments = listOf("Hey, are you free tomorrow?"),
            rawTextDigest = "digest_1",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
        obsStore.persistObservation(obs)

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        vm.refresh(isPermissionGranted = true, isListenerConnected = true)

        assertEquals(1, vm.state.value.conversations.size)
        assertTrue(vm.state.value.isPermissionGranted)
        assertTrue(vm.state.value.isListenerConnected)

        val summary = vm.state.value.conversations.first()
        vm.selectConversation(summary)

        assertEquals(summary, vm.state.value.selectedConversation)
        assertEquals(1, vm.state.value.selectedTimeline.size)
        assertNotNull(vm.state.value.retrievedContext)
        assertNotNull(vm.state.value.activeStyleProfile)
    }

    @Test
    fun testDraftPreparationApprovalAndDispatch() {
        val obsStore = InMemoryObservationStore()
        val actionStore = InMemoryActionStore()
        val retrieval = ScopedRetrievalEngine(obsStore)
        val style = AdaptiveStyleEngine()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val attentionStore = InMemoryAttentionStore()

        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_2"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "notif_key_2",
            notificationId = 2,
            tag = null,
            senderHint = "Bob",
            title = "Bob",
            textFragments = listOf("Review the proposal"),
            rawTextDigest = "digest_2",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
        obsStore.persistObservation(obs)

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock, attentionStore)
        vm.refresh(isPermissionGranted = true, isListenerConnected = true)
        vm.selectConversation(vm.state.value.conversations.first())

        // 1. Prepare draft
        vm.prepareDraft(body = "I have reviewed and approved.", recipient = "Bob")
        val reviewPayload = vm.state.value.activeReviewPayload
        assertNotNull(reviewPayload)
        assertEquals(ActionRoute.NativeHandoff, reviewPayload.route)
        assertEquals("I have reviewed and approved.", reviewPayload.body)

        // 2. Approve and dispatch via Mock Handoff
        var openedCommand: OutboxCommand? = null
        val mockHandoff = HandoffLauncher { cmd, _ ->
            openedCommand = cmd
            true
        }

        vm.approveAndDispatch(mockHandoff)

        assertNotNull(openedCommand)
        assertEquals(reviewPayload.route, openedCommand!!.route)

        // Handoff never automatically marks the conversation Done.
        val record = vm.state.value.attentionRecords[convId.key]
        if (record != null) {
            assertFalse(record.isUserSet, "Attention record is untouched by handoff")
        }
    }

    @Test
    fun testDraftUsefulnessFeedbackKeepsEditedTextLocal() {
        val observations = InMemoryObservationStore()
        val vm = InboxViewModel(
            observationStore = observations,
            retrievalEngine = ScopedRetrievalEngine(observations),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
        )

        vm.updateLocalDraftText("Edited locally before review")
        vm.recordDraftUsefulness(DraftUsefulnessFeedback.NeedsEditing)

        assertEquals("Edited locally before review", vm.state.value.localDraftText)
        assertEquals(DraftUsefulnessFeedback.NeedsEditing, vm.state.value.localDraftFeedback)
        assertTrue(vm.state.value.localDraftStatus?.contains("local") == true)
    }

    @Test
    fun testAttentionActionsAndStorageFailure() {
        val obsStore = InMemoryObservationStore()
        val attentionStore = InMemoryAttentionStore()
        val vm = InboxViewModel(obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(), ActionController(clock, digest, DomainActionIdGenerator(idGen)), InMemoryActionStore(), clock, attentionStore)

        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_3"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "notif_key_3",
            notificationId = 3,
            tag = null,
            senderHint = "Alice",
            title = "Alice",
            textFragments = listOf("Ping"),
            rawTextDigest = "digest_3",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
        obsStore.persistObservation(obs)
        vm.refresh(true, true)

        // Initial default state
        assertEquals(FollowUpStatus.NeedsAttention, vm.state.value.attentionRecords[convId.key]?.status ?: FollowUpStatus.NeedsAttention)
        
        // Mark waiting
        vm.applyAttentionAction(convId, AttentionAction.MarkWaiting)
        assertEquals(FollowUpStatus.Waiting, vm.state.value.attentionRecords[convId.key]?.status)
        assertEquals(1, attentionStore.writeCount)
        
        // Fail a write
        attentionStore.failWrites = "Disk full"
        vm.applyAttentionAction(convId, AttentionAction.MarkDone)
        assertEquals(FollowUpStatus.Waiting, vm.state.value.attentionRecords[convId.key]?.status, "State not updated when storage fails")
        assertEquals("Disk full", vm.state.value.storageFailure)
        assertEquals(1, attentionStore.writeCount)
    }

    @Test
    fun refreshSurfacesUnderstandingAndPreservesDoneOverride() {
        val obsStore = InMemoryObservationStore()
        val attentionStore = InMemoryAttentionStore()
        val vm = InboxViewModel(obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(), ActionController(clock, digest, DomainActionIdGenerator(idGen)), InMemoryActionStore(), clock, attentionStore)
        obsStore.persistObservation(
            ObservedNotification(
                id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_deadline"),
                accountId = account,
                conversationId = convId,
                packageId = "com.whatsapp",
                notificationKey = "notif_deadline",
                notificationId = 9,
                tag = null,
                senderHint = "Alice",
                title = "Alice",
                textFragments = listOf("Please send the signed proposal by Friday"),
                rawTextDigest = "digest_deadline",
                observedAt = clock.now(),
                postTime = clock.now(),
                revision = 1L,
                isRemoved = false,
                isGroupSummary = false,
                truncationState = TruncationState.Complete,
                correlationConfidence = CorrelationConfidence.High,
                provenance = ObservationProvenance.AndroidNotificationListener,
            ),
        )
        vm.refresh(true, true)
        val understood = vm.state.value.understanding[convId.key]
        assertEquals("by Friday", understood?.deadline)
        assertTrue(understood?.unansweredRequest == true)

        vm.applyAttentionAction(convId, AttentionAction.MarkDone)
        val done = vm.state.value.understanding[convId.key]
        assertTrue(done?.unansweredRequest == false)
        assertEquals("by Friday", done?.deadline)
    }

    @Test
    fun userEditedApprovedTextIsLearnedAndUneditedGeneratedTextIsNot() {
        val obsStore = InMemoryObservationStore()
        val learned = mutableListOf<Pair<String, app.mini.hub.style.TextAuthorship>>()
        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_learn"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "notif_learn",
            notificationId = 11,
            tag = null,
            senderHint = "Alice",
            title = "Alice",
            textFragments = listOf("Please send the signed proposal by Friday"),
            rawTextDigest = "digest_learn",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
        obsStore.persistObservation(obs)
        val vm = InboxViewModel(
            observationStore = obsStore,
            retrievalEngine = ScopedRetrievalEngine(obsStore),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            onLearnFromApprovedText = { _, body, _, authorship -> learned += body to authorship },
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.updateLocalDraftText("Thanks, I will review the proposal.")
        vm.prepareDraft("Thanks, I will review the proposal.", "Alice")
        vm.approveAndDispatch(HandoffLauncher { _, _ -> true })
        assertEquals(listOf("Thanks, I will review the proposal." to app.mini.hub.style.TextAuthorship.UserAuthoredSentMessage), learned)
    }
}
