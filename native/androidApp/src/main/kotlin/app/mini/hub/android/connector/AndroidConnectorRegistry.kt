package app.mini.hub.android.connector

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.BuildConfig
import app.mini.hub.connector.AccountSyncReport
import app.mini.hub.connector.AccountOperationCoordinator
import app.mini.hub.connector.AccountConnectionManager
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.Capability
import app.mini.hub.connector.CapabilityResolver
import app.mini.hub.connector.CapabilitySnapshot
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorCatalog
import app.mini.hub.connector.ConnectorRoute
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
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.TokenRefresher
import app.mini.hub.connector.XDirectMessageConnector
import app.mini.hub.connector.XDirectMessageSender
import app.mini.hub.domain.AccountId
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
    /** Debug Instagram phone-local owner sign-in. Not Matrix and not the official token paste route. */
    val usesInstagramPhoneLocalSignIn: Boolean = false,
    /** Instagram official API with a pasted long-lived token. */
    val usesInstagramToken: Boolean = false,
    /** Notification observer plus native-app handoff. This is not an authenticated account. */
    val usesNotificationFallback: Boolean = false,
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
    private val dispatchAuthorizationCheck: (app.mini.hub.action.OutboxCommand, app.mini.hub.action.ExactActionPayload) -> Boolean = { _, _ -> true },
) {
    private val appContext = context.applicationContext
    private val accounts = AndroidConnectedAccountStore(appContext)
    private val tokens = AndroidKeystoreAccessTokenStore(appContext)
    private val operations = AccountOperationCoordinator.Shared
    private val gateEvidence = GateEvidenceStore(appContext)
    internal val whatsappHost = WhatsAppAndroidHost(appContext, accounts, tokens)

    val gmailOAuth = GmailOAuthClient(appContext)
    val outlookOAuth = OutlookOAuthClient(appContext)
    private val gmail = AndroidGmailConnector(appContext, store, digest, transport = transport, onMessagesFetched = onMessagesFetched, operations = operations)

    private val outlookConnector = OutlookGraphConnector()
    private val teamsConnector = TeamsChatConnector()
    private val slackConnector = SlackConnector()
    private val chatConnector = GoogleChatConnector()
    private val telegramConnector = TelegramBotConnector()
    private val whatsappConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.WHATSAPP, "whatsapp")
    private val whatsappBusinessConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.WHATSAPP_BUSINESS, "whatsapp")
    private val instagramConnector = app.mini.hub.connector.MatrixBridgeConnector(Provider.INSTAGRAM, "instagram")
    private val instagramOfficial = app.mini.hub.connector.InstagramApiConnector()
    val instagram = InstagramAccountConnector(appContext, instagramOfficial, transport)
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
            Provider.INSTAGRAM to instagramOfficial,
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
        operations = operations,
        clock = app.mini.hub.domain.SystemClock,
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
            whatsappHost.connector, whatsappBusinessConnector, app.mini.hub.connector.InstagramApiSender(), linkedinConnector,
        ),
        gates = gatePolicy,
        transport = transport,
        nowMillis = { System.currentTimeMillis() },
        transportForAccount = ::requestTransport,
    )

    /** Refreshes an expired token through the provider's existing refresher before any send. */
    val gateway: app.mini.hub.action.ProviderGateway = RefreshingGateway(
        accounts, tokens, { account, token -> refreshForUse(account, token) }, directGateway,
        operations = operations,
        admissionAllowed = { app.mini.hub.connector.AccountRetryAdmission.allowed(store, it, System.currentTimeMillis()) },
        authorizationCheck = dispatchAuthorizationCheck,
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
            Provider.INSTAGRAM, "Instagram (Business/Creator)", "Official API · paste your token", true,
            "Official Meta API. Reads your 20 latest messages per chat and replies within 24h; later replies open Instagram with your text.",
            usesInstagramToken = true,
        ),
        ConnectRouteInfo(
            Provider.INSTAGRAM,
            "Instagram personal",
            "Phone-local sign-in on this device",
            configured = true,
            status = "Owner signs in on this phone. Bounded recent reads and foreground receiving only; sending stays disabled. Not live-qualified.",
            usesInstagramPhoneLocalSignIn = true,
        ),
        ConnectRouteInfo(
            Provider.WHATSAPP, "WhatsApp personal", "Linked device on this phone", whatsappHost.packaged,
            if (whatsappHost.packaged) "On this phone: Connect, enter your number, then enter the code in WhatsApp > Linked devices > Link with phone number. Debug companion session; unofficial multi-device protocol."
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
        ConnectRouteInfo(
            Provider.LINKEDIN,
            "LinkedIn notifications (personal)",
            "Notification-only observer; no LinkedIn login",
            configured = true,
            status = "Enable Android Notification Access and select LinkedIn under Sources. Replies open LinkedIn with your reviewed text.",
            usesNotificationFallback = true,
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
            otherReports.count {
                it.state != app.mini.hub.connector.ConnectionState.Healthy &&
                    it.skippedReason != "disconnected" &&
                    it.skippedReason != "phone_local_instagram"
            }
        return SyncSummary(
            accounts = gmailReports.size + otherReports.size,
            inserted = gmailReports.sumOf { it.inserted } + otherReports.sumOf { it.inserted },
            updated = otherReports.sumOf { it.updated },
            needsAttention = needsAttention,
            moreAvailable = gmailReports.any { it.moreAvailable },
        )
    }

    /** Drains companion-session messages into MINI. Returns inserted+updated count. */
    fun ingestWhatsApp(): Int {
        val account = accounts.list().firstOrNull {
            it.id.provider == Provider.WHATSAPP && it.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge
        } ?: return 0
        val report = syncEngine.syncAccount(account, System.currentTimeMillis())
        runCatching { whatsappHost.persistIfDirty() }
        return report.inserted + report.updated
    }

    /** Drains the phone-local Instagram session into the canonical observation store. */
    fun ingestInstagram(): InstagramReadIngestor.Result {
        val account = accounts.list().firstOrNull {
            it.id.provider == Provider.INSTAGRAM && it.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge
        } ?: return InstagramReadIngestor.Result.Failed("instagram_phone_local_not_connected")
        val result = InstagramReadIngestor(appContext, store).sync(account.id)
        val now = System.currentTimeMillis()
        when (result) {
            is InstagramReadIngestor.Result.Success -> {
                val summaries = store.getConversationSummaries(account.id)
                val summaryDiag = summaries.joinToString(";") { summary ->
                    "pkg=${summary.packageId},cat=${summary.category},cov=${summary.coverage},msgs=${summary.observationCount},evidence=${summary.messagingEvidence},snippetBlank=${summary.latestSnippet.isBlank()}"
                }
                android.util.Log.i(
                    LOG_TAG,
                    "instagram_phone_local_ingest persisted=${result.persistedCount} upstream_conversations=${result.view.conversations.size} stored_conversations=${summaries.size} partial=${result.partial} summaries=$summaryDiag",
                )
                accounts.upsert(
                    account.copy(
                        state = ConnectionState.Healthy,
                        userActionRequired = if (result.partial) {
                            "Partial history: only a bounded recent window is fetched while MINI is open."
                        } else null,
                        lastSuccessfulSyncMillis = now,
                    ),
                )
            }
            is InstagramReadIngestor.Result.Failed -> {
                if (result.code == "session_expired") {
                    accounts.upsert(
                        account.copy(
                            state = ConnectionState.Expired,
                            userActionRequired = "Reconnect Instagram to resume this account. Local history is kept.",
                        ),
                    )
                }
            }
        }
        return result
    }

    fun completeInstagramPhoneLocal(
        accountKey: String,
        username: String?,
    ): ConnectOutcome {
        val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull()
            ?: return ConnectOutcome.Blocked("Instagram account key was invalid.")
        if (accountId.provider != Provider.INSTAGRAM) {
            return ConnectOutcome.Blocked("Verified account is not Instagram.")
        }
        val session = SqlCipherInstagramSessionStore(appContext).load(accountId)
            ?: return ConnectOutcome.Blocked("Sign in again. No encrypted Instagram session is stored for this account.")
        // Touch the session object so a missing store cannot silently succeed.
        if (session.ciphertext.isEmpty()) {
            return ConnectOutcome.Blocked("Encrypted Instagram session was empty.")
        }
        val display = username?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" }
            ?: accountId.localId
        val account = ConnectedAccount(
            id = accountId,
            providerAccountId = accountId.localId,
            displayName = display,
            accountType = AccountType.Unknown,
            route = ConnectorRoute.PhoneLocalBridge,
            state = ConnectionState.Healthy,
            grantedScopes = setOf(PHONE_LOCAL_INSTAGRAM_SCOPE),
            userActionRequired = "Partial history: only a bounded recent window is fetched while MINI is open.",
        )
        AccountConnectionManager.installCredential(
            accounts,
            tokens,
            account,
            StoredAccessToken("phone-local-session", expiresAtMillis = null),
        )
        return ConnectOutcome.Connected(account)
    }

    /**
     * Full conversation text from the provider, for reading and drafting. Returns null when the
     * provider has no conversation reader, so the caller keeps the stored timeline.
     */
    fun fetchConversation(account: ConnectedAccount, conversationId: String): List<ProviderMessage>? =
        operations.withAccount(account.id) { fetchConversationInternal(account, conversationId) }

    private fun fetchConversationInternal(account: ConnectedAccount, conversationId: String): List<ProviderMessage>? {
        val current = accounts.get(account.id) ?: return null
        if (current.state == app.mini.hub.connector.ConnectionState.Disconnected ||
            !app.mini.hub.connector.AccountRetryAdmission.allowed(store, current, System.currentTimeMillis())) return null
        val transport = requestTransport(current)
        var token = tokens.get(account.id) ?: return null
        if (token.isExpired(System.currentTimeMillis() + RefreshingGateway.EXPIRY_SKEW_MILLIS)) {
            token = refreshForUse(account, token) ?: return null
        }
        return when (account.id.provider) {
            Provider.GMAIL -> gmail.fetchThread(conversationId, account)
            Provider.OUTLOOK -> outlookConnector.fetchConversation(conversationId, token, transport, app.mini.hub.connector.EmailAddress.extract(account.displayName))
            Provider.SLACK -> runCatching { slackConnector.fetchConversation(account, conversationId, token, transport) }.getOrNull()
            Provider.TEAMS -> runCatching { teamsConnector.fetchConversation(account, conversationId, token, transport) }.getOrNull()
            Provider.GOOGLE_CHAT -> runCatching { chatConnector.fetchConversation(account, conversationId, token, transport) }.getOrNull()
            Provider.TWITTER -> runCatching { xConnector.fetchConversation(account, conversationId, token, transport) }.getOrNull()
            Provider.INSTAGRAM -> if (account.route == app.mini.hub.connector.ConnectorRoute.OfficialProviderApi) {
                runCatching { instagramOfficial.fetchConversationWith(transport, token, conversationId, account.providerAccountId) }.getOrNull()
            } else null
            Provider.WHATSAPP -> if (account.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge) {
                whatsappHost.conversation(conversationId)
            } else null
            else -> null
        }
    }

    private fun requestTransport(account: ConnectedAccount): ProviderHttpTransport =
        app.mini.hub.connector.AccountRequestTransport(account, store, transport) { System.currentTimeMillis() }

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

    fun whatsappMediaThumbnail(id: String) = whatsappHost.mediaThumbnail(id)
    fun whatsappDownloadMedia(id: String) = whatsappHost.downloadMedia(id)
    fun whatsappLastMediaError() = whatsappHost.lastMediaError()

    /** One way to reach a person through a connected account. */
    data class ContactRoute(val provider: Provider, val accountId: app.mini.hub.domain.AccountId, val address: String, val name: String, val label: String)

    /**
     * Finds a person by name or number across every connected account. WhatsApp uses the linked
     * session's contacts; email and chat accounts use people already seen in synced conversations.
     */
    fun findContacts(query: String): List<ContactRoute> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = mutableListOf<ContactRoute>()
        accounts.list().filter { it.state != app.mini.hub.connector.ConnectionState.Disconnected }.forEach { account ->
            when {
                account.id.provider == Provider.WHATSAPP && account.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge ->
                    whatsappHost.searchContacts(q).forEach { (jid, name) ->
                        out += ContactRoute(Provider.WHATSAPP, account.id, jid, name, "WhatsApp · " + (if (jid.endsWith("@g.us")) "group" else "+" + jid.substringBefore('@')))
                    }
                else -> {
                    val lq = q.lowercase()
                    store.getConversationSummaries(account.id)
                        .filter { it.title.lowercase().contains(lq) || it.senderHint.lowercase().contains(lq) }
                        .distinctBy { it.senderHint.lowercase() }
                        .take(8)
                        .forEach { s ->
                            val addr = app.mini.hub.connector.EmailAddress.extract(s.senderHint) ?: s.conversationId.value
                            out += ContactRoute(account.id.provider, account.id, addr, s.senderHint.substringBefore('<').trim().ifBlank { s.title }, "${app.mini.hub.observation.SourceAppNames.forPackage(s.packageId)} · $addr")
                        }
                }
            }
        }
        return out.distinctBy { "${it.provider}|${it.address}" }.take(30)
    }

    private val mediaCache = EncryptedAttachmentCache(appContext)

    /** Downloads a Gmail attachment using only [accountId], into an encrypted cache then a short-lived export. */
    fun gmailDownloadAttachment(accountId: app.mini.hub.domain.AccountId, ref: String): java.io.File? {
        val parsed = app.mini.hub.connector.GmailAttachmentRef.parse(ref, accountId) ?: return null
        val scoped = app.mini.hub.connector.GmailAttachmentRef.resolveAccount(accountId, parsed) ?: return null
        val account = accounts.get(scoped) ?: return null
        if (account.id.provider != Provider.GMAIL) return null
        return mediaCache.export(scoped, parsed.messageId, parsed.attachmentId, parsed.fileName) {
            runCatching { gmail.fetchAttachment(account, parsed.messageId, parsed.attachmentId) }.getOrNull()
        }
    }

    fun sweepAttachmentExports(): Int = mediaCache.sweepStaleExports()
    fun startWhatsAppPairing() = whatsappHost.startPairing()
    fun startWhatsAppPhonePairing(phoneE164: String) = whatsappHost.startPhonePairing(phoneE164)
    fun pollWhatsAppPairing() = whatsappHost.pollPairing()
    fun cancelWhatsAppPairing() = whatsappHost.cancelPairing()
    fun completeWhatsAppLink(linked: app.mini.hub.connector.WhatsAppPairing.Linked): ConnectOutcome =
        ConnectOutcome.Connected(whatsappHost.completeLinked(linked))
    fun restoreWhatsAppSession() = whatsappHost.restoreOnStart()

    fun disconnectAccount(account: ConnectedAccount) = operations.withAccount(account.id) {
        if (account.id.provider == Provider.WHATSAPP && account.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge) {
            whatsappHost.logout(account.id)
        }
        if (account.id.provider == Provider.INSTAGRAM && account.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge) {
            SqlCipherInstagramSessionStore(appContext).clear(account.id)
        }
        app.mini.hub.connector.AccountConnectionManager(
            accounts, tokens, app.mini.hub.domain.IdGenerator { "unused-$it" }, operations,
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
        const val PHONE_LOCAL_INSTAGRAM_SCOPE = "instagram.phone-local-session"
    }
}
