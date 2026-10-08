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
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
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

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock)
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

        val vm = InboxViewModel(obsStore, retrieval, style, controller, actionStore, clock)
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

        // Review dialog dismissed and status message updated
        assertNull(vm.state.value.activeReviewPayload)
        assertNotNull(vm.state.value.outboxStatusMessage)
        assertTrue(vm.state.value.outboxStatusMessage!!.contains("opened in native app"))

        // Verify action store has HandoffOpened state
        val outbox = actionStore.snapshot().outbox
        assertEquals(1, outbox.size)
        assertEquals(OutboxState.HandoffOpened, outbox.first().state)
    }
}
