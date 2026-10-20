package app.mini.hub.android.connector

import android.content.Context
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates one real owner-operated Instagram sign-in attempt in the
 * dedicated login process. It owns the single [InstagramLoginController],
 * serializes upstream turns on a background executor, routes WebView steps to a
 * single-attempt [InstagramWebViewLoginController], and persists the verified
 * session only after an authenticated read succeeds.
 *
 * It never enables the account in normal Accounts and never performs a live
 * send. It only stores the verified session and proves a harmless read.
 */
class InstagramLoginSession(
    context: Context,
    private val slotKey: String,
) {
    private val appContext = context.applicationContext
    private val store = SqlCipherInstagramSessionStore(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val controller = AtomicReference<InstagramLoginController?>()
    private val listener = AtomicReference<((InstagramLoginUiState) -> Unit)?>()
    private val latest = AtomicReference(InstagramLoginUiState.initial())
    private val pendingWebView = AtomicReference<InstagramWebViewRequest?>()

    @Volatile private var verifiedAccountKey: String? = null

    fun observe(onState: (InstagramLoginUiState) -> Unit) {
        listener.set(onState)
        onState(latest.get())
    }

    /** Current pending WebView request for the UI to drive, or null. */
    fun pendingWebViewRequest(): InstagramWebViewRequest? = pendingWebView.get()

    fun start() {
        executor.execute {
            val lc = InstagramLoginController.create(slotKey)
            if (lc == null) {
                publish(InstagramLoginUiState.unavailable())
                return@execute
            }
            controller.set(lc)
            publishFromController(lc)
        }
    }

    /** Restores the last verified account after process/activity restart. */
    fun restoreExisting(accountKey: String, onMiss: () -> Unit) {
        executor.execute {
            val accountId = runCatching { AccountId.parse(accountKey) }.getOrNull()
            if (accountId == null || accountId.provider != Provider.INSTAGRAM) {
                onMiss()
                return@execute
            }
            when (val result = InstagramSessionRestore(appContext).restoreAndVerify(accountId)) {
                is InstagramSessionRestore.Result.Verified -> {
                    verifiedAccountKey = accountKey
                    publish(
                        InstagramLoginUiState(
                            status = InstagramLoginStatus.AUTHENTICATED,
                            state = "authenticated",
                            instructions = null,
                            failureCode = null,
                            verifiedUserId = result.userId,
                            verifiedUsername = null,
                            verifiedAccountKey = accountKey,
                        ),
                    )
                }
                is InstagramSessionRestore.Result.NoSession,
                is InstagramSessionRestore.Result.Failed,
                is InstagramSessionRestore.Result.ReadJson -> onMiss()
            }
        }
    }

    fun submitCredentials(username: String, password: String) {
        val lc = controller.get() ?: return
        executor.execute {
            runUpstream(lc) { lc.submitCredentials(username, password) }
        }
    }

    fun submitCode(code: String) {
        val lc = controller.get() ?: return
        val trimmed = code.trim()
        if (trimmed.length < 5) {
            publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "verification_code_invalid"))
            return
        }
        executor.execute {
            val current = latest.get()
            publish(current.copy(status = InstagramLoginStatus.LOADING, instructions = "Submitting verification code…"))
            val needsEnterCodeAction = current.fieldIds.contains("action") ||
                current.choices.any { it.fieldId == "action" && it.value.equals("Enter code", ignoreCase = true) } ||
                current.instructions.orEmpty().contains("Enter code", ignoreCase = true)
            if (needsEnterCodeAction && !current.fieldIds.contains("verification_code")) {
                val selectEnter = JSONObject().put("action", "Enter code").toString().toByteArray(Charsets.UTF_8)
                try {
                    lc.submitInput(selectEnter)
                } catch (error: Throwable) {
                    publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = error.message ?: "upstream_error"))
                    return@execute
                }
                // Refresh snapshot after selecting Enter code; continue only if still interactive.
                publishFromController(lc)
            }
            val codeField = when {
                latest.get().fieldIds.contains("two_factor_code") -> "two_factor_code"
                else -> "verification_code"
            }
            val input = JSONObject().put(codeField, trimmed).toString().toByteArray(Charsets.UTF_8)
            runUpstream(lc) { lc.submitInput(input) }
        }
    }

    fun submitSelection(fieldId: String, value: String) {
        val lc = controller.get() ?: return
        if (!isCurrentSelection(fieldId, value)) {
            publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "invalid_or_stale_selection"))
            return
        }
        executor.execute {
            if (!isCurrentSelection(fieldId, value)) {
                publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "invalid_or_stale_selection"))
                return@execute
            }
            val input = JSONObject().put(fieldId, value).toString().toByteArray(Charsets.UTF_8)
            runUpstream(lc) { lc.submitInput(input) }
        }
    }

    /** Called by the WebView controller with an attempt-tagged result. */
    fun onWebViewResult(attempt: Long, resultJson: ByteArray) {
        val lc = controller.get() ?: return
        pendingWebView.set(null)
        executor.execute { runUpstream(lc) { lc.submitWebViewResult(attempt, resultJson) } }
    }

    fun onWebViewError(reason: String) {
        pendingWebView.set(null)
        publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = reason))
    }

    fun cancel() {
        val lc = controller.get()
        pendingWebView.set(null)
        executor.execute {
            lc?.cancel()
            publish(InstagramLoginUiState(InstagramLoginStatus.CANCELLED, "cancelled", null, null))
        }
    }

    fun close() {
        executor.execute {
            controller.get()?.close()
            controller.set(null)
        }
        executor.shutdown()
    }

    private inline fun runUpstream(lc: InstagramLoginController, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            // Error message from Go is a fixed failure code; never log raw detail.
            publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = error.message ?: "upstream_error"))
            return
        }
        publishFromController(lc)
    }

    private fun publishFromController(lc: InstagramLoginController) {
        val snapshot = runCatching { JSONObject(String(lc.snapshot(), Charsets.UTF_8)) }.getOrNull()
        val state = snapshot?.optString("state") ?: lc.state()
        when (state) {
            "authenticated" -> persistAndVerify(lc)
            "web_challenge", "captcha" -> {
                val request = lc.webViewRequest()?.let(InstagramWebViewRequest::parse)
                if (request == null) {
                    publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "invalid_webview_request"))
                    return
                }
                pendingWebView.set(request)
                publish(
                    InstagramLoginUiState(
                        status = InstagramLoginStatus.WEBVIEW,
                        state = state,
                        instructions = snapshot?.optString("instructions"),
                        failureCode = null,
                    ),
                )
            }
            "failed" -> publish(
                InstagramLoginUiState(
                    status = InstagramLoginStatus.FAILED,
                    state = state,
                    instructions = snapshot?.optString("instructions"),
                    failureCode = snapshot?.optString("failure_code")?.ifEmpty { "upstream_error" } ?: "upstream_error",
                ),
            )
            "cancelled" -> publish(InstagramLoginUiState(InstagramLoginStatus.CANCELLED, state, null, null))
            else -> publish(
                InstagramLoginUiState(
                    status = InstagramLoginStatus.INPUT,
                    state = state,
                    instructions = snapshot?.optString("instructions"),
                    failureCode = null,
                        fieldIds = snapshot?.optJSONArray("field_ids")?.let { arr ->
                            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                        } ?: emptyList(),
                        choices = parseChoices(snapshot),
                    ),
                )
        }
    }

    private fun isCurrentSelection(fieldId: String, value: String): Boolean {
        return isValidInstagramSelection(latest.get(), fieldId, value)
    }

    private fun parseChoices(snapshot: JSONObject?): List<InstagramSelectionOption> {
        val parsed = parseInstagramSelectionChoices(snapshot)
        return parsed
    }

    private fun persistAndVerify(lc: InstagramLoginController) {
        val accountKey = lc.verifiedAccountKey()
        val envelope = runCatching { lc.exportVerifiedSession() }.getOrNull()
        if (accountKey.isEmpty() || envelope == null) {
            publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "missing_verified_session"))
            return
        }
        val accountId = AccountId.parse(accountKey)
        require(accountId.provider == Provider.INSTAGRAM) { "verified account is not Instagram" }
        val wrapped = InstagramSessionEnvelope.fromEncoded(envelope)
        if (wrapped == null) {
            publish(latest.get().copy(status = InstagramLoginStatus.FAILED, failureCode = "invalid_session_envelope"))
            return
        }
        store.save(accountId, wrapped)
        appContext.getSharedPreferences(PROBE_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_ACCOUNT_KEY, accountKey)
            .apply()
        verifiedAccountKey = accountKey
        publish(
            InstagramLoginUiState(
                status = InstagramLoginStatus.AUTHENTICATED,
                state = "authenticated",
                instructions = null,
                failureCode = null,
                verifiedUserId = lc.verifiedUserId(),
                verifiedUsername = lc.verifiedUsername(),
                verifiedAccountKey = accountKey,
            ),
        )
    }

    private fun publish(state: InstagramLoginUiState) {
        latest.set(state)
        listener.get()?.invoke(state)
    }

    companion object {
        const val PROBE_STATE_PREFS = "mini_instagram_probe_state"
        const val LAST_ACCOUNT_KEY = "last_verified_account_key"
    }
}

enum class InstagramLoginStatus { LOADING, INPUT, WEBVIEW, AUTHENTICATED, FAILED, CANCELLED, UNAVAILABLE }

data class InstagramLoginUiState(
    val status: InstagramLoginStatus,
    val state: String?,
    val instructions: String?,
    val failureCode: String?,
    val fieldIds: List<String> = emptyList(),
    val verifiedUserId: String? = null,
    val verifiedUsername: String? = null,
    val verifiedAccountKey: String? = null,
    val choices: List<InstagramSelectionOption> = emptyList(),
) {
    companion object {
        fun initial() = InstagramLoginUiState(InstagramLoginStatus.LOADING, null, null, null)
        fun unavailable() = InstagramLoginUiState(InstagramLoginStatus.UNAVAILABLE, null, null, "runtime_unavailable")
    }
}

data class InstagramSelectionOption(
    val fieldId: String,
    val value: String,
    val label: String,
)

internal fun parseInstagramSelectionChoices(snapshot: JSONObject?): List<InstagramSelectionOption> {
    val choices = snapshot?.optJSONArray("choices") ?: return emptyList()
    return buildList {
        for (i in 0 until choices.length()) {
            val item = choices.optJSONObject(i) ?: continue
            val fieldId = item.optString("field_id")
            val value = item.optString("value")
            val label = item.optString("label")
            if (fieldId.isNotBlank() && value.isNotBlank() && label.isNotBlank()) {
                add(InstagramSelectionOption(fieldId, value, label))
            }
        }
    }
}

internal fun isValidInstagramSelection(
    state: InstagramLoginUiState,
    fieldId: String,
    value: String,
): Boolean {
    if (fieldId.isBlank() || value.isBlank() || state.status != InstagramLoginStatus.INPUT) return false
    if (state.choices.any { it.fieldId == fieldId && it.value == value }) return true
    // Synthesized OTP action/method choices when upstream field IDs survived but choice labels did not.
    if (fieldId == "action" && state.fieldIds.contains("action") &&
        value in setOf("Enter code", "Resend code", "Try another method")
    ) return true
    if (fieldId == "method" && state.fieldIds.contains("method") && value.isNotBlank()) return true
    return state.state == "select" && state.choices.any { it.fieldId == fieldId && it.value == value }
}
