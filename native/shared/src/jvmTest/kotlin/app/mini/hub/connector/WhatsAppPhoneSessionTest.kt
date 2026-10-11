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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsAppPhoneSessionTest {
    private val now = UtcInstant(1_700_000_000_000L)
    private val accountId = AccountId(Provider.WHATSAPP, "phone_local")

    @Test(timeout = 10_000)
    fun unprovisionedRuntimeCannotPairOrPretendToBeMatrix() {
        val session = WhatsAppPhoneSession(accountId, InMemoryEncryptedBridgeSessionStore())
        val pairing = session.pair()
        assertTrue(pairing is WhatsAppPairing.Unavailable)
        assertEquals(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED, (pairing as WhatsAppPairing.Unavailable).reason)
        val proof = session.proof()
        assertEquals(BridgeHosting.PhoneLocal, proof.hosting)
        assertFalse(PhoneLocalBridgePolicy.allowsSession(proof))
        assertEquals(BridgeIdentityKind.Unknown, proof.identityKind)
        assertFalse(proof.reconnectsWithoutPastedHomeserverToken)
        assertEquals(WhatsAppReconnect.Unavailable("whatsapp_session_missing"), session.reconnect())
        val phonePairing = session.pairPhone("+15551234567")
        assertTrue(phonePairing is WhatsAppPairing.Unavailable)
        assertEquals(
            UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED,
            (phonePairing as WhatsAppPairing.Unavailable).reason,
        )
        val store = InMemoryEncryptedBridgeSessionStore()
        store.save(accountId, WhatsAppSessionEnvelope(byteArrayOf(4, 5, 6)))
        val withBlob = WhatsAppPhoneSession(accountId, store)
        assertEquals(
            WhatsAppReconnect.Unavailable(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED),
            withBlob.reconnect(),
        )
        assertTrue(withBlob.proof().encryptedLocalSession)
        assertFalse(withBlob.proof().reconnectsWithoutPastedHomeserverToken)
    }

    @Test(timeout = 10_000)
    fun qrPayloadCannotBeAHomeserverUrl() {
        assertFailsWith<IllegalArgumentException> {
            WhatsAppPairing.QrReady("https://example.com|syt_token")
        }
        val qr = WhatsAppPairing.QrReady("2@opaque-linked-device-ref")
        assertFalse(qr.qrPayload.contains("://"))
    }

    @Test(timeout = 10_000)
    fun e164IdentityIsAProviderAccountAndWhatsAppReceiptIsForwarding() {
        assertEquals(BridgeIdentityKind.ProviderAccount, PhoneLocalBridgePolicy.classifyIdentity("+15551234567"))
        assertEquals(BridgeReceiptKind.ProviderForwarding, PhoneLocalBridgePolicy.classifyReceipt("whatsapp:15551234567@s.whatsapp.net:stanza1"))
        assertEquals(BridgeIdentityKind.MatrixUser, PhoneLocalBridgePolicy.classifyIdentity("@whatsapp_user:example.com"))
    }

    @Test(timeout = 10_000)
    fun encryptedEnvelopeRoundTripsWithoutUsingAHomeserverToken() {
        val store = InMemoryEncryptedBridgeSessionStore()
        val original = byteArrayOf(9, 8, 7, 6)
        store.save(accountId, WhatsAppSessionEnvelope(original))
        original[0] = 0
        val loaded = store.load(accountId)
        assertEquals(WhatsAppSessionEnvelope(byteArrayOf(9, 8, 7, 6)), loaded)
        val restored = InMemoryEncryptedBridgeSessionStore().also {
            loaded?.let { env -> it.save(accountId, env) }
        }
        assertEquals(loaded, restored.load(accountId))
        assertTrue(store.clear(accountId))
        assertEquals(null, store.load(accountId))
    }

    @Test(timeout = 10_000)
    fun linkedLocalClientMeetsPhoneLocalPolicyAndSavesEnvelope() {
        val store = InMemoryEncryptedBridgeSessionStore()
        val session = WhatsAppPhoneSession(accountId, store, FakeLinkedWhatsAppClient())
        val linked = session.pair() as WhatsAppPairing.Linked
        assertEquals("+15551234567", linked.phoneE164)
        assertEquals(WhatsAppSessionEnvelope(byteArrayOf(1, 2, 3)), store.load(accountId))
        val reconnect = session.reconnect() as WhatsAppReconnect.Restored
        assertEquals("+15551234567", reconnect.phoneE164)
        assertTrue(PhoneLocalBridgePolicy.allowsSession(session.proof()))
        assertEquals(BridgeIdentityKind.ProviderAccount, session.proof().identityKind)
        assertTrue(session.proof().encryptedLocalSession)
        assertTrue(session.proof().reconnectsWithoutPastedHomeserverToken)
    }

    @Test(timeout = 10_000)
    fun catalogPrefersPhoneLocalWhatsAppOverMatrix() {
        val route = ConnectorCatalog.apiRouteFor(Provider.WHATSAPP)
        assertEquals(ConnectorRoute.PhoneLocalBridge, route?.route)
        assertEquals(ImplementationStatus.DeferredExternalRequirement, route?.status)
        assertTrue(ConnectorCatalog.routesFor(Provider.WHATSAPP).any { it.route == ConnectorRoute.ThirdPartyBridge })
    }

    @Test(timeout = 10_000)
    fun unprovisionedSenderRejectsWithoutNetworkAndRemoteMatrixStillForbidden() {
        val accounts = InMemoryConnectedAccountStore()
        val phoneLocal = ConnectedAccount(
            accountId, "+15551234567", "WA", AccountType.Unknown,
            ConnectorRoute.PhoneLocalBridge, ConnectionState.Healthy,
            grantedScopes = setOf(UnprovisionedWhatsAppClient.PHONE_SESSION_SCOPE),
        )
        accounts.upsert(phoneLocal)
        val tokens = InMemoryAccessTokenStore().apply {
            put(accountId, StoredAccessToken("local-session", null))
        }
        val gateway = ConnectorProviderGateway(
            accounts, tokens, listOf(UnprovisionedWhatsAppSender()),
            GateEvidenceSource { listOf(GateEvidence(ReleaseGate.SendQualification, "fixture")) },
            ProviderHttpTransport.of { error("unprovisioned WhatsApp must not call a network") },
            nowMillis = { now.epochMillis },
        )
        val payload = ExactActionPayload(
            accountId.key, "15551239999", ActionRoute.Api, listOf("+15551239999"),
            null, "Hello", emptyList(), emptyList(), "v1", "v1",
        )
        val command = OutboxCommand("c1", "a1", "digest", ActionRoute.Api, OutboxState.Dispatching, now)
        val result = gateway.execute(command, payload)
        assertEquals(GatewayResult.Rejected(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED), result)

        accounts.upsert(phoneLocal.copy(route = ConnectorRoute.ThirdPartyBridge, providerAccountId = "@wa:hs"))
        val remote = gateway.execute(command, payload)
        assertEquals(GatewayResult.Rejected("remote_bridge_plaintext_forbidden"), remote)
    }

    private class FakeLinkedWhatsAppClient : WhatsAppPhoneClient {
        override fun startPairing(): WhatsAppPairing =
            WhatsAppPairing.Linked("+15551234567", WhatsAppSessionEnvelope(byteArrayOf(1, 2, 3)))
        override fun restoreAndReconnect(envelope: WhatsAppSessionEnvelope): WhatsAppReconnect =
            WhatsAppReconnect.Restored("+15551234567")
        override fun linkedPhoneE164(): String = "+15551234567"
        override fun send(payload: ExactActionPayload): SendOutcome =
            SendOutcome.Accepted("whatsapp:${payload.conversationId}:stanza1")
    }
}
