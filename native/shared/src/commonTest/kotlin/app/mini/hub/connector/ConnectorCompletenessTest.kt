package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Completeness fixes for official connectors (13 Oct 2026). Fixture-tested only. */
class ConnectorCompletenessTest {
    private fun account(provider: Provider, providerId: String, display: String?, scopes: Set<String> = emptySet()) = ConnectedAccount(
        AccountId(provider, "acc_1"), providerId, display, AccountType.Unknown,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy, scopes,
    )

    @Test
    fun outlookSyncsSentItemsWithDirectionAndSkipsDrafts() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/mailFolders/inbox/") -> ProviderHttpResponse(200, """
                    {"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?deltatoken=i1","value":[
                      {"id":"in1","conversationId":"c1","subject":"Hi","bodyPreview":"p","receivedDateTime":"2026-10-10T10:00:00Z",
                       "from":{"emailAddress":{"name":"Bob","address":"bob@example.com"}}},
                      {"id":"dr1","conversationId":"c1","isDraft":true,"receivedDateTime":"2026-10-10T10:01:00Z",
                       "from":{"emailAddress":{"address":"me@outlook.com"}}}
                    ]}""".trimIndent())
                req.url.contains("/mailFolders/sentitems/") -> ProviderHttpResponse(200, """
                    {"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta?deltatoken=s1","value":[
                      {"id":"out1","conversationId":"c1","subject":"Re: Hi","bodyPreview":"reply","receivedDateTime":"2026-10-10T11:00:00Z",
                       "sentDateTime":"2026-10-10T10:59:00Z","from":{"emailAddress":{"address":"Me@Outlook.com"}},
                       "toRecipients":[{"emailAddress":{"address":"bob@example.com"}}],"ccRecipients":[{"emailAddress":{"address":"c@example.com"}}]}
                    ]}""".trimIndent())
                else -> error("Unexpected ${req.url}")
            }
        }
        val result = OutlookGraphConnector().sync(
            account(Provider.OUTLOOK, "u1", "me@outlook.com"), StoredAccessToken("t", null), SyncCheckpoint(), transport, 50,
        )
        assertEquals(listOf("in1", "out1"), result.messages.map { it.providerMessageId })
        assertEquals(MessageDirection.Inbound, result.messages[0].direction)
        val sent = result.messages[1]
        assertEquals(MessageDirection.Outbound, sent.direction)
        assertEquals(listOf("bob@example.com", "c@example.com"), sent.recipients)
        assertEquals(Rfc3339.parseMillis("2026-10-10T10:59:00Z"), sent.occurredAtMillis)
        val links = OutlookGraphConnector.decodeCursor(result.next.cursor)
        assertTrue(links.getValue("inbox").contains("deltatoken=i1"))
        assertTrue(links.getValue("sentitems").contains("deltatoken=s1"))
    }

    @Test
    fun outlookLegacyBareCursorResumesInboxAndStartsSentItems() {
        val requested = mutableListOf<String>()
        val transport = ProviderHttpTransport.of { req ->
            requested += req.url
            ProviderHttpResponse(200, "{\"value\":[],\"@odata.deltaLink\":\"${req.url.substringBefore('?')}?deltatoken=new\"}")
        }
        val legacy = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?deltatoken=old"
        OutlookGraphConnector().sync(account(Provider.OUTLOOK, "u1", "me@outlook.com"), StoredAccessToken("t", null), SyncCheckpoint(legacy), transport, 50)
        assertEquals(legacy, requested[0])
        assertTrue(requested[1].contains("/mailFolders/sentitems/messages/delta?"))
        assertFalse(requested[1].contains("deltatoken"))
    }

    @Test
    fun outlookMissingSentItemsDoesNotBlockInbox() {
        val transport = ProviderHttpTransport.of { req ->
            if (req.url.contains("/sentitems/")) ProviderHttpResponse(403, "{\"error\":{\"code\":\"ErrorAccessDenied\"}}")
            else ProviderHttpResponse(200, "{\"value\":[{\"id\":\"in1\",\"conversationId\":\"c1\",\"receivedDateTime\":\"2026-10-10T10:00:00Z\"}],\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/x?deltatoken=i\"}")
        }
        val result = OutlookGraphConnector().sync(account(Provider.OUTLOOK, "u1", null), StoredAccessToken("t", null), SyncCheckpoint(), transport, 50)
        assertEquals(1, result.messages.size)
        assertEquals(MessageDirection.Unknown, result.messages.single().direction)
    }

    @Test
    fun xPagesBackToLastSeenEventWithoutGap() {
        var calls = 0
        val transport = ProviderHttpTransport.of { req ->
            calls++
            if (!req.url.contains("pagination_token")) ProviderHttpResponse(200, """
                {"data":[{"id":"5","dm_conversation_id":"c","sender_id":"9","text":"e","created_at":"2026-10-10T10:00:05Z"},
                         {"id":"4","dm_conversation_id":"c","sender_id":"9","text":"d","created_at":"2026-10-10T10:00:04Z"}],
                 "includes":{"users":[{"id":"9","name":"Ann","username":"ann"}]},"meta":{"next_token":"p2"}}""".trimIndent())
            else ProviderHttpResponse(200, """
                {"data":[{"id":"3","dm_conversation_id":"c","sender_id":"1","text":"c","created_at":"2026-10-10T10:00:03Z"},
                         {"id":"2","dm_conversation_id":"c","sender_id":"9","text":"b","created_at":"2026-10-10T10:00:02Z"}]}""".trimIndent())
        }
        val result = XDirectMessageConnector().sync(
            account(Provider.TWITTER, "1", "@me"), StoredAccessToken("t", null),
            SyncCheckpoint(ChatCursor(global = "2").encode(), 0, 0), transport, 2,
        )
        assertEquals(2, calls)
        assertEquals(listOf("5", "4", "3"), result.messages.map { it.providerMessageId })
        assertEquals("Ann (@ann)", result.messages[0].sender)
        assertEquals(MessageDirection.Outbound, result.messages[2].direction)
        assertEquals(0, result.next.gapCount)
        assertEquals("5", ChatCursor.decode(result.next.cursor).global)
    }

    @Test
    fun xRecordsGapWhenLastSeenEventIsBeyondPageBudget() {
        var n = 100
        val transport = ProviderHttpTransport.of {
            n -= 2
            ProviderHttpResponse(200, "{\"data\":[{\"id\":\"$n\",\"dm_conversation_id\":\"c\",\"sender_id\":\"9\",\"text\":\"x\"},{\"id\":\"${n - 1}\",\"dm_conversation_id\":\"c\",\"sender_id\":\"9\",\"text\":\"y\"}],\"meta\":{\"next_token\":\"more\"}}")
        }
        val result = XDirectMessageConnector().sync(
            account(Provider.TWITTER, "1", "@me"), StoredAccessToken("t", null),
            SyncCheckpoint(ChatCursor(global = "1").encode(), 0, 0), transport, 2,
        )
        assertEquals(1, result.next.gapCount)
    }

    @Test
    fun slackResolvesUserNamesOncePerSync() {
        var userInfoCalls = 0
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.endsWith("/conversations.list") -> ProviderHttpResponse(200, "{\"ok\":true,\"channels\":[{\"id\":\"D1\",\"is_im\":true,\"user\":\"U2\"}]}")
                req.url.endsWith("/conversations.history") -> ProviderHttpResponse(200,
                    "{\"ok\":true,\"messages\":[{\"ts\":\"2.0\",\"user\":\"U2\",\"text\":\"b\"},{\"ts\":\"1.0\",\"user\":\"U2\",\"text\":\"a\",\"thread_ts\":\"0.5\"}]}")
                req.url.endsWith("/users.info") -> { userInfoCalls++; ProviderHttpResponse(200, "{\"ok\":true,\"user\":{\"name\":\"bob\",\"profile\":{\"display_name\":\"Bobby\"}}}") }
                else -> error("Unexpected ${req.url}")
            }
        }
        val result = SlackConnector().sync(
            account(Provider.SLACK, "T1:U1", "me", setOf("im:history", "users:read")), StoredAccessToken("t", null), SyncCheckpoint(), transport, 50,
        )
        assertEquals(1, userInfoCalls)
        assertTrue(result.messages.all { it.sender == "Bobby" })
        assertEquals("Bobby", result.messages.first().subject)
        // The threaded message keeps its parent ts as the anchor; ReplyTargets threads into it.
        val threaded = result.messages.first { it.providerMessageId == "D1:1.0" }
        assertEquals("0.5", ReplyTargets.forProvider(Provider.SLACK, threaded.providerMessageId, threaded.replyAnchor))
        val top = result.messages.first { it.providerMessageId == "D1:2.0" }
        assertNull(ReplyTargets.forProvider(Provider.SLACK, top.providerMessageId, top.replyAnchor))
    }

    @Test
    fun slackWithoutUsersReadKeepsIds() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.endsWith("/conversations.list") -> ProviderHttpResponse(200, "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}]}")
                req.url.endsWith("/conversations.history") -> ProviderHttpResponse(200, "{\"ok\":true,\"messages\":[{\"ts\":\"1.0\",\"user\":\"U2\",\"text\":\"a\"}]}")
                else -> error("users.info must not be called without users:read")
            }
        }
        val result = SlackConnector().sync(account(Provider.SLACK, "T1:U1", "me", setOf("channels:history")), StoredAccessToken("t", null), SyncCheckpoint(), transport, 50)
        assertEquals("U2", result.messages.single().sender)
    }

    @Test
    fun slackReaderIncludesThreadRepliesOldestFirst() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.endsWith("/conversations.history") -> ProviderHttpResponse(200,
                    "{\"ok\":true,\"messages\":[{\"ts\":\"3.0\",\"user\":\"U1\",\"text\":\"mine\"},{\"ts\":\"1.0\",\"user\":\"U2\",\"text\":\"parent\",\"thread_ts\":\"1.0\",\"reply_count\":1,\"latest_reply\":\"2.0\"}]}")
                req.url.endsWith("/conversations.replies") -> ProviderHttpResponse(200,
                    "{\"ok\":true,\"messages\":[{\"ts\":\"1.0\",\"user\":\"U2\",\"text\":\"parent\",\"thread_ts\":\"1.0\"},{\"ts\":\"2.0\",\"user\":\"U2\",\"text\":\"reply\",\"thread_ts\":\"1.0\"}]}")
                else -> error("Unexpected ${req.url}")
            }
        }
        val messages = SlackConnector().fetchConversation(account(Provider.SLACK, "T1:U1", "me", setOf("im:history")), "C1", StoredAccessToken("t", null), transport)
        assertEquals(listOf("C1:1.0", "C1:2.0", "C1:3.0"), messages.map { it.providerMessageId })
        assertEquals(MessageDirection.Outbound, messages.last().direction)
        assertEquals("1.0", ReplyTargets.forProvider(Provider.SLACK, "C1:2.0", messages[1].replyAnchor))
    }

    @Test
    fun teamsReaderMarksDirectionAndSkipsSystemEvents() {
        val transport = ProviderHttpTransport.of { req ->
            assertTrue(req.url.contains("/chats/19%3Aabc%40thread.v2/messages"))
            ProviderHttpResponse(200, """
                {"value":[
                  {"id":"2","messageType":"message","createdDateTime":"2026-10-10T10:02:00Z","from":{"user":{"id":"me","displayName":"Me"}},"body":{"contentType":"html","content":"<p>ok</p>"}},
                  {"id":"x","messageType":"systemEventMessage","createdDateTime":"2026-10-10T10:01:30Z"},
                  {"id":"1","messageType":"message","createdDateTime":"2026-10-10T10:01:00Z","from":{"user":{"id":"ann","displayName":"Ann"}},"body":{"contentType":"text","content":"hi"}}
                ]}""".trimIndent())
        }
        val messages = TeamsChatConnector().fetchConversation(account(Provider.TEAMS, "me", "me@corp.com"), "19:abc@thread.v2", StoredAccessToken("t", null), transport)
        assertEquals(listOf("19:abc@thread.v2:1", "19:abc@thread.v2:2"), messages.map { it.providerMessageId })
        assertEquals(MessageDirection.Inbound, messages[0].direction)
        assertEquals(MessageDirection.Outbound, messages[1].direction)
        assertEquals("ok", messages[1].bodyText?.trim())
    }

    @Test
    fun xReaderUsesConversationEndpoint() {
        val transport = ProviderHttpTransport.of { req ->
            assertTrue(req.url.contains("/dm_conversations/1-9/dm_events"))
            ProviderHttpResponse(200, "{\"data\":[{\"id\":\"b\",\"sender_id\":\"1\",\"text\":\"later\",\"created_at\":\"2026-10-10T10:02:00Z\"},{\"id\":\"a\",\"sender_id\":\"9\",\"text\":\"first\",\"created_at\":\"2026-10-10T10:01:00Z\"}]}")
        }
        val messages = XDirectMessageConnector().fetchConversation(account(Provider.TWITTER, "1", "@me"), "1-9", StoredAccessToken("t", null), transport)
        assertEquals(listOf("a", "b"), messages.map { it.providerMessageId })
        assertEquals(MessageDirection.Outbound, messages[1].direction)
    }

    @Test
    fun googleChatMarksOwnMessagesOutbound() {
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/spaces?") -> ProviderHttpResponse(200, "{\"spaces\":[{\"name\":\"spaces/S1\",\"displayName\":\"Team\"}]}")
                req.url.contains("/spaces/S1/messages") -> ProviderHttpResponse(200, """
                    {"messages":[
                      {"name":"spaces/S1/messages/M1","text":"mine","createTime":"2026-10-10T10:00:00Z","sender":{"name":"users/111"},"thread":{"name":"spaces/S1/threads/T1"}},
                      {"name":"spaces/S1/messages/M2","text":"theirs","createTime":"2026-10-10T10:01:00Z","sender":{"name":"users/222","displayName":"Ann"}}
                    ]}""".trimIndent())
                else -> error("Unexpected ${req.url}")
            }
        }
        val result = GoogleChatConnector().sync(account(Provider.GOOGLE_CHAT, "111", "me@x.com"), StoredAccessToken("t", null), SyncCheckpoint(), transport, 50)
        val byId = result.messages.associateBy { it.providerMessageId }
        assertEquals(MessageDirection.Outbound, byId.getValue("spaces/S1/messages/M1").direction)
        assertEquals(MessageDirection.Inbound, byId.getValue("spaces/S1/messages/M2").direction)
        assertEquals("spaces/S1/threads/T1", ReplyTargets.forProvider(Provider.GOOGLE_CHAT, "spaces/S1/messages/M1", byId.getValue("spaces/S1/messages/M1").replyAnchor))
    }
}
