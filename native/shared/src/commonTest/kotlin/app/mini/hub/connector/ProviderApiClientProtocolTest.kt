package app.mini.hub.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderApiClientProtocolTest {
    private fun client(response: ProviderHttpResponse) = ProviderApiClient(
        providerName = "Fixture",
        transport = ProviderHttpTransport.of { response },
        token = StoredAccessToken("token", null),
    )

    @Test
    fun allowsDocumentedEmptyAcceptedAndNoContentResponses() {
        val empty = emptyMap<String, JsonValue>()
        val body = emptyMap<String, Any?>()
        assertEquals(empty, (client(ProviderHttpResponse(202, "")).postJson("https://example.test/send", body) as JsonValue.Obj).fields)
        assertEquals(empty, (client(ProviderHttpResponse(204, "")).postJson("https://example.test/send", body) as JsonValue.Obj).fields)
    }

    @Test
    fun rejectsMissingOrMalformedRequiredSuccessBodies() {
        listOf(
            ProviderHttpResponse(200, ""),
            ProviderHttpResponse(200, "{malformed"),
            ProviderHttpResponse(202, "{malformed"),
        ).forEach { response ->
            val error = assertFailsWith<ConnectorHttpException> {
                client(response).get("https://example.test/read")
            }
            assertEquals(ProviderErrorKind.Protocol, error.kind)
            assertEquals("invalid_success_body", error.reason)
        }
    }
}
