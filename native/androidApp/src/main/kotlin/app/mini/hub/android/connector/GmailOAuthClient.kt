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
import app.mini.hub.connector.GmailApiConnector
import app.mini.hub.connector.OAuthAuthorizationRequestBuilder
import app.mini.hub.connector.OAuthCallback
import app.mini.hub.connector.OAuthPublicClientConfiguration
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

sealed class GmailOAuthOutcome {
    data class Started(val authorizationUrl: String) : GmailOAuthOutcome()
    data class Connected(val account: ConnectedAccount) : GmailOAuthOutcome()
    data class Blocked(val reason: String) : GmailOAuthOutcome()
}

/** Android public-client OAuth edge. Client secrets are neither accepted nor compiled into MINI. */
class GmailOAuthClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val accounts = AndroidConnectedAccountStore(appContext)
    private val tokens = AndroidKeystoreAccessTokenStore(appContext)
    private val transport = AndroidProviderHttpTransport()
    private val connector = GmailApiConnector()

    private val configuration: OAuthPublicClientConfiguration?
        get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim().takeIf { it.isGoogleClientId() }?.let {
            OAuthPublicClientConfiguration(
                clientId = it,
                authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenEndpoint = "https://oauth2.googleapis.com/token",
                redirectUri = redirectUriFor(it),
                scopes = setOf("https://www.googleapis.com/auth/gmail.readonly"),
            )
        }

    val isConfigured: Boolean get() = configuration != null
    val setupStatus: String get() = if (isConfigured) {
        "Gmail uses official read-only OAuth and stores tokens in Android Keystore. Reply/send is not connected yet."
    } else {
        "Gmail setup missing: build with -Pmini.googleOAuthClientId=<Android client ID ending in .apps.googleusercontent.com>. Never add a client secret to Android."
    }

    fun acceptsRedirect(uri: Uri): Boolean = configuration?.let { config ->
        uri.path == REDIRECT_PATH && uri.scheme == Uri.parse(config.redirectUri).scheme
    } == true

    fun launch(activity: Activity): GmailOAuthOutcome {
        val config = configuration ?: return GmailOAuthOutcome.Blocked(
            "Gmail setup is incomplete. Supply a valid Android client ID with -Pmini.googleOAuthClientId; no client secret is needed.",
        )
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val request = OAuthAuthorizationRequestBuilder.build(config, state, verifier, challenge)
        prefs.edit().putString(KEY_STATE, state).putString(KEY_VERIFIER, verifier).apply()
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
        return GmailOAuthOutcome.Started(request.url)
    }

    fun handleRedirect(uri: Uri, nowMillis: Long = System.currentTimeMillis()): GmailOAuthOutcome {
        val config = configuration ?: return GmailOAuthOutcome.Blocked("Gmail public OAuth client ID is not configured.")
        uri.getQueryParameter("error")?.let { return GmailOAuthOutcome.Blocked("Google authorization was not completed: $it") }
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val expectedState = prefs.getString(KEY_STATE, null).orEmpty()
        val verifier = prefs.getString(KEY_VERIFIER, null).orEmpty()
        if (!validateOAuthCallback(expectedState, OAuthCallback(code, state))) {
            return GmailOAuthOutcome.Blocked("Gmail authorization response did not match the pending request.")
        }
        return try {
            val token = exchangeCode(config, code, verifier, nowMillis)
            val identity = connector.discoverAccount(token, transport)
            val existing = accounts.list().firstOrNull {
                it.id.provider == Provider.GMAIL && it.providerAccountId == identity.providerAccountId
            }
            val account = (existing ?: ConnectedAccount(
                id = AccountId(Provider.GMAIL, "gmail-${UUID.randomUUID()}"),
                providerAccountId = identity.providerAccountId,
                displayName = identity.displayName,
                accountType = AccountType.GoogleConsumer,
                route = ConnectorRoute.OfficialProviderApi,
                state = ConnectionState.Authorized,
            )).copy(
                displayName = identity.displayName,
                state = ConnectionState.Authorized,
                userActionRequired = null,
            )
            tokens.put(account.id, token)
            accounts.upsert(account)
            prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()
            GmailOAuthOutcome.Connected(account)
        } catch (e: Exception) {
            GmailOAuthOutcome.Blocked("Gmail authorization could not finish: ${e.message ?: "provider request failed"}")
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
            if (status !in 200..299) error("Google token endpoint returned HTTP $status")
            val access = jsonString(response, "access_token") ?: error("Google response omitted access_token")
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

    private fun String.isGoogleClientId(): Boolean =
        endsWith(".apps.googleusercontent.com") &&
            substringBeforeLast(".apps.googleusercontent.com").isNotBlank()

    private fun redirectUriFor(clientId: String): String {
        val prefix = clientId.substringBeforeLast(".apps.googleusercontent.com")
        return "com.googleusercontent.apps.$prefix:$REDIRECT_PATH"
    }

    companion object {
        const val REDIRECT_PATH = "/oauth2redirect/google"
        private const val FILE = "mini_gmail_oauth_session"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "code_verifier"
    }
}
