package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.IdGenerator

/** Coordinates metadata and token lifecycle without deleting account-scoped message history. */
class AccountConnectionManager(
    private val accounts: ConnectedAccountStore,
    private val tokens: AccessTokenStore,
    private val ids: IdGenerator,
    private val operations: AccountOperationCoordinator = AccountOperationCoordinator.Shared,
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

    /**
     * Disconnects only this account's credential. The metadata record is kept in the Disconnected
     * state so a later reconnect of the same provider identity reuses the same local account ID,
     * which keeps existing history attached and prevents a second copy of every message.
     */
    fun disconnect(id: AccountId): Boolean {
        return operations.withAccount(id) {
            operations.invalidate(id)
            tokens.remove(id)
            val existing = accounts.get(id) ?: return@withAccount false
            accounts.upsert(
                existing.copy(
                    state = ConnectionState.Disconnected,
                    userActionRequired = DISCONNECTED_ACTION,
                ),
            )
            true
        }
    }

    /** Permanently forgets the account metadata. Local message history is still not deleted here. */
    fun forget(id: AccountId): Boolean {
        return operations.withAccount(id) {
            operations.invalidate(id)
            tokens.remove(id)
            accounts.remove(id)
        }
    }

    companion object {
        const val DISCONNECTED_ACTION = "Disconnected. Local history is kept. Reconnect to resume syncing."

        /** Reconnection publishes metadata and its credential under the same owner as refresh. */
        fun installCredential(accounts: ConnectedAccountStore, tokens: AccessTokenStore,
            account: ConnectedAccount, token: StoredAccessToken,
            operations: AccountOperationCoordinator = AccountOperationCoordinator.Shared,
        ) = operations.withAccount(account.id) {
            operations.invalidate(account.id)
            tokens.put(account.id, token)
            accounts.upsert(account)
        }
    }
}
