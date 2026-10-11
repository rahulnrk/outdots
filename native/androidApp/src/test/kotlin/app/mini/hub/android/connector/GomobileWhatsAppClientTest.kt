package app.mini.hub.android.connector

import org.junit.Test
import kotlin.test.assertEquals

class GomobileWhatsAppClientTest {
    @Test(timeout = 10_000)
    fun inboundJsonMapsToProviderMessages() {
        val messages = GomobileWhatsAppClient.parseInbound(
            """[{"id":"abc","chat":"15551239999@s.whatsapp.net","sender":"15551239999@s.whatsapp.net","body":"hello","ts":1700000000000}]""",
        )
        assertEquals(1, messages.size)
        assertEquals("abc", messages[0].providerMessageId)
        assertEquals("15551239999@s.whatsapp.net", messages[0].conversationId)
        assertEquals("hello", messages[0].bodyText)
    }
}
