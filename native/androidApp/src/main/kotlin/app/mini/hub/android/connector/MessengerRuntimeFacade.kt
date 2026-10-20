package app.mini.hub.android.connector

/**
 * Reflection wrapper over gomobile `app.mini.hub.wa.msgruntime`.
 * Package-level funcs (including newRuntime) live on `Msgruntime`;
 * instance methods live on `Runtime`.
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

    fun newRuntime(): Any = packageClass.getMethod("newRuntime").invoke(null)
        ?: error("messenger_runtime_unavailable")

    fun importOwnerSession(runtime: Any, cookieHeader: String): String =
        call(runtime, "importOwnerSession", cookieHeader) ?: "import_failed"

    fun verifyIdentity(runtime: Any): String =
        call(runtime, "verifyIdentity") ?: """{"ok":false,"code":"verify_unavailable"}"""

    fun conversationReadJson(runtime: Any): String =
        call(runtime, "conversationReadJSON") ?: """{"read":false,"threads":0}"""

    fun exportSessionJson(runtime: Any): String = call(runtime, "exportSessionJSON") ?: ""

    fun importSessionJson(runtime: Any, sessionJson: String): String =
        call(runtime, "importSessionJSON", sessionJson) ?: "import_failed"

    fun clearSession(runtime: Any) {
        runtimeClass.getMethod("clearSession").invoke(runtime)
    }

    fun openEncryptedDeviceStore(runtime: Any, dbPath: String): String =
        call(runtime, "openEncryptedDeviceStore", dbPath) ?: "device_store_open_failed"

    fun prepareEncryptedClient(runtime: Any): String =
        call(runtime, "prepareEncryptedClient") ?: "prepare_e2ee_failed"

    fun registerEncryptedDevice(runtime: Any): String =
        call(runtime, "registerEncryptedDevice") ?: "register_failed"

    fun encryptedDeviceRegistered(runtime: Any): Boolean =
        runtimeClass.getMethod("encryptedDeviceRegistered").invoke(runtime) as? Boolean ?: false

    fun listenEncrypted(runtime: Any, seconds: Long): String =
        runtimeClass.getMethod("listenEncrypted", java.lang.Long.TYPE).invoke(runtime, seconds) as? String
            ?: """{"code":"listen_unavailable"}"""

    fun hasEncryptedDevice(runtime: Any): Boolean =
        runtimeClass.getMethod("hasEncryptedDevice").invoke(runtime) as? Boolean ?: false

    fun hasSessionCookies(runtime: Any): Boolean =
        runtimeClass.getMethod("hasSessionCookies").invoke(runtime) as? Boolean ?: false

    fun isAuthenticated(runtime: Any): Boolean =
        runtimeClass.getMethod("isAuthenticated").invoke(runtime) as? Boolean ?: false

    fun verifiedUserId(runtime: Any): String = call(runtime, "verifiedUserID") ?: ""

    fun close(runtime: Any) {
        runtimeClass.getMethod("close").invoke(runtime)
    }

    private fun call(runtime: Any, method: String): String? =
        runtimeClass.getMethod(method).invoke(runtime) as? String

    private fun call(runtime: Any, method: String, arg: String): String? =
        runtimeClass.getMethod(method, String::class.java).invoke(runtime, arg) as? String

    private fun invokeStaticString(method: String): String =
        packageClass.getMethod(method).invoke(null) as? String ?: ""
}
