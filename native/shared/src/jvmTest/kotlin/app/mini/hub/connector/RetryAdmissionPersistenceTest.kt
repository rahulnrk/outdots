package app.mini.hub.connector

import app.mini.hub.domain.*
import app.mini.hub.persistence.JdbcObservationStore
import org.junit.Test
import kotlin.test.*

class RetryAdmissionPersistenceTest {
    @Test fun aLongProviderCooldownSurvivesReopenAndBlocksReadAndSendWithoutRetrying() {
        val file = java.io.File.createTempFile("retry-admission", ".db").apply { delete(); deleteOnExit() }
        val account = ConnectedAccount(AccountId(Provider.GMAIL, "retry-account"), "synthetic", null,
            AccountType.GoogleConsumer, ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy)
        val store = JdbcObservationStore(file.path)
        var now = 1_700_000_000_000L
        var calls = 0
        val delegate = ProviderHttpTransport.of { calls++; ProviderHttpResponse(429, "{}", mapOf("Retry-After" to "3600")) }
        AccountRequestTransport(account, store, delegate) { now }.get("https://example.test/messages", emptyMap())
        val next = store.getCursor(AccountRetryAdmission.source(account))!!.nextAllowedAtMillis
        assertTrue(next >= now + 3_600_000)
        store.connection().close()
        val reopened = JdbcObservationStore(file.path)
        val request = AccountRequestTransport(account, reopened, delegate) { now }
        now += 901_000
        assertFailsWith<ConnectorHttpException> { request.get("https://example.test/messages", emptyMap()) }
        assertFailsWith<ConnectorHttpException> { request.execute(ProviderHttpRequest(HttpMethod.POST, "https://example.test/send")) }
        assertEquals(1, calls, "cooldown admission must not make another provider call")
        now = next
        request.get("https://example.test/messages", emptyMap())
        assertEquals(2, calls)
        reopened.connection().close()
    }

    @Test fun hugeRetryAfterSaturatesInsteadOfOverflowingToImmediateAdmission() {
        val store = app.mini.hub.persistence.InMemoryObservationStore()
        AccountRetryAdmission.record(store, "synthetic", 1_700_000_000_000,
            ConnectorHttpException(429, "Synthetic rate limit", kind = ProviderErrorKind.RateLimited, retryAfterSeconds = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, store.getCursor("synthetic")!!.nextAllowedAtMillis)
    }
}
