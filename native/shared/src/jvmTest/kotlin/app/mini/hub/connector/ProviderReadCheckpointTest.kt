package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.*
import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.InMemoryObservationStore
import org.junit.Test
import kotlin.test.*

class ProviderReadCheckpointTest {
    @Test fun aMalformedGraphPageDoesNotAdvanceAnExistingCheckpoint() {
        val account = ConnectedAccount(AccountId(Provider.OUTLOOK, "protocol-test"), "synthetic", null,
            AccountType.MicrosoftPersonal, ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy)
        val accounts = InMemoryConnectedAccountStore().also { it.upsert(account) }
        val tokens = InMemoryAccessTokenStore().also { it.put(account.id, StoredAccessToken("synthetic", null)) }
        val store = InMemoryObservationStore()
        val previous = IngestCursor(ConnectorSyncEngine.cursorSource(account), 1_000, null, 7, 3)
        store.checkpointCursor(previous)
        val engine = ConnectorSyncEngine(accounts, tokens, store, DigestProvider { it },
            mapOf(Provider.OUTLOOK to OutlookGraphConnector()), emptyMap(),
            ProviderHttpTransport.of { ProviderHttpResponse(200, "{\"unexpected\":[]}") })
        assertEquals(ProviderErrorKind.Protocol, assertFailsWith<ConnectorHttpException> { engine.syncAccount(account, 2_000) }.kind)
        assertEquals(previous, store.getCursor(previous.source))
        assertEquals(ConnectionState.Degraded, accounts.get(account.id)?.state)
    }
}
