package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ProviderGateway
import app.mini.hub.action.toReviewedSnapshot
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

    @Test
    fun newEmailDraftIsDiscoverableAndReopenableAfterRestart() {
        val account = AccountId(Provider.GMAIL, "work")
        val actions = InMemoryActionStore()
        val first = vm(actions = actions)
        first.startComposeEmail(account)
        first.updateComposeTo("to@example.com")
        first.updateComposeCc("cc@example.com")
        first.updateComposeBcc("bcc@example.com")
        first.updateComposeSubject("Quarterly note")
        first.updateLocalDraftText("Please review the attached plan.")
        val key = first.state.value.selectedConversation!!.conversationId.key
        first.clearSelection()
        assertTrue(first.state.value.savedDrafts.any { it.conversationKey == key && it.isNewEmail })

        val restarted = vm(actions = actions)
        restarted.refresh(true, true)
        val saved = restarted.state.value.savedDrafts.single { it.conversationKey == key }
        assertEquals("to@example.com", saved.to)
        assertEquals("cc@example.com", saved.cc)
        assertEquals("bcc@example.com", saved.bcc)
        assertEquals("Quarterly note", saved.subject)
        restarted.openSavedDraft(saved)
        assertEquals("Please review the attached plan.", restarted.state.value.localDraftText)
        assertEquals("to@example.com", restarted.state.value.composeNewTo)
        assertEquals("cc@example.com", restarted.state.value.composeCc)
        assertEquals("bcc@example.com", restarted.state.value.composeBcc)
        assertEquals("Quarterly note", restarted.state.value.composeSubject)
    }

    @Test(timeout = 30_000)
    fun restoredUneditedAiTextDoesNotTeachStyle() {
        val obs = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val account = AccountId(Provider.GMAIL, "work")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread")
        obs.persistObservation(message(account, conv, packageId = "com.google.android.gm", sender = "Bob <bob@example.com>"))
        actions.saveDraft(
            app.mini.hub.persistence.ConversationDraft(
                conversationKey = conv.key,
                accountId = account.key,
                body = "AI proposed reply",
                originalGeneratedDraft = "AI proposed reply",
                updatedAtMillis = clock.now().epochMillis,
            ),
        )
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
        assertEquals("AI proposed reply", vm.state.value.localDraftText)
        vm.prepareDraft("AI proposed reply", "")
        vm.approveAndDispatch(HandoffLauncher { _, _ -> error("handoff") })
        assertEquals(emptyList(), learned)
    }

    @Test
    fun failedGenerationKeepsExistingDraftText() {
        val obs = InMemoryObservationStore()
        val account = AccountId(Provider.WHATSAPP, "local")
        val conv = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")
        obs.persistObservation(message(account, conv))
        val vm = vm(obs)
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.updateLocalDraftText("keep this typed draft")
        vm.applyGenerationResultForTest(app.mini.hub.android.intelligence.LocalDraftResult.Failed("model missing", ""))
        assertEquals("keep this typed draft", vm.state.value.localDraftText)
        vm.applyGenerationResultForTest(app.mini.hub.android.intelligence.LocalDraftResult.Failed("stopped", "", cancelled = true))
        assertEquals("keep this typed draft", vm.state.value.localDraftText)
    }

    @Test
    fun recoveredUnknownsAreVisibleWithoutSelectingAConversation() {
        val actions = InMemoryActionStore()
        val payload = app.mini.hub.action.ExactActionPayload(
            accountId = "gmail|work", conversationId = "gmail|work|conversation|t1",
            route = ActionRoute.Api, recipients = listOf("bob@example.com"), subject = "Re: x",
            body = "Exact reviewed body", attachments = emptyList(), sourceVersions = emptyList(),
            grantVersion = "g", policyVersion = "p",
        )
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val approved = controller.approve(app.mini.hub.action.ActionState(), payload)
        actions.transaction {
            approved.first.copy(
                outbox = approved.first.outbox.map { it.copy(state = OutboxState.Dispatching) },
                reviewedSnapshots = listOf(payload.toReviewedSnapshot(approved.second).copy(state = OutboxState.Dispatching)),
            )
        }
        actions.recoverInterrupted(controller)
        val vm = vm(actions = actions)
        vm.refresh(true, true)
        assertNull(vm.state.value.selectedConversation)
        val unknown = vm.state.value.reviewedSnapshots.single()
        assertEquals(OutboxState.OutcomeUnknown, unknown.state)
        assertEquals("Exact reviewed body", unknown.body)
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
