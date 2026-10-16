package app.mini.hub.connector

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionIdGenerator
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectorEngineAndGatewayTest {
    private val digest = DigestProvider { "dig-$it" }

    @Test
    fun syncEnginePersistsObservationsAndCursorAtomically() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val store = InMemoryObservationStore()
        val account = ConnectedAccount(
            AccountId(Provider.OUTLOOK, "out_1"), "usr_1", "User", AccountType.MicrosoftPersonal,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized, setOf("Mail.Read"),
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))

        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/sentitems/") -> ProviderHttpResponse(200, "{\"value\":[],\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta?deltatoken=s1\"}")
                req.url.contains("/delta") -> ProviderHttpResponse(
                    200,
                    "{\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?deltatoken=d1\",\"value\":[{\"id\":\"m1\",\"conversationId\":\"c1\",\"bodyPreview\":\"s1\",\"receivedDateTime\":\"2026-10-10T12:00:00Z\",\"from\":{\"emailAddress\":{\"address\":\"s@ex.com\"}}}]}",
                )
                else -> error("Unexpected URL: ${req.url}")
            }
        }
        val engine = ConnectorSyncEngine(
            accounts = accounts,
            tokens = tokens,
            store = store,
            digest = digest,
            connectors = mapOf(Provider.OUTLOOK to OutlookGraphConnector("https://graph.microsoft.com/v1.0")),
            refreshers = emptyMap(),
            transport = transport,
        )
        val reports = engine.syncAll(1_700_000_000_000L)
        assertEquals(1, reports.size)
        assertEquals(1, reports.single().inserted)
        assertEquals(ConnectionState.Healthy, accounts.get(account.id)?.state)
        val storedCursor = store.getCursor(ConnectorSyncEngine.cursorSource(account))
        assertTrue(storedCursor?.lastProcessedKey?.contains("deltatoken=d1") == true)
        val obs = store.getObservationByNotificationKey("provider:${account.id.key}:m1")
        assertEquals("s1", obs?.textFragments?.single())
    }

    @Test
    fun syncEngineNotifiesAfterCommitSoStyleCanLearnWithoutSelectingAConversation() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val store = InMemoryObservationStore()
        val account = ConnectedAccount(
            AccountId(Provider.OUTLOOK, "out_learn"), "usr_1", "User", AccountType.MicrosoftPersonal,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized, setOf("Mail.Read"),
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))
        var received = 0
        val engine = ConnectorSyncEngine(
            accounts = accounts,
            tokens = tokens,
            store = store,
            digest = digest,
            connectors = mapOf(Provider.OUTLOOK to OutlookGraphConnector("https://graph.microsoft.com/v1.0")),
            refreshers = emptyMap(),
            transport = ProviderHttpTransport.of { req ->
                when {
                    req.url.contains("/sentitems/") -> ProviderHttpResponse(200, "{\"value\":[],\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta?deltatoken=s1\"}")
                req.url.contains("/delta") -> ProviderHttpResponse(
                        200,
                        "{\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?deltatoken=d2\",\"value\":[{\"id\":\"m2\",\"conversationId\":\"c2\",\"bodyPreview\":\"please find the invoice\",\"receivedDateTime\":\"2026-10-10T12:00:00Z\",\"from\":{\"emailAddress\":{\"address\":\"me@ex.com\"}}}]}",
                    )
                    else -> error("Unexpected URL: ${req.url}")
                }
            },
            onMessagesFetched = { fetchedAccount, messages ->
                assertEquals(account.id, fetchedAccount.id)
                received += messages.size
            },
        )
        engine.syncAll(1_700_000_000_000L)
        assertEquals(1, received)
        assertEquals("please find the invoice", store.getObservationByNotificationKey("provider:${account.id.key}:m2")?.textFragments?.single())
    }

    @Test
    fun gatewayRejectsDispatchWhenCapabilityOrScopeIsMissing() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "g_1"), "user@gmail.com", "User", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY), // missing send scope
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))

        val gateway = ConnectorProviderGateway(
            accounts = accounts,
            tokens = tokens,
            senders = listOf(GmailSender("https://gmail.googleapis.com/gmail/v1/users/me")),
            gates = GateEvidenceSource { emptyList() },
            transport = ProviderHttpTransport.of { ProviderHttpResponse(200, "") },
            nowMillis = { 1_700_000_000_000L },
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "t1", route = ActionRoute.Api,
            recipients = listOf("to@example.com"), subject = "Sub", body = "Body", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        var nextId = 0
        val controller = ActionController(
            clock = Clock { UtcInstant(1_700_000_000_000L) },
            digest = digest,
            ids = ActionIdGenerator { "id-${++nextId}" },
        )
        val (state, command) = controller.approve(app.mini.hub.action.ActionState(), payload)
        val resultState = controller.dispatch(state, command.id, payload, gateway, null)
        val outbox = resultState.outbox.single()
        assertEquals(app.mini.hub.action.OutboxState.Rejected, outbox.state)
        assertTrue(outbox.failureReason?.contains("capability_disabled") == true)
    }

    @Test
    fun gatewayDispatchesWhenScopesAndGatesAreCleared() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "g_1"), "user@gmail.com", "User", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY, GmailApiConnector.SCOPE_SEND),
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))

        var sentRaw: String? = null
        val transport = ProviderHttpTransport.of { req ->
            sentRaw = req.body
            ProviderHttpResponse(200, "{\"id\":\"m_sent_1\"}")
        }
        val gateway = ConnectorProviderGateway(
            accounts = accounts,
            tokens = tokens,
            senders = listOf(GmailSender("https://gmail.googleapis.com/gmail/v1/users/me")),
            gates = GateEvidenceSource { listOf(GateEvidence(ReleaseGate.SendQualification, "Verified live")) },
            transport = transport,
            nowMillis = { 1_700_000_000_000L },
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "t1", route = ActionRoute.Api,
            recipients = listOf("to@example.com"), subject = "Sub", body = "Body text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        var nextId = 0
        val controller = ActionController(
            clock = Clock { UtcInstant(1_700_000_000_000L) },
            digest = digest,
            ids = ActionIdGenerator { "id-${++nextId}" },
        )
        val (state, command) = controller.approve(app.mini.hub.action.ActionState(), payload)
        val resultState = controller.dispatch(state, command.id, payload, gateway, null)
        val outbox = resultState.outbox.single()
        assertEquals(app.mini.hub.action.OutboxState.AcceptedByProvider, outbox.state)
        assertEquals("gmail:m_sent_1", outbox.receipt)
        assertTrue(sentRaw != null)
    }

    @Test
    fun malformedSuccessfulSendBecomesUnknownAndIsNotRetryable() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "g_protocol"), "user@gmail.com", "User", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY, GmailApiConnector.SCOPE_SEND),
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))
        val gateway = ConnectorProviderGateway(
            accounts = accounts,
            tokens = tokens,
            senders = listOf(GmailSender("https://gmail.googleapis.com/gmail/v1/users/me")),
            gates = GateEvidenceSource { listOf(GateEvidence(ReleaseGate.SendQualification, "Verified live")) },
            transport = ProviderHttpTransport.of { ProviderHttpResponse(200, "{\"accepted\":true") },
            nowMillis = { 1_700_000_000_000L },
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "t1", route = ActionRoute.Api,
            recipients = listOf("to@example.com"), subject = "Sub", body = "Body text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        var nextId = 0
        val controller = ActionController(
            clock = Clock { UtcInstant(1_700_000_000_000L) },
            digest = digest,
            ids = ActionIdGenerator { "protocol-${++nextId}" },
        )
        val (state, command) = controller.approve(app.mini.hub.action.ActionState(), payload)
        val afterDispatch = controller.dispatch(state, command.id, payload, gateway, null)
        assertEquals(app.mini.hub.action.OutboxState.OutcomeUnknown, afterDispatch.outbox.single().state)
        assertTrue(afterDispatch.outbox.single().failureReason?.contains("protocol") == true)
    }

    @Test
    fun rateLimitPersistsRetryAdmissionAndPreventsPrematureSync() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val store = InMemoryObservationStore()
        val account = ConnectedAccount(
            AccountId(Provider.OUTLOOK, "retry_account"), "user", "User", AccountType.MicrosoftPersonal,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized, setOf("Mail.Read"),
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("token", null))
        var calls = 0
        val connector = object : AccountConnector {
            override val provider = Provider.OUTLOOK
            override val route = ConnectorRoute.OfficialProviderApi
            override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport) =
                ProviderAccountIdentity(Provider.OUTLOOK, "user", "User", AccountType.MicrosoftPersonal)
            override fun sync(account: ConnectedAccount, accessToken: StoredAccessToken, checkpoint: SyncCheckpoint, transport: ProviderHttpTransport, maxMessages: Int): SyncResult {
                calls++
                throw ConnectorHttpException(429, "rate limited", kind = ProviderErrorKind.RateLimited, retryAfterSeconds = 3_600)
            }
        }
        val engine = ConnectorSyncEngine(
            accounts = accounts,
            tokens = tokens,
            store = store,
            digest = digest,
            connectors = mapOf(Provider.OUTLOOK to connector),
            refreshers = emptyMap(),
            transport = ProviderHttpTransport.of { error("transport must not be called") },
        )

        engine.syncAll(1_700_000_000_000L)
        val cursor = store.getCursor(ConnectorSyncEngine.cursorSource(account))
        assertEquals(1, calls)
        assertEquals(1, cursor?.retryAttempt)
        assertTrue(cursor != null && cursor.nextAllowedAtMillis in 1_700_003_600_000L..1_700_003_630_000L)

        val report = engine.syncAll(1_700_000_001_000L).single()
        assertEquals("backoff_active", report.skippedReason)
        assertEquals(1, calls)
    }
}
