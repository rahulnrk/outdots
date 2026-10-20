package app.mini.hub.android.ui

import app.mini.hub.action.*
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.domain.*
import app.mini.hub.observation.*
import app.mini.hub.persistence.*
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.Test
import kotlin.test.*

class SelectionPersistenceTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000) }
    private val account = AccountId(Provider.WHATSAPP, "local_user")
    private fun summary(name: String) = ConversationObservationSummary(ScopedId(account, ScopedId.Kind.CONVERSATION, name),
        account, name, "WhatsApp", name, "com.whatsapp", "hello", NotificationCategory.DirectMessage,
        ConversationCoverage.NotificationOnly, "Notification only", clock.now(), 1, 1, false)

    @Test fun waitingForTheSendWorkerDoesNotExtendTheUsersApproval() {
        var now = 1_700_000_000_000L
        val mutableClock = Clock { UtcInstant(now) }
        val work = ArrayDeque<Runnable>()
        val store = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        var writes = 0
        var ids = 0
        val vm = InboxViewModel(store, ScopedRetrievalEngine(store), AdaptiveStyleEngine(),
            ActionController(mutableClock, DigestProvider { it }, ActionIdGenerator { "command-${ids++}" }), actions, mutableClock,
            InMemoryAttentionStore(), sendRunner = { work.add(it) },
            gateway = ProviderGateway { _, _ -> writes++; GatewayResult.Accepted("synthetic-receipt") })
        vm.setReviewPayloadForTest(ExactActionPayload(accountId = account.key, conversationId = "synthetic-thread",
            route = ActionRoute.Api, recipients = listOf("synthetic"), subject = null, body = "Reviewed text",
            attachments = emptyList(), sourceVersions = emptyList(), grantVersion = "grant", policyVersion = "policy"))
        vm.approveAndDispatch(HandoffLauncher { _, _ -> error("not a handoff") })
        now += 120_001
        while (work.isNotEmpty()) work.removeFirst().run()
        assertEquals(0, writes)
        assertEquals(OutboxState.ExpiredOrInvalidated, actions.snapshot().outbox.single().state)
    }

    @Test fun approvalExpiryDuringCredentialRefreshStopsTheWrite() {
        var now = 1_700_000_000_000L
        val mutableClock = Clock { UtcInstant(now) }
        var ids = 0
        val controller = ActionController(mutableClock, DigestProvider { it }, ActionIdGenerator { "refresh-${ids++}" })
        val payload = ExactActionPayload(accountId = account.key, conversationId = "synthetic-thread", route = ActionRoute.Api,
            recipients = listOf("synthetic"), subject = null, body = "Reviewed text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "grant", policyVersion = "policy")
        val (approved, command) = controller.approve(ActionState(), payload)
        val checks = LiveDispatchChecks("grant", "policy", emptyList())
        val begun = controller.beginDispatch(approved, command.id, payload, checks)
        val connected = app.mini.hub.connector.ConnectedAccount(account, "synthetic", null,
            app.mini.hub.connector.AccountType.Unknown, app.mini.hub.connector.ConnectorRoute.OfficialProviderApi,
            app.mini.hub.connector.ConnectionState.Healthy)
        val accounts = app.mini.hub.connector.InMemoryConnectedAccountStore().also { it.upsert(connected) }
        val tokens = app.mini.hub.connector.InMemoryAccessTokenStore().also { it.put(account, app.mini.hub.connector.StoredAccessToken("old", 0)) }
        var writes = 0
        val gateway = app.mini.hub.android.connector.RefreshingGateway(accounts, tokens,
            refresh = { _, _ -> now += 120_001; app.mini.hub.connector.StoredAccessToken("fresh", null).also { tokens.put(account, it) } },
            delegate = ProviderGateway { _, _ -> writes++; GatewayResult.Accepted("synthetic") },
            authorizationCheck = { c, p -> controller.executionStillAuthorized(begun, c.id, p, checks) },
            nowMillis = { now })
        assertTrue(gateway.execute(command, payload) is GatewayResult.Rejected)
        assertEquals(0, writes)
    }

    @Test fun typingWhileSelectionLoadsIsNotReplacedByAnOlderStoredDraft() {
        val work = ArrayDeque<Runnable>()
        val store = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val vm = InboxViewModel(store, ScopedRetrievalEngine(store), AdaptiveStyleEngine(),
            ActionController(clock, DigestProvider { it }, ActionIdGenerator { it }), actions, clock,
            InMemoryAttentionStore(), refreshRunner = { work.add(it) })
        actions.saveDraft(ConversationDraft(summary("Alice").conversationId.key, account.key, "Previously saved writing", updatedAtMillis = clock.now().epochMillis))
        vm.selectConversation(summary("Alice"))
        assertEquals("Alice", vm.state.value.selectedConversation?.title, "selection is immediate while storage loads")
        vm.updateLocalDraftText("New writing while loading")
        while (work.isNotEmpty()) work.removeFirst().run()
        assertEquals("New writing while loading", vm.state.value.localDraftText)
        assertTrue(vm.state.value.draftVersions.any { it.text == "Previously saved writing" })
        repeat(6) { vm.updateLocalDraftText("New writing continued $it") }
        while (work.isNotEmpty()) work.removeFirst().run()
        assertTrue(actions.loadDraft(summary("Alice").conversationId.key)!!.versions.any { it.text == "Previously saved writing" })
    }

    @Test fun rapidSelectionDoesNotSaveThePreviousPersonsWritingUnderTheNextPerson() {
        val work = ArrayDeque<Runnable>()
        val store = InMemoryObservationStore()
        val actions = InMemoryActionStore()
        val vm = InboxViewModel(store, ScopedRetrievalEngine(store), AdaptiveStyleEngine(),
            ActionController(clock, DigestProvider { it }, ActionIdGenerator { it }), actions, clock,
            InMemoryAttentionStore(), refreshRunner = { work.add(it) })
        val alice = summary("Alice"); val bob = summary("Bob"); val carol = summary("Carol")
        actions.saveDraft(ConversationDraft(bob.conversationId.key, account.key, "Bob's existing saved draft", updatedAtMillis = clock.now().epochMillis))
        vm.selectConversation(alice)
        while (work.isNotEmpty()) work.removeFirst().run()
        vm.updateLocalDraftText("For Alice only")
        vm.selectConversation(bob)
        vm.selectConversation(carol)
        while (work.isNotEmpty()) work.removeFirst().run()
        assertEquals("For Alice only", actions.loadDraft(alice.conversationId.key)?.body)
        assertEquals("Bob's existing saved draft", actions.loadDraft(bob.conversationId.key)?.body, "loading Bob must neither overwrite nor delete his saved draft")
    }
}
