package app.mini.hub.connector

import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.ObservationStore

/** Durable account-level provider admission. This never schedules or retries an outgoing action. */
object AccountRetryAdmission {
    fun source(account: ConnectedAccount) = "${account.id.provider.wire}:${account.id.key}"

    fun allowed(store: ObservationStore, account: ConnectedAccount, now: Long): Boolean =
        (store.getCursor(source(account))?.nextAllowedAtMillis ?: 0L) <= now

    fun record(store: ObservationStore, account: ConnectedAccount, now: Long, error: ConnectorHttpException) {
        record(store, source(account), now, error)
    }

    fun record(store: ObservationStore, source: String, now: Long, error: ConnectorHttpException) {
        if (error.kind !in setOf(ProviderErrorKind.RateLimited, ProviderErrorKind.Transient)) return
        val previous = store.getCursor(source)
        val attempt = (previous?.retryAttempt ?: 0).coerceIn(0, Int.MAX_VALUE - 1) + 1
        val exponential = (1_000L shl (attempt - 1).coerceAtMost(13)).coerceAtMost(900_000L)
        val requested = error.retryAfterSeconds?.takeIf { it >= 0 }?.let {
            if (it > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else it * 1_000L
        } ?: 0
        val delay = maxOf(exponential, requested)
        val jitter = source.hashCode().toLong().and(Long.MAX_VALUE) % (minOf(delay / 4, 30_000L) + 1)
        fun add(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        // Persistence failures propagate: silently losing admission would hammer the provider.
        store.checkpointCursor(IngestCursor(source, previous?.lastProcessedTimeMillis ?: 0L,
            previous?.lastProcessedKey, previous?.processedCount ?: 0, previous?.gapCount ?: 0,
            maxOf(previous?.nextAllowedAtMillis ?: 0, add(add(now, delay), jitter)), attempt))
    }
}

/** Reads and sends share sync's cooldown. Failures after a write still flow to unknown outcomes. */
class AccountRequestTransport(
    private val account: ConnectedAccount,
    private val store: ObservationStore,
    private val delegate: ProviderHttpTransport,
    private val nowMillis: () -> Long,
) : ProviderHttpTransport {
    override fun get(url: String, headers: Map<String, String>) = execute(ProviderHttpRequest(HttpMethod.GET, url, headers))

    override fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
        val now = nowMillis()
        if (!AccountRetryAdmission.allowed(store, account, now))
            throw ConnectorHttpException(429, "Provider cooldown active", kind = ProviderErrorKind.RateLimited)
        val response = try { delegate.execute(request) }
        catch (e: UnsupportedOperationException) { throw e }
        catch (e: Exception) {
            val error = e as? ConnectorHttpException
                ?: ConnectorHttpException(0, "Provider unavailable", kind = ProviderErrorKind.Transient)
            AccountRetryAdmission.record(store, account, nowMillis(), error)
            throw e
        }
        val kind = ConnectorHttpException.defaultKind(response.statusCode)
        if (kind == ProviderErrorKind.RateLimited || kind == ProviderErrorKind.Transient) {
            AccountRetryAdmission.record(store, account, nowMillis(), ConnectorHttpException(response.statusCode,
                "Provider retry requested", kind = kind,
                retryAfterSeconds = ConnectorHttpException.retryAfter(response, nowMillis())))
        }
        return response
    }
}
