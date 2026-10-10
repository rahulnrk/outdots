package app.mini.hub.connector

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MatrixBridgeConnectorTest {
    @Test
    fun bridgeRequiresHttpsHomeserver() {
        val connector = MatrixBridgeConnector(app.mini.hub.domain.Provider.WHATSAPP, "whatsapp")
        val transport = ProviderHttpTransport.of { error("HTTP homeservers must be rejected before network access") }

        assertFailsWith<IllegalArgumentException> {
            connector.discoverAccount(StoredAccessToken("http://homeserver|token", null), transport)
        }
    }
}
