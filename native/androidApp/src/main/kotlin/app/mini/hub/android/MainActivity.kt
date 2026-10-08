package app.mini.hub.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.android.notification.MiniNotificationListenerService
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxScreen
import app.mini.hub.android.ui.InboxViewModel
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.persistence.SqlCipherActionStore
import app.mini.hub.persistence.SqlCipherObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import java.security.MessageDigest

/**
 * Main Android host Activity rendering the private communication hub.
 */
class MainActivity : ComponentActivity() {
    private var counter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${System.currentTimeMillis()}-${counter++}" }
    private val clock = Clock { UtcInstant(System.currentTimeMillis()) }
    private val digest = DigestProvider { payload ->
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private lateinit var viewModel: InboxViewModel
    private lateinit var handoffLauncher: AndroidNativeHandoffLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress app-switcher previews of private content (architecture section 11).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val observationStore = try {
            SqlCipherObservationStore(this)
        } catch (_: Throwable) {
            InMemoryObservationStore()
        }

        val actionStore = try {
            SqlCipherActionStore(this)
        } catch (_: Throwable) {
            InMemoryActionStore()
        }

        val retrievalEngine = ScopedRetrievalEngine(observationStore)
        val styleEngine = AdaptiveStyleEngine()
        val actionController = ActionController(
            clock = clock,
            digest = digest,
            ids = DomainActionIdGenerator(idGen),
        )

        viewModel = InboxViewModel(
            observationStore = observationStore,
            retrievalEngine = retrievalEngine,
            styleEngine = styleEngine,
            actionController = actionController,
            actionStore = actionStore,
            clock = clock,
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
            val isGranted = MiniNotificationListenerService.isPermissionGranted(this)
            val isConnected = MiniNotificationListenerService.isConnected
            viewModel.refresh(isPermissionGranted = isGranted, isListenerConnected = isConnected)
        }
    }
}
