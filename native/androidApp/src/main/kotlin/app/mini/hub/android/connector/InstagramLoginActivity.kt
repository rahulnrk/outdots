package app.mini.hub.android.connector

import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimum owner-operated Instagram sign-in screen. Runs in the dedicated
 * `:iglogin` process so its fixed WebView data-directory suffix cannot collide
 * with any other WebView in the app.
 *
 * It is launched only from the rtprobe debug entry (see [InstagramLoginLauncher]).
 * It stores the verified session and proves a harmless authenticated read; it
 * never enables the account in normal Accounts and never sends a message.
 */
class InstagramLoginActivity : ComponentActivity() {

    private val model: InstagramLoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The WebView data directory suffix is process-global and must be set
        // before any WebView is created in this process. This dedicated process
        // uses one fixed isolated profile for the single active attempt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix(LOGIN_PROFILE_SUFFIX) }
        }
        super.onCreate(savedInstanceState)

        val slotKey = intent.getStringExtra(EXTRA_SLOT_KEY) ?: "instagram|pending"
        model.bind(applicationContext, slotKey)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by model.state.collectAsStateCompat()
                    InstagramLoginScreen(
                        state = state,
                        onSubmitCredentials = model::submitCredentials,
                        onSubmitCode = model::submitCode,
                        onSubmitSelection = model::submitSelection,
                        onCancel = {
                            model.cancel()
                            finish()
                        },
                        onDone = { finish() },
                        webViewFactory = { webView -> model.attachWebView(webView) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        model.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SLOT_KEY = "ig_slot_key"
        const val LOGIN_PROFILE_SUFFIX = "mini_ig_login"
    }
}

/** ViewModel owning the session and the single-attempt WebView controller. */
class InstagramLoginViewModel : ViewModel() {
    private var session: InstagramLoginSession? = null
    private var webViewController: InstagramWebViewLoginController? = null
    private val uiState = MutableStateFlow(InstagramLoginUiState.initial())
    val state: StateFlow<InstagramLoginUiState> = uiState.asStateFlow()

    fun bind(context: android.content.Context, slotKey: String) {
        if (session != null) return
        val created = InstagramLoginSession(context, slotKey)
        session = created
        created.observe { uiState.value = it }
        val lastAccountKey = context.getSharedPreferences(
            InstagramLoginSession.PROBE_STATE_PREFS,
            android.content.Context.MODE_PRIVATE,
        ).getString(InstagramLoginSession.LAST_ACCOUNT_KEY, null)
        if (lastAccountKey == null) {
            created.start()
        } else {
            created.restoreExisting(lastAccountKey) { created.start() }
        }
    }

    fun submitCredentials(username: String, password: String) {
        session?.submitCredentials(username, password)
    }

    fun submitCode(code: String) = session?.submitCode(code)

    fun submitSelection(fieldId: String, value: String) = session?.submitSelection(fieldId, value)

    fun cancel() {
        webViewController?.cancel()
        webViewController = null
        session?.cancel()
    }

    /** Attaches the per-attempt WebView controller when a WebView step is active. */
    fun attachWebView(webView: WebView) {
        val current = session ?: return
        val request = current.pendingWebViewRequest() ?: return
        webViewController?.cancel()
        val controller = InstagramWebViewLoginController(
            webView = webView,
            request = request,
            onResult = { attempt, result ->
                webViewController = null
                current.onWebViewResult(attempt, result)
            },
            onError = { reason ->
                webViewController = null
                current.onWebViewError(reason)
            },
        )
        webViewController = controller
        controller.start()
    }

    fun shutdown() {
        webViewController?.cancel()
        webViewController = null
        session?.close()
        session = null
    }
}

// Small shim so the Activity does not need the full lifecycle-compose artifact.
@androidx.compose.runtime.Composable
private fun <T> StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> {
    val stateFlow = this
    val result = remember { mutableStateOf(stateFlow.value) }
    androidx.compose.runtime.LaunchedEffect(stateFlow) {
        stateFlow.collect { result.value = it }
    }
    return result
}

@androidx.compose.runtime.Composable
private fun InstagramLoginScreen(
    state: InstagramLoginUiState,
    onSubmitCredentials: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSubmitSelection: (String, String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    webViewFactory: (WebView) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Instagram sign-in (owner only)", style = MaterialTheme.typography.titleLarge)
        state.instructions?.takeIf { it.isNotEmpty() }?.let { Text(it) }

        when (state.status) {
            InstagramLoginStatus.LOADING -> Text("Preparing secure login...")
            InstagramLoginStatus.UNAVAILABLE -> Text("Instagram runtime is not available in this build.")
            InstagramLoginStatus.INPUT -> {
                if (state.fieldIds.contains("password")) {
                    CredentialsForm(onSubmitCredentials)
                } else if (state.fieldIds.contains("verification_code")) {
                    CodeForm(onSubmitCode)
                } else if (state.state == "select") {
                    SelectionForm(state.choices, onSubmitSelection)
                } else {
                    Text("Awaiting the next sign-in step.")
                }
            }
            InstagramLoginStatus.WEBVIEW -> {
                Text("Complete the verification shown below. Your login continues automatically.")
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        val container = FrameLayout(context)
                        val webView = WebView(context)
                        container.addView(webView)
                        webViewFactory(webView)
                        container
                    },
                )
            }
            InstagramLoginStatus.AUTHENTICATED -> {
                Text("Signed in and verified as @${state.verifiedUsername.orEmpty()} (id ${state.verifiedUserId.orEmpty()}).")
                Text("The encrypted session is stored. This probe does not enable the account or send anything.")
                Button(onClick = onDone) { Text("Done") }
            }
            InstagramLoginStatus.FAILED -> {
                Text("Sign-in did not complete: ${state.failureCode ?: "unknown"}.")
                Button(onClick = onDone) { Text("Close") }
            }
            InstagramLoginStatus.CANCELLED -> {
                Text("Sign-in cancelled. No account was stored.")
                Button(onClick = onDone) { Text("Close") }
            }
        }

        if (state.status != InstagramLoginStatus.AUTHENTICATED &&
            state.status != InstagramLoginStatus.FAILED &&
            state.status != InstagramLoginStatus.CANCELLED
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CredentialsForm(onSubmit: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Email or username") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            onSubmit(username, password)
            password = "" // Transient: never retained after submit.
        },
        enabled = username.isNotBlank() && password.isNotEmpty(),
    ) { Text("Sign in") }
}

@androidx.compose.runtime.Composable
private fun CodeForm(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Verification code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(code) }, enabled = code.isNotBlank()) { Text("Continue") }
}

@androidx.compose.runtime.Composable
private fun SelectionForm(
    options: List<InstagramSelectionOption>,
    onSubmit: (String, String) -> Unit,
) {
    if (options.isEmpty()) {
        Text("Instagram did not provide any selectable options. Cancel and retry the sign-in.")
        return
    }
    var selected by remember(options) { mutableStateOf<InstagramSelectionOption?>(null) }
    options.forEach { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { selected = option },
        ) {
            RadioButton(selected = selected == option, onClick = { selected = option })
            Text(option.label, modifier = Modifier.padding(top = 12.dp))
        }
    }
    Button(
        onClick = { selected?.let { onSubmit(it.fieldId, it.value) } },
        enabled = selected != null,
    ) { Text("Continue") }
}
