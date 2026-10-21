package app.mini.hub.android.connector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class MessengerLoginUiState {
    data object Ready : MessengerLoginUiState()
    data object SigningIn : MessengerLoginUiState()
    data class Verifying(val detail: String) : MessengerLoginUiState()
    data class Verified(val accountKey: String, val restored: Boolean) : MessengerLoginUiState()
    data class Failed(val code: String) : MessengerLoginUiState()
}

/**
 * Owns one Messenger probe attempt: WebView harvest -> native import -> VerifyIdentity
 * -> encrypted cookie store + local E2EE device DB. Never enables Manage Accounts and never sends.
 */
class MessengerLoginSession(private val appContext: Context) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "messenger-login-probe") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiState = MutableStateFlow<MessengerLoginUiState>(MessengerLoginUiState.Ready)
    private val attempt = AtomicLong(0)
    private var webController: MessengerWebViewLoginController? = null
    private var runtime: Any? = null
    private var attachedWebView: WebView? = null

    val state: StateFlow<MessengerLoginUiState> = uiState.asStateFlow()

    fun start(webView: WebView) {
        if (!MessengerRuntimeFacade.available()) {
            publish(MessengerLoginUiState.Failed("runtime_unavailable"))
            return
        }
        cancelActiveWebView()
        attachedWebView = webView
        val nextAttempt = attempt.incrementAndGet()
        publish(MessengerLoginUiState.SigningIn)
        val pattern = runCatching { Regex(MessengerRuntimeFacade.loginSuccessUrlPattern()) }.getOrNull()
        if (pattern == null) {
            publish(MessengerLoginUiState.Failed("invalid_success_pattern"))
            return
        }
        val controller = MessengerWebViewLoginController(
            webView = webView,
            userAgent = MessengerRuntimeFacade.browserUserAgent(),
            startUrl = MessengerRuntimeFacade.loginStartUrl(),
            successPattern = pattern,
            attempt = nextAttempt,
            onSession = { at, cookieHeader ->
                if (at != attempt.get()) return@MessengerWebViewLoginController
                executor.execute { completeWithSession(at, cookieHeader) }
            },
            onError = { at, reason ->
                if (at != attempt.get()) return@MessengerWebViewLoginController
                publish(MessengerLoginUiState.Failed(reason))
            },
        )
        webController = controller
        controller.start()
    }

    fun restoreIfPresent() {
        if (!MessengerRuntimeFacade.available()) return
        executor.execute {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val accountKey = prefs.getString(PREF_LAST_ACCOUNT, null) ?: return@execute
            val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull() ?: return@execute
            if (accountId.provider != Provider.FACEBOOK) return@execute
            val envelope = SqlCipherMessengerSessionStore(appContext).load(accountId) ?: return@execute
            val sessionJson = envelope.sessionJsonOrNull() ?: return@execute
            val store = SqlCipherMessengerSessionStore(appContext)
            val deviceKey = store.loadDeviceKey(accountId)
            if (deviceKey == null) {
                publish(MessengerLoginUiState.Failed("device_key_missing"))
                return@execute
            }
            publish(MessengerLoginUiState.Verifying("restoring"))
            val rt = MessengerRuntimeFacade.newRuntime()
            val importCode = MessengerRuntimeFacade.importSessionJson(rt, sessionJson)
            if (importCode.isNotEmpty()) {
                publish(MessengerLoginUiState.Failed(importCode))
                return@execute
            }
            val deviceCode = MessengerRuntimeFacade.openEncryptedDeviceStore(
                rt,
                deviceSealedPath(accountId).absolutePath,
                accountId.key,
                deviceKey.toHex(),
            )
            if (deviceCode.isNotEmpty()) {
                publish(MessengerLoginUiState.Failed(deviceCode))
                return@execute
            }
            val verify = JSONObject(MessengerRuntimeFacade.verifyIdentity(rt))
            if (!verify.optBoolean("ok")) {
                publish(MessengerLoginUiState.Failed(verify.optString("code", "verify_failed")))
                return@execute
            }
            val userId = verify.optString("user_id")
            if (userId.isBlank() || userId != accountId.localId) {
                publish(MessengerLoginUiState.Failed("verified_identity_mismatch"))
                return@execute
            }
            val prepareCode = MessengerRuntimeFacade.prepareEncryptedClient(rt)
            if (prepareCode.isNotEmpty()) {
                publish(MessengerLoginUiState.Failed(prepareCode))
                return@execute
            }
            val persistCode = MessengerRuntimeFacade.persistEncryptedDevice(rt)
            recordPersistence(rt, "restore_persist", persistCode)
            val read = JSONObject(MessengerRuntimeFacade.conversationReadJson(rt))
            val registered = MessengerRuntimeFacade.encryptedDeviceRegistered(rt)
            evidence("restore", "identity_match=true device_registered=$registered")
            evidence("restore_read", "read=${read.optBoolean("read")} threads=${read.optInt("threads")}")
            evidence("restore_identity", MessengerRuntimeFacade.deviceIdentityJson(rt))
            if (registered) {
                publish(MessengerLoginUiState.Verifying("e2ee_receive"))
                evidence("restore_e2ee_listen", listenSummary(rt))
                recordPersistence(rt, "restore_listen_persist", MessengerRuntimeFacade.persistEncryptedDevice(rt))
            }
            runtime = rt
            publish(MessengerLoginUiState.Verified(accountId.key, restored = true))
        }
    }

    private fun evidence(key: String, value: String) {
        appContext.getSharedPreferences(EVIDENCE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).putLong("$key.at", System.currentTimeMillis()).commit()
    }

    private fun listenSummary(rt: Any): String {
        val r = JSONObject(MessengerRuntimeFacade.listenEncrypted(rt, LISTEN_SECONDS))
        return "connected=${r.optBoolean("connected")} events=${r.optInt("events")} " +
            "fb_messages=${r.optInt("fb_messages")} logged_out=${r.optBoolean("logged_out")} " +
            "code=${r.optString("code").ifBlank { "ok" }}"
    }

    private fun completeWithSession(at: Long, cookieHeader: String) {
        if (at != attempt.get()) return
        publish(MessengerLoginUiState.Verifying("importing"))
        val rt = MessengerRuntimeFacade.newRuntime()
        val importCode = MessengerRuntimeFacade.importOwnerSession(rt, cookieHeader)
        if (importCode.isNotEmpty()) {
            publish(MessengerLoginUiState.Failed(importCode))
            return
        }
        publish(MessengerLoginUiState.Verifying("identity"))
        val verify = JSONObject(MessengerRuntimeFacade.verifyIdentity(rt))
        if (!verify.optBoolean("ok")) {
            publish(MessengerLoginUiState.Failed(verify.optString("code", "verify_failed")))
            return
        }
        val userId = verify.optString("user_id")
        if (userId.isBlank()) {
            publish(MessengerLoginUiState.Failed("empty_verified_identity"))
            return
        }
        val accountId = AccountId(Provider.FACEBOOK, userId)
        evidence("identity", "verified=true provider=facebook")
        val read = JSONObject(MessengerRuntimeFacade.conversationReadJson(rt))
        evidence("read", "read=${read.optBoolean("read")} threads=${read.optInt("threads")}")
        publish(MessengerLoginUiState.Verifying("encrypted_device"))
        val store = SqlCipherMessengerSessionStore(appContext)
        val deviceKey = store.getOrCreateDeviceKey(accountId)
        val deviceCode = MessengerRuntimeFacade.openEncryptedDeviceStore(
            rt,
            deviceSealedPath(accountId).absolutePath,
            accountId.key,
            deviceKey.toHex(),
        )
        if (deviceCode.isNotEmpty()) {
            publish(MessengerLoginUiState.Failed(deviceCode))
            return
        }
        val prepareCode = MessengerRuntimeFacade.prepareEncryptedClient(rt)
        if (prepareCode.isNotEmpty()) {
            publish(MessengerLoginUiState.Failed(prepareCode))
            return
        }
        val sessionJson = MessengerRuntimeFacade.exportSessionJson(rt)
        if (sessionJson.isBlank()) {
            publish(MessengerLoginUiState.Failed("export_failed"))
            return
        }
        val envelope = MessengerSessionEnvelope.fromSessionJson(sessionJson)
        SqlCipherMessengerSessionStore(appContext).save(accountId, envelope)
        check(
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_ACCOUNT, accountId.key)
                .commit(),
        ) { "could not persist Messenger probe account key" }
        val stored = SqlCipherMessengerSessionStore(appContext).load(accountId) != null
        evidence("session_bind", "sqlcipher_saved=$stored bound_to_verified_identity=true")
        recordPersistence(rt, "login_device", MessengerRuntimeFacade.persistEncryptedDevice(rt))
        evidence("identity_fp", MessengerRuntimeFacade.deviceIdentityJson(rt))
        val alreadyRegistered = MessengerRuntimeFacade.encryptedDeviceRegistered(rt)
        if (!alreadyRegistered) {
            publish(MessengerLoginUiState.Verifying("e2ee_register"))
            val registerCode = MessengerRuntimeFacade.registerEncryptedDevice(rt)
            val registered = MessengerRuntimeFacade.encryptedDeviceRegistered(rt)
            evidence("e2ee_register", "registered=$registered code=${registerCode.ifBlank { "ok" }}")
            recordPersistence(rt, "register_persist", MessengerRuntimeFacade.persistEncryptedDevice(rt))
            if (registered) {
                publish(MessengerLoginUiState.Verifying("e2ee_receive"))
                evidence("e2ee_listen", listenSummary(rt))
                recordPersistence(rt, "listen_persist", MessengerRuntimeFacade.persistEncryptedDevice(rt))
            } else {
                evidence("e2ee_listen", "skipped=device_unregistered")
            }
        } else {
            evidence("e2ee_register", "registered=true code=already_registered")
            publish(MessengerLoginUiState.Verifying("e2ee_receive"))
            evidence("e2ee_listen", listenSummary(rt))
            recordPersistence(rt, "listen_persist", MessengerRuntimeFacade.persistEncryptedDevice(rt))
        }
        runtime = rt
        publish(MessengerLoginUiState.Verified(accountId.key, restored = false))
    }

    fun cancel() {
        attempt.incrementAndGet()
        cancelActiveWebView()
        runtime?.let {
            MessengerRuntimeFacade.clearSession(it)
            MessengerRuntimeFacade.close(it)
        }
        runtime = null
        publish(MessengerLoginUiState.Failed("cancelled"))
    }

    fun shutdown() {
        attempt.incrementAndGet()
        cancelActiveWebView()
        runtime?.let {
            MessengerRuntimeFacade.clearSession(it)
            MessengerRuntimeFacade.close(it)
        }
        runtime = null
        executor.shutdownNow()
    }

    private fun deviceSealedPath(accountId: AccountId): File {
        val dir = File(appContext.filesDir, "messenger-e2ee").apply { mkdirs() }
        return File(dir, "${accountId.localId}.sealed")
    }

    private fun recordPersistence(rt: Any, key: String, persistCode: String) {
        val artifacts = MessengerRuntimeFacade.plaintextDeviceArtifacts(rt).ifBlank { "none" }
        val sealed = MessengerRuntimeFacade.sealedDevicePresent(rt)
        val generation = MessengerRuntimeFacade.lastPersistGeneration(rt)
        evidence(
            key,
            "persist=${persistCode.ifBlank { "ok" }} sealed=$sealed generation=$generation plaintext=$artifacts",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun publish(state: MessengerLoginUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiState.value = state
        } else {
            mainHandler.post { uiState.value = state }
        }
    }

    private fun cancelActiveWebView() {
        webController?.cancel()
        webController = null
        attachedWebView?.apply {
            stopLoading()
            webViewClient = android.webkit.WebViewClient()
            loadUrl("about:blank")
        }
        attachedWebView = null
    }

    companion object {
        private const val PREFS = "mini_messenger_probe"
        private const val EVIDENCE_PREFS = "mini_messenger_probe_evidence"
        private const val LISTEN_SECONDS = 30L
        private const val PREF_LAST_ACCOUNT = "last_account_key"
    }
}
