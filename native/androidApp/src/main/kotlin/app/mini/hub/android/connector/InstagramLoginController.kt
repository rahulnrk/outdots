package app.mini.hub.android.connector

/**
 * Reflection wrapper over the gomobile-bound Go `igruntime.LoginController`.
 *
 * The controller drives the real pinned mautrix-meta native Instagram login.
 * This Kotlin side never sees a cookie, token, challenge URL, or the password
 * after it is handed to [submitCredentials]; only the sanitized JSON snapshot
 * and the dedicated-WebView request cross this boundary.
 *
 * All methods are thread-confined by the caller ([InstagramLoginSession]).
 */
class InstagramLoginController private constructor(private val native: Any) {

    private fun method(name: String, vararg types: Class<*>) =
        native.javaClass.getMethod(name, *types)

    private fun invoke(name: String, vararg args: Any?): Any? =
        try {
            if (args.isEmpty()) {
                native.javaClass.getMethod(name).invoke(native)
            } else {
                native.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }
                    .invoke(native, *args)
            }
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }

    /** Secret-free JSON snapshot safe for ordinary UI state. */
    fun snapshot(): ByteArray = invoke("snapshot") as ByteArray

    fun state(): String = invoke("state") as String

    fun attemptId(): Long = invoke("attemptID") as Long

    /** The current dedicated-WebView request JSON, or null for non-WebView steps. */
    fun webViewRequest(): ByteArray? = invoke("webViewRequestJSON") as? ByteArray

    /**
     * Begins the real upstream native login. The password is used only for this
     * call and is never retained by Kotlin or Go.
     */
    fun submitCredentials(username: String, password: String) {
        try {
            method("submitCredentials", String::class.java, String::class.java).invoke(native, username, password)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    /** Continues a non-WebView upstream step (2FA code, account selection). */
    fun submitInput(inputJson: ByteArray) {
        try {
            method("submitInput", ByteArray::class.java).invoke(native, inputJson)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    /**
     * Feeds a dedicated-WebView result back into the upstream step. [attempt]
     * must match the generation that issued the request; stale callbacks are
     * rejected by the Go controller.
     */
    fun submitWebViewResult(attempt: Long, resultJson: ByteArray) {
        method("submitWebViewResult", java.lang.Long.TYPE, ByteArray::class.java)
            .invoke(native, attempt, resultJson)
    }

    fun verifiedUserId(): String = invoke("verifiedUserID") as String

    fun verifiedUsername(): String = invoke("verifiedUsername") as String

    fun verifiedAccountKey(): String = invoke("verifiedAccountKey") as String

    /** Encrypted-session envelope bytes to hand to the SQLCipher store. */
    fun exportVerifiedSession(): ByteArray =
        try {
            method("exportVerifiedSession").invoke(native) as ByteArray
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }

    fun cancel() {
        invoke("cancel")
    }

    fun close() {
        invoke("close")
    }

    companion object {
        /**
         * Creates a login controller for [accountSlotKey] (a provisional MINI
         * key). The stored account is rebound to the upstream-verified user id on
         * success. Returns null if the Instagram runtime is not packaged.
         */
        fun create(accountSlotKey: String): InstagramLoginController? {
            return try {
                val facade = Class.forName("app.mini.hub.wa.igruntime.Igruntime")
                val native = facade.methods
                    .first { it.name == "newLoginController" && it.parameterTypes.size == 1 }
                    .invoke(null, accountSlotKey)
                    ?: return null
                InstagramLoginController(native)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
