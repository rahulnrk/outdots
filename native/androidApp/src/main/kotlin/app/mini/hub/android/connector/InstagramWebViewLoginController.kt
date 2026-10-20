package app.mini.hub.android.connector

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated, single-attempt WebView controller for the two upstream
 * Instagram steps that require a browser: a web challenge (cookie extraction)
 * and a reCAPTCHA (token extraction). It is created and destroyed per attempt.
 *
 * Isolation and safety contract (see docs/INSTAGRAM_SESSION_LOGIN):
 *  - It runs only inside the dedicated `:iglogin` process whose WebView data
 *    directory suffix is fixed at process start (see [InstagramLoginProcess]).
 *    `WebView.setDataDirectorySuffix` is process-wide and cannot change after a
 *    WebView exists, so a per-attempt suffix is impossible; one fixed isolated
 *    profile plus one active attempt is the design.
 *  - Only HTTPS Instagram top-level navigation is allowed. `fbsbx.com` is
 *    permitted solely as the CAPTCHA iframe origin the upstream script uses.
 *  - The account-scoped cookies are cleared from this process's CookieManager
 *    after extraction. The shared default CookieManager of other processes is
 *    never touched because this is a separate process.
 *  - Nothing here logs URLs, cookies, tokens, credentials, or page content.
 *  - The result is tagged with the upstream attempt id; a result produced after
 *    cancellation or step advance is rejected by the Go controller as stale.
 */
class InstagramWebViewLoginController(
    private val webView: WebView,
    private val request: InstagramWebViewRequest,
    private val onResult: (attempt: Long, resultJson: ByteArray) -> Unit,
    private val onError: (reason: String) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val waitPattern = request.waitPattern?.let { runCatching { Regex(it) }.getOrNull() }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (request.userAgent.isNotEmpty()) {
            settings.userAgentString = request.userAgent
        }
        // Account-scoped cookie acceptance within this dedicated process only.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Reject any top-level navigation outside the HTTPS Instagram allowlist.
                return !isAllowedTopLevel(req.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (finished.get() || url == null) return
                onNavigation(url)
            }
        }
        webView.loadUrl(request.url)
    }

    private fun onNavigation(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isAllowedTopLevel(uri)) {
            fail("navigation_rejected")
            return
        }
        when (request.kind) {
            InstagramWebViewRequest.Kind.CAPTCHA -> runExtractJs()
            InstagramWebViewRequest.Kind.CHALLENGE -> {
                // Only harvest once the logged-in URL pattern is reached.
                val pattern = waitPattern
                if (pattern == null || pattern.matches(url)) {
                    harvestCookies()
                }
            }
        }
    }

    private fun runExtractJs() {
        val js = request.extractJs
        if (js.isEmpty()) {
            fail("missing_extract_js")
            return
        }
        // The upstream script resolves to an object of result fields. We wrap it
        // so a Promise or a value both serialize to JSON for extraction.
        val wrapped = "(function(){try{return Promise.resolve($js).then(function(r){return JSON.stringify(r);});}" +
            "catch(e){return Promise.resolve('');}})()"
        webView.evaluateJavascript(wrapped) { raw -> handleExtractResult(raw) }
    }

    private fun handleExtractResult(raw: String?) {
        if (finished.get()) return
        // raw is a JSON-encoded string (possibly null/"null"/empty).
        val decoded = decodeDoubleJson(raw) ?: return
        val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return
        val result = JSONObject()
        for (field in request.fields) {
            val value = obj.optString(field, "")
            if (value.isNotEmpty()) result.put(field, value)
        }
        if (result.length() == 0) {
            // Keep waiting for the script to resolve on a later navigation.
            return
        }
        emit(result)
    }

    private fun harvestCookies() {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie("https://instagram.com") ?: cookieManager.getCookie(request.url)
        if (cookieHeader.isNullOrEmpty()) return
        val jar = parseCookieHeader(cookieHeader)
        val result = JSONObject()
        var haveRequired = true
        for (field in request.fields) {
            val value = jar[field]
            if (value != null) result.put(field, value)
        }
        // Required fields are the ones the Go step marked required; the upstream
        // submit will reject a set missing required cookies, so we simply emit
        // whatever is present once the logged-in URL is reached.
        if (result.length() == 0) haveRequired = false
        if (!haveRequired) return
        emit(result)
    }

    private fun emit(result: JSONObject) {
        if (!finished.compareAndSet(false, true)) return
        val payload = result.toString().toByteArray(Charsets.UTF_8)
        // Scrub this process's account-scoped cookies after extraction.
        clearProcessCookies()
        onResult(request.attempt, payload)
    }

    private fun fail(reason: String) {
        if (!finished.compareAndSet(false, true)) return
        clearProcessCookies()
        onError(reason)
    }

    /** Cancels an in-flight attempt, destroys the WebView, and scrubs cookies. */
    fun cancel() {
        if (!finished.compareAndSet(false, true)) {
            clearProcessCookies()
            return
        }
        clearProcessCookies()
        onError("cancelled")
    }

    private fun clearProcessCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    companion object {
        private val ALLOWED_HOSTS = setOf("www.instagram.com", "instagram.com", "www.fbsbx.com", "fbsbx.com")

        fun isAllowedTopLevel(uri: Uri?): Boolean {
            if (uri == null) return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.userInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            return ALLOWED_HOSTS.contains(host)
        }

        private fun decodeDoubleJson(raw: String?): String? {
            if (raw.isNullOrEmpty() || raw == "null") return null
            // evaluateJavascript returns a JSON-encoded value. Our wrapper returns
            // a JSON string, so raw is a quoted string. Unwrap it.
            return runCatching {
                val unwrapped = org.json.JSONTokener(raw).nextValue()
                when (unwrapped) {
                    is String -> unwrapped
                    else -> null
                }
            }.getOrNull()
        }

        private fun parseCookieHeader(header: String): Map<String, String> {
            val map = HashMap<String, String>()
            header.split(';').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty()) map[name] = value
                }
            }
            return map
        }
    }
}

/** Parsed, secret-free-to-UI WebView request. Only the controller sees it. */
data class InstagramWebViewRequest(
    val attempt: Long,
    val kind: Kind,
    val url: String,
    val userAgent: String,
    val extractJs: String,
    val waitPattern: String?,
    val fields: List<String>,
) {
    enum class Kind { CHALLENGE, CAPTCHA }

    companion object {
        /** Parses the Go controller's webViewRequestJSON output. */
        fun parse(json: ByteArray): InstagramWebViewRequest? {
            val obj = runCatching { JSONObject(String(json, Charsets.UTF_8)) }.getOrNull() ?: return null
            val url = obj.optString("url")
            if (url.isEmpty()) return null
            if (!InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse(url))) return null
            val extractJs = obj.optString("extract_js")
            val waitPattern = obj.optString("wait_pattern").ifEmpty { null }
            val fieldsArray = obj.optJSONArray("fields")
            val fields = buildList {
                if (fieldsArray != null) for (i in 0 until fieldsArray.length()) add(fieldsArray.optString(i))
            }.filter { it.isNotEmpty() }
            val kind = if (extractJs.isNotEmpty() && waitPattern == null) Kind.CAPTCHA else Kind.CHALLENGE
            return InstagramWebViewRequest(
                attempt = obj.optLong("attempt"),
                kind = kind,
                url = url,
                userAgent = obj.optString("user_agent"),
                extractJs = extractJs,
                waitPattern = waitPattern,
                fields = fields,
            )
        }
    }
}
