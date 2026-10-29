package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.*

class GmailFaultInjectionAndReliabilityTest {
    @Test
    fun syncCheckpointIsNotAdvancedOnMalformedOrFailedResponse() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val account = ConnectedAccount(
            id = AccountId(Provider.GMAIL, "g-fault"),
            providerAccountId = "fault@example.com",
            displayName = "Fault User",
            accountType = AccountType.GoogleConsumer,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Authorized,
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("token-ok", null))

        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/profile") -> ProviderHttpResponse(200, "{\"emailAddress\":\"fault@example.com\",\"historyId\":\"20000\"}")
                req.url.contains("/messages?") -> ProviderHttpResponse(500, "Internal Server Error")
                else -> error("Unexpected URL: ${req.url}")
            }
        }

        val connector = GmailApiConnector("https://gmail.test/users/me")
        val checkpoint = SyncCheckpoint("page-token-1", 10L)

        val exception = assertFailsWith<ConnectorHttpException> {
            connector.sync(account, tokens.get(account.id)!!, checkpoint, transport, 10)
        }
        assertEquals(500, exception.statusCode)
        // Checkpoint must remain unchanged and not be advanced on HTTP failure.
        assertEquals("page-token-1", checkpoint.cursor)
        assertEquals(10L, checkpoint.processedCount)
    }
}
