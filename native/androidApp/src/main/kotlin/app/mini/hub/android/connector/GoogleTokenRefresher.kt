package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.StoredAccessToken
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Outcome of using a stored refresh token. Never carries token text in messages. */
sealed class TokenRefreshOutcome {
    data class Refreshed(val token: StoredAccessToken) : TokenRefreshOutcome()
    /** Google rejected the refresh token (invalid_grant): the user revoked access or it expired. */
    data class Revoked(val errorCode: String) : TokenRefreshOutcome()
    data class NoRefreshToken(val reason: String = "no_refresh_token") : TokenRefreshOutcome()
    data class Transient(val reason: String) : TokenRefreshOutcome()
}

/** Public-client refresh grant. No client secret is used or accepted. */
class GoogleTokenRefresher(
    private val clientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim(),
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) {
    fun refresh(current: StoredAccessToken, nowMillis: Long): TokenRefreshOutcome {
        val refreshToken = current.refreshToken ?: return TokenRefreshOutcome.NoRefreshToken()
        if (clientId.isBlank()) return TokenRefreshOutcome.Transient("client_not_configured")
        val body = mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ).entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
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
            if (status in 200..299) {
                val access = jsonValue(response, "access_token")
                    ?: return TokenRefreshOutcome.Transient("missing_access_token")
                val expires = jsonValue(response, "expires_in")?.toLongOrNull()
                TokenRefreshOutcome.Refreshed(
                    StoredAccessToken(
                        value = access,
                        expiresAtMillis = expires?.let { nowMillis + it * 1000L },
                        // Google usually omits a new refresh token. Keep the existing one.
                        refreshToken = jsonValue(response, "refresh_token") ?: refreshToken,
                    ),
                )
            } else {
                val code = jsonValue(response, "error") ?: "http_$status"
                Log.w(LOG_TAG, "Token refresh rejected: HTTP $status ($code)")
                if (code == "invalid_grant") TokenRefreshOutcome.Revoked(code) else TokenRefreshOutcome.Transient(code)
            }
        } catch (e: Exception) {
            TokenRefreshOutcome.Transient(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonValue(json: String, name: String): String? =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([0-9]+))")
            .find(json)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }

    companion object { const val LOG_TAG = "MiniGmailSync" }
}
