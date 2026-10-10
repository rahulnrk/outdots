package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.ActionRoute
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatConnectorsTest {
    @Test
    fun slackDiscoversAccountAndSyncsConversations() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.endsWith("/auth.test") -> ProviderHttpResponse(200, "{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"U1\",\"user\":\"alice\",\"team\":\"Acme\"}")
                req.url.endsWith("/conversations.list") -> ProviderHttpResponse(200, "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\",\"is_member\":true}]}")
                req.url.endsWith("/conversations.history") -> ProviderHttpResponse(
                    200,
                    "{\"ok\":true,\"messages\":[{\"ts\":\"1700000000.000100\",\"user\":\"U2\",\"text\":\"Hello Slack\"}]}",
                )
                else -> error("Unexpected URL: ${req.url}")
            }
        }
        val connector = SlackConnector("https://slack.com/api")
        val token = StoredAccessToken("xoxp-user-token", null)
        val identity = connector.discoverAccount(token, transport)
        assertEquals("T1:U1", identity.providerAccountId)
        assertEquals("alice @ Acme", identity.displayName)

        val account = ConnectedAccount(
            id = AccountId(Provider.SLACK, "slk_1"),
            providerAccountId = identity.providerAccountId,
            displayName = identity.displayName,
            accountType = AccountType.Workspace,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Authorized,
            grantedScopes = setOf("channels:history", "chat:write"),
        )
        val result = connector.sync(account, token, SyncCheckpoint(), transport, 10)
        assertEquals(1, result.messages.size)
        val m = result.messages.single()
        assertEquals("C1:1700000000.000100", m.providerMessageId)
        assertEquals("Hello Slack", m.snippet)
        assertEquals(MessageDirection.Inbound, m.direction)
    }

    @Test
    fun slackSenderPostsMessage() {
        var formBody: String? = null
        val transport = ProviderHttpTransport.of { req ->
            formBody = req.body
            ProviderHttpResponse(200, "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"1700000001.000200\"}")
        }
        val sender = SlackSender("https://slack.com/api")
        val account = ConnectedAccount(
            AccountId(Provider.SLACK, "slk_1"), "T1:U1", "alice", AccountType.Workspace,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "C1", route = ActionRoute.Api,
            recipients = listOf("U2"), subject = null, body = "Reply text", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1", replyTarget = "1700000000.000100",
        )
        val outcome = sender.send(account, StoredAccessToken("t", null), payload, "cmd_1", transport)
        assertTrue(outcome is SendOutcome.Accepted)
        assertEquals("slack:C1:1700000001.000200", outcome.receipt)
        assertTrue(formBody?.contains("thread_ts=1700000000.000100") == true)
    }

    @Test
    fun telegramBotLongPollsAndPostsReply() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.endsWith("/getMe") -> ProviderHttpResponse(200, "{\"ok\":true,\"result\":{\"id\":999,\"username\":\"minibot\"}}")
                req.url.endsWith("/getUpdates") -> ProviderHttpResponse(
                    200,
                    """
                    {
                      "ok": true,
                      "result": [
                        {
                          "update_id": 100,
                          "message": {
                            "message_id": 42,
                            "date": 1700000000,
                            "chat": { "id": 12345, "title": "Group" },
                            "from": { "id": 555, "username": "bob" },
                            "text": "Hi bot"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                req.url.endsWith("/sendMessage") -> ProviderHttpResponse(
                    200,
                    "{\"ok\":true,\"result\":{\"message_id\":43}}",
                )
                else -> error("Unexpected URL: ${req.url}")
            }
        }
        val connector = TelegramBotConnector("https://api.telegram.org")
        val token = StoredAccessToken("12345:ABCDEF1234567890abcdef1234567890", null)
        val identity = connector.discoverAccount(token, transport)
        assertEquals("bot:999", identity.providerAccountId)

        val account = ConnectedAccount(
            AccountId(Provider.TELEGRAM, "tg_1"), identity.providerAccountId, "@minibot (bot)", AccountType.Unknown,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized,
        )
        val result = connector.sync(account, token, SyncCheckpoint(), transport, 10)
        assertEquals(1, result.messages.size)
        assertEquals("12345:42", result.messages.single().providerMessageId)
        val cursor = ChatCursor.decode(result.next.cursor)
        assertEquals("101", cursor.global)

        val sender = TelegramBotSender("https://api.telegram.org")
        val payload = ExactActionPayload(
            accountId = account.id.key, conversationId = "12345", route = ActionRoute.Api,
            recipients = emptyList(), subject = null, body = "Bot reply", attachments = emptyList(),
            sourceVersions = emptyList(), grantVersion = "v1", policyVersion = "v1", replyTarget = "12345:42",
        )
        val outcome = sender.send(account, token, payload, "cmd_tg", transport)
        assertTrue(outcome is SendOutcome.Accepted)
        assertEquals("telegram:12345:43", outcome.receipt)
    }
}
