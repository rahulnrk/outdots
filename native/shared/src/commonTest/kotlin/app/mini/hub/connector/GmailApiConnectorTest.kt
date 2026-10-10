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
}
