package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.OutboxState
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.AuthorizationSnapshot
import app.mini.hub.connector.Capability
import app.mini.hub.connector.CapabilitySnapshot
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InMemoryConnectedAccountStore
import app.mini.hub.connector.MessageDirection
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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthorizationBindingTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }
    private val account = AccountId(Provider.GMAIL, "work")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread_auth")
    private val sendScope = "https://www.googleapis.com/auth/gmail.send"
    private val readScope = "https://www.googleapis.com/auth/gmail.readonly"

    private fun connected(scopes: Set<String>) = ConnectedAccount(
        account, "me@work.com", "Me Work", AccountType.GoogleConsumer,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy, grantedScopes = scopes,
    )

    private fun observation(revision: Long = 1L) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_m1"),
        accountId = account,
        conversationId = convId,
        packageId = "com.google.android.gm",
        notificationKey = "provider:${account.key}:m1",
        notificationId = 1,
        tag = null,
        senderHint = "Alice <alice@example.com>",
        title = "Hello",
        textFragments = listOf("hi"),
        rawTextDigest = "d",
        observedAt = clock.now(),
        postTime = clock.now(),
        revision = revision,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.OfficialProviderApi,
        direction = MessageDirection.Inbound,
    )

    @Test(timeout = 30_000)
    fun prepareDraftBindsLiveGrantAndPolicyVersions() {
        val obs = InMemoryObservationStore().also { it.persistObservation(observation()) }
        val accounts = InMemoryConnectedAccountStore().apply { upsert(connected(setOf(readScope, sendScope))) }
        val vm = InboxViewModel(
            observationStore = obs,
            retrievalEngine = ScopedRetrievalEngine(obs),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            accountStore = accounts,
            apiReplyAllowed = { true },
            capabilitiesFor = {
                CapabilitySnapshot(setOf(Capability.Reply, Capability.ReadBody), emptyMap())
            },
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("Thanks", "alice@example.com")
        val payload = vm.state.value.activeReviewPayload
        assertNotNull(payload)
        assertEquals(AuthorizationSnapshot.grantVersion(setOf(readScope, sendScope)), payload.grantVersion)
        assertEquals(
            AuthorizationSnapshot.policyVersion(setOf(Capability.Reply, Capability.ReadBody), ActionRoute.Api),
            payload.policyVersion,
        )
        assertEquals(listOf(observation().id.key to 1L), payload.sourceVersions.map { it.id to it.revision })
    }

    @Test(timeout = 30_000)
    fun revokedSendScopeInvalidatesDispatchWithoutCallingTheGateway() {
        val obs = InMemoryObservationStore().also { it.persistObservation(observation()) }
        val accounts = InMemoryConnectedAccountStore().apply { upsert(connected(setOf(readScope, sendScope))) }
        val actionStore = InMemoryActionStore()
        var gatewayCalls = 0
        val vm = InboxViewModel(
            observationStore = obs,
            retrievalEngine = ScopedRetrievalEngine(obs),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = actionStore,
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            accountStore = accounts,
            gateway = { _, _ ->
                gatewayCalls++
                error("gateway must not run after a grant change")
            },
            apiReplyAllowed = { true },
            capabilitiesFor = { account ->
                val enabled = if (sendScope in account.grantedScopes) setOf(Capability.Reply) else emptySet()
                CapabilitySnapshot(enabled, emptyMap())
            },
            sendRunner = { it.run() },
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("Thanks", "alice@example.com")
        accounts.upsert(connected(setOf(readScope)))
        vm.approveAndDispatch { _, _ -> error("handoff must not run") }
        val command = actionStore.snapshot().outbox.single()
        assertEquals(OutboxState.ExpiredOrInvalidated, command.state)
        assertEquals("policy_or_grants_changed_since_approval", command.failureReason)
        assertEquals(0, gatewayCalls)
        assertTrue(actionStore.snapshot().approvals.single().let { !it.consumed })
    }

    @Test(timeout = 30_000)
    fun editedEvidenceInvalidatesDispatch() {
        val obs = InMemoryObservationStore().also { it.persistObservation(observation(1L)) }
        val accounts = InMemoryConnectedAccountStore().apply { upsert(connected(setOf(sendScope))) }
        val actionStore = InMemoryActionStore()
        val vm = InboxViewModel(
            observationStore = obs,
            retrievalEngine = ScopedRetrievalEngine(obs),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = actionStore,
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            accountStore = accounts,
            gateway = { _, _ -> error("gateway must not run after evidence change") },
            apiReplyAllowed = { true },
            capabilitiesFor = { CapabilitySnapshot(setOf(Capability.Reply), emptyMap()) },
            sendRunner = { it.run() },
        )
        vm.refresh(true, true)
        vm.selectConversation(vm.state.value.conversations.first())
        vm.prepareDraft("Thanks", "alice@example.com")
        obs.persistObservation(observation(2L))
        vm.approveAndDispatch { _, _ -> error("handoff must not run") }
        val command = actionStore.snapshot().outbox.single()
        assertEquals(OutboxState.ExpiredOrInvalidated, command.state)
        assertEquals("source_changed_since_approval", command.failureReason)
    }
}
