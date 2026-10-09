package app.mini.hub.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import app.mini.hub.android.connector.GmailOAuthClient
import app.mini.hub.android.connector.GmailOAuthOutcome
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.notification.MiniNotificationListenerService
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxScreen
import app.mini.hub.android.ui.InboxViewModel

/**
 * Main Android host Activity rendering the private communication hub.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: InboxViewModel
    private lateinit var handoffLauncher: AndroidNativeHandoffLauncher
    private lateinit var gmailOAuth: GmailOAuthClient
    private val connectionStatus = mutableStateOf<String?>(null)
    private val accountsVersion = mutableStateOf(0)

    private fun sha256() = app.mini.hub.action.DigestProvider { payload ->
        java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress app-switcher previews of private content (architecture section 11).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        MiniHubRuntime.init(applicationContext)
        app.mini.hub.android.connector.GmailSyncJobService.schedulePeriodic(applicationContext)
        gmailOAuth = GmailOAuthClient(applicationContext)
        handleOAuthIntent(intent)

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
                    accountStore = AndroidConnectedAccountStore(applicationContext),
                    gmailCanConnect = gmailOAuth.isConfigured,
                    gmailSetupStatus = gmailOAuth.setupStatus,
                    connectionStatus = connectionStatus.value,
                    onConnectGmail = {
                        connectionStatus.value = when (val outcome = gmailOAuth.launch(this)) {
                            is GmailOAuthOutcome.Started -> "Google authorization opened in your browser. Return here to finish the account connection."
                            is GmailOAuthOutcome.Blocked -> outcome.reason
                            is GmailOAuthOutcome.Connected -> "Gmail account connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}"
                        }
                    },
                    onDisconnectAccount = { account ->
                        val manager = app.mini.hub.android.connector.AndroidGmailConnector(
                            applicationContext, MiniHubRuntime.observationStore, sha256(),
                        ).connectionManager()
                        manager.disconnect(account.id)
                        android.util.Log.i("MiniGmailSync", "account=${account.id.localId} disconnected by user")
                        connectionStatus.value = "Disconnected ${account.displayName ?: "Gmail account"}. Its messages stay in MINI. Reconnect to resume syncing."
                        accountsVersion.value++
                    },
                    onSyncNow = {
                        connectionStatus.value = "Syncing Gmail..."
                        Thread({
                            val reports = app.mini.hub.android.connector.GmailSyncJobService.runSync(applicationContext, "manual")
                            val added = reports.sumOf { it.inserted }
                            val attention = reports.count { it.state != app.mini.hub.connector.ConnectionState.Healthy && it.skippedReason != "disconnected" }
                            runOnUiThread {
                                connectionStatus.value = buildString {
                                    append("Sync finished: $added new message(s).")
                                    if (attention > 0) append(" $attention account(s) need attention.")
                                }
                                accountsVersion.value++
                                viewModel.refresh(
                                    isPermissionGranted = MiniNotificationListenerService.isPermissionGranted(this),
                                    isListenerConnected = MiniNotificationListenerService.isConnected,
                                )
                            }
                        }, "mini-gmail-manual-sync").start()
                    },
                    accountsVersion = accountsVersion.value,
                    onSourceSelectionChanged = {
                        viewModel.refresh(
                            isPermissionGranted = MiniNotificationListenerService.isPermissionGranted(this),
                            isListenerConnected = MiniNotificationListenerService.isConnected,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            MiniNotificationListenerService.rebindIfNeeded(this)
            val isGranted = MiniNotificationListenerService.isPermissionGranted(this)
            val isConnected = MiniNotificationListenerService.isConnected
            viewModel.refresh(isPermissionGranted = isGranted, isListenerConnected = isConnected)

            // Register reactive UI listener so background ingest updates UI immediately
            MiniHubRuntime.ingestCoordinator.setUiListener {
                val updatedGranted = MiniNotificationListenerService.isPermissionGranted(this)
                val updatedConnected = MiniNotificationListenerService.isConnected
                viewModel.refresh(isPermissionGranted = updatedGranted, isListenerConnected = updatedConnected)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        android.util.Log.i("MiniGmailOAuth", "Intent data received: scheme=${uri.scheme} path=${uri.path} accepted=${gmailOAuth.acceptsRedirect(uri)}")
        if (!gmailOAuth.acceptsRedirect(uri)) return
        // Prevent re-processing the same one-time code on recreation.
        intent.data = null
        connectionStatus.value = "Finishing Gmail connection..."
        // Token exchange and profile discovery are network calls. Android rejects them on the UI thread.
        Thread({
            val message = when (val outcome = gmailOAuth.handleRedirect(uri)) {
                is GmailOAuthOutcome.Connected -> "Gmail account connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}"
                is GmailOAuthOutcome.Blocked -> outcome.reason
                is GmailOAuthOutcome.Started -> "Google authorization is already in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-gmail-oauth-callback").start()
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) viewModel.onAppBackgrounded()
        MiniHubRuntime.ingestCoordinator.setUiListener(null)
    }
}
