package app.mini.hub.android.connector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.OAuthAuthorizationRequestBuilder
import app.mini.hub.connector.OAuthCallback
import app.mini.hub.connector.OAuthPublicClientConfiguration
import app.mini.hub.connector.OutlookGraphConnector
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.validateOAuthCallback
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

sealed class OutlookOAuthOutcome {
    data class Started(val authorizationUrl: String) : OutlookOAuthOutcome()
    data class Connected(val account: ConnectedAccount) : OutlookOAuthOutcome()
    data class Blocked(val reason: String) : OutlookOAuthOutcome()
}

/**
 * Android public-client OAuth edge for Microsoft accounts (personal Outlook.com and work/school).
 * Uses MSAL-compatible PKCE S256 authorization code exchange against Microsoft Entra v2.0 endpoint.
 * Client secrets are neither accepted nor compiled into MINI.
 */
class OutlookOAuthClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val accounts = AndroidConnectedAccountStore(appContext)
    private val tokens = AndroidKeystoreAccessTokenStore(appContext)
    private val transport = AndroidProviderHttpTransport()
    private val connector = OutlookGraphConnector()

    private val configuration: OAuthPublicClientConfiguration?
        get() = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID.trim().takeIf { it.isNotBlank() }?.let {
            OAuthPublicClientConfiguration(
                clientId = it,
                authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                redirectUri = REDIRECT_URI,
                scopes = setOf("Mail.Read", "Mail.Send", "offline_access", "openid", "profile", "User.Read"),
            )
        }

    val isConfigured: Boolean get() = configuration != null
    val setupStatus: String get() = if (isConfigured) {
        "Outlook uses official Microsoft Graph OAuth (PKCE) for personal and work/school accounts."
    } else {
        "Microsoft connection setup incomplete: build with -Pmini.outlookOAuthClientId=<your Entra App Client ID>. Never add a client secret to Android."
    }

    fun acceptsRedirect(uri: Uri): Boolean =
        uri.path == REDIRECT_PATH && (uri.scheme == "app.mini.hub" || uri.scheme == "msauth")

    fun launch(activity: Activity): OutlookOAuthOutcome {
        val config = configuration ?: return OutlookOAuthOutcome.Blocked(
            "Microsoft connection setup incomplete. Supply an Entra App Client ID with -Pmini.outlookOAuthClientId; no client secret is needed.",
        )
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        // prompt=select_account allows the user to choose between personal and work/school sign-in.
        val request = OAuthAuthorizationRequestBuilder.build(config, state, verifier, challenge)
        val url = request.url + "&prompt=select_account"
        prefs.edit().putString(KEY_STATE, state).putString(KEY_VERIFIER, verifier).apply()
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return OutlookOAuthOutcome.Started(url)
    }

    /** Performs network I/O. Must not be called on the Android main thread. */
    fun handleRedirect(uri: Uri, nowMillis: Long = System.currentTimeMillis()): OutlookOAuthOutcome {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Outlook OAuth callback must be handled off the main thread"
        }
        val config = configuration ?: return OutlookOAuthOutcome.Blocked("Microsoft OAuth client ID is not configured.")
        uri.getQueryParameter("error")?.let {
            val description = uri.getQueryParameter("error_description") ?: it
            android.util.Log.w(LOG_TAG, "Outlook OAuth callback returned provider error: $it: $description")
            return OutlookOAuthOutcome.Blocked("Microsoft authorization was not completed: $description")
        }
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val expectedState = prefs.getString(KEY_STATE, null).orEmpty()
        val verifier = prefs.getString(KEY_VERIFIER, null).orEmpty()
        if (!validateOAuthCallback(expectedState, OAuthCallback(code, state))) {
            android.util.Log.w(LOG_TAG, "Outlook OAuth callback rejected: state mismatch")
            return OutlookOAuthOutcome.Blocked("Microsoft authorization response did not match the pending request.")
        }
        return try {
            val token = exchangeCode(config, code, verifier, nowMillis)
            val identity = connector.discoverAccount(token, transport)
            val existing = accounts.list().firstOrNull {
                it.id.provider == Provider.OUTLOOK && it.providerAccountId == identity.providerAccountId
            }
            val displayName = identity.displayName
            val accountType = if (displayName?.contains("@") == true && !displayName.endsWith("@outlook.com") && !displayName.endsWith("@hotmail.com") && !displayName.endsWith("@live.com")) {
                AccountType.MicrosoftWorkSchool
            } else {
                AccountType.MicrosoftPersonal
            }
            val account = (existing ?: ConnectedAccount(
                id = AccountId(Provider.OUTLOOK, "outlook-${UUID.randomUUID()}"),
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = accountType,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                accountType = accountType,
                state = ConnectionState.Authorized,
                grantedScopes = setOf("Mail.Read", "Mail.Send"),
                userActionRequired = null,
            )
            tokens.put(account.id, token)
            accounts.upsert(account)
            prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()
            android.util.Log.i(LOG_TAG, "Outlook OAuth callback stored account (new=${existing == null})")
            OutlookOAuthOutcome.Connected(account)
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "Outlook OAuth callback failed: ${e.javaClass.simpleName}: ${e.message}")
            OutlookOAuthOutcome.Blocked("Microsoft authorization could not finish: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun exchangeCode(
        config: OAuthPublicClientConfiguration,
        code: String,
        verifier: String,
        nowMillis: Long,
    ): StoredAccessToken {
        val body = formEncode(
            mapOf(
                "code" to code,
                "client_id" to config.clientId,
                "redirect_uri" to config.redirectUri,
                "grant_type" to "authorization_code",
                "code_verifier" to verifier,
                "scope" to config.scopes.joinToString(" "),
            ),
        )
        val connection = URL(config.tokenEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        return try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val err = jsonString(response, "error") ?: "unknown_error"
                val desc = jsonString(response, "error_description") ?: ""
                error("Microsoft token endpoint returned HTTP $status ($err: $desc)")
            }
            val access = jsonString(response, "access_token") ?: error("Microsoft response omitted access_token")
            val expires = jsonString(response, "expires_in")?.toLongOrNull()
            StoredAccessToken(
                value = access,
                expiresAtMillis = expires?.let { nowMillis + it * 1000L },
                refreshToken = jsonString(response, "refresh_token"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    private fun jsonString(json: String, name: String): String? =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([0-9]+))")
            .find(json)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }

    companion object {
        const val REDIRECT_PATH = "/oauth2redirect/outlook"
        const val REDIRECT_URI = "app.mini.hub://oauth2redirect/outlook"
        private const val LOG_TAG = "MiniOutlookOAuth"
        private const val FILE = "mini_outlook_oauth_session"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "code_verifier"
    }
}
