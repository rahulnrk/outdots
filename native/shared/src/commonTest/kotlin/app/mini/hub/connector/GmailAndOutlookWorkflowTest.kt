package app.mini.hub.connector

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionIdGenerator
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GmailAndOutlookWorkflowTest {
    private val digest = DigestProvider { "hash-$it" }

    @Test
    fun gmailIncrementalSyncStoresHistoryIdAndFetchesFullThread() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()

        val account = ConnectedAccount(
            id = AccountId(Provider.GMAIL, "g1"),
            providerAccountId = "owner@example.com",
            displayName = "Owner",
            accountType = AccountType.GoogleConsumer,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Authorized,
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("t1", null))

        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/profile") -> ProviderHttpResponse(200, "{\"emailAddress\":\"owner@example.com\",\"historyId\":\"10050\"}")
                req.url.contains("/messages?") -> ProviderHttpResponse(
                    200,
                    "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"}],\"nextPageToken\":null}",
                )
                req.url.contains("/messages/m1?format=metadata") -> ProviderHttpResponse(
                    200,
                    "{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1700000000000\",\"snippet\":\"Preview text\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"Sender <s@example.com>\"},{\"name\":\"Subject\",\"value\":\"Test Subject\"}]}}",
                )
                req.url.contains("/threads/t1?format=full") -> ProviderHttpResponse(
                    200,
                    "{\"id\":\"t1\",\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1700000000000\",\"snippet\":\"Preview text\",\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"SGVsbG8gRnVsbCBDb250ZXh0\"},\"headers\":[{\"name\":\"From\",\"value\":\"Sender <s@example.com>\"},{\"name\":\"Subject\",\"value\":\"Test Subject\"}]}}]}",
                )
                else -> error("Unexpected URL: ${req.url}")
            }
        }

        val connector = GmailApiConnector("https://gmail.test/users/me")
        val incResult = connector.syncIncremental(account, tokens.get(account.id)!!, null, transport, 10)

        assertEquals("10050", incResult.nextHistoryId)
        assertEquals(1, incResult.messages.size)
        assertEquals("m1", incResult.messages.single().providerMessageId)

        val fullThread = connector.fetchThread("t1", tokens.get(account.id)!!, transport, account.displayName)
        assertEquals(1, fullThread.size)
        assertEquals("Hello Full Context", fullThread.single().bodyText)
    }

    @Test
    fun gatewayDispatchesReviewedApiSendForGmailAndOutlook() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()

        val gmailAccount = ConnectedAccount(
            id = AccountId(Provider.GMAIL, "g_live_1"),
            providerAccountId = "owner@gmail.com",
            displayName = "Owner",
            accountType = AccountType.GoogleConsumer,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Healthy,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY, GmailApiConnector.SCOPE_SEND),
        )
        val outlookAccount = ConnectedAccount(
            id = AccountId(Provider.OUTLOOK, "out_live_1"),
            providerAccountId = "owner@outlook.com",
            displayName = "Owner",
            accountType = AccountType.MicrosoftPersonal,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Healthy,
            grantedScopes = setOf("Mail.Read", "Mail.Send"),
        )
        accounts.upsert(gmailAccount)
        accounts.upsert(outlookAccount)
        tokens.put(gmailAccount.id, StoredAccessToken("t_gmail", null))
        tokens.put(outlookAccount.id, StoredAccessToken("t_outlook", null))

        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/messages/send") -> ProviderHttpResponse(200, "{\"id\":\"m_gmail_sent_1\"}")
                req.url.contains("/sendMail") -> ProviderHttpResponse(202, "")
                else -> error("Unexpected URL: ${req.url}")
            }
        }

        val gateway = ConnectorProviderGateway(
            accounts = accounts,
            tokens = tokens,
            senders = listOf(GmailSender("https://gmail.test/users/me"), OutlookSender("https://graph.test/v1.0")),
            gates = GateEvidenceSource { listOf(
                GateEvidence(ReleaseGate.SendQualification, "Reviewed send"),
                GateEvidence(ReleaseGate.MicrosoftAppRegistration, "App registration configured"),
            ) },
            transport = transport,
            nowMillis = { 1_700_000_000_000L },
        )

        var idCount = 0
        val controller = ActionController(
            clock = Clock { UtcInstant(1_700_000_000_000L) },
            digest = digest,
            ids = ActionIdGenerator { "id-${++idCount}" },
        )

        // Dispatch Gmail API send
        val gmailPayload = ExactActionPayload(
            accountId = gmailAccount.id.key, conversationId = "t1", route = ActionRoute.Api,
            recipients = listOf("user@example.com"), subject = "Sub", body = "Body text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        val actionStore = InMemoryActionStore()
        val (s1, c1) = controller.approve(app.mini.hub.action.ActionState(), gmailPayload)
        val res1 = controller.dispatch(s1, c1.id, gmailPayload, gateway, null)
        assertEquals(OutboxState.AcceptedByProvider, res1.outbox.single().state)
        assertEquals("gmail:m_gmail_sent_1", res1.outbox.single().receipt)

        // Dispatch Outlook API send
        val outlookPayload = ExactActionPayload(
            accountId = outlookAccount.id.key, conversationId = "c2", route = ActionRoute.Api,
            recipients = listOf("user@example.com"), subject = "Sub", body = "Body text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1",
        )
        val (s2, c2) = controller.approve(app.mini.hub.action.ActionState(), outlookPayload)
        val res2 = controller.dispatch(s2, c2.id, outlookPayload, gateway, null)
        assertEquals(OutboxState.AcceptedByProvider, res2.outbox.single().state)
        assertEquals("graph-accepted:${c2.id}", res2.outbox.single().receipt)
    }

    @Test
    fun accountIsolationKeepsGmailAndOutlookAccountsSeparate() {
        val accounts = InMemoryConnectedAccountStore()
        val gmail = ConnectedAccount(
            AccountId(Provider.GMAIL, "acc1"), "same@domain.com", "Rahul", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        val outlook = ConnectedAccount(
            AccountId(Provider.OUTLOOK, "acc1"), "same@domain.com", "Rahul", AccountType.MicrosoftPersonal,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        accounts.upsert(gmail)
        accounts.upsert(outlook)

        assertEquals(2, accounts.list().size)
        assertEquals(gmail, accounts.get(AccountId(Provider.GMAIL, "acc1")))
        assertEquals(outlook, accounts.get(AccountId(Provider.OUTLOOK, "acc1")))
    }
}
