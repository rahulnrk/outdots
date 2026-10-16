package app.mini.hub.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.ConnectOutcome
import app.mini.hub.android.connector.GmailSyncJobService
import app.mini.hub.android.notification.MiniNotificationListenerService
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxScreen
import app.mini.hub.android.ui.InboxViewModel
import app.mini.hub.android.ui.MiniTheme
import app.mini.hub.domain.Provider
import app.mini.hub.style.StyleContext

/**
 * Main Android host Activity rendering the private communication hub.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: InboxViewModel
    private lateinit var handoffLauncher: AndroidNativeHandoffLauncher
    private val connectionStatus = mutableStateOf<String?>(null)
    private val accountsVersion = mutableStateOf(0)
    private val styleLearningEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiniHubRuntime.init(applicationContext)
        val storage = MiniHubRuntime.storage
        if (storage is RuntimeStorage.Failed) {
            // Blocking: no inbox, no accounts, no sending. Nothing would be durable.
            setContent {
                MaterialTheme {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Text("MINI cannot open its storage", style = MaterialTheme.typography.headlineSmall)
                        Text(storage.userMessage, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            return
        }
        styleLearningEnabled.value = MiniHubRuntime.styleEngine.getConsent().isEnabled
        // One background job syncs every connected account (Gmail and the other API routes).
        GmailSyncJobService.schedulePeriodic(applicationContext)
        val registry = MiniHubRuntime.connectors
        handleOAuthIntent(intent)

        val accountStore = AndroidConnectedAccountStore(applicationContext)
        // Retained across rotation: the same view model (and any draft in it) is reused.
        viewModel = androidx.lifecycle.ViewModelProvider(this, app.mini.hub.android.ui.InboxViewModelHolder.factory { InboxViewModel(
            observationStore = MiniHubRuntime.observationStore,
            retrievalEngine = MiniHubRuntime.retrievalEngine,
            styleEngine = MiniHubRuntime.styleEngine,
            actionController = MiniHubRuntime.actionController,
            actionStore = MiniHubRuntime.actionStore,
            clock = MiniHubRuntime.clock,
            attentionStore = MiniHubRuntime.attentionStore,
            localDraftExperiment = MiniHubRuntime.localDraftExperiment,
            selectedSources = { MiniHubRuntime.communicationSourcePreferences.selectedSources() },
            accountStore = accountStore,
            gateway = registry.gateway,
            apiReplyAllowed = registry::canReplyViaApi,
            capabilitiesFor = registry::capabilities,
            onLearnFromApprovedText = { summary, body, recipient, authorship ->
                val category = StyleContext.forMessage(summary.accountId.provider, summary.title, body)
                val recipientId = "${summary.accountId.key}|${recipient.ifBlank { summary.senderHint }}"
                val ids = StyleContext.sampleIds(summary.accountId.key, summary.conversationId.key, body)
                val primary = ids.first()
                val extra = ids.drop(1)
                if (MiniHubRuntime.styleEngine.learnFromText(body, authorship, category, recipientId, MiniHubRuntime.clock.now(), primary, account = summary.accountId, extraSampleIds = extra)) {
                    MiniHubRuntime.styleEngine.learnFromText(body, authorship, category, null, MiniHubRuntime.clock.now(), "cat:$primary", account = summary.accountId, extraSampleIds = extra.map { "cat:$it" })
                    MiniHubRuntime.styleStore.save(MiniHubRuntime.styleEngine)
                }
            },
            conversationLoader = { summary ->
                accountStore.get(summary.accountId)?.let { registry.loadConversation(it, summary.conversationId) }
            },
            extraVisiblePackages = if (BuildConfig.DEBUG) setOf(packageName) else emptySet(),
        ) })[app.mini.hub.android.ui.InboxViewModelHolder::class.java].viewModel

        handoffLauncher = AndroidNativeHandoffLauncher(this)

        setContent {
            MiniTheme {
                InboxScreen(
                    viewModel = viewModel,
                    handoffLauncher = handoffLauncher,
                    onRequestPermission = {
                        startActivity(MiniNotificationListenerService.createSettingsIntent())
                    },
                    sourcePreferences = MiniHubRuntime.communicationSourcePreferences,
                    accountStore = accountStore,
                    routes = registry.routes(),
                    capabilities = registry::capabilities,
                    connectionStatus = connectionStatus.value,
                    onConnect = { provider -> connect(provider) },
                    onConnectTelegram = { token -> connectTelegram(token) },
                    onConnectMatrix = { provider, url, token -> connectMatrix(provider, url, token) },
                    onStartWhatsAppPairing = { MiniHubRuntime.connectors.startWhatsAppPairing() },
                    onStartWhatsAppPhonePairing = { phone -> MiniHubRuntime.connectors.startWhatsAppPhonePairing(phone) },
                    onPollWhatsAppPairing = { MiniHubRuntime.connectors.pollWhatsAppPairing() },
                    onCancelWhatsAppPairing = { MiniHubRuntime.connectors.cancelWhatsAppPairing() },
                    onWhatsAppLinked = { linked ->
                        val message = when (val outcome = MiniHubRuntime.connectors.completeWhatsAppLink(linked)) {
                            is ConnectOutcome.Connected -> {
                            WhatsAppSyncService.start(applicationContext)
                            "WhatsApp linked: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                        }
                            is ConnectOutcome.Blocked -> outcome.reason
                            is ConnectOutcome.Started -> "WhatsApp pairing started."
                        }
                        connectionStatus.value = message
                        accountsVersion.value++
                    },
                    onDisconnectAccount = { account ->
                        MiniHubRuntime.connectors.disconnectAccount(account)
                        if (account.id.provider == Provider.WHATSAPP) WhatsAppSyncService.stop(applicationContext)
                        android.util.Log.i("MiniSync", "account=${account.id.localId} disconnected by user")
                        connectionStatus.value = "Disconnected ${account.displayName ?: account.id.provider.wire}. Messages stay in MINI. Reconnect to resume."
                        accountsVersion.value++
                    },
                    onSyncNow = { syncNow() },
                    styleLearningEnabled = styleLearningEnabled.value,
                    onStyleLearningChanged = { enabled ->
                        val consent = MiniHubRuntime.styleEngine.getConsent()
                        MiniHubRuntime.styleEngine.updateConsent(consent.copy(isEnabled = enabled))
                        MiniHubRuntime.styleStore.save(MiniHubRuntime.styleEngine)
                        styleLearningEnabled.value = enabled
                        if (enabled) MiniHubRuntime.bootstrapStyleFromStoredHistory()
                    },
                    accountsVersion = accountsVersion.value,
                    onSourceSelectionChanged = { refreshInbox() },
                )
            }
        }
    }

    private fun connect(provider: Provider) {
        connectionStatus.value = when (val outcome = MiniHubRuntime.connectors.launch(provider, this)) {
            is ConnectOutcome.Started -> "Sign-in opened in your browser. Return to MINI to finish."
            is ConnectOutcome.Blocked -> outcome.reason
            is ConnectOutcome.Connected -> "Connected ${outcome.account.displayName ?: outcome.account.providerAccountId}."
        }
    }

    private fun connectTelegram(token: String) {
        val isInstagram = token.startsWith(app.mini.hub.android.ui.INSTAGRAM_TOKEN_PREFIX)
        connectionStatus.value = if (isInstagram) "Checking the token with Instagram..." else "Checking the bot token with Telegram..."
        Thread({
            val message = if (isInstagram) {
                when (val outcome = MiniHubRuntime.connectors.instagram.connect(token.removePrefix(app.mini.hub.android.ui.INSTAGRAM_TOKEN_PREFIX))) {
                    is ConnectOutcome.Connected -> {
                        runCatching { MiniHubRuntime.connectors.syncAll("instagram_connect") }
                        "Instagram connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                    }
                    is ConnectOutcome.Blocked -> outcome.reason
                    is ConnectOutcome.Started -> "Instagram connection in progress."
                }
            } else when (val outcome = MiniHubRuntime.connectors.connectTelegram(token)) {
                is ConnectOutcome.Connected -> "Telegram bot connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}. Only chats the bot is in are visible."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Telegram connection in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
                refreshInbox()
            }
        }, "mini-telegram-connect").start()
    }

    private fun connectMatrix(provider: Provider, url: String, token: String) {
        connectionStatus.value = "Connecting to Matrix homeserver..."
        Thread({
            val message = when (val outcome = MiniHubRuntime.connectors.connectMatrixBridge(provider, url, token)) {
                is ConnectOutcome.Connected -> "Matrix bridge connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Connection in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-matrix-connect").start()
    }

    private fun syncNow() {
        connectionStatus.value = "Syncing connected accounts..."
        Thread({
            val summary = MiniHubRuntime.connectors.syncAll("manual")
            runOnUiThread {
                connectionStatus.value = buildString {
                    append("Sync finished: ${summary.inserted} new, ${summary.updated} updated across ${summary.accounts} account(s).")
                    if (summary.needsAttention > 0) append(" ${summary.needsAttention} account(s) need attention.")
                }
                accountsVersion.value++
                refreshInbox()
            }
        }, "mini-manual-sync").start()
    }

    private fun refreshInbox() {
        viewModel.refresh(
            isPermissionGranted = MiniNotificationListenerService.isPermissionGranted(this),
            isListenerConnected = MiniNotificationListenerService.isConnected,
        )
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            MiniNotificationListenerService.rebindIfNeeded(this)
            refreshInbox()

            // Register reactive UI listener so background ingest updates UI immediately
            MiniHubRuntime.ingestCoordinator.setUiListener { refreshInbox() }
            MiniHubRuntime.inboxRefresh = { refreshInbox() }
            MiniHubRuntime.setWhatsAppForeground(true)
            if (MiniHubRuntime.connectors.listAccounts().any {
                    it.id.provider == Provider.WHATSAPP && it.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge &&
                        it.state != app.mini.hub.connector.ConnectionState.Disconnected
                }
            ) WhatsAppSyncService.start(applicationContext)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (!MiniHubRuntime.isStorageReady) return
        val registry = MiniHubRuntime.connectors
        val handled = registry.handlesRedirect(uri)
        android.util.Log.i("MiniOAuth", "Intent received: scheme=${uri.scheme} path=${uri.path} handled=$handled")
        if (!handled) return
        intent.data = null
        connectionStatus.value = "Finishing connection..."
        Thread({
            val message = when (val outcome = registry.completeRedirect(uri)) {
                is ConnectOutcome.Connected -> "Connected ${outcome.account.displayName ?: outcome.account.providerAccountId} (${outcome.account.id.provider.wire})."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Sign-in is in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-oauth-callback").start()
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) viewModel.onAppBackgrounded()
        if (MiniHubRuntime.isStorageReady) {
            MiniHubRuntime.ingestCoordinator.setUiListener(null)
            MiniHubRuntime.inboxRefresh = null
            MiniHubRuntime.setWhatsAppForeground(false)
        }
    }
}
