package app.mini.hub.persistence

/**
 * Observation database schema shared by the Android SQLCipher store and the JVM sqlite-jdbc store,
 * so both migrate the same way. Version history:
 *  - 1: observations, conversations, cursors
 *  - 2: conversation_summaries.app_name and .category
 *  - 3: notification_observations.direction, .recipients_json, .reply_anchor (null = unknown)
 *  - 4: notification_observations.notification_category (null = reconstruct from stored text)
 */
object ObservationSchema {
    const val VERSION = 5

    const val INDEX_FTS_VERSION = "CREATE INDEX IF NOT EXISTS idx_obs_fts_version ON ingest_cursors (source, last_processed_time_millis)"
    const val CREATE_OBSERVATIONS_V1 = """
        CREATE TABLE IF NOT EXISTS notification_observations (
            id TEXT NOT NULL PRIMARY KEY,
            account_key TEXT NOT NULL,
            conversation_key TEXT NOT NULL,
            package_id TEXT NOT NULL,
            notification_key TEXT NOT NULL,
            notification_id INTEGER NOT NULL,
            tag TEXT,
            sender_hint TEXT NOT NULL,
            title TEXT,
            text_fragments_json TEXT NOT NULL,
            raw_text_digest TEXT NOT NULL,
            observed_at INTEGER NOT NULL,
            post_time INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            is_removed INTEGER NOT NULL CHECK (is_removed IN (0, 1)),
            is_group_summary INTEGER NOT NULL CHECK (is_group_summary IN (0, 1)),
            truncation_state TEXT NOT NULL,
            correlation_confidence TEXT NOT NULL,
            provenance TEXT NOT NULL
        )
    """

    /** Conversation summaries in their version 2 shape. */
    const val CREATE_CONVERSATIONS_V1 = """
        CREATE TABLE IF NOT EXISTS conversation_summaries (
            conversation_key TEXT NOT NULL PRIMARY KEY,
            account_key TEXT NOT NULL,
            title TEXT NOT NULL,
            app_name TEXT NOT NULL DEFAULT '',
            sender_hint TEXT NOT NULL,
            package_id TEXT NOT NULL,
            latest_snippet TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT 'GeneralNotification',
            coverage TEXT NOT NULL,
            coverage_explanation TEXT NOT NULL,
            latest_event_at INTEGER NOT NULL,
            observation_count INTEGER NOT NULL,
            unread_count INTEGER NOT NULL,
            is_removed INTEGER NOT NULL CHECK (is_removed IN (0, 1))
        )
    """

    const val CREATE_CURSORS_V1 = """
        CREATE TABLE IF NOT EXISTS ingest_cursors (
            source TEXT NOT NULL PRIMARY KEY,
            last_processed_time_millis INTEGER NOT NULL,
            last_processed_key TEXT,
            processed_count INTEGER NOT NULL,
            gap_count INTEGER NOT NULL
        )
    """

    const val CREATE_FTS_OBSERVATIONS = """
        CREATE VIRTUAL TABLE IF NOT EXISTS obs_fts USING fts5(
            obs_id UNINDEXED,
            conversation_key UNINDEXED,
            account_key UNINDEXED,
            package_id UNINDEXED,
            title,
            body,
            tokenize="unicode61 remove_diacritics 2 categories 'L* N* Co M*'"
        )
    """

    const val POPULATE_FTS_FROM_EXISTING = """
        INSERT INTO obs_fts(obs_id, conversation_key, account_key, package_id, title, body)
        SELECT id, conversation_key, account_key, package_id, coalesce(title, ''), text_fragments_json
        FROM notification_observations
        WHERE (is_removed = 0 OR (is_removed = 1 AND notification_key NOT LIKE 'provider:%'))
          AND id NOT IN (SELECT obs_id FROM obs_fts)
    """

    const val INDEX_NOTIFICATION_KEY =
        "CREATE INDEX IF NOT EXISTS idx_obs_notif_key ON notification_observations (notification_key)"

    /** Timeline order: provider event time, then fetch time, then id (stable for equal timestamps). */
    const val INDEX_CONVERSATION_EVENT =
        "CREATE INDEX IF NOT EXISTS idx_obs_conv_event ON notification_observations (conversation_key, post_time DESC, observed_at DESC, id DESC)"

    const val TIMELINE_ORDER = "post_time DESC, observed_at DESC, id DESC"

    /** Statements that bring the observations table to version 3, given its existing columns. */
    fun version3Statements(existingObservationColumns: Set<String>): List<String> = buildList {
        if ("direction" !in existingObservationColumns) add("ALTER TABLE notification_observations ADD COLUMN direction TEXT")
        if ("recipients_json" !in existingObservationColumns) add("ALTER TABLE notification_observations ADD COLUMN recipients_json TEXT")
        if ("reply_anchor" !in existingObservationColumns) add("ALTER TABLE notification_observations ADD COLUMN reply_anchor TEXT")
        add(INDEX_CONVERSATION_EVENT)
    }

    /** Statements that add the ingest category column without rewriting existing rows. */
    fun version4Statements(existingObservationColumns: Set<String>): List<String> = buildList {
        if ("notification_category" !in existingObservationColumns) {
            add("ALTER TABLE notification_observations ADD COLUMN notification_category TEXT")
        }
    }

    private const val SEP = "\u001F"

    /** Null = unknown. An empty list is stored as unknown as well; providers always name a recipient. */
    fun encodeRecipients(recipients: List<String>?): String? = recipients?.takeIf { it.isNotEmpty() }?.joinToString(SEP)
    fun decodeRecipients(value: String?): List<String>? = value?.takeIf { it.isNotEmpty() }?.split(SEP)

    fun encodeDirection(direction: app.mini.hub.connector.MessageDirection?): String? = direction?.name
    fun decodeDirection(value: String?): app.mini.hub.connector.MessageDirection? =
        value?.let { v -> app.mini.hub.connector.MessageDirection.entries.firstOrNull { it.name == v } }

    fun encodeCategory(category: app.mini.hub.observation.NotificationCategory?): String? = category?.name
    fun decodeCategory(value: String?): app.mini.hub.observation.NotificationCategory? =
        value?.let { v -> app.mini.hub.observation.NotificationCategory.entries.firstOrNull { it.name == v } }
}
