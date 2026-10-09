package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/** Truthful route classes. A bridge is never presented as an official provider API. */
enum class ConnectorRoute {
    OfficialProviderApi,
    ThirdPartyBridge,
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

data class ProviderMessage(
    val providerMessageId: String,
    val conversationId: String,
    val sender: String,
    val subject: String?,
    val snippet: String,
    val occurredAtMillis: Long,
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
)

data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

fun interface ProviderHttpTransport {
    fun get(url: String, headers: Map<String, String>): ProviderHttpResponse
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

class ConnectorHttpException(val statusCode: Int, message: String) : RuntimeException(message)
