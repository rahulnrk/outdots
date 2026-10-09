package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
