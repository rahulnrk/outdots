package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.SystemClock
import app.mini.hub.domain.UtcInstant

/** Truthful route classes. A bridge is never presented as an official provider API. */
enum class ConnectorRoute {
    OfficialProviderApi,
    ThirdPartyBridge,
    PhoneLocalBridge,
    NotificationOnly,
    UserImport,
    NativeHandoff,
}

enum class AccountType {
    GoogleConsumer,
    MicrosoftPersonal,
    MicrosoftWorkSchool,
    MetaBusiness,
    SocialProfessional,
    Workspace,
    Unknown,
}

enum class ConnectionState {
    Disconnected,
    AuthorizationRequired,
    Authorized,
    Syncing,
    Healthy,
    Degraded,
    Expired,
    Revoked,
}

data class ProviderAccountIdentity(
    val provider: Provider,
    val providerAccountId: String,
    val displayName: String?,
    val accountType: AccountType,
) {
    init { require(providerAccountId.isNotBlank()) }
}

/** Metadata only. Access tokens and refresh tokens must never be held in this record. */
data class ConnectedAccount(
    val id: AccountId,
    val providerAccountId: String,
    val displayName: String?,
    val accountType: AccountType,
    val route: ConnectorRoute,
    val state: ConnectionState,
    val grantedScopes: Set<String> = emptySet(),
    val lastSuccessfulSyncMillis: Long? = null,
    val userActionRequired: String? = null,
) {
    init {
        require(providerAccountId.isNotBlank())
        require(id.provider != Provider.APP || route != ConnectorRoute.OfficialProviderApi) {
            "An official connector cannot use the generic APP provider"
        }
    }
}

data class StoredAccessToken(
    val value: String,
    val expiresAtMillis: Long?,
    val refreshToken: String? = null,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && expiresAtMillis <= nowMillis
}

interface ConnectedAccountStore {
    fun list(): List<ConnectedAccount>
    fun get(id: AccountId): ConnectedAccount?
    fun upsert(account: ConnectedAccount)
    fun remove(id: AccountId): Boolean
}

interface AccessTokenStore {
    fun get(accountId: AccountId): StoredAccessToken?
    fun put(accountId: AccountId, token: StoredAccessToken)
    fun remove(accountId: AccountId)
}

/**
 * Serializes provider work per account while allowing unrelated accounts to proceed. The same
 * owner is shared by sync, reads, sends, refreshes and disconnect/reconnect mutations so an older
 * refresh cannot race a credential removal or a newer connection.
 */
class AccountOperationCoordinator {
    private class Entry {
        val lock = Any()
        var generation: Long = 0L
        var syncing = false
    }

    private val entries = mutableMapOf<String, Entry>()

    companion object {
        /** Process owner, including independently constructed headless Android hosts. */
        val Shared = AccountOperationCoordinator()
    }

    private fun entry(accountId: AccountId): Entry = synchronized(entries) {
        entries.getOrPut(accountId.key) { Entry() }
    }

    fun <T> withAccount(accountId: AccountId, block: () -> T): T {
        val owner = entry(accountId)
        return synchronized(owner.lock) { block() }
    }

    /** Coalesces sync triggers across independently constructed hosts sharing this owner. */
    fun <T : Any> withSync(accountId: AccountId, block: () -> T): T? {
        val owner = entry(accountId)
        synchronized(entries) {
            if (owner.syncing) return null
            owner.syncing = true
        }
        return try { withAccount(accountId, block) }
        finally { synchronized(entries) { owner.syncing = false } }
    }

    /** Invalidates work that was logically older than a disconnect or account replacement. */
    fun invalidate(accountId: AccountId) {
        val owner = entry(accountId)
        synchronized(owner.lock) { owner.generation++ }
    }

    fun generation(accountId: AccountId): Long {
        val owner = entry(accountId)
        return synchronized(owner.lock) { owner.generation }
    }
}

class InMemoryConnectedAccountStore : ConnectedAccountStore {
    private val accounts = linkedMapOf<String, ConnectedAccount>()
    override fun list(): List<ConnectedAccount> = synchronized(accounts) { accounts.values.toList() }
    override fun get(id: AccountId): ConnectedAccount? = synchronized(accounts) { accounts[id.key] }
    override fun upsert(account: ConnectedAccount) = synchronized(accounts) { accounts[account.id.key] = account }
    override fun remove(id: AccountId): Boolean = synchronized(accounts) { accounts.remove(id.key) != null }
}

class InMemoryAccessTokenStore : AccessTokenStore {
    private val tokens = linkedMapOf<String, StoredAccessToken>()
    override fun get(accountId: AccountId): StoredAccessToken? = synchronized(tokens) { tokens[accountId.key] }
    override fun put(accountId: AccountId, token: StoredAccessToken) = synchronized(tokens) { tokens[accountId.key] = token }
    override fun remove(accountId: AccountId) {
        synchronized(tokens) { tokens.remove(accountId.key) }
    }
}

data class SyncCheckpoint(
    val cursor: String? = null,
    val processedCount: Long = 0,
    val gapCount: Long = 0,
)

enum class MessageDirection { Inbound, Outbound, Unknown }

data class ProviderMessage(
    val providerMessageId: String,
    val conversationId: String,
    val sender: String,
    val subject: String?,
    val snippet: String,
    val occurredAtMillis: Long,
    /** Full plain-text body when the adapter fetched it; null means only [snippet] is known. */
    val bodyText: String? = null,
    val direction: MessageDirection = MessageDirection.Unknown,
    /** Provider tombstone: the message was deleted or unsent at the provider. */
    val isDeleted: Boolean = false,
    /** Monotonic per-message revision when the provider reports edits; 1 for the first version. */
    val revision: Long = 1L,
    /** Provider-native reply anchor (RFC 822 Message-ID, Slack ts, Chat thread name). Never shown as content. */
    val replyAnchor: String? = null,
    val recipients: List<String> = emptyList(),
) {
    init {
        require(providerMessageId.isNotBlank())
        require(conversationId.isNotBlank())
    }
}

data class SyncResult(
    val messages: List<ProviderMessage>,
    val next: SyncCheckpoint,
    val state: ConnectionState,
    val userActionRequired: String? = null,
    /** Messages listed by the provider in this page, including ones skipped as already stored. */
    val listedCount: Int = messages.size,
)

data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

enum class HttpMethod { GET, POST, PATCH, DELETE, PUT }

data class ProviderHttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
) {
    init {
        require(url.startsWith("https://")) {
            "Provider requests must use HTTPS"
        }
        require(method != HttpMethod.GET || body == null) { "GET requests cannot carry a body" }
    }

    /** Diagnostic form that never contains headers, bodies or query strings (which can hold tokens). */
    fun redacted(): String = "$method ${url.substringBefore('?').replace(Regex("/bot[^/]+/"), "/bot<redacted>/")}"
}

/**
 * Platform HTTP edge. [get] is kept for the original read-only adapters. Writes go through
 * [execute]; a transport that only implements [get] refuses writes instead of silently dropping them.
 */
fun interface ProviderHttpTransport {
    fun get(url: String, headers: Map<String, String>): ProviderHttpResponse

    fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
        if (request.method == HttpMethod.GET) return get(request.url, request.headers)
        throw UnsupportedOperationException("This transport is read-only")
    }

    companion object {
        /** Builds a transport that supports every method (used by Android and by fixtures). */
        fun of(handler: (ProviderHttpRequest) -> ProviderHttpResponse): ProviderHttpTransport = object : ProviderHttpTransport {
            override fun get(url: String, headers: Map<String, String>) = handler(ProviderHttpRequest(HttpMethod.GET, url, headers))
            override fun execute(request: ProviderHttpRequest) = handler(request)
        }
    }
}

interface AccountConnector {
    val provider: Provider
    val route: ConnectorRoute
    fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity
    fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int = 50,
    ): SyncResult
}

/** Recovery class for a provider failure. Drives account health and outbox outcome, never retries by itself. */
enum class ProviderErrorKind {
    /** Credential expired or revoked: refresh once, then ask the user to reconnect. */
    Unauthorized,
    /** A grant/scope/entitlement is missing: reconnect with the right permission or admin approval. */
    PermissionMissing,
    /** Quota or rate limit: wait at least [ConnectorHttpException.retryAfterSeconds]. */
    RateLimited,
    /** Provider outage, timeout or 5xx. Safe to retry reads later; sends become OutcomeUnknown. */
    Transient,
    /** Incremental cursor is no longer valid: run a bounded resync and record a visible gap. */
    CursorInvalid,
    /** Target conversation/message no longer exists or is not visible. */
    NotFound,
    /** The provider definitively rejected the request (validation, policy, window, recipient). */
    Rejected,
    /** The provider returned a successful status with an invalid or missing required body. */
    Protocol,
}

class ConnectorHttpException(
    val statusCode: Int,
    message: String,
    /** Provider reason code (for example insufficientPermissions). Never contains message content. */
    val reason: String? = null,
    val kind: ProviderErrorKind = defaultKind(statusCode),
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message) {
    companion object {
        fun defaultKind(status: Int): ProviderErrorKind = when {
            status == 401 -> ProviderErrorKind.Unauthorized
            status == 403 -> ProviderErrorKind.PermissionMissing
            status == 404 -> ProviderErrorKind.NotFound
            status == 410 -> ProviderErrorKind.CursorInvalid
            status == 429 -> ProviderErrorKind.RateLimited
            status >= 500 || status == 408 || status == 0 -> ProviderErrorKind.Transient
            else -> ProviderErrorKind.Rejected
        }

        /** Parses bounded seconds or RFC 7231 HTTP-date Retry-After values. */
        fun retryAfter(response: ProviderHttpResponse, nowMillis: Long = SystemClock.now().epochMillis): Long? {
            val value = response.header("Retry-After")?.trim() ?: return null
            value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
            val match = HTTP_DATE.matchEntire(value) ?: return null
            val g = match.groupValues
            val month = MONTHS.indexOf(g[2]) + 1
            if (month !in 1..12) return null
            val dateMillis = runCatching {
                UtcInstant.parse("${g[3]}-${month.toString().padStart(2, '0')}-${g[1]}T${g[4]}:${g[5]}:${g[6]}Z").epochMillis
            }.getOrNull() ?: return null
            return ((dateMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1_000L
        }

        private val HTTP_DATE = Regex("^[A-Za-z]{3}, (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT$")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private const val MAX_RETRY_AFTER_SECONDS = 30L * 24L * 60L * 60L
    }
}
