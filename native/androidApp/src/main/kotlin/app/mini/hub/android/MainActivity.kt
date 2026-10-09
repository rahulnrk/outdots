package app.mini.hub.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress app-switcher previews of private content (architecture section 11).
        // window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        MiniHubRuntime.init(applicationContext)

        viewModel = InboxViewModel(
            observationStore = MiniHubRuntime.observationStore,
            retrievalEngine = MiniHubRuntime.retrievalEngine,
            styleEngine = MiniHubRuntime.styleEngine,
            actionController = MiniHubRuntime.actionController,
            actionStore = MiniHubRuntime.actionStore,
            clock = MiniHubRuntime.clock,
            attentionStore = MiniHubRuntime.attentionStore,
            localDraftExperiment = MiniHubRuntime.localDraftExperiment,
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

    override fun onPause() {
        super.onPause()
        MiniHubRuntime.ingestCoordinator.setUiListener(null)
    }
}
