package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class InstagramDispatchWindowTest {
    private val now = 1_700_000_000_000L
    private val account = ConnectedAccount(
        id = AccountId(Provider.INSTAGRAM, "business"),
        providerAccountId = "ig-business",
        displayName = "Business",
        accountType = AccountType.MetaBusiness,
        route = ConnectorRoute.OfficialProviderApi,
        state = ConnectionState.Healthy,
        grantedScopes = setOf(InstagramApiConnector.SCOPE),
    )

    private fun payload(lastInbound: Long?) = ExactActionPayload(
        accountId = account.id.key,
        conversationId = "thread-1",
        route = ActionRoute.Api,
        recipients = listOf("recipient"),
        subject = null,
        body = "Reviewed reply",
        attachments = emptyList(),
        sourceVersions = emptyList(),
        grantVersion = "grant",
        policyVersion = "policy",
        replyWindowLastInboundMillis = lastInbound,
    )

    private val command = OutboxCommand(
        id = "cmd-1",
        approvalId = "approval-1",
        payloadDigest = "digest",
        route = ActionRoute.Api,
        state = OutboxState.Dispatching,
        createdAt = UtcInstant(now),
    )

    @Test
    fun closedWindowRejectsBeforeProviderSender() {
        var sends = 0
        val gateway = ConnectorProviderGateway(
            accounts = InMemoryConnectedAccountStore().also { it.upsert(account) },
            tokens = InMemoryAccessTokenStore().also { it.put(account.id, StoredAccessToken("token", null)) },
            senders = listOf(object : ProviderSender {
                override val provider = Provider.INSTAGRAM
                override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
                    sends++
                    return SendOutcome.Accepted("unexpected")
                }
            }),
            gates = GateEvidenceSource { emptyList() },
            transport = ProviderHttpTransport.of { error("provider must not be called") },
            nowMillis = { now },
        )

        val result = gateway.execute(command, payload(now - InstagramApiConnector.WINDOW_MILLIS))

        assertEquals(GatewayResult.Rejected(InstagramApiSender.OUTSIDE_WINDOW), result)
        assertEquals(0, sends)
    }

    @Test
    fun missingWindowFactFailsClosedBeforeProviderSender() {
        val gateway = ConnectorProviderGateway(
            accounts = InMemoryConnectedAccountStore().also { it.upsert(account) },
            tokens = InMemoryAccessTokenStore().also { it.put(account.id, StoredAccessToken("token", null)) },
            senders = emptyList(),
            gates = GateEvidenceSource { emptyList() },
            transport = ProviderHttpTransport.of { error("provider must not be called") },
            nowMillis = { now },
        )

        assertEquals(GatewayResult.Rejected("instagram_reply_window_unknown"), gateway.execute(command, payload(null)))
    }

    @Test
    fun windowExpiryAfterReviewRejectsWithoutProviderWrite() {
        var nowMillis = now + InstagramApiConnector.WINDOW_MILLIS - 1L
        var sends = 0
        val gateway = ConnectorProviderGateway(
            accounts = InMemoryConnectedAccountStore().also { it.upsert(account) },
            tokens = InMemoryAccessTokenStore().also { it.put(account.id, StoredAccessToken("token", null)) },
            senders = listOf(object : ProviderSender {
                override val provider = Provider.INSTAGRAM
                override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
                    sends++
                    return SendOutcome.Accepted("unexpected")
                }
            }),
            gates = GateEvidenceSource { emptyList() },
            transport = ProviderHttpTransport.of { error("provider must not be called") },
            nowMillis = { nowMillis },
        )
        val reviewed = payload(now)
        nowMillis = now + InstagramApiConnector.WINDOW_MILLIS + 1L

        assertEquals(GatewayResult.Rejected(InstagramApiSender.OUTSIDE_WINDOW), gateway.execute(command, reviewed))
        assertEquals(0, sends)
    }

    @Test
    fun openWindowAllowsOfficialApiRoute() {
        var sends = 0
        val gateway = ConnectorProviderGateway(
            accounts = InMemoryConnectedAccountStore().also { it.upsert(account) },
            tokens = InMemoryAccessTokenStore().also { it.put(account.id, StoredAccessToken("token", null)) },
            senders = listOf(object : ProviderSender {
                override val provider = Provider.INSTAGRAM
                override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
                    sends++
                    return SendOutcome.Accepted("instagram:ok")
                }
            }),
            gates = GateEvidenceSource { listOf(GateEvidence(ReleaseGate.SendQualification, "Verified live")) },
            transport = ProviderHttpTransport.of { error("transport should be unused by fixture sender") },
            nowMillis = { now },
        )

        assertEquals(GatewayResult.Accepted("instagram:ok"), gateway.execute(command, payload(now)))
        assertEquals(1, sends)
    }

    @Test
    fun phoneLocalRouteRejectsSendBeforeProvider() {
        val phoneLocal = account.copy(
            route = ConnectorRoute.PhoneLocalBridge,
            grantedScopes = setOf("instagram.phone-local-session"),
        )
        var sends = 0
        val gateway = ConnectorProviderGateway(
            accounts = InMemoryConnectedAccountStore().also { it.upsert(phoneLocal) },
            tokens = InMemoryAccessTokenStore().also { it.put(phoneLocal.id, StoredAccessToken("phone-local-session", null)) },
            senders = listOf(object : ProviderSender {
                override val provider = Provider.INSTAGRAM
                override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
                    sends++
                    return SendOutcome.Accepted("unexpected")
                }
            }),
            gates = GateEvidenceSource { listOf(GateEvidence(ReleaseGate.SendQualification, "Verified live")) },
            transport = ProviderHttpTransport.of { error("provider must not be called") },
            nowMillis = { now },
        )

        assertEquals(GatewayResult.Rejected("instagram_phone_local_send_disabled"), gateway.execute(command, payload(now)))
        assertEquals(0, sends)
    }
}
