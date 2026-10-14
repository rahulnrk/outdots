package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GmailApiConnectorTest {
    @Test
    fun discoversAccountAndSyncsBoundedMessagesWithCursor() {
        val requested = mutableListOf<String>()
        val transport = ProviderHttpTransport { url, headers ->
            requested += url
            assertEquals("Bearer access-token", headers["Authorization"])
            when {
                url.endsWith("/profile") -> ProviderHttpResponse(200, "{\"emailAddress\":\"owner@example.com\"}")
                url.contains("/messages?") -> ProviderHttpResponse(
                    200,
                    "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"}],\"nextPageToken\":\"next-1\"}",
                )
                url.contains("/messages/m1") -> ProviderHttpResponse(
                    200,
                    "{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1700000000000\",\"snippet\":\"Hello\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"Sender <sender@example.com>\"},{\"name\":\"Subject\",\"value\":\"Subject\"}]}}",
                )
                else -> error("Unexpected URL: $url")
            }
        }
        val connector = GmailApiConnector("https://gmail.test/users/me")
        val token = StoredAccessToken("access-token", null)
        val identity = connector.discoverAccount(token, transport)
        val account = ConnectedAccount(
            id = AccountId(Provider.GMAIL, "local-a"),
            providerAccountId = identity.providerAccountId,
            displayName = identity.displayName,
            accountType = identity.accountType,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Authorized,
        )
        val result = connector.sync(account, token, SyncCheckpoint(), transport, maxMessages = 10)

        assertEquals("owner@example.com", identity.providerAccountId)
        assertEquals(1, result.messages.size)
        assertEquals("t1", result.messages.single().conversationId)
        assertEquals("next-1", result.next.cursor)
        assertEquals(1, result.next.processedCount)
        assertTrue(requested.any { it.contains("maxResults=10") })
    }

    @Test
    fun prettyPrintedListReturnsEveryMessage() {
        val list = """
            {
              "messages": [
                {
                  "id": "m1",
                  "threadId": "t1"
                },
                {
                  "id": "m2",
                  "threadId": "t1"
                },
                {
                  "id": "m3",
                  "threadId": "t3"
                }
              ],
              "resultSizeEstimate": 3
            }
        """.trimIndent()
        val transport = ProviderHttpTransport { url, _ ->
            when {
                url.contains("/messages?") -> ProviderHttpResponse(200, list)
                url.contains("/messages/") -> {
                    val id = url.substringAfter("/messages/").substringBefore("?")
                    ProviderHttpResponse(
                        200,
                        "{\n  \"id\": \"$id\",\n  \"snippet\": \"s\",\n  \"payload\": {\n    \"headers\": [\n      {\n        \"name\": \"Subject\",\n        \"value\": \"x\"\n      },\n      {\n        \"name\": \"From\",\n        \"value\": \"f\"\n      }\n    ]\n  }\n}",
                    )
                }
                else -> error("Unexpected URL")
            }
        }
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "local-a"), "owner@example.com", "Owner", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized,
        )
        val result = GmailApiConnector("https://gmail.test/users/me")
            .sync(account, StoredAccessToken("t", null), SyncCheckpoint(), transport, maxMessages = 10)
        assertEquals(listOf("m1", "m2", "m3"), result.messages.map { it.providerMessageId })
        assertEquals(listOf("t1", "t1", "t3"), result.messages.map { it.conversationId })
        assertEquals("f", result.messages.first().sender)
    }

    @Test
    fun knownMessagesSkipMetadataRequests() {
        val requested = mutableListOf<String>()
        val transport = ProviderHttpTransport { url, _ ->
            requested += url
            when {
                url.contains("/messages?") -> ProviderHttpResponse(
                    200,
                    "{\"messages\":[{\"id\":\"old1\",\"threadId\":\"t\"},{\"id\":\"new1\",\"threadId\":\"t\"},{\"id\":\"old2\",\"threadId\":\"t\"}]}",
                )
                url.contains("/messages/new1") -> ProviderHttpResponse(200, "{\"id\":\"new1\",\"snippet\":\"s\"}")
                else -> error("Unexpected URL: $url")
            }
        }
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "local-a"), "owner@example.com", "Owner", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized,
        )
        val result = GmailApiConnector("https://gmail.test/users/me").sync(
            account, StoredAccessToken("t", null), SyncCheckpoint(), transport, 10,
        ) { it.startsWith("old") }
        assertEquals(listOf("new1"), result.messages.map { it.providerMessageId })
        assertEquals(3, result.listedCount)
        assertEquals(2, requested.size)
    }

    @Test
    fun authorizationAndTransientFailuresAreExplicit() {
        val connector = GmailApiConnector("https://gmail.test/users/me")
        val token = StoredAccessToken("access-token", null)
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "local-a"), "owner@example.com", "Owner", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized,
        )
        assertEquals(401, assertFailsWith<ConnectorHttpException> {
            connector.sync(account, token, SyncCheckpoint(), ProviderHttpTransport { _, _ -> ProviderHttpResponse(401, "") })
        }.statusCode)
        assertEquals(429, assertFailsWith<ConnectorHttpException> {
            connector.sync(account, token, SyncCheckpoint(), ProviderHttpTransport { _, _ -> ProviderHttpResponse(429, "") })
        }.statusCode)
    }

    @Test
    fun accountStoreAndTokensCannotCrossReadAccounts() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val a = AccountId(Provider.GMAIL, "a")
        val b = AccountId(Provider.GMAIL, "b")
        val record = ConnectedAccount(a, "a@example.com", "A", AccountType.GoogleConsumer, ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized)
        accounts.upsert(record)
        tokens.put(a, StoredAccessToken("a-token", null))

        assertEquals(record, accounts.get(a))
        assertEquals(null, accounts.get(b))
        assertEquals("a-token", tokens.get(a)?.value)
        assertEquals(null, tokens.get(b))
        accounts.remove(a)
        tokens.remove(a)
        assertEquals(null, accounts.get(a))
        assertEquals(null, tokens.get(a))
    }

    @Test
    fun expiredTokenIsRecognizedBeforeNetworkUse() {
        assertTrue(StoredAccessToken("expired", 99L).isExpired(100L))
        assertEquals(false, StoredAccessToken("live", 101L).isExpired(100L))
        assertEquals(false, StoredAccessToken("non-expiring", null).isExpired(Long.MAX_VALUE))
    }

    @Test
    fun reconnectPreservesAccountIdAndDisconnectsOnlyItsCredential() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        var next = 0
        val manager = AccountConnectionManager(accounts, tokens, IdGenerator { "local-${++next}" })
        val identity = ProviderAccountIdentity(Provider.GMAIL, "owner@example.com", "Owner", AccountType.GoogleConsumer)
        val first = manager.attach(identity, ConnectorRoute.OfficialProviderApi, setOf("gmail.readonly"))
        tokens.put(first.id, StoredAccessToken("first", null, "refresh-first"))
        manager.markHealth(first.id, ConnectionState.Expired, "Reconnect")

        val reconnected = manager.attach(identity, ConnectorRoute.OfficialProviderApi, setOf("gmail.readonly"))
        assertEquals(first.id, reconnected.id)
        assertEquals(ConnectionState.Authorized, reconnected.state)
        assertEquals(null, reconnected.userActionRequired)
        assertEquals(true, manager.disconnect(reconnected.id))
        assertEquals(ConnectionState.Disconnected, accounts.get(first.id)?.state)
        assertEquals(null, tokens.get(first.id))

        // Reconnecting after a disconnect must reuse the same local ID so history stays attached.
        val afterDisconnect = manager.attach(identity, ConnectorRoute.OfficialProviderApi, setOf("gmail.readonly"))
        assertEquals(first.id, afterDisconnect.id)
        assertEquals(ConnectionState.Authorized, afterDisconnect.state)

        assertEquals(true, manager.forget(first.id))
        assertEquals(null, accounts.get(first.id))
    }

    @Test
    fun providerMessageObservationIdIsStableForDuplicateDelivery() {
        val factory = ConnectorObservationFactory(DigestProvider { "digest" })
        val account = AccountId(Provider.GMAIL, "local-a")
        val message = ProviderMessage("m1", "t1", "Sender", "Subject", "Hello", 1_700_000_000_000L)
        val first = factory.fromMessage(account, message, 1_700_000_001_000L)
        val duplicate = factory.fromMessage(account, message, 1_700_000_002_000L)
        val otherAccount = factory.fromMessage(AccountId(Provider.GMAIL, "local-b"), message, 1_700_000_002_000L)
        assertEquals(first.id, duplicate.id)
        assertEquals(first.notificationKey, duplicate.notificationKey)
        assertTrue(first.id != otherAccount.id)
        assertTrue(first.notificationKey != otherAccount.notificationKey)
    }

    /** Fake Gmail history: record ids are numeric; [pageSize] records per history page. */
    private class FakeHistory(val records: List<Pair<Long, String>>, val pageSize: Int, val latest: Long) {
        var historyCalls = 0
        val transport = ProviderHttpTransport { url, _ ->
            when {
                url.contains("/history?") -> {
                    historyCalls++
                    val start = url.substringAfter("startHistoryId=").substringBefore("&").toLong()
                    val offset = url.substringAfter("pageToken=", "0").substringBefore("&").toInt()
                    val after = records.filter { it.first > start }
                    val slice = after.drop(offset).take(pageSize)
                    val body = slice.joinToString(",") { (id, change) ->
                        val (kind, msg) = change.split(":")
                        val key = if (kind == "add") "messagesAdded" else "messagesDeleted"
                        "{\"id\":\"$id\",\"$key\":[{\"message\":{\"id\":\"$msg\",\"threadId\":\"t-$msg\"}}]}"
                    }
                    val next = if (offset + pageSize < after.size) ",\"nextPageToken\":\"${offset + pageSize}\"" else ""
                    ProviderHttpResponse(200, "{\"history\":[$body],\"historyId\":\"$latest\"$next}")
                }
                url.contains("/messages/") -> {
                    val id = url.substringAfter("/messages/").substringBefore("?")
                    ProviderHttpResponse(200, "{\"id\":\"$id\",\"snippet\":\"s\"}")
                }
                else -> error("Unexpected URL")
            }
        }
    }

    private class Drained(val added: List<String>, val deleted: List<String>, val passes: Int)

    private fun drain(fake: FakeHistory, start: String, maxMessages: Int, preKnown: Set<String> = emptySet()): Drained {
        val connector = GmailApiConnector("https://gmail.test/users/me")
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "local-a"), "owner@example.com", "Owner", AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi, ConnectionState.Authorized,
        )
        val known = preKnown.toMutableSet()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var cursor = start
        var passes = 0
        while (true) {
            passes++
            assertTrue(passes <= 20, "sync did not terminate; cursor stuck at $cursor")
            val r = connector.syncIncremental(account, StoredAccessToken("t", null), cursor, fake.transport, maxMessages) { it in known }
            r.messages.forEach { added += it.providerMessageId; known += it.providerMessageId }
            r.deleted.forEach { deleted += it.providerMessageId; known -= it.providerMessageId }
            val next = assertNotNull(r.nextHistoryId)
            if (!r.moreAvailable) break
            assertTrue(next.toLong() > cursor.toLong(), "cursor must advance on moreAvailable: $cursor -> $next")
            cursor = next
        }
        return Drained(added, deleted, passes)
    }

    @Test
    fun incrementalReturnsAll120MessagesExactlyOnceAcrossPasses() {
        val records = (1..120).map { (1000L + it) to "add:m$it" }
        val result = drain(FakeHistory(records, pageSize = 25, latest = 2000), "1000", maxMessages = 10)
        assertEquals((1..120).map { "m$it" }, result.added)
        assertTrue(result.passes in 12..13)
    }

    @Test
    fun incrementalCursorAdvancesWhenPassIsCutAcrossManyPages() {
        // Small pages exhaust MAX_HISTORY_PAGES before maxMessages; already-known messages must not stall it.
        val records = (1..120).map { (1000L + it) to "add:m$it" }
        val result = drain(FakeHistory(records, pageSize = 3, latest = 2000), "1000", maxMessages = 50, preKnown = setOf("m1", "m2", "m3"))
        assertEquals((4..120).map { "m$it" }, result.added)
    }

    @Test
    fun incrementalMidPageCutoffSkipsNothingAndHonoursDeletions() {
        val records = (1..30).map { (1000L + it) to "add:m$it" } +
            listOf(1031L to "del:m5", 1032L to "del:m28", 1033L to "add:m31")
        val result = drain(FakeHistory(records, pageSize = 33, latest = 2000), "1000", maxMessages = 7)
        assertEquals((1..31).map { "m$it" }.toSet(), result.added.toSet())
        assertEquals(result.added.size, result.added.toSet().size)
        assertEquals(listOf("m5", "m28"), result.deleted)
    }

    @Test
    fun disconnectPreservesLocalProviderHistory() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val manager = AccountConnectionManager(accounts, tokens, IdGenerator { "local-account" })
        val account = manager.attach(
            ProviderAccountIdentity(Provider.GMAIL, "owner@example.com", "Owner", AccountType.GoogleConsumer),
            ConnectorRoute.OfficialProviderApi,
            setOf("gmail.readonly"),
        )
        val observation = ConnectorObservationFactory(DigestProvider { "digest" }).fromMessage(
            account.id,
            ProviderMessage("m1", "t1", "Sender", "Subject", "Hello", 1_700_000_000_000L),
            1_700_000_001_000L,
        )
        val history = InMemoryObservationStore()
        assertTrue(history.persistObservation(observation).isSuccess)

        assertTrue(manager.disconnect(account.id))
        assertNotNull(history.getObservation(observation.id))
    }

    @Test
    fun gmailAttachmentRefParsesScopedAndLegacyAndRejectsAccountMismatch() {
        val work = AccountId(Provider.GMAIL, "work")
        val home = AccountId(Provider.GMAIL, "home")
        val scoped = GmailAttachmentRef.parse("gmail|work|msg1/att1|quote.pdf")!!
        assertEquals(work, scoped.accountId)
        assertEquals("msg1", scoped.messageId)
        assertEquals("att1", scoped.attachmentId)
        assertEquals("quote.pdf", scoped.fileName)
        assertEquals(work, GmailAttachmentRef.resolveAccount(work, scoped))
        assertEquals(null, GmailAttachmentRef.resolveAccount(home, scoped))
        val legacy = GmailAttachmentRef.parse("msg1/att1|quote.pdf", work)!!
        assertEquals(work, legacy.accountId)
        assertTrue(GmailAttachmentRef.cachePartition(work) != GmailAttachmentRef.cachePartition(home))
    }
}
