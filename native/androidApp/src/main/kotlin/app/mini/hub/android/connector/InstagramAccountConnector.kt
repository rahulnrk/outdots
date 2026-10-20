package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InstagramApiConnector
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/**
 * Official Instagram route (Business/Creator). The owner pastes the long-lived Instagram User
 * access token generated for their own account in their Meta app (App Dashboard, Instagram >
 * API setup with Instagram login > Generate token). Validated with /me, then stored in Keystore.
 */
class InstagramAccountConnector(
    context: Context,
    private val connector: InstagramApiConnector,
    private val transport: ProviderHttpTransport,
) {
    private val accounts = AndroidConnectedAccountStore(context.applicationContext)
    private val tokens = AndroidKeystoreAccessTokenStore(context.applicationContext)

    /** Network I/O. Call off the main thread. */
    fun connect(accessToken: String): ConnectOutcome {
        val token = accessToken.trim()
        if (token.length < 40 || token.any { it.isWhitespace() }) {
            return ConnectOutcome.Blocked("That doesn't look like an Instagram access token.")
        }
        return try {
            // Long-lived tokens last 60 days; refreshed by re-pasting for now.
            val credential = StoredAccessToken(token, expiresAtMillis = System.currentTimeMillis() + 59L * 24 * 3600_000L)
            val identity = connector.discoverAccount(credential, transport)
            val id = AccountId(Provider.INSTAGRAM, "ig-${identity.providerAccountId}")
            val account = (accounts.get(id) ?: ConnectedAccount(
                id = id,
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = AccountType.SocialProfessional,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
                grantedScopes = setOf(InstagramApiConnector.SCOPE),
                userActionRequired = null,
            )
            app.mini.hub.connector.AccountConnectionManager.installCredential(accounts, tokens, account, credential)
            Log.i(PkceAccountConnector.LOG_TAG, "instagram connected account=${account.id.localId}")
            ConnectOutcome.Connected(account)
        } catch (e: app.mini.hub.connector.ConnectorHttpException) {
            ConnectOutcome.Blocked(
                if (e.statusCode == 400 || e.statusCode == 401 || e.statusCode == 190) "Instagram rejected the token. Generate a new one in your Meta app."
                else "Instagram error (${e.statusCode}). Check the account is Business or Creator.",
            )
        } catch (e: Exception) {
            ConnectOutcome.Blocked("Instagram could not be reached (${e.javaClass.simpleName}).")
        }
    }
}
