package app.mini.hub.android.connector

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.AccountSyncReport
import app.mini.hub.connector.Capability
import app.mini.hub.connector.CapabilityResolver
import app.mini.hub.connector.CapabilitySnapshot
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorCatalog
import app.mini.hub.connector.ConnectorProviderGateway
import app.mini.hub.connector.ConnectorSyncEngine
import app.mini.hub.connector.GatePolicy
import app.mini.hub.connector.GmailSender
import app.mini.hub.connector.GoogleChatConnector
import app.mini.hub.connector.GoogleChatSender
import app.mini.hub.connector.OAuthProviderSpecs
import app.mini.hub.connector.OutlookGraphConnector
import app.mini.hub.connector.OutlookSender
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.connector.SlackConnector
import app.mini.hub.connector.SlackSender
import app.mini.hub.connector.TeamsChatConnector
import app.mini.hub.connector.TeamsChatSender
import app.mini.hub.connector.TelegramBotConnector
import app.mini.hub.connector.TelegramBotSender
import app.mini.hub.connector.TokenRefresher
import app.mini.hub.connector.XDirectMessageConnector
import app.mini.hub.connector.XDirectMessageSender
import app.mini.hub.domain.Provider
import app.mini.hub.persistence.ObservationStore

/** One connectable route shown in the Accounts screen. */
data class ConnectRouteInfo(
    val provider: Provider,
    val title: String,
    val accountKinds: String,
    val configured: Boolean,
    val status: String,
    /** Telegram uses a pasted bot token instead of a browser sign-in. */
    val usesTokenEntry: Boolean = false,
    /** Matrix bridge uses Homeserver URL + Token instead of a browser sign-in. */
    val usesMatrixEntry: Boolean = false,
    /** Debug WhatsApp companion QR pairing. Not Matrix. */
    val usesPhoneLocalPairing: Boolean = false,
)

/**
 * Owns every API route on Android: OAuth/token connect, refresh, background sync, full
 * conversation reads and the gated send gateway. Gmail keeps its specialized incremental host.
 */
class AndroidConnectorRegistry(
    context: Context,
    private val store: ObservationStore,
    private val digest: DigestProvider,
    private val transport: ProviderHttpTransport,
    private val onMessagesFetched: (ConnectedAccount, List<ProviderMessage>) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val accounts = AndroidConnectedAccountStore(appContext)
    private val tokens = AndroidKeystoreAccessTokenStore(appContext)
    private val gateEvidence = GateEvidenceStore(appContext)
    internal val whatsappHost = WhatsAppAndroidHost(appContext, accounts, tokens)

    val gmailOAuth = GmailOAuthClient(appContext)
    val outlookOAuth = OutlookOAuthClient(appContext)
    private val gmail = AndroidGmailConnector(appContext, store, digest, transport = transport, onMessagesFetched = onMessagesFetched)

    private val outlookConnector = OutlookGraphConnector()
    private val teamsConnector = TeamsChatConnector()
    private val slackConnector = SlackConnector()
    private val chatConnector = GoogleChatConnector()
    private val telegramConnector = TelegramBotConnector()
    private val whatsappConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.WHATSAPP, "whatsapp")
    private val whatsappBusinessConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.WHATSAPP_BUSINESS, "whatsapp")
    private val instagramConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.INSTAGRAM, "instagram")
    private val linkedinConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.LINKEDIN, "linkedin")
    private val xConnector = XDirectMessageConnector()

    private val googleClientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim().takeIf { it.endsWith(".apps.googleusercontent.com") }.orEmpty()

    val slack = PkceAccountConnector(
        appContext, OAuthProviderSpecs.slack(REDIRECT_BASE + "slack"), BuildConfig.SLACK_CLIENT_ID.trim(),
        slackConnector, transport,
        "create a Slack app, enable PKCE, add redirect ${REDIRECT_BASE}slack and the user scopes, then build with -Pmini.slackClientId=<client ID>.",
    )
    val x = PkceAccountConnector(
        appContext, OAuthProviderSpecs.xDirectMessages(REDIRECT_BASE + "x"), BuildConfig.X_CLIENT_ID.trim(),
        xConnector, transport,
        "create an X Native App (public client) with OAuth 2.0, callback ${REDIRECT_BASE}x and DM access, then build with -Pmini.xClientId=<client ID>.",
    )
    val teams = PkceAccountConnector(
        appContext, OAuthProviderSpecs.teams(REDIRECT_BASE + "teams"), BuildConfig.MICROSOFT_OAUTH_CLIENT_ID.trim(),
        teamsConnector, transport,
        "uses the Outlook Entra app: add redirect ${REDIRECT_BASE}teams and delegated Chat.Read and ChatMessage.Send. A work/school tenant may need admin consent.",
        PkceAccountConnector.microsoftAccountType,
    )
    val googleChat = PkceAccountConnector(
        appContext,
        // Google installed-app clients redirect to the reversed client ID scheme, shared with Gmail.
        OAuthProviderSpecs.googleChat(googleRedirect("chat")),
        googleClientId, chatConnector, transport,
        "uses the Gmail Google client: enable the Google Chat API, configure a Chat app in the same Cloud project, and add the Chat scopes to the consent screen.",
    )
        val telegram = TelegramBotAccountConnector(appContext, telegramConnector, transport)
    private val matrixBridges = mapOf(
        Provider.WHATSAPP to MatrixBridgeAccountConnector(appContext, whatsappConnector, transport),
        Provider.WHATSAPP_BUSINESS to MatrixBridgeAccountConnector(appContext, whatsappBusinessConnector, transport),
        Provider.INSTAGRAM to MatrixBridgeAccountConnector(appContext, instagramConnector, transport),
        Provider.LINKEDIN to MatrixBridgeAccountConnector(appContext, linkedinConnector, transport),
    )

    private val pkceRoutes get() = listOf(slack, x, teams, googleChat)

    private val outlookRefresher = outlookOAuth.refresher()

    val syncEngine = ConnectorSyncEngine(
        accounts = accounts,
        tokens = tokens,
        store = store,
        digest = digest,
        connectors = mapOf(
            Provider.OUTLOOK to outlookConnector,
            Provider.TEAMS to teamsConnector,
            Provider.SLACK to slackConnector,
            Provider.GOOGLE_CHAT to chatConnector,
            Provider.TELEGRAM to telegramConnector,
            Provider.TWITTER to xConnector,
            Provider.WHATSAPP to whatsappHost.connector,
            Provider.WHATSAPP_BUSINESS to whatsappBusinessConnector,
            Provider.INSTAGRAM to instagramConnector,
            Provider.LINKEDIN to linkedinConnector,
        ),
        refreshers = mapOf(
            Provider.OUTLOOK to outlookRefresher,
            Provider.TEAMS to teams.refresher(),
            Provider.SLACK to slack.refresher(),
            Provider.GOOGLE_CHAT to googleChat.refresher(),
            Provider.TWITTER to x.refresher(),
            // Telegram bot tokens do not expire; no refresher.
        ),
        transport = transport,
        onMessagesFetched = onMessagesFetched,
    )

    private fun configuredClients(): Set<Provider> = buildSet {
        if (outlookOAuth.isConfigured) { add(Provider.OUTLOOK) }
        if (teams.isConfigured) add(Provider.TEAMS)
        if (slack.isConfigured) add(Provider.SLACK)
        if (googleChat.isConfigured) add(Provider.GOOGLE_CHAT)
        if (x.isConfigured) add(Provider.TWITTER)
        // Matrix bridges don't need app-level build configuration variables
        add(Provider.WHATSAPP)
        add(Provider.WHATSAPP_BUSINESS)
        add(Provider.INSTAGRAM)
        add(Provider.LINKEDIN)
    }

    val gatePolicy = GatePolicy(configuredClients(), gateEvidence::evidenceFor)

    private val directGateway = ConnectorProviderGateway(
        accounts = accounts,
        tokens = tokens,
        senders = listOf(
            GmailSender(), OutlookSender(), TeamsChatSender(), SlackSender(),
            GoogleChatSender(), TelegramBotSender(), XDirectMessageSender(),
            whatsappHost.connector, whatsappBusinessConnector, instagramConnector, linkedinConnector,
        ),
        gates = gatePolicy,
        transport = transport,
        nowMillis = { System.currentTimeMillis() },
    )

    /** Refreshes an expired token through the provider's existing refresher before any send. */
    val gateway: app.mini.hub.action.ProviderGateway = RefreshingGateway(
        accounts, tokens, { account, token -> refreshForUse(account, token) }, directGateway,
    ) { System.currentTimeMillis() }

    /** Gmail uses its Google refresher; every other route uses the sync engine's refreshers. */
    private fun refreshForUse(account: ConnectedAccount, token: app.mini.hub.connector.StoredAccessToken): app.mini.hub.connector.StoredAccessToken? {
        val now = System.currentTimeMillis()
        return if (account.id.provider == Provider.GMAIL) gmail.refreshForUse(account, token, now)
        else syncEngine.refreshForUse(account, token, now)
    }

    /** Routes the Accounts screen may offer in this build. See [ConnectRoutePolicy]. */
    fun routes(): List<ConnectRouteInfo> = ConnectRoutePolicy.visible(allRoutes(), BuildConfig.DEBUG)

    /** Every route this registry knows, including ones never offered in the UI. */
    internal fun allRoutes(): List<ConnectRouteInfo> = listOf(
        ConnectRouteInfo(Provider.GMAIL, "Gmail", "Personal Gmail or Google Workspace", gmailOAuth.isConfigured, gmailOAuth.setupStatus),
        ConnectRouteInfo(Provider.OUTLOOK, "Outlook mail", "Personal Microsoft account or work/school", outlookOAuth.isConfigured, outlookOAuth.setupStatus),
        ConnectRouteInfo(Provider.TEAMS, "Microsoft Teams chats", "Work/school accounts only", teams.isConfigured, teams.setupStatus),
        ConnectRouteInfo(Provider.SLACK, "Slack", "Your user account in one workspace", slack.isConfigured, slack.setupStatus),
        ConnectRouteInfo(Provider.GOOGLE_CHAT, "Google Chat", "Google Workspace (consumer access varies)", googleChat.isConfigured, googleChat.setupStatus),
        ConnectRouteInfo(Provider.TWITTER, "X direct messages", "Your X account (paid API, 30-day history)", x.isConfigured, x.setupStatus),
        ConnectRouteInfo(
            Provider.TELEGRAM, "Telegram bot", "A bot you own; not your personal chats", true,
            "Paste a BotFather token. MINI sees only chats the bot is in. Telegram AI drafting stays off until its content-use terms are qualified.",
            usesTokenEntry = true,
        ),
        ConnectRouteInfo(
            Provider.WHATSAPP, "WhatsApp personal", "Linked device on this phone", whatsappHost.packaged,
            if (whatsappHost.packaged) "Scan the QR from WhatsApp > Linked devices. Debug companion session; unofficial multi-device protocol."
            else "WhatsApp runtime is not packaged in this build. Run native/whatsappRuntime/build-android.ps1.",
            usesPhoneLocalPairing = true,
        ),
        ConnectRouteInfo(
            Provider.WHATSAPP, "WhatsApp (Personal)", "Advanced Matrix bridge; not phone-local", true,
            "Advanced mode only: provide a user-controlled Matrix homeserver URL and access token. The bridge host can process plaintext; this is not a personal WhatsApp API connection.", usesMatrixEntry = true
        ),
        ConnectRouteInfo(
            Provider.WHATSAPP_BUSINESS, "WhatsApp Business App", "Advanced Matrix bridge; not Business API", true,
            "Advanced mode only: provide a user-controlled Matrix homeserver URL and access token. This is not WhatsApp Business Cloud API access.", usesMatrixEntry = true
        ),
        ConnectRouteInfo(
            Provider.INSTAGRAM, "Instagram", "Advanced Matrix bridge; hosting not qualified", true,
            "Advanced mode only: provide a user-controlled Matrix homeserver URL and access token. Review the bridge host's plaintext processing before use.", usesMatrixEntry = true
        ),
        ConnectRouteInfo(
            Provider.LINKEDIN, "LinkedIn", "Advanced Matrix bridge; hosting not qualified", true,
            "Advanced mode only: provide a user-controlled Matrix homeserver URL and access token. This does not prove LinkedIn inbox authorization.", usesMatrixEntry = true
        ),
    )

    fun launch(provider: Provider, activity: Activity): ConnectOutcome = when (provider) {
        Provider.GMAIL -> when (val o = gmailOAuth.launch(activity)) {
            is GmailOAuthOutcome.Started -> ConnectOutcome.Started(provider)
            is GmailOAuthOutcome.Blocked -> ConnectOutcome.Blocked(o.reason)
            is GmailOAuthOutcome.Connected -> ConnectOutcome.Connected(o.account)
        }
        Provider.OUTLOOK -> when (val o = outlookOAuth.launch(activity)) {
            is OutlookOAuthOutcome.Started -> ConnectOutcome.Started(provider)
            is OutlookOAuthOutcome.Blocked -> ConnectOutcome.Blocked(o.reason)
            is OutlookOAuthOutcome.Connected -> ConnectOutcome.Connected(o.account)
        }
        else -> pkceRoutes.firstOrNull { it.spec.provider == provider }?.launch(activity)
            ?: ConnectOutcome.Blocked("No browser sign-in for ${provider.wire}.")
    }

    /** Returns null when the URI is not an OAuth callback MINI is waiting for. */
    fun handlesRedirect(uri: Uri): Boolean =
        gmailOAuth.acceptsRedirect(uri) || outlookOAuth.acceptsRedirect(uri) || pkceRoutes.any { it.acceptsRedirect(uri) }

    /** Network I/O. Call off the main thread. Google Chat shares Gmail's scheme, so path decides. */
    fun completeRedirect(uri: Uri): ConnectOutcome {
        pkceRoutes.firstOrNull { it.acceptsRedirect(uri) }?.let { return it.handleRedirect(uri) }
        if (outlookOAuth.acceptsRedirect(uri)) return when (val o = outlookOAuth.handleRedirect(uri)) {
            is OutlookOAuthOutcome.Connected -> ConnectOutcome.Connected(o.account)
            is OutlookOAuthOutcome.Blocked -> ConnectOutcome.Blocked(o.reason)
            is OutlookOAuthOutcome.Started -> ConnectOutcome.Started(Provider.OUTLOOK)
        }
        if (gmailOAuth.acceptsRedirect(uri)) return when (val o = gmailOAuth.handleRedirect(uri)) {
            is GmailOAuthOutcome.Connected -> ConnectOutcome.Connected(o.account)
            is GmailOAuthOutcome.Blocked -> ConnectOutcome.Blocked(o.reason)
            is GmailOAuthOutcome.Started -> ConnectOutcome.Started(Provider.GMAIL)
        }
        return ConnectOutcome.Blocked("Unrecognized sign-in response.")
    }

    /** Syncs every connected account. One account's failure never affects another. */
    fun syncAll(trigger: String): SyncSummary {
        val now = System.currentTimeMillis()
        val gmailReports = gmail.syncAllConnected(now, trigger)
        val otherReports = syncEngine.syncAll(now).onEach { Log.i(LOG_TAG, it.logLine(trigger)) }
        runCatching { whatsappHost.persistIfDirty() }
        val needsAttention = gmailReports.count { it.state != app.mini.hub.connector.ConnectionState.Healthy && it.skippedReason != "disconnected" } +
            otherReports.count { it.state != app.mini.hub.connector.ConnectionState.Healthy && it.skippedReason != "disconnected" }
        return SyncSummary(
            accounts = gmailReports.size + otherReports.size,
            inserted = gmailReports.sumOf { it.inserted } + otherReports.sumOf { it.inserted },
            updated = otherReports.sumOf { it.updated },
            needsAttention = needsAttention,
            moreAvailable = gmailReports.any { it.moreAvailable },
        )
    }

    /**
     * Full conversation text from the provider, for reading and drafting. Returns null when the
     * provider has no conversation reader, so the caller keeps the stored timeline.
     */
    fun fetchConversation(account: ConnectedAccount, conversationId: String): List<ProviderMessage>? {
        var token = tokens.get(account.id) ?: return null
        if (token.isExpired(System.currentTimeMillis() + RefreshingGateway.EXPIRY_SKEW_MILLIS)) {
            token = refreshForUse(account, token) ?: return null
        }
        return when (account.id.provider) {
            Provider.GMAIL -> gmail.fetchThread(conversationId, account)
            Provider.OUTLOOK -> outlookConnector.fetchConversation(conversationId, token, transport)
            else -> null
        }
    }

    fun listAccounts(): List<ConnectedAccount> = accounts.list()

    data class SyncSummary(val accounts: Int, val inserted: Int, val updated: Int, val needsAttention: Int, val moreAvailable: Boolean = false)

    private val observationFactory = app.mini.hub.connector.ConnectorObservationFactory(digest)

    /**
     * Reads the full provider conversation, stores it (full bodies replace snippets for the same
     * message key) and returns the stored timeline. Null when there is no reader or no usable token.
     * Network I/O: call off the main thread.
     */
    fun loadConversation(account: ConnectedAccount, conversationId: app.mini.hub.domain.ScopedId): List<app.mini.hub.observation.ObservedNotification>? {
        if (conversationId.account != account.id) return null
        val messages = fetchConversation(account, conversationId.value) ?: return null
        val now = System.currentTimeMillis()
        val rows = messages.map { observationFactory.fromMessage(account.id, it, now) }
            .filter { it.conversationId == conversationId }
        if (rows.isNotEmpty()) store.persistBatch(rows)
        onMessagesFetched(account, messages)
        Log.i(LOG_TAG, "account=${account.id.localId} conversation loaded messages=${rows.size}")
        return store.getObservationsForConversation(conversationId)
    }

    /** Pasted BotFather token; validated with getMe. Network I/O, call off the main thread. */
    fun connectTelegram(botToken: String): ConnectOutcome = telegram.connect(botToken)

    fun startWhatsAppPairing() = whatsappHost.startPairing()
    fun pollWhatsAppPairing() = whatsappHost.pollPairing()
    fun cancelWhatsAppPairing() = whatsappHost.cancelPairing()
    fun completeWhatsAppLink(linked: app.mini.hub.connector.WhatsAppPairing.Linked): ConnectOutcome =
        ConnectOutcome.Connected(whatsappHost.completeLinked(linked))
    fun restoreWhatsAppSession() = whatsappHost.restoreOnStart()

    fun disconnectAccount(account: ConnectedAccount) {
        if (account.id.provider == Provider.WHATSAPP && account.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge) {
            whatsappHost.logout(account.id)
        }
        app.mini.hub.connector.AccountConnectionManager(
            accounts, tokens, app.mini.hub.domain.IdGenerator { "unused-$it" },
        ).disconnect(account.id)
    }

    fun connectMatrixBridge(provider: Provider, homeserverUrl: String, token: String): ConnectOutcome {
        if (!app.mini.hub.connector.PhoneLocalBridgePolicy.allowsRemoteHomeserverPlaintext()) {
            return ConnectOutcome.Blocked(
                "Remote Matrix homeserver bridges are not available. WhatsApp, Messenger, Instagram and LinkedIn require a phone-local session.",
            )
        }
        val hsUrlClean = homeserverUrl.trim().removeSuffix("/")
        val matrixBridge = matrixBridges[provider] ?: return ConnectOutcome.Blocked("Bridge not supported")
        return matrixBridge.connect("$hsUrlClean|${token.trim()}")
    }

    /**
     * What this account can do right now and why anything is off (missing permission, uncleared
     * gate, account state). Null for providers without an API route.
     */
    fun capabilities(account: ConnectedAccount): CapabilitySnapshot? =
        ConnectorCatalog.apiRouteFor(account.id.provider)?.let {
            CapabilityResolver.resolve(it, account.grantedScopes, gatePolicy.cleared(account), account.state)
        }

    /** True when a reviewed API reply would currently be allowed for this account. */
    fun canReplyViaApi(account: ConnectedAccount): Boolean =
        capabilities(account)?.enabled?.contains(Capability.Reply) == true

    private fun googleRedirect(suffix: String): String {
        val prefix = googleClientId.substringBefore(".apps.googleusercontent.com").ifBlank { "unconfigured" }
        return "com.googleusercontent.apps.$prefix:/oauth2redirect/$suffix"
    }

    companion object {
        const val LOG_TAG = "MiniUnifiedSync"
        const val REDIRECT_BASE = "app.mini.hub://oauth2redirect/"
    }
}
