package app.mini.hub.connector

import app.mini.hub.domain.Provider

/** How a provider shapes its token endpoint response. */
enum class TokenResponseStyle {
    /** RFC 6749 top-level access_token / refresh_token / expires_in / scope (Google, Microsoft, X). */
    Standard,
    /** Slack `oauth.v2.access` user-token install: credentials under `authed_user`, comma scopes. */
    SlackUserToken,
}

/**
 * A public-client PKCE route. No client secret exists anywhere in this type. Endpoints, scope
 * names and parameter names were rechecked against provider documentation on 2026-10-10.
 */
data class PkceProviderSpec(
    val id: String,
    val provider: Provider,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val redirectUri: String,
    /** Slack requests user scopes with `user_scope`; everyone else uses `scope`. */
    val scopeParam: String = "scope",
    val scopeSeparator: String = " ",
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    /** Microsoft requires the scope again on code exchange and refresh. */
    val sendScopeOnTokenRequests: Boolean = false,
    val style: TokenResponseStyle = TokenResponseStyle.Standard,
) {
    init {
        require(authorizationEndpoint.startsWith("https://") && tokenEndpoint.startsWith("https://"))
        require(scopes.isNotEmpty())
        // Custom schemes: "app.mini.hub://oauth2redirect/x" or Google's "com.googleusercontent.apps.N:/path".
        require(redirectUri.contains(":/"))
    }

    /** Exact callback match, ignoring the query string. Android must compare the whole URI. */
    fun isRedirect(uriWithoutQuery: String): Boolean = uriWithoutQuery == redirectUri
}

object PkceAuthorization {
    fun authorizeUrl(spec: PkceProviderSpec, clientId: String, state: String, codeChallenge: String): String {
        require(clientId.isNotBlank() && state.isNotBlank() && codeChallenge.isNotBlank())
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to spec.redirectUri,
            spec.scopeParam to spec.scopes.joinToString(spec.scopeSeparator),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        params.putAll(spec.extraAuthorizeParams)
        return spec.authorizationEndpoint + "?" + formEncode(params)
    }

    fun codeExchangeForm(spec: PkceProviderSpec, clientId: String, code: String, codeVerifier: String): Map<String, String> =
        buildMap {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", spec.redirectUri)
            put("code_verifier", codeVerifier)
            if (spec.sendScopeOnTokenRequests) put("scope", spec.scopes.joinToString(" "))
        }

    fun refreshForm(spec: PkceProviderSpec, clientId: String, refreshToken: String): Map<String, String> =
        buildMap {
            put("grant_type", "refresh_token")
            put("client_id", clientId)
            put("refresh_token", refreshToken)
            if (spec.sendScopeOnTokenRequests) put("scope", spec.scopes.joinToString(" "))
        }
}

data class TokenGrant(val token: StoredAccessToken, val grantedScopes: Set<String>, val idToken: String? = null)

sealed class TokenEndpointResult {
    data class Granted(val grant: TokenGrant) : TokenEndpointResult()
    /** [revoked] = the refresh credential is dead and the user must reconnect. */
    data class Rejected(val code: String, val revoked: Boolean) : TokenEndpointResult()
}

/** Parses token endpoint responses. Messages never contain token text. */
object TokenResponses {
    private val REVOKED = setOf("invalid_grant", "invalid_refresh_token", "token_revoked", "invalid_auth", "token_expired")

    fun parse(
        style: TokenResponseStyle,
        status: Int,
        body: String,
        nowMillis: Long,
        previousRefreshToken: String? = null,
    ): TokenEndpointResult {
        val json = JsonValue.parseOrNull(body)
        val ok = status in 200..299 && (style != TokenResponseStyle.SlackUserToken || json?.get("ok")?.bool == true)
        if (!ok) {
            val code = json?.get("error")?.let { it.string ?: it.str("code") } ?: "http_$status"
            return TokenEndpointResult.Rejected(code, code in REVOKED)
        }
        // Slack user installs nest the user credential; refresh responses may return it top-level.
        val source = if (style == TokenResponseStyle.SlackUserToken) json?.get("authed_user")?.takeIf { it["access_token"] != null } ?: json else json
        val access = source?.str("access_token") ?: return TokenEndpointResult.Rejected("missing_access_token", false)
        val expires = source.str("expires_in")?.toLongOrNull()
        val scopeText = source.str("scope").orEmpty()
        val scopes = scopeText.split(' ', ',').map(::normalizeScope).filter { it.isNotBlank() }.toSet()
        return TokenEndpointResult.Granted(
            TokenGrant(
                StoredAccessToken(
                    value = access,
                    expiresAtMillis = expires?.let { nowMillis + it * 1000L },
                    refreshToken = source.str("refresh_token") ?: previousRefreshToken,
                ),
                scopes,
                source.str("id_token"),
            ),
        )
    }

    /** Microsoft can return fully qualified Graph scopes; MINI's catalog uses short names. */
    fun normalizeScope(raw: String): String = raw.trim().removePrefix("https://graph.microsoft.com/")

    fun toRefreshOutcome(result: TokenEndpointResult): RefreshOutcome = when (result) {
        is TokenEndpointResult.Granted -> RefreshOutcome.Refreshed(result.grant.token)
        is TokenEndpointResult.Rejected -> if (result.revoked) RefreshOutcome.Revoked(result.code) else RefreshOutcome.Transient(result.code)
    }
}

/**
 * Personal Microsoft accounts all share one tenant ID in the id_token `tid` claim. Anything else is
 * a work/school (Entra) tenant. Reading the claim avoids guessing from email domains.
 */
object MicrosoftAccountKind {
    const val CONSUMER_TENANT_ID = "9188040d-6c67-4c5b-b112-36a304b66dad"

    fun fromIdToken(idToken: String?): AccountType {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return AccountType.Unknown
        val claims = runCatching { JsonValue.parse(Base64Url.decode(payload).decodeToString()) }.getOrNull() ?: return AccountType.Unknown
        return when (val tid = claims.str("tid")) {
            null -> AccountType.Unknown
            CONSUMER_TENANT_ID -> AccountType.MicrosoftPersonal
            else -> if (tid.isNotBlank()) AccountType.MicrosoftWorkSchool else AccountType.Unknown
        }
    }
}

/** Route specs for the PKCE connectors added after Gmail and Outlook. */
object OAuthProviderSpecs {
    private const val MS_AUTHORITY = "https://login.microsoftonline.com"

    /** Slack user token (not bot). Custom-scheme redirects require PKCE and get rotating tokens. */
    fun slack(redirectUri: String) = PkceProviderSpec(
        id = "slack", provider = Provider.SLACK, displayName = "Slack",
        authorizationEndpoint = "https://slack.com/oauth/v2/authorize",
        tokenEndpoint = "https://slack.com/api/oauth.v2.access",
        scopes = listOf(
            "channels:read", "channels:history", "groups:read", "groups:history",
            "im:read", "im:history", "mpim:read", "mpim:history", "users:read", "chat:write",
        ),
        redirectUri = redirectUri, scopeParam = "user_scope", scopeSeparator = ",",
        style = TokenResponseStyle.SlackUserToken,
    )

    /** X OAuth 2.0 PKCE; offline.access is required for a refresh token. */
    fun xDirectMessages(redirectUri: String) = PkceProviderSpec(
        id = "x", provider = Provider.TWITTER, displayName = "X Direct Messages",
        authorizationEndpoint = "https://x.com/i/oauth2/authorize",
        tokenEndpoint = "https://api.x.com/2/oauth2/token",
        scopes = listOf("dm.read", "dm.write", "tweet.read", "users.read", "offline.access"),
        redirectUri = redirectUri,
    )

    /** Teams chats are work/school only, so the `organizations` authority excludes personal MSAs. */
    fun teams(redirectUri: String) = PkceProviderSpec(
        id = "teams", provider = Provider.TEAMS, displayName = "Microsoft Teams",
        authorizationEndpoint = "$MS_AUTHORITY/organizations/oauth2/v2.0/authorize",
        tokenEndpoint = "$MS_AUTHORITY/organizations/oauth2/v2.0/token",
        scopes = listOf("Chat.Read", "ChatMessage.Send", "User.Read", "offline_access", "openid", "profile"),
        redirectUri = redirectUri, extraAuthorizeParams = mapOf("prompt" to "select_account"),
        sendScopeOnTokenRequests = true,
    )

    fun googleChat(redirectUri: String) = PkceProviderSpec(
        id = "google_chat", provider = Provider.GOOGLE_CHAT, displayName = "Google Chat",
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scopes = listOf(
            "openid", "email",
            "https://www.googleapis.com/auth/chat.spaces.readonly",
            "https://www.googleapis.com/auth/chat.messages.readonly",
            "https://www.googleapis.com/auth/chat.messages.create",
        ),
        redirectUri = redirectUri,
        extraAuthorizeParams = mapOf("access_type" to "offline", "prompt" to "consent"),
    )
}
