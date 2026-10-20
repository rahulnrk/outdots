package app.mini.hub.android.connector

import android.content.Context
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/**
 * Restores an encrypted Instagram session from the SQLCipher store into a fresh
 * Go runtime and proves a harmless authenticated read. Used on the restart path
 * and by the rtprobe verification. It never enables the account in normal
 * Accounts and never sends anything.
 */
class InstagramSessionRestore(context: Context) {
    private val appContext = context.applicationContext
    private val store = SqlCipherInstagramSessionStore(appContext)

    sealed interface Result {
        data class Verified(val userId: String) : Result
        data object NoSession : Result
        data class Failed(val reason: String) : Result
        data class ReadJson(val json: String) : Result
    }

    /**
     * Loads [accountId]'s session, restores it into a new runtime, and performs
     * the harmless authenticated inbox read. Returns the upstream-verified user
     * id on success. Performs network I/O.
     */
    fun restoreAndVerify(accountId: AccountId): Result {
        require(accountId.provider == Provider.INSTAGRAM) { "not an Instagram account" }
        val envelope = store.load(accountId) ?: return Result.NoSession
        val runtime = createRuntime() ?: return Result.Failed("runtime_unavailable")
        return try {
            restore(runtime, accountId.key, envelope.ciphertext)
            val userId = verifyRead(runtime)
            if (userId.isEmpty()) Result.Failed("empty_verified_identity") else Result.Verified(userId)
        } catch (error: Throwable) {
            Result.Failed(safeRestoreError(error))
        }
    }

    /** Reads a bounded inbox after restoring the account and proving the session. */
    fun readRecent(accountId: AccountId, maxThreads: Int = 20, maxMessages: Int = 20): Result {
        require(accountId.provider == Provider.INSTAGRAM) { "not an Instagram account" }
        val envelope = store.load(accountId) ?: return Result.NoSession
        val runtime = createRuntime() ?: return Result.Failed("runtime_unavailable")
        return try {
            restore(runtime, accountId.key, envelope.ciphertext)
            val userId = verifyRead(runtime)
            if (userId.isEmpty()) Result.Failed("empty_verified_identity")
            else Result.ReadJson(readInbox(runtime, maxThreads, maxMessages))
        } catch (error: Throwable) {
            Result.Failed(safeRestoreError(error))
        }
    }

    private fun readInbox(runtime: Any, maxThreads: Int, maxMessages: Int): String =
        try {
            val bytes = runtime.javaClass
                .getMethod("readRecentInboxJSON", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(runtime, maxThreads, maxMessages) as ByteArray
            bytes.toString(Charsets.UTF_8)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }

    private fun safeRestoreError(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "token" in message || "expired" in message || "invalid" in message -> "session_expired"
            "network" in message || "timeout" in message -> "network_error"
            else -> "read_failed"
        }
    }

    /** Restores the session in memory without the network read (for tests). */
    fun restoreInMemory(accountId: AccountId): Boolean {
        require(accountId.provider == Provider.INSTAGRAM) { "not an Instagram account" }
        val envelope = store.load(accountId) ?: return false
        val runtime = createRuntime() ?: return false
        return try {
            restore(runtime, accountId.key, envelope.ciphertext)
            isAuthenticated(runtime)
        } catch (_: Throwable) {
            false
        }
    }

    private fun createRuntime(): Any? = try {
        Class.forName("app.mini.hub.wa.igruntime.Igruntime")
            .getMethod("newRuntime").invoke(null)
    } catch (_: Throwable) {
        null
    }

    private fun restore(runtime: Any, accountKey: String, encoded: ByteArray) {
        try {
            runtime.javaClass.methods
                .first { it.name == "restoreSessionEnvelope" && it.parameterTypes.size == 2 }
                .invoke(runtime, accountKey, encoded)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    private fun verifyRead(runtime: Any): String =
        try {
            runtime.javaClass.getMethod("verifyAuthenticatedRead").invoke(runtime) as String
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }

    private fun isAuthenticated(runtime: Any): Boolean =
        runtime.javaClass.getMethod("isAuthenticated").invoke(runtime) as Boolean
}
