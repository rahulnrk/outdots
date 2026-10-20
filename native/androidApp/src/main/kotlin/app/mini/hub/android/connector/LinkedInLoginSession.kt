package app.mini.hub.android.connector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed class LinkedInLoginUiState {
    data object Ready : LinkedInLoginUiState()
    data object SigningIn : LinkedInLoginUiState()
    data class Verifying(val detail: String) : LinkedInLoginUiState()
    data class Verified(val accountKey: String, val restored: Boolean) : LinkedInLoginUiState()
    data class Failed(val code: String) : LinkedInLoginUiState()
}

/**
 * Owns one LinkedIn probe attempt: WebView harvest -> native import -> VerifyIdentity
 * -> encrypted store. Never enables Manage Accounts and never sends.
 */
class LinkedInLoginSession(private val appContext: Context) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "linkedin-login-probe") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiState = MutableStateFlow<LinkedInLoginUiState>(LinkedInLoginUiState.Ready)
    private val attempt = AtomicLong(0)
    private var webController: LinkedInWebViewLoginController? = null
    private var runtime: Any? = null
    private var attachedWebView: WebView? = null

    val state: StateFlow<LinkedInLoginUiState> = uiState.asStateFlow()

    fun start(webView: WebView) {
        if (!LinkedInRuntimeFacade.available()) {
            publish(LinkedInLoginUiState.Failed("runtime_unavailable"))
            return
        }
        cancelActiveWebView()
        attachedWebView = webView
        val nextAttempt = attempt.incrementAndGet()
        publish(LinkedInLoginUiState.SigningIn)
        val pattern = runCatching { Regex(LinkedInRuntimeFacade.loginSuccessUrlPattern()) }.getOrNull()
        if (pattern == null) {
            publish(LinkedInLoginUiState.Failed("invalid_success_pattern"))
            return
        }
        val controller = LinkedInWebViewLoginController(
            webView = webView,
            userAgent = LinkedInRuntimeFacade.browserUserAgent(),
            startUrl = LinkedInRuntimeFacade.loginStartUrl(),
            successPattern = pattern,
            attempt = nextAttempt,
            onSession = { at, cookieHeader, pageInstance, xLiTrack ->
                if (at != attempt.get()) return@LinkedInWebViewLoginController
                executor.execute { completeWithSession(at, cookieHeader, pageInstance, xLiTrack) }
            },
            onError = { at, reason ->
                if (at != attempt.get()) return@LinkedInWebViewLoginController
                publish(LinkedInLoginUiState.Failed(reason))
            },
        )
        webController = controller
        controller.start()
    }

    fun restoreIfPresent() {
        if (!LinkedInRuntimeFacade.available()) return
        executor.execute {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val accountKey = prefs.getString(PREF_LAST_ACCOUNT, null) ?: return@execute
            val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull() ?: return@execute
            if (accountId.provider != Provider.LINKEDIN) return@execute
            val envelope = SqlCipherLinkedInSessionStore(appContext).load(accountId) ?: return@execute
            val sessionJson = envelope.sessionJsonOrNull() ?: return@execute
            publish(LinkedInLoginUiState.Verifying("restoring"))
            val rt = LinkedInRuntimeFacade.newRuntime()
            val importCode = LinkedInRuntimeFacade.importSessionJson(rt, sessionJson)
            if (importCode.isNotEmpty()) {
                publish(LinkedInLoginUiState.Failed(importCode))
                return@execute
            }
            val verify = JSONObject(LinkedInRuntimeFacade.verifyIdentity(rt))
            if (!verify.optBoolean("ok")) {
                publish(LinkedInLoginUiState.Failed(verify.optString("code", "verify_failed")))
                return@execute
            }
            val userId = verify.optString("user_id")
            if (userId.isBlank() || userId != accountId.localId) {
                publish(LinkedInLoginUiState.Failed("verified_identity_mismatch"))
                return@execute
            }
            runtime = rt
            publish(LinkedInLoginUiState.Verified(accountId.key, restored = true))
        }
    }

    private fun completeWithSession(at: Long, cookieHeader: String, pageInstance: String, xLiTrack: String) {
        if (at != attempt.get()) return
        publish(LinkedInLoginUiState.Verifying("importing"))
        val rt = LinkedInRuntimeFacade.newRuntime()
        val importCode = LinkedInRuntimeFacade.importOwnerSession(rt, cookieHeader, pageInstance, xLiTrack)
        if (importCode.isNotEmpty()) {
            publish(LinkedInLoginUiState.Failed(importCode))
            return
        }
        publish(LinkedInLoginUiState.Verifying("identity"))
        val verify = JSONObject(LinkedInRuntimeFacade.verifyIdentity(rt))
        if (!verify.optBoolean("ok")) {
            publish(LinkedInLoginUiState.Failed(verify.optString("code", "verify_failed")))
            return
        }
        val userId = verify.optString("user_id")
        if (userId.isBlank()) {
            publish(LinkedInLoginUiState.Failed("empty_verified_identity"))
            return
        }
        val accountId = AccountId(Provider.LINKEDIN, userId)
        val sessionJson = LinkedInRuntimeFacade.exportSessionJson(rt)
        if (sessionJson.isBlank()) {
            publish(LinkedInLoginUiState.Failed("export_failed"))
            return
        }
        val envelope = LinkedInSessionEnvelope.fromSessionJson(sessionJson)
        SqlCipherLinkedInSessionStore(appContext).save(accountId, envelope)
        check(
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_ACCOUNT, accountId.key)
                .commit(),
        ) { "could not persist LinkedIn probe account key" }
        runtime = rt
        publish(LinkedInLoginUiState.Verified(accountId.key, restored = false))
    }

    fun cancel() {
        attempt.incrementAndGet()
        cancelActiveWebView()
        runtime?.let(LinkedInRuntimeFacade::clearSession)
        runtime = null
        publish(LinkedInLoginUiState.Failed("cancelled"))
    }

    fun shutdown() {
        attempt.incrementAndGet()
        cancelActiveWebView()
        runtime?.let(LinkedInRuntimeFacade::clearSession)
        runtime = null
        executor.shutdownNow()
    }

    private fun publish(state: LinkedInLoginUiState) {
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
        private const val PREFS = "mini_linkedin_probe"
        private const val PREF_LAST_ACCOUNT = "last_account_key"
    }
}
