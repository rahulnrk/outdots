package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.persistence.InMemoryObservationStore
import org.junit.Test
import kotlin.test.*

class CredentialOwnershipTest {
    private val account = ConnectedAccount(AccountId(Provider.SLACK, "rotation-test"), "user", null,
        AccountType.Workspace, ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy)

    @Test fun concurrentStaleRefreshCallersRotateExactlyOnce() {
        val accounts = InMemoryConnectedAccountStore().also { it.upsert(account) }
        val tokens = InMemoryAccessTokenStore()
        val old = StoredAccessToken("old", 0, "old-refresh")
        val rotated = StoredAccessToken("new", 1_000_000, "new-refresh")
        tokens.put(account.id, old)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val engine = ConnectorSyncEngine(accounts, tokens, InMemoryObservationStore(), DigestProvider { it },
            emptyMap(), mapOf(Provider.SLACK to TokenRefresher { token, _ ->
                calls.incrementAndGet(); assertEquals(old, token)
                entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                RefreshOutcome.Refreshed(rotated)
            }), ProviderHttpTransport.of { error("no HTTP") })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<StoredAccessToken?> { engine.refreshForUse(account, old, 1_000) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<StoredAccessToken?> { engine.refreshForUse(account, old, 1_000) }
            release.countDown()
            assertEquals(rotated, first.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(rotated, second.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun staleRefreshCallerReusesCurrentCredentialInsteadOfRotatingAnOldToken() {
        val accounts = InMemoryConnectedAccountStore().also { it.upsert(account) }
        val tokens = InMemoryAccessTokenStore()
        val stale = StoredAccessToken("expired", 0, "old-refresh")
        val current = StoredAccessToken("current", 100_000, "rotated-refresh")
        tokens.put(account.id, current)
        var refreshes = 0
        val engine = ConnectorSyncEngine(accounts, tokens, InMemoryObservationStore(), DigestProvider { it },
            emptyMap(), mapOf(Provider.SLACK to TokenRefresher { _, _ ->
                refreshes++; RefreshOutcome.Revoked("invalid_grant")
            }), ProviderHttpTransport.of { error("no HTTP") })
        assertEquals(current, engine.refreshForUse(account, stale, 1_000))
        assertEquals(0, refreshes)
        assertEquals(current, tokens.get(account.id))
    }

    @Test fun refreshQueuedBeforeDisconnectCannotRevokeOrRecreateDisconnectedAccount() {
        val accounts = InMemoryConnectedAccountStore().also { it.upsert(account.copy(state = ConnectionState.Disconnected)) }
        val tokens = InMemoryAccessTokenStore()
        var refreshes = 0
        val engine = ConnectorSyncEngine(accounts, tokens, InMemoryObservationStore(), DigestProvider { it },
            emptyMap(), mapOf(Provider.SLACK to TokenRefresher { _, _ ->
                refreshes++; RefreshOutcome.Refreshed(StoredAccessToken("stale-result", null, "stale-refresh"))
            }), ProviderHttpTransport.of { error("no HTTP") })
        assertNull(engine.refreshForUse(account, StoredAccessToken("old", 0, "old-refresh"), 1_000))
        assertEquals(0, refreshes)
        assertNull(tokens.get(account.id))
        assertEquals(ConnectionState.Disconnected, accounts.get(account.id)?.state)
    }
}
