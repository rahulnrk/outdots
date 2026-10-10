package app.mini.hub.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.AndroidKeystoreAccessTokenStore
import app.mini.hub.android.connector.ConnectOutcome
import app.mini.hub.android.connector.GmailSyncJobService
import app.mini.hub.android.notification.MiniNotificationListenerService
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxScreen
import app.mini.hub.android.ui.InboxViewModel
import app.mini.hub.domain.Provider

/**
 * Main Android host Activity rendering the private communication hub.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: InboxViewModel
    private lateinit var handoffLauncher: AndroidNativeHandoffLauncher
    private val connectionStatus = mutableStateOf<String?>(null)
    private val accountsVersion = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress app-switcher previews of private content (architecture section 11).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        MiniHubRuntime.init(applicationContext)
        // One background job syncs every connected account (Gmail and the other API routes).
        GmailSyncJobService.schedulePeriodic(applicationContext)
        val registry = MiniHubRuntime.connectors
        handleOAuthIntent(intent)

        val accountStore = AndroidConnectedAccountStore(applicationContext)
        viewModel = InboxViewModel(
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
            conversationLoader = { summary ->
                accountStore.get(summary.accountId)?.let { registry.loadConversation(it, summary.conversationId) }
            },
        )

        handoffLauncher = AndroidNativeHandoffLauncher(this)

        setContent {
            MaterialTheme {
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
                    onDisconnectAccount = { account ->
                        val manager = app.mini.hub.connector.AccountConnectionManager(
                            accountStore, AndroidKeystoreAccessTokenStore(applicationContext), app.mini.hub.domain.IdGenerator { "unused-$it" },
                        )
                        manager.disconnect(account.id)
                        android.util.Log.i("MiniSync", "account=${account.id.localId} disconnected by user")
                        connectionStatus.value = "Disconnected ${account.displayName ?: account.id.provider.wire}. Messages stay in MINI. Reconnect to resume."
                        accountsVersion.value++
                    },
                    onSyncNow = { syncNow() },
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
        connectionStatus.value = "Checking the bot token with Telegram..."
        Thread({
            val message = when (val outcome = MiniHubRuntime.connectors.connectTelegram(token)) {
                is ConnectOutcome.Connected -> "Telegram bot connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}. Only chats the bot is in are visible."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Telegram connection in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-telegram-connect").start()
    }

    private fun syncNow() {
        connectionStatus.value = "Syncing connected accounts..."
        Thread({
            val summary = GmailSyncJobService.runAll(applicationContext, "manual")
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
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
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
        MiniHubRuntime.ingestCoordinator.setUiListener(null)
    }
}
