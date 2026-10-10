package app.mini.hub.android.connector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import app.mini.hub.connector.AccountConnector
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.HttpMethod
import app.mini.hub.connector.MicrosoftAccountKind
import app.mini.hub.connector.PkceAuthorization
import app.mini.hub.connector.PkceProviderSpec
import app.mini.hub.connector.ProviderHttpRequest
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.RefreshOutcome
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.TokenEndpointResult
import app.mini.hub.connector.TokenRefresher
import app.mini.hub.connector.TokenResponses
import app.mini.hub.connector.formEncode
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

sealed class ConnectOutcome {
    data class Started(val provider: Provider) : ConnectOutcome()
    data class Connected(val account: ConnectedAccount) : ConnectOutcome()
    data class Blocked(val reason: String) : ConnectOutcome()
}

/**
 * Generic Android PKCE host for one provider route. It opens the system browser, validates state,
 * exchanges the code with the verifier (never a client secret), discovers the provider identity,
 * records only the scopes the provider actually granted, and attaches to the existing local
 * account for the same identity so reconnect never duplicates history.
 */
class PkceAccountConnector(
    context: Context,
    val spec: PkceProviderSpec,
    private val clientId: String,
    private val connector: AccountConnector,
    private val transport: ProviderHttpTransport,
    private val setupHint: String,
    /** Picks the account type from the grant; Microsoft reads the tenant claim. */
    private val accountTypeOf: (identityType: AccountType, idToken: String?) -> AccountType = { type, _ -> type },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mini_pkce_${spec.id}", Context.MODE_PRIVATE)
    private val accounts = AndroidConnectedAccountStore(appContext)
    private val tokens = AndroidKeystoreAccessTokenStore(appContext)

    val isConfigured: Boolean get() = clientId.isNotBlank()
    val setupStatus: String get() = if (isConfigured) {
        "${spec.displayName}: ready to connect (${spec.scopes.size} permissions requested)."
    } else {
        "${spec.displayName} setup required: $setupHint No client secret is ever used."
    }

    fun acceptsRedirect(uri: Uri): Boolean = isConfigured && spec.isRedirect(uri.toString().substringBefore('?'))

    fun launch(activity: Activity): ConnectOutcome {
        if (!isConfigured) return ConnectOutcome.Blocked(setupStatus)
        val state = random(32)
        val verifier = random(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        prefs.edit().putString(KEY_STATE, state).putString(KEY_VERIFIER, verifier).commit()
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PkceAuthorization.authorizeUrl(spec, clientId, state, challenge))))
        return ConnectOutcome.Started(spec.provider)
    }

    /** Network I/O. Call off the main thread. */
    fun handleRedirect(uri: Uri, nowMillis: Long = System.currentTimeMillis()): ConnectOutcome {
        uri.getQueryParameter("error")?.let {
            Log.w(LOG_TAG, "${spec.id} authorization returned error=$it")
            return ConnectOutcome.Blocked("${spec.displayName} authorization was not completed ($it).")
        }
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val expected = prefs.getString(KEY_STATE, null).orEmpty()
        val verifier = prefs.getString(KEY_VERIFIER, null).orEmpty()
        if (code.isBlank() || expected.isBlank() || state != expected) {
            Log.w(LOG_TAG, "${spec.id} callback rejected: state mismatch")
            return ConnectOutcome.Blocked("${spec.displayName} response did not match the pending request.")
        }
        prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).commit()
        return try {
            val result = postForm(PkceAuthorization.codeExchangeForm(spec, clientId, code, verifier), nowMillis, null)
            val grant = when (result) {
                is TokenEndpointResult.Granted -> result.grant
                is TokenEndpointResult.Rejected -> return ConnectOutcome.Blocked("${spec.displayName} token exchange failed (${result.code}).")
            }
            val identity = connector.discoverAccount(grant.token, transport)
            val existing = accounts.list().firstOrNull { it.id.provider == spec.provider && it.providerAccountId == identity.providerAccountId }
            val account = (existing ?: ConnectedAccount(
                id = AccountId(spec.provider, "${spec.provider.wire}-${UUID.randomUUID()}"),
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = identity.accountType,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                accountType = accountTypeOf(identity.accountType, grant.idToken),
                state = ConnectionState.Authorized,
                // Only what the provider granted. Missing scopes keep their capabilities off.
                grantedScopes = grant.grantedScopes.ifEmpty { spec.scopes.toSet() },
                userActionRequired = null,
            )
            tokens.put(account.id, grant.token)
            accounts.upsert(account)
            Log.i(LOG_TAG, "${spec.id} connected account=${account.id.localId} new=${existing == null} scopes=${account.grantedScopes.size}")
            ConnectOutcome.Connected(account)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "${spec.id} connect failed: ${e.javaClass.simpleName}")
            ConnectOutcome.Blocked("${spec.displayName} connection could not finish (${e.javaClass.simpleName}).")
        }
    }

    /** Refresher bound to this route's endpoint and client ID. */
    fun refresher(): TokenRefresher = TokenRefresher { token, now ->
        val refresh = token.refreshToken ?: return@TokenRefresher RefreshOutcome.NoRefreshToken
        if (!isConfigured) return@TokenRefresher RefreshOutcome.Transient("client_not_configured")
        try {
            TokenResponses.toRefreshOutcome(postForm(PkceAuthorization.refreshForm(spec, clientId, refresh), now, refresh))
        } catch (e: Exception) {
            RefreshOutcome.Transient(e.javaClass.simpleName)
        }
    }

    private fun postForm(form: Map<String, String>, nowMillis: Long, previousRefresh: String?): TokenEndpointResult {
        val type = "application/x-www-form-urlencoded"
        val response = transport.execute(
            ProviderHttpRequest(HttpMethod.POST, spec.tokenEndpoint, mapOf("Content-Type" to type), formEncode(form), type),
        )
        return TokenResponses.parse(spec.style, response.statusCode, response.body, nowMillis, previousRefresh)
    }

    private fun random(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    companion object {
        const val LOG_TAG = "MiniConnect"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "verifier"

        val microsoftAccountType: (AccountType, String?) -> AccountType = { fallback, idToken ->
            MicrosoftAccountKind.fromIdToken(idToken).takeIf { it != AccountType.Unknown } ?: fallback
        }
    }
}

/**
 * Telegram bot route. The credential is a BotFather token the user pastes; MINI never ships a
 * shared bot token. The token is validated with getMe before it is stored in Android Keystore.
 */
class TelegramBotAccountConnector(
    context: Context,
    private val connector: app.mini.hub.connector.TelegramBotConnector,
    private val transport: ProviderHttpTransport,
) {
    private val accounts = AndroidConnectedAccountStore(context.applicationContext)
    private val tokens = AndroidKeystoreAccessTokenStore(context.applicationContext)

    /** Network I/O. Call off the main thread. */
    fun connect(botToken: String): ConnectOutcome {
        val token = botToken.trim()
        if (!app.mini.hub.connector.TelegramBotConnector.BOT_TOKEN.matches(token)) {
            return ConnectOutcome.Blocked("That is not a BotFather token (expected digits:letters).")
        }
        return try {
            val credential = StoredAccessToken(token, expiresAtMillis = null)
            val identity = connector.discoverAccount(credential, transport)
            val existing = accounts.list().firstOrNull { it.id.provider == Provider.TELEGRAM && it.providerAccountId == identity.providerAccountId }
            val account = (existing ?: ConnectedAccount(
                id = AccountId(Provider.TELEGRAM, "telegram-${UUID.randomUUID()}"),
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = AccountType.Unknown,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                state = ConnectionState.Authorized,
                grantedScopes = setOf(app.mini.hub.connector.ConnectorCatalog.TELEGRAM_BOT_SCOPE),
                userActionRequired = null,
            )
            tokens.put(account.id, credential)
            accounts.upsert(account)
            Log.i(PkceAccountConnector.LOG_TAG, "telegram bot connected account=${account.id.localId} new=${existing == null}")
            ConnectOutcome.Connected(account)
        } catch (e: app.mini.hub.connector.ConnectorHttpException) {
            ConnectOutcome.Blocked("Telegram rejected the token (${e.statusCode}).")
        } catch (e: Exception) {
            ConnectOutcome.Blocked("Telegram could not be reached (${e.javaClass.simpleName}).")
        }
    }
}

class MatrixBridgeAccountConnector(
    context: Context,
    private val connector: app.mini.hub.connector.MatrixBridgeConnector,
    private val transport: ProviderHttpTransport,
) {
    private val accounts = AndroidConnectedAccountStore(context.applicationContext)
    private val tokens = AndroidKeystoreAccessTokenStore(context.applicationContext)

    /** Network I/O. Call off the main thread. Expected token format: "homeserver_url|matrix_token" */
    fun connect(bridgeToken: String): ConnectOutcome {
        val parts = bridgeToken.split('|', limit = 2)
        if (parts.size != 2 || !parts[0].startsWith("http")) {
            return ConnectOutcome.Blocked("Invalid bridge credential format. Must be 'url|token'.")
        }
        
        return try {
            val credential = StoredAccessToken(bridgeToken, expiresAtMillis = null)
            val identity = connector.discoverAccount(credential, transport)
            val provider = connector.provider
            val existing = accounts.list().firstOrNull { it.id.provider == provider && it.providerAccountId == identity.providerAccountId }
            val account = (existing ?: ConnectedAccount(
                id = AccountId(provider, "${provider.wire}-${UUID.randomUUID()}"),
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = AccountType.Unknown,
                route = ConnectorRoute.ThirdPartyBridge,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                state = ConnectionState.Authorized,
                grantedScopes = setOf("matrix_token"),
                userActionRequired = null,
            )
            tokens.put(account.id, credential)
            accounts.upsert(account)
            Log.i(PkceAccountConnector.LOG_TAG, "${provider.wire} matrix bridge connected account=${account.id.localId} new=${existing == null}")
            ConnectOutcome.Connected(account)
        } catch (e: app.mini.hub.connector.ConnectorHttpException) {
            ConnectOutcome.Blocked("Matrix homeserver rejected the token (${e.statusCode}).")
        } catch (e: Exception) {
            ConnectOutcome.Blocked("Matrix homeserver could not be reached (${e.javaClass.simpleName}).")
        }
    }
}
