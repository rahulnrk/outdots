package app.mini.hub.android.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.ProviderGateway
import app.mini.hub.connector.AccessTokenStore
import app.mini.hub.connector.AccountOperationCoordinator
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.domain.AccountId

/**
 * Refreshes an expired access token through the existing per-provider refreshers before the
 * provider send. [refresh] stores the new token and returns it, or marks the account (Revoked,
 * Expired, Degraded) and returns null. A failed refresh is a definite rejection before any
 * provider call, carrying the account's existing reconnect message. All gate checks stay in
 * [delegate].
 */
class RefreshingGateway(
    private val accounts: ConnectedAccountStore,
    private val tokens: AccessTokenStore,
    private val refresh: (ConnectedAccount, StoredAccessToken) -> StoredAccessToken?,
    private val delegate: ProviderGateway,
    private val operations: AccountOperationCoordinator = AccountOperationCoordinator.Shared,
    private val admissionAllowed: (ConnectedAccount) -> Boolean = { true },
    private val authorizationCheck: (OutboxCommand, ExactActionPayload) -> Boolean = { _, _ -> true },
    private val nowMillis: () -> Long,
) : ProviderGateway {
    override fun execute(command: OutboxCommand, payload: ExactActionPayload): GatewayResult {
        val accountId = runCatching { AccountId.parse(payload.accountId) }.getOrNull()
            ?: return delegate.execute(command, payload)
        return operations.withAccount(accountId) {
            val account = accounts.get(accountId) ?: return@withAccount delegate.execute(command, payload)
            if (account.state == app.mini.hub.connector.ConnectionState.Disconnected) return@withAccount GatewayResult.Rejected("account_disconnected")
            if (!admissionAllowed(account)) return@withAccount GatewayResult.Rejected("provider_cooldown")
            val token = tokens.get(accountId) ?: return@withAccount delegate.execute(command, payload)
            if (token.isExpired(nowMillis() + EXPIRY_SKEW_MILLIS)) {
                if (refresh(account, token) == null) {
                    val message = accounts.get(accountId)?.userActionRequired ?: DEFAULT_RECONNECT
                    return@withAccount GatewayResult.Rejected("credential_refresh_failed: $message")
                }
            }
            if (!authorizationCheck(command, payload)) return@withAccount GatewayResult.Rejected("approval_or_sources_changed_before_send")
            delegate.execute(command, payload)
        }
    }

    companion object {
        const val EXPIRY_SKEW_MILLIS = 60_000L
        const val DEFAULT_RECONNECT = "Reconnect this account to resume. Local history is kept."
    }
}
