package app.mini.hub.android.intelligence

import android.os.Looper
import android.util.Log

/**
 * Structured, content-free trace lines for the local AI runtime (logcat tag `MiniLocalAi`).
 * Never logs message text, prompts, or drafts. Safe to call from JVM unit tests.
 */
internal object LocalAiTrace {
    const val TAG = "MiniLocalAi"

    fun log(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is not available in local JVM tests.
        }
    }

    /** Current process PSS in kB, or -1 when unavailable (JVM tests). Call off the main thread. */
    fun pssKb(): Long = try {
        android.os.Debug.getPss()
    } catch (_: RuntimeException) {
        -1L
    }
}

internal fun onAndroidMainThread(): Boolean = try {
    Looper.getMainLooper()?.thread === Thread.currentThread()
} catch (_: RuntimeException) {
    false
}
