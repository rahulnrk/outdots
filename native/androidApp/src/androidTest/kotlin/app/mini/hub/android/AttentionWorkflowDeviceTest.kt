package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxViewModel
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.AttentionStoreResult
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.SqlCipherActionStore
import app.mini.hub.persistence.SqlCipherAttentionStore
import app.mini.hub.persistence.SqlCipherObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttentionWorkflowDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock { UtcInstant(System.currentTimeMillis()) }
    private val idGen = IdGenerator { prefix -> "$prefix-${System.nanoTime()}" }
    private val digest = DigestProvider { it }

    @Before
    @After
    fun cleanup() {
        context.deleteDatabase("test-obs.db")
        context.deleteDatabase("test-actions.db")
        context.deleteDatabase("test-attention.db")
    }

    @Test
    fun verifyAttentionWorkflowAndPersistence() {
        // 1. Initialize encrypted device stores
        var obsStore = SqlCipherObservationStore(context, "test-obs.db")
        var actionStore = SqlCipherActionStore(context, "test-actions.db")
        var attentionStore = SqlCipherAttentionStore(context, "test-attention.db")

        var vm = InboxViewModel(
            observationStore = obsStore,
            retrievalEngine = ScopedRetrievalEngine(obsStore),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = actionStore,
            clock = clock,
            attentionStore = attentionStore
        )

        // 2. Inject synthetic conversation
        val account = AccountId(Provider.WHATSAPP, "dev_test")
        val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "BobBuilder")
        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs1"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "key1",
            notificationId = 100,
            tag = null,
            senderHint = "Bob",
            title = "Bob",
            textFragments = listOf("Can we review the plans?"),
            rawTextDigest = "hash",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.SyntheticFixture
        )
        obsStore.persistObservation(obs)
        vm.refresh(true, true)

        // 3. Set Important and Waiting
        vm.applyAttentionAction(convId, AttentionAction.SetPriority(ConversationPriority.Important))
        vm.applyAttentionAction(convId, AttentionAction.MarkWaiting)

        var record = vm.state.value.attentionRecords[convId.key]
        assertNotNull("Record should be saved", record)
        assertEquals(ConversationPriority.Important, record?.priority)
        assertEquals(FollowUpStatus.Waiting, record?.status)

        // 4. Force-stop and reopen (simulate by re-instantiating everything, so in-memory state is wiped)
        obsStore = SqlCipherObservationStore(context, "test-obs.db")
        actionStore = SqlCipherActionStore(context, "test-actions.db")
        attentionStore = SqlCipherAttentionStore(context, "test-attention.db")
        vm = InboxViewModel(
            observationStore = obsStore,
            retrievalEngine = ScopedRetrievalEngine(obsStore),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = actionStore,
            clock = clock,
            attentionStore = attentionStore
        )
        vm.refresh(true, true)

        // 5. Verify the exact saved choices remain
        record = vm.state.value.attentionRecords[convId.key]
        assertNotNull("Record must survive process restart", record)
        assertEquals("Priority survives restart", ConversationPriority.Important, record?.priority)
        assertEquals("Follow-up status survives restart", FollowUpStatus.Waiting, record?.status)

        // 6. Verify native handoff does not mark done automatically
        vm.selectConversation(vm.state.value.conversations.first { it.conversationId == convId })
        vm.prepareDraft("Sure, here they are.", "Bob")
        val handoff = AndroidNativeHandoffLauncher(context)
        vm.approveAndDispatch(handoff)

        // Assert handoff opened but status is STILL waiting, not done.
        record = vm.state.value.attentionRecords[convId.key]
        assertEquals("Native handoff must not auto-mark done", FollowUpStatus.Waiting, record?.status)
    }

    @Test
    fun verifyAttentionIsolationAndStorageFailures() {
        val obsStore = SqlCipherObservationStore(context, "test-obs.db")
        var attentionStore = SqlCipherAttentionStore(context, "test-attention.db")
        var vm = InboxViewModel(
            obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(),
            ActionController(clock, digest, DomainActionIdGenerator(idGen)), SqlCipherActionStore(context, "test-actions.db"),
            clock, attentionStore
        )

        // Account isolation
        val personalAccount = AccountId(Provider.WHATSAPP, "personal")
        val workAccount = AccountId(Provider.WHATSAPP, "work")
        val personalAlice = ScopedId(personalAccount, ScopedId.Kind.CONVERSATION, "Alice")
        val workAlice = ScopedId(workAccount, ScopedId.Kind.CONVERSATION, "Alice")

        // We mark personal Alice as Done
        val r = app.mini.hub.attention.AttentionStoreResult.Ok(Unit)
        vm.applyAttentionAction(personalAlice, AttentionAction.MarkDone)

        vm.refresh(true, true)
        val pRec = vm.state.value.attentionRecords[personalAlice.key]
        assertEquals(FollowUpStatus.Done, pRec?.status)

        // Work Alice should be unaffected
        val wRec = vm.state.value.attentionRecords[workAlice.key]
        assertNull("Identical contact names in different accounts must not merge", wRec)

        // Verify Storage Failure visibility
        val brokenAttentionStore = app.mini.hub.attention.UnavailableAttentionStore("Simulated disk error")
        vm = InboxViewModel(
            obsStore, ScopedRetrievalEngine(obsStore), AdaptiveStyleEngine(),
            ActionController(clock, digest, DomainActionIdGenerator(idGen)), SqlCipherActionStore(context, "test-actions.db"),
            clock, brokenAttentionStore
        )
        vm.refresh(true, true)
        
        assertEquals("Storage Error banner must be visible", "Simulated disk error", vm.state.value.storageFailure)
        
        vm.applyAttentionAction(workAlice, AttentionAction.MarkWaiting)
        assertEquals("Storage Error banner updates on save failure", "Simulated disk error", vm.state.value.storageFailure)
    }
}
