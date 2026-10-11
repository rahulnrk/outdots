package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhoneLocalBridgePolicyTest {
    private val now = UtcInstant(1_700_000_000_000L)

    @Test(timeout = 10_000)
    fun matrixHomeserverAdapterIsNotAPhoneLocalSession() {
        val proof = PhoneLocalBridgePolicy.matrixHomeserverAdapterProof()
        assertFalse(PhoneLocalBridgePolicy.allowsRemoteHomeserverPlaintext())
        assertFalse(PhoneLocalBridgePolicy.allowsSession(proof))
        assertTrue(PhoneLocalBridgePolicy.unmetRequirements(proof).contains("hosting_not_phone_local"))
        assertTrue(PhoneLocalBridgePolicy.unmetRequirements(proof).contains("identity_not_provider_account"))
        assertTrue(PhoneLocalBridgePolicy.unmetRequirements(proof).contains("plaintext_not_processed_on_phone"))
        assertTrue(PhoneLocalBridgePolicy.unmetRequirements(proof).contains("receipt_not_provider_forwarding"))
    }

    @Test(timeout = 10_000)
    fun whoamiMatrixUserIsNotAWhatsAppAccount() {
        val connector = MatrixBridgeConnector(Provider.WHATSAPP, "whatsapp")
        val transport = ProviderHttpTransport.of { req ->
            assertTrue(req.url.contains("/account/whoami"), req.url)
            ProviderHttpResponse(200, """{"user_id":"@whatsapp_user:example.com"}""")
        }
        val identity = connector.discoverAccount(StoredAccessToken("https://example.com|syt_token", null), transport)
        assertEquals("@whatsapp_user:example.com", identity.providerAccountId)
        assertEquals(BridgeIdentityKind.MatrixUser, PhoneLocalBridgePolicy.classifyIdentity(identity.providerAccountId))
        assertNotEquals(BridgeIdentityKind.ProviderAccount, PhoneLocalBridgePolicy.classifyIdentity(identity.providerAccountId))
    }

    @Test(timeout = 10_000)
    fun matrixSendReceiptIsHomeserverAcceptanceNotProviderForwarding() {
        val connector = MatrixBridgeConnector(Provider.WHATSAPP, "whatsapp")
        val transport = ProviderHttpTransport.of { req ->
            assertTrue(req.url.contains("/send/m.room.message/"), req.url)
            ProviderHttpResponse(200, """{"event_id":"${'$'}abc:example.com"}""")
        }
        val account = ConnectedAccount(
            AccountId(Provider.WHATSAPP, "wa1"), "@whatsapp_user:example.com", "Matrix",
            AccountType.Unknown, ConnectorRoute.ThirdPartyBridge, ConnectionState.Healthy,
            grantedScopes = setOf("matrix_token"),
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "!room:example.com", route = ActionRoute.Api,
            recipients = listOf("room"), subject = null, body = "Hello", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        val outcome = connector.send(account, StoredAccessToken("https://example.com|syt_token", null), payload, "cmd1", transport)
        val accepted = outcome as SendOutcome.Accepted
        assertTrue(accepted.receipt.startsWith("matrix:"))
        assertEquals(BridgeReceiptKind.HomeserverEvent, PhoneLocalBridgePolicy.classifyReceipt(accepted.receipt))
    }

    @Test(timeout = 10_000)
    fun gatewayRefusesRemoteBridgePlaintextSend() {
        val accounts = InMemoryConnectedAccountStore()
        val account = ConnectedAccount(
            AccountId(Provider.WHATSAPP, "wa1"), "@whatsapp_user:example.com", "Matrix",
            AccountType.Unknown, ConnectorRoute.ThirdPartyBridge, ConnectionState.Healthy,
            grantedScopes = setOf("matrix_token"),
        )
        accounts.upsert(account)
        val gateway = ConnectorProviderGateway(
            accounts = accounts,
            tokens = InMemoryAccessTokenStore(),
            senders = listOf(MatrixBridgeConnector(Provider.WHATSAPP, "whatsapp")),
            gates = GateEvidenceSource { emptyList() },
            transport = ProviderHttpTransport.of { error("remote bridge must not reach the network") },
            nowMillis = { now.epochMillis },
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "!room:example.com", route = ActionRoute.Api,
            recipients = listOf("room"), subject = null, body = "Hello", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        val command = OutboxCommand("c1", "a1", "digest", ActionRoute.Api, OutboxState.Dispatching, now)
        val result = gateway.execute(command, payload)
        assertEquals(GatewayResult.Rejected("remote_bridge_plaintext_forbidden"), result)
    }

    @Test(timeout = 10_000)
    fun messengerHasNoMatrixRouteAndIsStillAPilotBridgeNetwork() {
        assertTrue(PhoneLocalBridgePolicy.isPilotProvider(Provider.FACEBOOK))
        assertTrue(PhoneLocalBridgePolicy.isPilotProvider(Provider.WHATSAPP_BUSINESS))
        val facebookRoutes = ConnectorCatalog.routesFor(Provider.FACEBOOK)
        assertTrue(facebookRoutes.none { it.route == ConnectorRoute.ThirdPartyBridge })
        assertTrue(facebookRoutes.any { it.route == ConnectorRoute.NotificationOnly })
        assertEquals(null, ConnectorCatalog.apiRouteFor(Provider.FACEBOOK))
    }
}
