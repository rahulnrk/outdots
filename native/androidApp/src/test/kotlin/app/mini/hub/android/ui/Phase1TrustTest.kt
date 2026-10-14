package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ProviderGateway
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InMemoryConnectedAccountStore
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
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.TextAuthorship
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase1TrustTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    private fun vm(
        obs: InMemoryObservationStore = InMemoryObservationStore(),
        actions: InMemoryActionStore = InMemoryActionStore(),
        accounts: InMemoryConnectedAccountStore = InMemoryConnectedAccountStore(),
        gateway: ProviderGateway? = null,
        learn: ((String, TextAuthorship) -> Unit)? = null,
        apiAllowed: (ConnectedAccount) -> Boolean = { false },
        sendRunner: (Runnable) -> Unit = { it.run() },
    ): InboxViewModel = InboxViewModel(
        observationStore = obs,
        retrievalEngine = ScopedRetrievalEngine(obs),
        styleEngine = AdaptiveStyleEngine(),
        actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
        actionStore = actions,
        clock = clock,
        attentionStore = InMemoryAttentionStore(),
        accountStore = accounts,
        gateway = gateway,
        apiReplyAllowed = apiAllowed,
        onLearnFromApprovedText = learn?.let { cb -> { _, body, _, authorship -> cb(body, authorship) } },
        sendRunner = sendRunner,
    )

    @Test
    fun composeReviewIncludesCcBccAndFrom() {
        val account = AccountId(Provider.GMAIL, "work")
        val accounts = InMemoryConnectedAccountStore().apply {
            upsert(
                ConnectedAccount(
                    account, "me@work.com", "Me Work", AccountType.GoogleConsumer,
                    ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
                ),
            )
        }
        val vm = vm(accounts = accounts)
        vm.prepareComposeEmail(account, listOf("to@example.com"), listOf("cc@example.com"), listOf("bcc@example.com"), "Hello", "Body text")
        val payload = assertNotNull(vm.state.value.activeReviewPayload)
        assertEquals(listOf("to@example.com"), payload.recipients)
        assertEquals(listOf("cc@example.com"), payload.cc)
        assertEquals(listOf("bcc@example.com"), payload.bcc)
        assertEquals("Hello", payload.subject)
        assertEquals("Body text", payload.body)
        assertEquals("me@work.com", vm.state.value.reviewContext?.fromAddress)
    }

    @Test
    fun blankBodyDoesNotOpenReview() {
        val obs = InMemoryObservationStore()
        val account = AccountId(Provider.WHATSAPP, "local")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")
        obs.persistObservation(message(account, conv))
        val vm = vm(obs)
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("   ", "Alice")
        assertNull(vm.state.value.activeReviewPayload)
        assertTrue(vm.state.value.outboxStatusMessage!!.contains("Write a message"))
    }

    @Test
    fun outlookComposeHandoffTargetsOutlookNotGmail() {
        val account = AccountId(Provider.OUTLOOK, "work")
        val vm = vm()
        vm.prepareComposeEmail(account, listOf("to@example.com"), emptyList(), emptyList(), "Hi", "Body")
        assertEquals(ActionRoute.NativeHandoff, vm.state.value.activeReviewPayload!!.route)
        assertEquals("com.microsoft.office.outlook", vm.state.value.reviewContext?.handoffPackage)
        assertTrue(vm.state.value.reviewContext!!.note!!.contains("Outlook"))
        assertTrue(!vm.state.value.reviewContext!!.note!!.contains("Gmail"))
    }

    @Test
    fun draftsSurviveLeavingAndReturningToAConversation() {
        val obs = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val account = AccountId(Provider.WHATSAPP, "local")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")
        obs.persistObservation(message(account, conv))
        val vm = vm(obs, actions)
        vm.refresh(true, true)
        val summary = vm.state.value.conversations.first()
        vm.selectConversation(summary)
        vm.updateLocalDraftText("typed locally")
        vm.clearSelection()
        assertEquals("", vm.state.value.localDraftText)
        assertEquals("typed locally", actions.loadDraft(summary.conversationId.key)?.body)
        vm.selectConversation(summary)
        assertEquals("typed locally", vm.state.value.localDraftText)
    }

    @Test
    fun handoffStoresSnapshotAndDoesNotLearn() {
        val obs = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val learned = mutableListOf<String>()
        val account = AccountId(Provider.WHATSAPP, "local")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")
        obs.persistObservation(message(account, conv))
        val vm = vm(obs, actions, learn = { body, _ -> learned += body })
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("Please send this in WhatsApp", "Alice")
        vm.approveAndDispatch(HandoffLauncher { _, _ -> true })
        assertEquals(emptyList(), learned)
        val snap = actions.snapshot().reviewedSnapshots.single()
        assertEquals(OutboxState.HandoffOpened, snap.state)
        assertEquals("Please send this in WhatsApp", snap.body)
        assertEquals(account.key, snap.accountId)
        assertEquals(conv.key, snap.conversationId)
    }

    @Test(timeout = 30_000)
    fun acceptedApiSendLearnsEditedTextAndKeepsSnapshot() {
        val obs = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val account = AccountId(Provider.GMAIL, "work")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread")
        obs.persistObservation(message(account, conv, packageId = "com.google.android.gm", sender = "Bob <bob@example.com>"))
        val accounts = InMemoryConnectedAccountStore().apply {
            upsert(
                ConnectedAccount(
                    account, "me@work.com", "Me Work", AccountType.GoogleConsumer,
                    ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
                    grantedScopes = setOf("https://www.googleapis.com/auth/gmail.send"),
                ),
            )
        }
        val learned = mutableListOf<TextAuthorship>()
        val vm = vm(
            obs, actions, accounts,
            gateway = ProviderGateway { _, _ -> GatewayResult.Accepted("r1") },
            learn = { _, authorship -> learned += authorship },
            apiAllowed = { true },
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("Reviewed reply body", "")
        assertEquals(ActionRoute.Api, vm.state.value.activeReviewPayload!!.route)
        assertTrue(vm.state.value.activeReviewPayload!!.cc.isEmpty())
        vm.approveAndDispatch(HandoffLauncher { _, _ -> error("handoff") })
        assertEquals(listOf(TextAuthorship.UserAuthoredSentMessage), learned)
        assertEquals(OutboxState.AcceptedByProvider, actions.snapshot().reviewedSnapshots.single().state)
        assertEquals("Reviewed reply body", actions.snapshot().reviewedSnapshots.single().body)
    }

    private fun message(
        account: AccountId,
        conv: ScopedId,
        packageId: String = "com.whatsapp",
        sender: String = "Alice",
    ) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_1"),
        accountId = account,
        conversationId = conv,
        packageId = packageId,
        notificationKey = "provider:${account.key}:m1",
        notificationId = 1,
        tag = null,
        senderHint = sender,
        title = "Hello",
        textFragments = listOf("hi"),
        rawTextDigest = "d",
        observedAt = clock.now(),
        postTime = clock.now(),
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.AndroidNotificationListener,
    )
}
