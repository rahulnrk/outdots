package app.mini.hub.android.connector

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Dedicated WebView controller for owner-operated LinkedIn cookie login.
 *
 * Isolation contract:
 * - Runs only in the `:lilogin` process with a fixed WebView data-directory suffix.
 * - Top-level navigation is HTTPS LinkedIn hosts only. No cookie bypass if LinkedIn
 *   rejects the embedded browser; the owner sees the provider challenge as-is.
 * - Browser-identity headers (`X-LI-Track`, `X-LI-Page-Instance`) are captured from
 *   LinkedIn HTTPS requests during/after navigation. They are not required before
 *   opening sign-in.
 * - Cookie header plus those headers are handed to [onSession] only after the feed
 *   wait pattern and required cookies (`JSESSIONID`, `li_at`) are present, then
 *   scrubbed from this process. Nothing logs or displays cookies/headers/URLs.
 * - Results are tagged with [attempt]; stale callbacks after cancel are ignored.
 */
class LinkedInWebViewLoginController(
    private val webView: WebView,
    private val userAgent: String,
    private val startUrl: String,
    private val successPattern: Regex,
    private val attempt: Long,
    private val onSession: (attempt: Long, cookieHeader: String, pageInstance: String, xLiTrack: String) -> Unit,
    private val onError: (attempt: Long, reason: String) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val pageInstance = AtomicReference("")
    private val xLiTrack = AtomicReference("")

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

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                captureBrowserIdentity(request)
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (finished.get() || url.isNullOrBlank()) return
                onNavigation(url)
            }
        }
        webView.loadUrl(startUrl)
    }

    private fun captureBrowserIdentity(request: WebResourceRequest) {
        val url = request.url?.toString().orEmpty()
        if (!url.startsWith("https://www.linkedin.com") && !url.startsWith("https://linkedin.com")) return
        val headers = request.requestHeaders ?: return
        headers.entries.forEach { (key, value) ->
            when (key.lowercase()) {
                "x-li-page-instance" -> if (value.contains("urn:li:page")) pageInstance.set(value)
                "x-li-track" -> if (value.contains("clientVersion")) xLiTrack.set(value)
            }
        }
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
        val cookieHeader = cookieManager.getCookie("https://www.linkedin.com")
            ?: cookieManager.getCookie("https://linkedin.com")
        if (cookieHeader.isNullOrBlank()) return
        if (!cookieHeader.contains("JSESSIONID=") || !cookieHeader.contains("li_at=")) {
            // Feed may paint before both cookies settle; keep waiting.
            return
        }
        val page = pageInstance.get()
        val track = xLiTrack.get()
        if (page.isBlank() || track.isBlank()) {
            // Browser-identity headers arrive on XHR; keep waiting briefly via later navigations.
            return
        }
        if (!finished.compareAndSet(false, true)) return
        clearProcessCookies()
        onSession(attempt, cookieHeader, page, track)
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
            return host == "linkedin.com" || host.endsWith(".linkedin.com")
        }
    }
}
