package app.mini.hub.connector

import app.mini.hub.action.AttachmentDigest
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.ActionRoute
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlookGraphConnectorTest {
    @Test
    fun syncsMessagesViaDeltaAndStoresDeltaLink() {
        val requested = mutableListOf<String>()
        val transport = ProviderHttpTransport.of { req ->
            requested += req.url
            assertEquals("Bearer token-123", req.headers["Authorization"])
            when {
                req.url.contains("/me?") -> ProviderHttpResponse(200, "{\"id\":\"usr_1\",\"mail\":\"user@outlook.com\"}")
                req.url.contains("/sentitems/") -> ProviderHttpResponse(200, "{\"value\":[],\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta?deltatoken=s1\"}")
                req.url.contains("/delta") -> ProviderHttpResponse(
                    200,
                    """
                    {
                      "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?deltatoken=d1",
                      "value": [
                        {
                          "id": "msg1",
                          "conversationId": "conv1",
                          "subject": "Hello",
                          "bodyPreview": "Snippet",
                          "receivedDateTime": "2026-10-10T10:00:00Z",
                          "from": { "emailAddress": { "name": "Sender", "address": "s@example.com" } }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> error("Unexpected URL: ${req.url}")
            }
        }
        val connector = OutlookGraphConnector("https://graph.microsoft.com/v1.0")
        val token = StoredAccessToken("token-123", null)
        val identity = connector.discoverAccount(token, transport)
        assertEquals("usr_1", identity.providerAccountId)
        assertEquals("user@outlook.com", identity.displayName)

        val account = ConnectedAccount(
            id = AccountId(Provider.OUTLOOK, "out_1"),
            providerAccountId = identity.providerAccountId,
            displayName = identity.displayName,
            accountType = AccountType.MicrosoftPersonal,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Authorized,
        )
        val result = connector.sync(account, token, SyncCheckpoint(), transport, 10)
        assertEquals(1, result.messages.size)
        assertEquals("msg1", result.messages.single().providerMessageId)
        assertTrue(result.next.cursor?.contains("deltatoken=d1") == true)
    }

    @Test
    fun senderPostsToGraphSendMail() {
        var postedJson: String? = null
        val transport = ProviderHttpTransport.of { req ->
            if (req.url.contains("/sendMail")) {
                postedJson = req.body
                ProviderHttpResponse(202, "")
            } else error("Unexpected URL: ${req.url}")
        }
        val sender = OutlookSender("https://graph.microsoft.com/v1.0")
        val account = ConnectedAccount(
            id = AccountId(Provider.OUTLOOK, "out_1"),
            providerAccountId = "usr_1",
            displayName = "User",
            accountType = AccountType.MicrosoftPersonal,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Healthy,
        )
        val payload = ExactActionPayload(
            accountId = account.id.key,
            conversationId = "conv1",
            route = ActionRoute.Api,
            recipients = listOf("recipient@example.com"),
            subject = "Subject",
            body = "Body text",
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = "v1",
            policyVersion = "v1",
        )
        val outcome = sender.send(account, StoredAccessToken("t", null), payload, "cmd_123", transport)
        assertTrue(outcome is SendOutcome.Accepted)
        assertEquals("graph-accepted:cmd_123", outcome.receipt)
        assertTrue(postedJson?.contains("recipient@example.com") == true)
        assertTrue(postedJson?.contains("Body text") == true)
    }
}
