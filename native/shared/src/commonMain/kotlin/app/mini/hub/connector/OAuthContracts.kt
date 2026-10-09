package app.mini.hub.connector

/** Configuration for an installed/public OAuth client. There is intentionally no client secret. */
data class OAuthPublicClientConfiguration(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scopes: Set<String>,
) {
    init {
        require(clientId.isNotBlank())
        require(authorizationEndpoint.startsWith("https://"))
        require(tokenEndpoint.startsWith("https://"))
        require(redirectUri.isNotBlank())
        require(scopes.isNotEmpty())
    }
}

data class OAuthAuthorizationRequest(
    val url: String,
    val state: String,
    val codeVerifier: String,
)

/** Builds the Google authorization request after the Android edge supplies random PKCE material. */
object OAuthAuthorizationRequestBuilder {
    fun build(
        configuration: OAuthPublicClientConfiguration,
        state: String,
        codeVerifier: String,
        codeChallenge: String,
    ): OAuthAuthorizationRequest {
        require(state.isNotBlank() && codeVerifier.isNotBlank() && codeChallenge.isNotBlank())
        val params = linkedMapOf(
            "client_id" to configuration.clientId,
            "redirect_uri" to configuration.redirectUri,
            "response_type" to "code",
            "scope" to configuration.scopes.sorted().joinToString(" "),
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        return OAuthAuthorizationRequest(
            url = configuration.authorizationEndpoint + "?" + params.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            },
            state = state,
            codeVerifier = codeVerifier,
        )
    }

    private fun encode(value: String): String = value
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace(":", "%3A")
        .replace("/", "%2F")
        .replace("+", "%2B")
}

data class OAuthCallback(val code: String, val state: String)

fun validateOAuthCallback(expectedState: String, callback: OAuthCallback): Boolean =
    expectedState.isNotBlank() && callback.code.isNotBlank() && expectedState == callback.state
