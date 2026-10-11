package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ActionState
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ProviderGateway
import app.mini.hub.android.StartupRecovery
import app.mini.hub.android.connector.RefreshingGateway
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InMemoryAccessTokenStore
import app.mini.hub.connector.InMemoryConnectedAccountStore
import app.mini.hub.connector.StoredAccessToken
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
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T3: direct send runs off the main thread, outside DB transactions, at most once, and recovers after a crash. */
class SendDispatchTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }
    private val account = AccountId(Provider.GMAIL, "local_a")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "t1")

    /** Records whether a transaction is open, so a gateway can assert it is not called inside one. */
    private class TrackingStore(private val inner: ActionStore = InMemoryActionStore()) : ActionStore by inner {
        @Volatile var inTransaction = false
        override fun transaction(update: (ActionState) -> ActionState): ActionState {
            inTransaction = true
            try { return inner.transaction(update) } finally { inTransaction = false }
        }
    }

    private fun apiPayload() = ExactActionPayload(
        accountId = account.key, conversationId = convId.key, route = ActionRoute.Api,
        recipients = listOf("bob@example.com"), subject = "Re: x", body = "Reviewed body",
        attachments = emptyList(), sourceVersions = emptyList(),
        grantVersion = "grant-v1-api-send", policyVersion = "policy-v1-api-review", replyTarget = "m1",
    )

    private fun viewModel(store: ActionStore, gateway: ProviderGateway, runner: (Runnable) -> Unit): InboxViewModel {
        val obs = InMemoryObservationStore()
        obs.persistObservation(
            ObservedNotification(
                id = ScopedId(account, ScopedId.Kind.OBSERVATION, "o1"), accountId = account, conversationId = convId,
                packageId = "provider.gmail", notificationKey = "provider:${account.key}:m1", notificationId = 1, tag = null,
                senderHint = "Bob <bob@example.com>", title = "x", textFragments = listOf("hello"), rawTextDigest = "d",
                observedAt = clock.now(), postTime = clock.now(), revision = 1L, isRemoved = false, isGroupSummary = false,
                truncationState = TruncationState.Complete, correlationConfidence = CorrelationConfidence.High,
                provenance = ObservationProvenance.AndroidNotificationListener,
            ),
        )
        val vm = InboxViewModel(
            observationStore = obs, retrievalEngine = ScopedRetrievalEngine(obs), styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)), actionStore = store,
            clock = clock, attentionStore = InMemoryAttentionStore(), gateway = gateway, sendRunner = runner,
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.setReviewPayloadForTest(apiPayload())
        return vm
    }

    @Test(timeout = 30_000)
    fun providerCallRunsOffCallerThreadOutsideTransactionAfterDurableDispatching() {
        val store = TrackingStore()
        var callThread: String? = null
        var sawDispatching = false
        var sawConsumed = false
        var sawOpenTransaction = true
        val gateway = ProviderGateway { cmd, _ ->
            callThread = Thread.currentThread().name
            sawOpenTransaction = store.inTransaction
            val snap = store.snapshot()
            sawDispatching = snap.outbox.single { it.id == cmd.id }.state == OutboxState.Dispatching
            sawConsumed = snap.approvals.single { it.id == cmd.approvalId }.consumed
            GatewayResult.Accepted("receipt-1")
        }
        var worker: Thread? = null
        val vm = viewModel(store, gateway) { r -> worker = Thread(r, "mini-send-test").also { it.start() } }

        vm.approveAndDispatch { _, _ -> error("handoff must not be used") }
        assertTrue(vm.state.value.isSending, "UI shows in-progress state while sending")
        worker!!.join(10_000)

        assertEquals("mini-send-test", callThread)
        assertFalse(sawOpenTransaction, "provider call must not run inside a database transaction")
        assertTrue(sawDispatching, "Dispatching must be committed before the network call")
        assertTrue(sawConsumed, "approval must be consumed before the network call")
        assertEquals(OutboxState.AcceptedByProvider, store.snapshot().outbox.single().state)
        assertFalse(vm.state.value.isSending)
        assertTrue(vm.state.value.outboxStatusMessage!!.contains("accepted"))
    }

    @Test(timeout = 30_000)
    fun secondTapWhileInFlightSendsNothingMore() {
        val calls = AtomicInteger()
        val queued = mutableListOf<Runnable>()
        val vm = viewModel(InMemoryActionStore(), ProviderGateway { _, _ -> calls.incrementAndGet(); GatewayResult.Accepted("r") }) { queued += it }

        vm.approveAndDispatch { _, _ -> true }
        vm.approveAndDispatch { _, _ -> true }
        queued.toList().forEach { it.run() }
        vm.approveAndDispatch { _, _ -> true } // review payload cleared after completion: no-op

        assertEquals(1, calls.get())
        assertEquals(1, queued.size)
    }

    @Test(timeout = 30_000)
    fun interruptedDispatchBecomesUnknownAtStartupAndIsNeverResent() {
        val store = InMemoryActionStore()
        val controller = ActionController(clock, digest, DomainActionIdGenerator(idGen))
        val (approved, command) = controller.approve(store.snapshot(), apiPayload())
        store.transaction { approved }
        store.transaction { controller.beginDispatch(it, command.id, apiPayload()) }
        assertEquals(OutboxState.Dispatching, store.snapshot().outbox.single().state)

        assertEquals(1, StartupRecovery.recoverOutbox(store, controller) { })

        assertEquals(OutboxState.OutcomeUnknown, store.snapshot().outbox.single().state)
        val calls = AtomicInteger()
        store.transaction { controller.dispatch(it, command.id, apiPayload(), ProviderGateway { _, _ -> calls.incrementAndGet(); GatewayResult.Accepted("x") }, null) }
        assertEquals(0, calls.get())
    }

    private fun gmailAccount() = ConnectedAccount(
        account, "owner@example.com", "Owner", AccountType.GoogleConsumer,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
    )

    @Test(timeout = 30_000)
    fun expiredTokenIsRefreshedBeforeGatewayCall() {
        val accounts = InMemoryConnectedAccountStore().apply { upsert(gmailAccount()) }
        val tokens = InMemoryAccessTokenStore().apply { put(account, StoredAccessToken("old", expiresAtMillis = 1L, refreshToken = "refresh")) }
        var seenToken: String? = null
        val delegate = ProviderGateway { _, _ -> seenToken = tokens.get(account)?.value; GatewayResult.Accepted("r") }
        val gateway = RefreshingGateway(accounts, tokens, { _: ConnectedAccount, _: StoredAccessToken -> StoredAccessToken("fresh", expiresAtMillis = Long.MAX_VALUE, refreshToken = "refresh").also { tokens.put(account, it) } }, delegate) { 1_000L }

        val result = gateway.execute(dummyCommand(), apiPayload())
        assertEquals(GatewayResult.Accepted("r"), result)
        assertEquals("fresh", seenToken)
    }

    @Test(timeout = 30_000)
    fun failedRefreshIsDefiniteRejectionWithReconnectMessageAndNoProviderCall() {
        val accounts = InMemoryConnectedAccountStore().apply { upsert(gmailAccount()) }
        val tokens = InMemoryAccessTokenStore().apply { put(account, StoredAccessToken("old", expiresAtMillis = 1L, refreshToken = "refresh")) }
        val calls = AtomicInteger()
        val delegate = ProviderGateway { _, _ -> calls.incrementAndGet(); GatewayResult.Accepted("r") }
        val gateway = RefreshingGateway(accounts, tokens, { acct: ConnectedAccount, _: StoredAccessToken ->
            accounts.upsert(acct.copy(state = ConnectionState.Revoked, userActionRequired = "Reconnect Gmail to resume this account. Local history is preserved."))
            null
        }, delegate) { 1_000L }

        val result = gateway.execute(dummyCommand(), apiPayload())
        val rejected = assertNotNull(result as? GatewayResult.Rejected)
        assertTrue(rejected.reason.contains("Reconnect Gmail"))
        assertEquals(0, calls.get())
    }

    private fun dummyCommand() = app.mini.hub.action.OutboxCommand("c", "a", "d", ActionRoute.Api, OutboxState.Dispatching, clock.now())
}
