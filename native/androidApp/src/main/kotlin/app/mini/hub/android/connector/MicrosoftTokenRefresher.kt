package app.mini.hub.android.connector

import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.RefreshOutcome
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.TokenRefresher
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** MSAL / Microsoft Entra public-client token refresher for Outlook accounts. */
class MicrosoftTokenRefresher(
    private val clientId: String = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID.trim(),
    private val tokenEndpoint: String = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
) : TokenRefresher {
    override fun refresh(token: StoredAccessToken, nowMillis: Long): RefreshOutcome {
        val refreshToken = token.refreshToken ?: return RefreshOutcome.NoRefreshToken
        if (clientId.isBlank()) return RefreshOutcome.Transient("client_not_configured")
        val body = mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "scope" to "Mail.Read Mail.Send offline_access openid profile User.Read",
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
                val access = jsonValue(response, "access_token") ?: return RefreshOutcome.Transient("missing_access_token")
                val expires = jsonValue(response, "expires_in")?.toLongOrNull()
                val newRefresh = jsonValue(response, "refresh_token") ?: refreshToken
                RefreshOutcome.Refreshed(
                    StoredAccessToken(
                        value = access,
                        expiresAtMillis = expires?.let { nowMillis + it * 1000L },
                        refreshToken = newRefresh,
                    ),
                )
            } else {
                val code = jsonValue(response, "error") ?: "http_$status"
                if (code == "invalid_grant" || code == "unauthorized_client") {
                    RefreshOutcome.Revoked(code)
                } else {
                    RefreshOutcome.Transient("http_$status:$code")
                }
            }
        } catch (e: Exception) {
            RefreshOutcome.Transient(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonValue(json: String, name: String): String? =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([0-9]+))")
            .find(json)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
}
