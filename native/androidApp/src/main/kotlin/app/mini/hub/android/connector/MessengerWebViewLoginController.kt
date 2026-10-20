package app.mini.hub.android.connector

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated WebView controller for owner-operated Messenger cookie login.
 *
 * Isolation contract:
 * - Runs only in the `:mslogin` process with a fixed WebView data-directory suffix.
 * - Top-level navigation is HTTPS facebook.com / messenger.com only.
 * - Cookie header is handed to [onSession] only after the Messenger success URL
 *   pattern and required cookies (`xs`, `c_user`, `datr`) are present, then
 *   scrubbed from this process. Nothing logs or displays cookies/URLs.
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

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (userAgent.isNotBlank()) {
            settings.userAgentString = userAgent
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isAllowedTopLevel(request.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (finished.get() || url.isNullOrBlank()) return
                onNavigation(url)
            }
        }
        webView.loadUrl(startUrl)
    }

    private fun onNavigation(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isAllowedTopLevel(uri)) {
            fail("navigation_rejected")
            return
        }
        if (!successPattern.matches(url)) return
        harvest()
    }

    private fun harvest() {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie("https://www.messenger.com")
            ?: cookieManager.getCookie("https://messenger.com")
            ?: cookieManager.getCookie("https://www.facebook.com")
        if (cookieHeader.isNullOrBlank()) return
        if (!cookieHeader.contains("xs=") ||
            !cookieHeader.contains("c_user=") ||
            !cookieHeader.contains("datr=")
        ) {
            return
        }
        if (!finished.compareAndSet(false, true)) return
        clearProcessCookies()
        onSession(attempt, cookieHeader)
    }

    fun cancel() {
        if (!finished.compareAndSet(false, true)) {
            clearProcessCookies()
            return
        }
        clearProcessCookies()
        onError(attempt, "cancelled")
    }

    private fun fail(reason: String) {
        if (!finished.compareAndSet(false, true)) return
        clearProcessCookies()
        onError(attempt, reason)
    }

    private fun clearProcessCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    companion object {
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
