package app.mini.hub.android

import android.content.Context

/**
 * Account-scoped local seen state. This is not a provider read receipt.
 * A conversation is unseen when its latest local event is newer than the last open in MINI.
 */
class LocalSeenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mini_local_seen", Context.MODE_PRIVATE)

    @Synchronized
    fun markSeen(conversationKey: String, atMillis: Long) {
        val previous = prefs.getLong(conversationKey, 0L)
        if (atMillis >= previous) prefs.edit().putLong(conversationKey, atMillis).apply()
    }

    @Synchronized
    fun lastSeenMillis(conversationKey: String): Long = prefs.getLong(conversationKey, 0L)

    fun isUnseen(conversationKey: String, latestEventMillis: Long): Boolean =
        latestEventMillis > lastSeenMillis(conversationKey)
}
