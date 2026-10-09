package app.mini.hub.connector

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContains

class OAuthContractsTest {
    private val config = OAuthPublicClientConfiguration(
        clientId = "public-client.apps.googleusercontent.com",
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        redirectUri = "app.mini.hub:/oauth2redirect/google",
        scopes = setOf("https://www.googleapis.com/auth/gmail.readonly"),
    )

    @Test
    fun authorizationUsesPkceOfflineConsentAndNoClientSecret() {
        val request = OAuthAuthorizationRequestBuilder.build(config, "state-1", "verifier-1", "challenge-1")
        assertContains(request.url, "client_id=public-client.apps.googleusercontent.com")
        assertContains(request.url, "code_challenge=challenge-1")
        assertContains(request.url, "code_challenge_method=S256")
        assertContains(request.url, "access_type=offline")
        assertContains(request.url, "prompt=consent")
        assertFalse(request.url.contains("client_secret"))
        assertTrue(validateOAuthCallback("state-1", OAuthCallback("code-1", "state-1")))
        assertFalse(validateOAuthCallback("state-1", OAuthCallback("code-1", "wrong-state")))
    }
}
