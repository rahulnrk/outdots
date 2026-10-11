package app.mini.hub.android

import android.content.Context

/**
 * User-chosen category labels per conversation (Priority, Family, Work, Sales, ...).
 * Stored only on this device. Never inferred by AI.
 */
class ConversationLabels(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mini_conversation_labels", Context.MODE_PRIVATE)

    @Synchronized
    fun labelFor(conversationKey: String): String? = prefs.getString(conversationKey, null)

    @Synchronized
    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    @Synchronized
    fun set(conversationKey: String, label: String?) {
        prefs.edit().apply { if (label.isNullOrBlank()) remove(conversationKey) else putString(conversationKey, label) }.apply()
    }

    companion object {
        val DEFAULTS = listOf("Priority", "Family", "Friends", "Work", "Sales")
    }
}
