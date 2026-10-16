package app.mini.hub.connector

import app.mini.hub.domain.UtcInstant
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

    @Test
    fun parsesRetryAfterSecondsAndHttpDateWithInjectedClock() {
        val now = UtcInstant.parse("2026-10-16T12:00:00Z").epochMillis
        assertEquals(3_600L, ConnectorHttpException.retryAfter(
            ProviderHttpResponse(429, "", mapOf("Retry-After" to "3600")), now,
        ))
        assertEquals(60L, ConnectorHttpException.retryAfter(
            ProviderHttpResponse(429, "", mapOf("Retry-After" to "Fri, 16 Oct 2026 12:01:00 GMT")), now,
        ))
        assertEquals(null, ConnectorHttpException.retryAfter(
            ProviderHttpResponse(429, "", mapOf("Retry-After" to "999999999999999999999")), now,
        ))
    }
}
