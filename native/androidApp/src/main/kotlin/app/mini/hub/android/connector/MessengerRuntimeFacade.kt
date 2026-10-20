package app.mini.hub.android.connector

/**
 * Reflection wrapper over gomobile `app.mini.hub.wa.msgruntime`.
 * Keeps release builds compiling when the debug JNI jar is absent.
 * Never logs cookies, headers, or identity payloads.
 */
internal object MessengerRuntimeFacade {
    private val packageClass by lazy { Class.forName("app.mini.hub.wa.msgruntime.Msgruntime") }
    private val runtimeClass by lazy { Class.forName("app.mini.hub.wa.msgruntime.Runtime") }

    fun available(): Boolean = runCatching { packageClass; runtimeClass; true }.getOrDefault(false)

    fun browserUserAgent(): String = invokeStaticString("browserUserAgent")
    fun loginStartUrl(): String = invokeStaticString("loginStartURL")
    fun loginSuccessUrlPattern(): String = invokeStaticString("loginSuccessURLPattern")
    fun requiredCookieNamesCsv(): String = invokeStaticString("requiredCookieNamesCSV")

    fun newRuntime(): Any = runtimeClass.getMethod("newRuntime").invoke(null)
        ?: error("messenger_runtime_unavailable")

    fun importOwnerSession(runtime: Any, cookieHeader: String): String =
        runtimeClass.getMethod("importOwnerSession", String::class.java)
            .invoke(runtime, cookieHeader) as? String ?: "import_failed"

    fun verifyIdentity(runtime: Any): String =
        runtimeClass.getMethod("verifyIdentity").invoke(runtime) as? String
            ?: """{"ok":false,"code":"verify_unavailable"}"""

    fun exportSessionJson(runtime: Any): String =
        runtimeClass.getMethod("exportSessionJSON").invoke(runtime) as? String ?: ""

    fun importSessionJson(runtime: Any, sessionJson: String): String =
        runtimeClass.getMethod("importSessionJSON", String::class.java).invoke(runtime, sessionJson) as? String
            ?: "import_failed"

    fun clearSession(runtime: Any) {
        runtimeClass.getMethod("clearSession").invoke(runtime)
    }

    fun openEncryptedDeviceStore(runtime: Any, dbPath: String): String =
        runtimeClass.getMethod("openEncryptedDeviceStore", String::class.java)
            .invoke(runtime, dbPath) as? String ?: "device_store_open_failed"

    fun prepareEncryptedClient(runtime: Any): String =
        runtimeClass.getMethod("prepareEncryptedClient").invoke(runtime) as? String ?: "prepare_e2ee_failed"

    fun registerEncryptedDevice(runtime: Any): String =
        runtimeClass.getMethod("registerEncryptedDevice").invoke(runtime) as? String ?: "register_failed"

    fun hasEncryptedDevice(runtime: Any): Boolean =
        runtimeClass.getMethod("hasEncryptedDevice").invoke(runtime) as? Boolean ?: false

    fun hasSessionCookies(runtime: Any): Boolean =
        runtimeClass.getMethod("hasSessionCookies").invoke(runtime) as? Boolean ?: false

    fun isAuthenticated(runtime: Any): Boolean =
        runtimeClass.getMethod("isAuthenticated").invoke(runtime) as? Boolean ?: false

    fun verifiedUserId(runtime: Any): String =
        runtimeClass.getMethod("verifiedUserID").invoke(runtime) as? String ?: ""

    fun close(runtime: Any) {
        runtimeClass.getMethod("close").invoke(runtime)
    }

    private fun invokeStaticString(method: String): String =
        packageClass.getMethod(method).invoke(null) as? String ?: ""
}
