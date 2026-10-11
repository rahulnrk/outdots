package app.mini.hub.android

import android.content.Context
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.LinkedInActivityType

/**
 * Persists notification-source choices only. This is not an authenticated account store and
 * never creates provider identities or credentials.
 */
class CommunicationSourcePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun selectedSources(): Set<CommunicationSource> {
        val stored = preferences.getStringSet(KEY_SELECTED, null)
            ?.mapNotNull(CommunicationSource::fromId)
            ?.toSet()
        return stored ?: CommunicationSource.defaultSelection
    }

    @Synchronized
    fun setSelected(source: CommunicationSource, selected: Boolean) {
        val next = selectedSources().toMutableSet()
        if (selected) next += source else next -= source
        preferences.edit()
            .putStringSet(KEY_SELECTED, next.map { it.name }.toSet())
            .apply()
    }

    @Synchronized
    fun replaceSelection(sources: Set<CommunicationSource>) {
        preferences.edit()
            .putStringSet(KEY_SELECTED, sources.map { it.name }.toSet())
            .apply()
    }

    @Synchronized
    fun selectedLinkedInActivities(): Set<LinkedInActivityType> {
        val stored = preferences.getStringSet(KEY_LINKEDIN_ACTIVITIES, null)
            ?.mapNotNull { name -> runCatching { LinkedInActivityType.valueOf(name) }.getOrNull() }
            ?.toSet()
        return stored ?: emptySet()
    }

    @Synchronized
    fun setLinkedInActivitySelected(activity: LinkedInActivityType, selected: Boolean) {
        val next = selectedLinkedInActivities().toMutableSet()
        if (selected) next += activity else next -= activity
        preferences.edit()
            .putStringSet(KEY_LINKEDIN_ACTIVITIES, next.map { it.name }.toSet())
            .apply()
    }

    companion object {
        private const val FILE_NAME = "mini_communication_sources"
        private const val KEY_SELECTED = "selected_sources"
        private const val KEY_LINKEDIN_ACTIVITIES = "linkedin_activity_types"
    }
}
