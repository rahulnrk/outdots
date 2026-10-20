package app.mini.hub.android.connector

import android.content.Intent
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
import app.mini.hub.domain.AccountId
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
        val returnResult = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false)
        model.bind(applicationContext, slotKey)
        model.returnResultMode = returnResult

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by model.state.collectAsStateCompat()
                    InstagramLoginScreen(
                        state = state,
                        readState = model.readState.collectAsStateCompat().value,
                        returnResultMode = returnResult,
                        onSubmitCredentials = model::submitCredentials,
                        onSubmitCode = model::submitCode,
                        onSubmitSelection = model::submitSelection,
                        onCancel = {
                            model.cancel()
                            finish()
                        },
                        onDone = { finish() },
                        onCompleteAccountsConnect = { accountKey, username ->
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(EXTRA_RESULT_ACCOUNT_KEY, accountKey)
                                    .putExtra(EXTRA_RESULT_USERNAME, username),
                            )
                            finish()
                        },
                        onSyncRecent = model::syncRecent,
                        webViewFactory = { webView -> model.attachWebView(webView) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        model.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SLOT_KEY = "ig_slot_key"
        const val EXTRA_RETURN_RESULT = "ig_return_result"
        const val EXTRA_RESULT_ACCOUNT_KEY = "ig_result_account_key"
        const val EXTRA_RESULT_USERNAME = "ig_result_username"
        const val LOGIN_PROFILE_SUFFIX = "mini_ig_login"
    }
}

/** ViewModel owning the session and the single-attempt WebView controller. */
class InstagramLoginViewModel : ViewModel() {
    private var session: InstagramLoginSession? = null
    private var webViewController: InstagramWebViewLoginController? = null
    private val uiState = MutableStateFlow(InstagramLoginUiState.initial())
    private val readUiState = MutableStateFlow<InstagramReadUiState>(InstagramReadUiState.Idle)
    val state: StateFlow<InstagramLoginUiState> = uiState.asStateFlow()
    val readState: StateFlow<InstagramReadUiState> = readUiState.asStateFlow()
    private var readIngestor: InstagramReadIngestor? = null
    private var foregroundReceiver: InstagramForegroundReceiver? = null
    private var sessionContext: android.content.Context? = null
    private var foreground = false
    var returnResultMode: Boolean = false

    fun bind(context: android.content.Context, slotKey: String) {
        if (session != null) return
        sessionContext = context.applicationContext
        val created = InstagramLoginSession(context, slotKey)
        session = created
        created.observe {
            uiState.value = it
            val accountKey = it.verifiedAccountKey
            if (it.status == InstagramLoginStatus.AUTHENTICATED && accountKey != null) {
                val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull()
                if (accountId != null) {
                    val ingestor = readIngestor ?: InstagramReadIngestor(context).also { readIngestor = it }
                    readUiState.value = InstagramReadUiState.Loaded(ingestor.loadPersisted(accountId))
                    if (foreground) startForegroundReceive(accountId)
                }
            }
        }
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
        foregroundReceiver?.close()
        foregroundReceiver = null
        webViewController?.cancel()
        webViewController = null
        session?.close()
        session = null
    }

    fun onForeground() {
        foreground = true
        val accountKey = uiState.value.verifiedAccountKey ?: return
        val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull() ?: return
        startForegroundReceive(accountId)
    }

    fun onBackground() {
        foreground = false
        foregroundReceiver?.stop()
    }

    private fun startForegroundReceive(accountId: AccountId) {
        val context = sessionContext ?: return
        val ingestor = readIngestor ?: InstagramReadIngestor(context).also { readIngestor = it }
        val receiver = foregroundReceiver ?: InstagramForegroundReceiver(ingestor) { result ->
            when (result) {
                is InstagramReadIngestor.Result.Success -> readUiState.value = InstagramReadUiState.Loaded(result.view)
                is InstagramReadIngestor.Result.Failed -> readUiState.value = InstagramReadUiState.Failed(result.code)
            }
        }.also { foregroundReceiver = it }
        receiver.start(accountId)
    }

    fun syncRecent() {
        val accountKey = uiState.value.verifiedAccountKey ?: return
        val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull() ?: return
        val context = sessionContext ?: return
        val ingestor = readIngestor ?: InstagramReadIngestor(context).also { readIngestor = it }
        readUiState.value = InstagramReadUiState.Syncing
        Thread {
            when (val result = ingestor.sync(accountId)) {
                is InstagramReadIngestor.Result.Success -> readUiState.value = InstagramReadUiState.Loaded(result.view)
                is InstagramReadIngestor.Result.Failed -> readUiState.value = InstagramReadUiState.Failed(result.code)
            }
        }.start()
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
    readState: InstagramReadUiState,
    returnResultMode: Boolean = false,
    onSubmitCredentials: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSubmitSelection: (String, String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onCompleteAccountsConnect: (accountKey: String, username: String?) -> Unit = { _, _ -> },
    onSyncRecent: () -> Unit,
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
                when {
                    state.choices.isNotEmpty() -> SelectionForm(state.choices, onSubmitSelection)
                    state.fieldIds.contains("action") || state.fieldIds.contains("method") -> {
                        val options = when {
                            state.fieldIds.contains("method") -> listOf(
                                InstagramSelectionOption("method", "Email", "Email"),
                                InstagramSelectionOption("method", "SMS", "SMS"),
                            )
                            else -> buildList {
                                add(InstagramSelectionOption("action", "Enter code", "Enter code"))
                                if (state.instructions.orEmpty().contains("new code", ignoreCase = true) ||
                                    state.instructions.orEmpty().contains("resend", ignoreCase = true)
                                ) {
                                    add(InstagramSelectionOption("action", "Resend code", "Resend code"))
                                }
                            }
                        }
                        SelectionForm(options, onSubmitSelection)
                    }
                    state.fieldIds.contains("password") -> {
                    CredentialsForm(onSubmitCredentials)
                    }
                    state.fieldIds.any { it == "verification_code" || it == "two_factor_code" || it == "captcha_code" } ||
                        state.state == "2fa" -> {
                    CodeForm(onSubmitCode)
                    }
                    else -> {
                    Text("Awaiting the next sign-in step.")
                    }
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
                if (returnResultMode) {
                    Text("The encrypted session is stored for this MINI install. Tap Continue to add it under Manage Accounts. Sending stays disabled.")
                    Button(
                        onClick = {
                            val key = state.verifiedAccountKey
                            if (key != null) onCompleteAccountsConnect(key, state.verifiedUsername)
                            else onDone()
                        },
                    ) { Text("Continue to Accounts") }
                } else {
                    Text("The encrypted session is stored. This probe does not enable the account or send anything.")
                    Button(onClick = onSyncRecent) { Text("Sync recent conversations") }
                    InstagramReadPanel(readState)
                    Button(onClick = onDone) { Text("Done") }
                }
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
private fun InstagramReadPanel(readState: InstagramReadUiState) {
    when (readState) {
        InstagramReadUiState.Idle -> Text("No Instagram messages loaded.")
        InstagramReadUiState.Syncing -> Text("Reading recent conversations. Messages are not marked read.")
        is InstagramReadUiState.Failed -> Text("Read failed: ${readState.code}.")
        is InstagramReadUiState.Loaded -> {
            Text(if (readState.view.partial) "Recent conversations (partial history)" else "Recent conversations")
            readState.view.conversations.forEach { conversation ->
                Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                conversation.messages.forEach { message ->
                    Text("${message.direction}: ${message.text}")
                }
            }
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
        label = { Text("Email or SMS verification code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(code.trim()) }, enabled = code.trim().length >= 5) { Text("Submit code") }
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
