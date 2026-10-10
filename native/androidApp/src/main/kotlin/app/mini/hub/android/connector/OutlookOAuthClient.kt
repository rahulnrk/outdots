package app.mini.hub.android.connector

import android.app.Activity
import android.content.Context
import android.net.Uri
import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.OutlookGraphConnector
import app.mini.hub.connector.PkceProviderSpec
import app.mini.hub.connector.RefreshOutcome
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.TokenRefresher
import app.mini.hub.domain.Provider

sealed class OutlookOAuthOutcome {
    data class Started(val authorizationUrl: String) : OutlookOAuthOutcome()
    data class Connected(val account: ConnectedAccount) : OutlookOAuthOutcome()
    data class Blocked(val reason: String) : OutlookOAuthOutcome()
}

/**
 * Outlook mail sign-in for personal Microsoft accounts and work/school accounts. Uses the
 * `common` authority so either kind can sign in, `prompt=select_account` so the user chooses,
 * and the id_token tenant claim (not the email domain) to record which kind was used.
 * Only scopes Microsoft actually granted are stored, so a declined Mail.Send keeps send off.
 */
object OutlookRoute {
    const val REDIRECT_URI = "app.mini.hub://oauth2redirect/outlook"
    val SCOPES = listOf("Mail.Read", "Mail.Send", "User.Read", "offline_access", "openid", "profile")

    val spec = PkceProviderSpec(
        id = "outlook", provider = Provider.OUTLOOK, displayName = "Outlook",
        authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        scopes = SCOPES, redirectUri = REDIRECT_URI,
        extraAuthorizeParams = mapOf("prompt" to "select_account"),
        sendScopeOnTokenRequests = true,
    )
}

class OutlookOAuthClient(context: Context, clientId: String = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID.trim()) {
    private val host = PkceAccountConnector(
        context, OutlookRoute.spec, clientId, OutlookGraphConnector(), AndroidProviderHttpTransport(),
        "register an Entra app (personal + work/school accounts), add the mobile redirect ${OutlookRoute.REDIRECT_URI} " +
            "with delegated Mail.Read, Mail.Send, User.Read and offline_access, then build with -Pmini.outlookOAuthClientId=<client ID>.",
        PkceAccountConnector.microsoftAccountType,
    )

    val isConfigured: Boolean get() = host.isConfigured
    val setupStatus: String get() = host.setupStatus

    fun acceptsRedirect(uri: Uri): Boolean = host.acceptsRedirect(uri)

    fun launch(activity: Activity): OutlookOAuthOutcome = when (val o = host.launch(activity)) {
        is ConnectOutcome.Started -> OutlookOAuthOutcome.Started(OutlookRoute.spec.authorizationEndpoint)
        is ConnectOutcome.Blocked -> OutlookOAuthOutcome.Blocked(o.reason)
        is ConnectOutcome.Connected -> OutlookOAuthOutcome.Connected(o.account)
    }

    fun handleRedirect(uri: Uri, nowMillis: Long = System.currentTimeMillis()): OutlookOAuthOutcome =
        when (val o = host.handleRedirect(uri, nowMillis)) {
            is ConnectOutcome.Connected -> OutlookOAuthOutcome.Connected(o.account)
            is ConnectOutcome.Blocked -> OutlookOAuthOutcome.Blocked(o.reason)
            is ConnectOutcome.Started -> OutlookOAuthOutcome.Started(OutlookRoute.spec.authorizationEndpoint)
        }

    fun refresher(): TokenRefresher = host.refresher()

    companion object {
        const val REDIRECT_URI = OutlookRoute.REDIRECT_URI
    }
}
