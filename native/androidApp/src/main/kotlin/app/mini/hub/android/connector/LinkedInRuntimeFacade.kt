package app.mini.hub.android.connector

/**
 * Reflection wrapper over gomobile `app.mini.hub.wa.liruntime`.
 * Keeps release builds compiling when the debug JNI jar is absent.
 * Never logs cookies, headers, or identity payloads.
 */
internal object LinkedInRuntimeFacade {
    private val packageClass by lazy { Class.forName("app.mini.hub.wa.liruntime.Liruntime") }
    private val runtimeClass by lazy { Class.forName("app.mini.hub.wa.liruntime.Runtime") }

    fun available(): Boolean = runCatching { packageClass; runtimeClass; true }.getOrDefault(false)

    fun browserUserAgent(): String = invokeStaticString("browserUserAgent")
    fun loginStartUrl(): String = invokeStaticString("loginStartURL")
    fun loginSuccessUrlPattern(): String = invokeStaticString("loginSuccessURLPattern")
    fun requiredCookieNamesCsv(): String = invokeStaticString("requiredCookieNamesCSV")

    fun newRuntime(): Any = runtimeClass.getMethod("newRuntime").invoke(null)
        ?: error("linkedin_runtime_unavailable")

    fun importOwnerSession(runtime: Any, cookieHeader: String, pageInstance: String, xLiTrack: String): String =
        runtimeClass.getMethod("importOwnerSession", String::class.java, String::class.java, String::class.java)
            .invoke(runtime, cookieHeader, pageInstance, xLiTrack) as? String ?: "import_failed"

    fun verifyIdentity(runtime: Any): String =
        runtimeClass.getMethod("verifyIdentity").invoke(runtime) as? String ?: """{"ok":false,"code":"verify_unavailable"}"""

    fun exportSessionJson(runtime: Any): String =
        runtimeClass.getMethod("exportSessionJSON").invoke(runtime) as? String ?: ""

    fun importSessionJson(runtime: Any, sessionJson: String): String =
        runtimeClass.getMethod("importSessionJSON", String::class.java).invoke(runtime, sessionJson) as? String
            ?: "import_failed"

    fun clearSession(runtime: Any) {
        runtimeClass.getMethod("clearSession").invoke(runtime)
    }

    fun hasSessionCookies(runtime: Any): Boolean =
        runtimeClass.getMethod("hasSessionCookies").invoke(runtime) as? Boolean ?: false

    fun isAuthenticated(runtime: Any): Boolean =
        runtimeClass.getMethod("isAuthenticated").invoke(runtime) as? Boolean ?: false

    fun verifiedUserId(runtime: Any): String =
        runtimeClass.getMethod("verifiedUserID").invoke(runtime) as? String ?: ""

    private fun invokeStaticString(method: String): String =
        packageClass.getMethod(method).invoke(null) as? String ?: ""
}
