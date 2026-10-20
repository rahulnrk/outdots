package app.mini.hub.android.connector

import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner-operated LinkedIn cookie login for the isolated rtprobe package.
 * Runs in `:lilogin` so its WebView profile cannot collide with other WebViews.
 * Stores an encrypted session after a harmless authenticated identity read.
 * Never enables LinkedIn in Manage Accounts and never sends.
 */
class LinkedInLoginActivity : ComponentActivity() {
    private var session: LinkedInLoginSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix(LOGIN_PROFILE_SUFFIX) }
        }
        super.onCreate(savedInstanceState)
        val loginSession = LinkedInLoginSession(applicationContext).also { session = it }
        setContent {
            var startRequested by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by loginSession.state.collectAsStateCompat()
                    LinkedInLoginScreen(
                        state = state,
                        startRequested = startRequested,
                        onRequestStart = { startRequested = true },
                        onStart = { webView -> loginSession.start(webView) },
                        onRestore = { loginSession.restoreIfPresent() },
                        onCancel = {
                            startRequested = false
                            loginSession.cancel()
                        },
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        session?.shutdown()
        session = null
        super.onDestroy()
    }

    companion object {
        const val LOGIN_PROFILE_SUFFIX = "mini_li_login"
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> {
    val stateFlow = this
    val result = remember { mutableStateOf(stateFlow.value) }
    LaunchedEffect(stateFlow) {
        stateFlow.collect { result.value = it }
    }
    return result
}

@Composable
private fun LinkedInLoginScreen(
    state: LinkedInLoginUiState,
    startRequested: Boolean,
    onRequestStart: () -> Unit,
    onStart: (WebView) -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LinkedIn sign-in (probe only)", style = MaterialTheme.typography.titleLarge)
        Text("Owner login runs in an isolated WebView profile. Complete LinkedIn's own login, 2FA, and security challenges. Cookies are never shown.")

        when (state) {
            LinkedInLoginUiState.Ready -> {
                if (!startRequested) {
                    Text("After the feed loads, MINI verifies identity with a harmless authenticated read and stores an encrypted session for this probe only.")
                    Button(onClick = onRequestStart) { Text("Start LinkedIn sign-in") }
                    TextButton(onClick = onRestore) { Text("Restore previous probe session") }
                } else {
                    Text("Opening LinkedIn…")
                    LinkedInWebViewHost(onStart)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            LinkedInLoginUiState.SigningIn -> {
                Text("Sign in with LinkedIn. Stay on this screen until verification finishes.")
                LinkedInWebViewHost(onStart)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is LinkedInLoginUiState.Verifying -> {
                Text("Verifying session (${state.detail}). No messages are marked read.")
            }
            is LinkedInLoginUiState.Verified -> {
                Text(
                    if (state.restored) {
                        "Restored encrypted LinkedIn session and re-verified identity."
                    } else {
                        "Signed in, identity verified, and encrypted session stored for this probe."
                    },
                )
                Text("LinkedIn remains unavailable in Manage Accounts. Sending stays disabled.")
                Button(onClick = onDone) { Text("Done") }
            }
            is LinkedInLoginUiState.Failed -> {
                Text("Probe did not complete: ${state.code}")
                Text("If LinkedIn rejected the embedded browser, use LinkedIn's challenge as shown. This probe does not bypass provider restrictions.")
                Button(onClick = onDone) { Text("Close") }
                TextButton(onClick = onRestore) { Text("Try restore") }
            }
        }
    }
}

@Composable
private fun LinkedInWebViewHost(onStart: (WebView) -> Unit) {
    val started = remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        factory = { context ->
            val container = FrameLayout(context)
            val webView = WebView(context)
            container.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.55f).toInt(),
                ),
            )
            if (!started.value) {
                started.value = true
                onStart(webView)
            }
            container
        },
    )
}
