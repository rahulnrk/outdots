package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.IdGenerator

/** Coordinates metadata and token lifecycle without deleting account-scoped message history. */
class AccountConnectionManager(
    private val accounts: ConnectedAccountStore,
    private val tokens: AccessTokenStore,
    private val ids: IdGenerator,
) {
    fun attach(
        identity: ProviderAccountIdentity,
        route: ConnectorRoute,
        scopes: Set<String>,
    ): ConnectedAccount {
        val existing = accounts.list().firstOrNull {
            it.id.provider == identity.provider && it.providerAccountId == identity.providerAccountId
        }
        val account = (existing ?: ConnectedAccount(
            id = AccountId(identity.provider, ids.next("${identity.provider.wire}_account")),
            providerAccountId = identity.providerAccountId,
            displayName = identity.displayName,
            accountType = identity.accountType,
            route = route,
            state = ConnectionState.Authorized,
        )).copy(
            displayName = identity.displayName,
            accountType = identity.accountType,
            route = route,
            state = ConnectionState.Authorized,
            grantedScopes = scopes,
            userActionRequired = null,
        )
        accounts.upsert(account)
        return account
    }

    fun markHealth(id: AccountId, state: ConnectionState, action: String? = null) {
        accounts.get(id)?.let { accounts.upsert(it.copy(state = state, userActionRequired = action)) }
    }

    /** Disconnects only this account's token and metadata. Existing observations remain local. */
    fun disconnect(id: AccountId): Boolean {
        tokens.remove(id)
        return accounts.remove(id)
    }
}
