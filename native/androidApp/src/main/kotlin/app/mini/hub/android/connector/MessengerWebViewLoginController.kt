package app.mini.hub.android.connector

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated WebView controller for owner-operated Messenger cookie login.
 *
 * Isolation contract:
 * - Runs only in the `:mslogin` process with a fixed WebView data-directory suffix.
 * - Top-level navigation is HTTPS facebook.com / messenger.com only.
 * - Cookie header is handed to [onSession] only after required cookies
 *   (`xs`, `c_user`, `datr`) are present on an authenticated Messenger/Facebook
 *   surface (not the login page). Messenger is SPA-heavy, so cookies are also
 *   polled while signing in. Nothing logs or displays cookies/URLs.
 * - Results are tagged with [attempt]; stale callbacks after cancel are ignored.
 */
class MessengerWebViewLoginController(
    private val webView: WebView,
    private val userAgent: String,
    private val startUrl: String,
    private val successPattern: Regex,
    private val attempt: Long,
    private val onSession: (attempt: Long, cookieHeader: String) -> Unit,
    private val onError: (attempt: Long, reason: String) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val bootstrapDone = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollCookies = object : Runnable {
        override fun run() {
            if (finished.get()) return
            tryHarvest(webView.url)
            if (!finished.get()) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.saveFormData = false
        // Facebook serves a "Reload Page" interstitial to desktop Linux Chrome UAs
        // inside Android WebView. Login uses a mobile Chrome UA; messagix API calls
        // still use their own upstream UA after cookie harvest.
        settings.userAgentString = MOBILE_CHROME_UA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isAllowedTopLevel(request.url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                if (!finished.get() && bootstrapDone.get()) {
                    mainHandler.post { tryHarvest(view.url ?: request.url?.toString()) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (finished.get() || url.isNullOrBlank()) return
                if (!bootstrapDone.get()) {
                    // After forced logout, open a clean Messenger login with no autofill.
                    if (url.contains("logout", ignoreCase = true) || url.contains("login", ignoreCase = true)) {
                        if (bootstrapDone.compareAndSet(false, true)) {
                            view.loadUrl(startUrl)
                            mainHandler.postDelayed(pollCookies, POLL_INTERVAL_MS)
                        }
                    }
                    return
                }
                onNavigation(url)
            }
        }

        scrubBrowserState {
            if (finished.get()) return@scrubBrowserState
            // Force Facebook logout first so autofill/password-manager cannot reopen
            // a different saved account.
            webView.loadUrl(FORCE_LOGOUT_URL)
            // Safety: if logout page never settles, still open login.
            mainHandler.postDelayed({
                if (!finished.get() && bootstrapDone.compareAndSet(false, true)) {
                    webView.loadUrl(startUrl)
                    mainHandler.postDelayed(pollCookies, POLL_INTERVAL_MS)
                }
            }, BOOTSTRAP_TIMEOUT_MS)
        }
    }

    private fun onNavigation(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isAllowedTopLevel(uri)) {
            fail("navigation_rejected")
            return
        }
        tryHarvest(url)
    }

    private fun tryHarvest(url: String?) {
        if (finished.get()) return
        if (!url.isNullOrBlank() && isTransientOrSwitcherUrl(url)) return
        if (!looksAuthenticated(url) && (url.isNullOrBlank() || !successPattern.matches(url))) {
            // Keep waiting: SPA login may not change the URL to the success pattern.
            // Still allow harvest once required cookies exist on messenger/facebook hosts.
            if (!hasRequiredCookies()) return
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
            if (!host.endsWith("messenger.com") && !host.endsWith("facebook.com") && host != "messenger.com" && host != "facebook.com") {
                return
            }
            if (url?.contains("/login", ignoreCase = true) == true) return
        }
        if (!hasRequiredCookies()) return
        val cookieHeader = cookieHeaderOrNull() ?: return
        if (!finished.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(pollCookies)
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        onSession(attempt, cookieHeader)
    }

    private fun looksAuthenticated(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isTransientOrSwitcherUrl(url)) return false
        if (successPattern.matches(url)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (url.contains("/login", ignoreCase = true)) return false
        if (host == "www.messenger.com" || host == "messenger.com" || host.endsWith(".messenger.com")) {
            return path.isEmpty() || path == "/" || path.startsWith("/t/") || path.startsWith("/inbox")
        }
        if (host == "www.facebook.com" || host == "facebook.com" || host.endsWith(".facebook.com")) {
            return !path.startsWith("/login") && !path.startsWith("/checkpoint") &&
                !path.startsWith("/recover") && !path.startsWith("/logout")
        }
        return false
    }

    private fun isTransientOrSwitcherUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("reload") ||
            lower.contains("account_switcher") ||
            lower.contains("login/identify") ||
            lower.contains("recover/password") ||
            lower.contains("two_step") ||
            lower.contains("checkpoint") && lower.contains("block")
    }

    private fun hasRequiredCookies(): Boolean {
        val cookieHeader = cookieHeaderOrNull() ?: return false
        return cookieHeader.contains("xs=") &&
            cookieHeader.contains("c_user=") &&
            cookieHeader.contains("datr=")
    }

    private fun cookieHeaderOrNull(): String? {
        val cookieManager = CookieManager.getInstance()
        return cookieManager.getCookie("https://www.facebook.com")
            ?: cookieManager.getCookie("https://m.facebook.com")
            ?: cookieManager.getCookie("https://facebook.com")
    }

    fun cancel() {
        mainHandler.removeCallbacks(pollCookies)
        if (!finished.compareAndSet(false, true)) {
            scrubBrowserState()
            return
        }
        scrubBrowserState()
        onError(attempt, "cancelled")
    }

    private fun fail(reason: String) {
        mainHandler.removeCallbacks(pollCookies)
        if (!finished.compareAndSet(false, true)) return
        scrubBrowserState()
        onError(attempt, reason)
    }

    private fun scrubBrowserState(onDone: (() -> Unit)? = null) {
        runCatching { WebStorage.getInstance().deleteAllData() }
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            if (onDone != null) {
                mainHandler.post(onDone)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val BOOTSTRAP_TIMEOUT_MS = 4000L
        private const val FORCE_LOGOUT_URL =
            "https://m.facebook.com/logout.php?next=https%3A%2F%2Fm.facebook.com%2Flogin%2F"
        // Pixel-like mobile Chrome. Desktop Linux Chrome UA triggers Facebook's
        // "not working for this browser / Reload Page" interstitial in WebView.
        private const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun isAllowedTopLevel(uri: Uri?): Boolean {
            if (uri == null) return false
            if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
            if (!uri.userInfo.isNullOrEmpty()) return false
            val host = uri.host?.lowercase() ?: return false
            return host == "messenger.com" ||
                host.endsWith(".messenger.com") ||
                host == "facebook.com" ||
                host.endsWith(".facebook.com") ||
                host == "fb.com" ||
                host.endsWith(".fb.com") ||
                host == "facebook.net" ||
                host.endsWith(".facebook.net") ||
                host == "fbcdn.net" ||
                host.endsWith(".fbcdn.net")
        }
    }
}
