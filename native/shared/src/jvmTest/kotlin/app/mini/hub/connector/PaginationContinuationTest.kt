package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginationContinuationTest {

    @Test(timeout = 30_000)
    fun outlookFetchConversationFollowsNextLink() {
        val connector = OutlookGraphConnector("https://graph.test")
        val token = StoredAccessToken("tok", null)

        // Mock 2 pages of messages in conversation
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("messages?\$filter=") -> {
                    ProviderHttpResponse(
                        200,
                        """{
                            "@odata.nextLink": "https://graph.test/me/messages?page=2",
                            "value": [
                                {"id": "msg_1", "conversationId": "conv_1", "subject": "Sub", "receivedDateTime": "2026-10-10T10:00:00Z", "bodyPreview": "First"}
                            ]
                        }""",
                    )
                }
                req.url.contains("page=2") -> {
                    ProviderHttpResponse(
                        200,
                        """{
                            "value": [
                                {"id": "msg_2", "conversationId": "conv_1", "subject": "Sub", "receivedDateTime": "2026-10-10T10:05:00Z", "bodyPreview": "Second"}
                            ]
                        }""",
                    )
                }
                else -> ProviderHttpResponse(404, "{}")
            }
        }

        val messages = connector.fetchConversation("conv_1", token, transport)
        assertEquals(2, messages.size, "fetchConversation must follow @odata.nextLink and retrieve all pages")
        assertEquals(listOf("msg_1", "msg_2"), messages.map { it.providerMessageId })
    }

    @Test(timeout = 30_000)
    fun slackSyncUsesEditedTimestampForRevision() {
        val connector = SlackConnector("https://slack.test")
        val account = ConnectedAccount(
            AccountId(Provider.SLACK, "test"), "U1", "User", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf("im:history"),
        )

        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("conversations.list") -> ProviderHttpResponse(200, "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"is_member\":true}]}")
                req.url.contains("conversations.history") -> {
                    ProviderHttpResponse(
                        200,
                        """{
                            "ok": true,
                            "messages": [
                                {"ts": "1700000000.000100", "text": "Original message", "edited": {"ts": "1700000005.000000"}}
                            ]
                        }""",
                    )
                }
                else -> ProviderHttpResponse(200, "{\"ok\":true}")
            }
        }

        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(1, result.messages.size)
        // Revision must be derived from edited timestamp or counter (> 1)
        assertTrue(result.messages.first().revision >= 2L, "Edited Slack message must have revision >= 2")
    }
}
