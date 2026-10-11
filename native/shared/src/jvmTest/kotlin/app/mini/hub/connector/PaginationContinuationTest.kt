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
        val firstEdit = result.messages.first().revision
        val secondTransport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("conversations.list") -> ProviderHttpResponse(200, "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"is_member\":true}]}")
                req.url.contains("conversations.history") -> ProviderHttpResponse(
                    200,
                    """{"ok":true,"messages":[{"ts":"1700000000.000100","text":"Second edit","edited":{"ts":"1700000009.000000"}}]}""",
                )
                else -> ProviderHttpResponse(200, "{\"ok\":true}")
            }
        }
        val second = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), secondTransport, 50)
        assertTrue(second.messages.first().revision > firstEdit, "A later Slack edit must have a higher revision than the first edit")
    }

    @Test(timeout = 30_000)
    fun slackHistoryFollowsHasMoreCursorAndDoesNotSkipOlderNewMessages() {
        val connector = SlackConnector("https://slack.test")
        val account = ConnectedAccount(
            AccountId(Provider.SLACK, "test"), "U1", "User", AccountType.Workspace,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf("im:history"),
        )
        var historyCalls = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("conversations.list") ->
                    ProviderHttpResponse(200, """{"ok":true,"channels":[{"id":"C1","is_member":true,"updated":"2"}]}""")
                req.url.contains("conversations.history") -> {
                    historyCalls++
                    val cursor = req.body.orEmpty().contains("cursor=")
                    if (!cursor) {
                        ProviderHttpResponse(
                            200,
                            """{"ok":true,"has_more":true,"response_metadata":{"next_cursor":"page2"},
                                "messages":[{"ts":"1700000003.000000","user":"U2","text":"newest"}]}""",
                        )
                    } else {
                        ProviderHttpResponse(
                            200,
                            """{"ok":true,"has_more":false,
                                "messages":[{"ts":"1700000002.000000","user":"U2","text":"older-new"}]}""",
                        )
                    }
                }
                else -> ProviderHttpResponse(200, """{"ok":true}""")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, historyCalls, "conversations.history must follow has_more/next_cursor")
        assertEquals(setOf("C1:1700000003.000000", "C1:1700000002.000000"), result.messages.map { it.providerMessageId }.toSet())
        assertEquals("1700000003.000000", ChatCursor.decode(result.next.cursor).perConversation["C1"])
        assertEquals(0, result.next.gapCount)
    }

    @Test(timeout = 30_000)
    fun teamsChatMessagesFollowNextLink() {
        val connector = TeamsChatConnector("https://graph.test")
        val account = ConnectedAccount(
            AccountId(Provider.TEAMS, "test"), "user-1", "User", AccountType.MicrosoftWorkSchool,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        var messagePages = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/me/chats?") -> ProviderHttpResponse(
                    200,
                    """{"value":[{"id":"chat1","topic":"Standup","lastMessagePreview":{"createdDateTime":"2026-10-11T12:00:00Z"}}]}""",
                )
                req.url.contains("/chats/chat1/messages") && !req.url.contains("page=2") -> {
                    messagePages++
                    ProviderHttpResponse(
                        200,
                        """{"@odata.nextLink":"https://graph.test/chats/chat1/messages?page=2",
                            "value":[{"id":"m1","messageType":"message","createdDateTime":"2026-10-11T12:00:00Z",
                            "body":{"contentType":"text","content":"first"},"from":{"user":{"id":"other","displayName":"A"}}}]}""",
                    )
                }
                req.url.contains("page=2") -> {
                    messagePages++
                    ProviderHttpResponse(
                        200,
                        """{"value":[{"id":"m2","messageType":"message","createdDateTime":"2026-10-11T11:00:00Z",
                            "body":{"contentType":"text","content":"second"},"from":{"user":{"id":"other","displayName":"A"}}}]}""",
                    )
                }
                else -> ProviderHttpResponse(404, "{}")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, messagePages, "Teams chat messages must follow @odata.nextLink")
        assertEquals(setOf("chat1:m1", "chat1:m2"), result.messages.map { it.providerMessageId }.toSet())
        assertEquals(0, result.next.gapCount)
    }

    @Test(timeout = 30_000)
    fun googleChatMessagesFollowNextPageToken() {
        val connector = GoogleChatConnector("https://chat.test")
        val account = ConnectedAccount(
            AccountId(Provider.GOOGLE_CHAT, "test"), "sub-1", "User", AccountType.Workspace,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        var messagePages = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/spaces?") -> ProviderHttpResponse(
                    200,
                    """{"spaces":[{"name":"spaces/s1","displayName":"Eng","lastActiveTime":"2026-10-11T12:00:00Z"}]}""",
                )
                req.url.contains("/spaces/s1/messages") && !req.url.contains("pageToken=") -> {
                    messagePages++
                    ProviderHttpResponse(
                        200,
                        """{"nextPageToken":"p2","messages":[{"name":"spaces/s1/messages/m1","text":"newest",
                            "createTime":"2026-10-11T12:00:00Z","sender":{"displayName":"A"}}]}""",
                    )
                }
                req.url.contains("pageToken=p2") -> {
                    messagePages++
                    ProviderHttpResponse(
                        200,
                        """{"messages":[{"name":"spaces/s1/messages/m2","text":"older-new",
                            "createTime":"2026-10-11T11:00:00Z","sender":{"displayName":"A"}}]}""",
                    )
                }
                else -> ProviderHttpResponse(404, "{}")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, messagePages, "Google Chat messages must follow nextPageToken")
        assertEquals(setOf("spaces/s1/messages/m1", "spaces/s1/messages/m2"), result.messages.map { it.providerMessageId }.toSet())
        assertEquals(0, result.next.gapCount)
    }

    @Test(timeout = 30_000)
    fun slackConversationListFollowsCursor() {
        val connector = SlackConnector("https://slack.test")
        val account = ConnectedAccount(
            AccountId(Provider.SLACK, "test"), "U1", "User", AccountType.Workspace,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
            grantedScopes = setOf("im:history"),
        )
        var listCalls = 0
        val historyChannels = mutableSetOf<String>()
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("conversations.list") -> {
                    listCalls++
                    if (!req.body.orEmpty().contains("cursor=")) {
                        ProviderHttpResponse(
                            200,
                            """{"ok":true,"response_metadata":{"next_cursor":"list2"},
                                "channels":[{"id":"C1","is_member":true,"updated":"300"}]}""",
                        )
                    } else {
                        ProviderHttpResponse(
                            200,
                            """{"ok":true,"channels":[{"id":"C2","is_member":true,"updated":"200"}]}""",
                        )
                    }
                }
                req.url.contains("conversations.history") -> {
                    val channel = req.body.orEmpty().substringAfter("channel=").substringBefore('&').substringBefore('=')
                    val id = when {
                        req.body.orEmpty().contains("channel=C1") -> "C1"
                        req.body.orEmpty().contains("channel=C2") -> "C2"
                        else -> "C?"
                    }
                    historyChannels += id
                    ProviderHttpResponse(200, """{"ok":true,"messages":[{"ts":"1700000001.000000","user":"U2","text":"$id"}]}""")
                }
                else -> ProviderHttpResponse(200, """{"ok":true}""")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, listCalls, "conversations.list must follow next_cursor")
        assertEquals(setOf("C1", "C2"), historyChannels)
        assertEquals(setOf("C1:1700000001.000000", "C2:1700000001.000000"), result.messages.map { it.providerMessageId }.toSet())
    }

    @Test(timeout = 30_000)
    fun teamsChatListFollowsNextLink() {
        val connector = TeamsChatConnector("https://graph.test")
        val account = ConnectedAccount(
            AccountId(Provider.TEAMS, "test"), "user-1", "User", AccountType.MicrosoftWorkSchool,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        var chatListPages = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/me/chats") && !req.url.contains("page=2") -> {
                    chatListPages++
                    ProviderHttpResponse(
                        200,
                        """{"@odata.nextLink":"https://graph.test/me/chats?page=2",
                            "value":[{"id":"chat1","topic":"A","lastMessagePreview":{"createdDateTime":"2026-10-11T12:00:00Z"}}]}""",
                    )
                }
                req.url.contains("/me/chats?page=2") -> {
                    chatListPages++
                    ProviderHttpResponse(
                        200,
                        """{"value":[{"id":"chat2","topic":"B","lastMessagePreview":{"createdDateTime":"2026-10-11T11:00:00Z"}}]}""",
                    )
                }
                req.url.contains("/chats/chat1/messages") -> ProviderHttpResponse(
                    200,
                    """{"value":[{"id":"m1","messageType":"message","createdDateTime":"2026-10-11T12:00:00Z",
                        "body":{"contentType":"text","content":"a"},"from":{"user":{"id":"other","displayName":"A"}}}]}""",
                )
                req.url.contains("/chats/chat2/messages") -> ProviderHttpResponse(
                    200,
                    """{"value":[{"id":"m2","messageType":"message","createdDateTime":"2026-10-11T11:00:00Z",
                        "body":{"contentType":"text","content":"b"},"from":{"user":{"id":"other","displayName":"A"}}}]}""",
                )
                else -> ProviderHttpResponse(404, "{}")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, chatListPages, "Teams /me/chats must follow @odata.nextLink")
        assertEquals(setOf("chat1:m1", "chat2:m2"), result.messages.map { it.providerMessageId }.toSet())
    }

    @Test(timeout = 30_000)
    fun googleChatSpaceListFollowsNextPageToken() {
        val connector = GoogleChatConnector("https://chat.test")
        val account = ConnectedAccount(
            AccountId(Provider.GOOGLE_CHAT, "test"), "sub-1", "User", AccountType.Workspace,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
        )
        var spacePages = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/spaces?") && !req.url.contains("pageToken=") -> {
                    spacePages++
                    ProviderHttpResponse(
                        200,
                        """{"nextPageToken":"s2","spaces":[{"name":"spaces/s1","displayName":"One","lastActiveTime":"2026-10-11T12:00:00Z"}]}""",
                    )
                }
                req.url.contains("/spaces?") && req.url.contains("pageToken=s2") -> {
                    spacePages++
                    ProviderHttpResponse(
                        200,
                        """{"spaces":[{"name":"spaces/s2","displayName":"Two","lastActiveTime":"2026-10-11T11:00:00Z"}]}""",
                    )
                }
                req.url.contains("/spaces/s1/messages") -> ProviderHttpResponse(
                    200,
                    """{"messages":[{"name":"spaces/s1/messages/m1","text":"one","createTime":"2026-10-11T12:00:00Z","sender":{"displayName":"A"}}]}""",
                )
                req.url.contains("/spaces/s2/messages") -> ProviderHttpResponse(
                    200,
                    """{"messages":[{"name":"spaces/s2/messages/m2","text":"two","createTime":"2026-10-11T11:00:00Z","sender":{"displayName":"A"}}]}""",
                )
                else -> ProviderHttpResponse(404, "{}")
            }
        }
        val result = connector.sync(account, StoredAccessToken("tok", null), SyncCheckpoint(), transport, 50)
        assertEquals(2, spacePages, "Google Chat spaces must follow nextPageToken")
        assertEquals(setOf("spaces/s1/messages/m1", "spaces/s2/messages/m2"), result.messages.map { it.providerMessageId }.toSet())
    }
}
