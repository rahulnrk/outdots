package app.mini.hub.persistence

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.IngestCursor
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.coverageExplanationFor
import app.mini.hub.observation.coverageFor
import app.mini.hub.observation.TruncationState
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Encrypted SQLCipher observation store for Android.
 * Securely persists notification observations, conversation summaries, and ingest cursors.
 */
class SqlCipherObservationStore(
    context: Context,
    databaseName: String = "mini-hub-observations.db",
) : ObservationStore {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val keyEnvelope = KeystoreObservationDatabaseKey(appContext, databaseName)

    override val qualification: StorageQualification = StorageQualification.SqlCipherAndroidKeystorePending

    var isFts5Supported: Boolean = false
        private set

    companion object {
        const val DATABASE_VERSION = 2

        const val SCHEMA_OBSERVATIONS = """
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
        const val SCHEMA_OBSERVATIONS_INDEX_CONV = """
            CREATE INDEX IF NOT EXISTS idx_obs_conversation ON notification_observations (conversation_key, observed_at DESC)
        """
        const val SCHEMA_OBSERVATIONS_INDEX_KEY = """
            CREATE INDEX IF NOT EXISTS idx_obs_notif_key ON notification_observations (notification_key)
        """
        const val SCHEMA_CONVERSATIONS = """
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
        const val SCHEMA_CURSORS = """
            CREATE TABLE IF NOT EXISTS ingest_cursors (
                source TEXT NOT NULL PRIMARY KEY,
                last_processed_time_millis INTEGER NOT NULL,
                last_processed_key TEXT,
                processed_count INTEGER NOT NULL,
                gap_count INTEGER NOT NULL
            )
        """
    }

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        SqlCipherObservationRuntime.ensureLoaded()
        val db = SQLiteDatabase.openOrCreateDatabase(
            databaseFile,
            keyEnvelope.databaseKey(),
            null,
            null,
            null,
        )
        db.execSQL(SCHEMA_OBSERVATIONS)
        db.execSQL(SCHEMA_OBSERVATIONS_INDEX_CONV)
        db.execSQL(SCHEMA_OBSERVATIONS_INDEX_KEY)
        db.execSQL(SCHEMA_CONVERSATIONS)
        db.execSQL(SCHEMA_CURSORS)

        // Versioned schema migration
        val currentVersion = db.version
        if (currentVersion < DATABASE_VERSION) {
            db.beginTransaction()
            try {
                if (currentVersion < 2) {
                    migrateToVersion2(db)
                }
                db.version = DATABASE_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // Probe FTS5 capability safely
        isFts5Supported = try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS _fts_probe USING fts5(content);")
            db.execSQL("DROP TABLE IF EXISTS _fts_probe;")
            true
        } catch (_: Throwable) {
            false
        }
        db
    }

    private fun migrateToVersion2(db: SQLiteDatabase) {
        val existingColumns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(conversation_summaries)", null).use { cursor ->
            while (cursor.moveToNext()) {
                existingColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

        if ("app_name" !in existingColumns) {
            db.execSQL("ALTER TABLE conversation_summaries ADD COLUMN app_name TEXT NOT NULL DEFAULT '';")
        }
        if ("category" !in existingColumns) {
            db.execSQL("ALTER TABLE conversation_summaries ADD COLUMN category TEXT NOT NULL DEFAULT 'GeneralNotification';")
        }

        db.execSQL("""
            UPDATE conversation_summaries 
            SET app_name = CASE 
                WHEN package_id LIKE '%twitter%' THEN 'X'
                WHEN package_id LIKE '%whoop%' THEN 'WHOOP'
                WHEN package_id LIKE '%whatsapp%' THEN 'WhatsApp'
                WHEN package_id LIKE '%android.gm%' THEN 'Gmail'
                WHEN package_id LIKE '%facebook.orca%' THEN 'Messenger'
                WHEN package_id LIKE '%facebook%' THEN 'Facebook'
                WHEN package_id LIKE '%instagram%' THEN 'Instagram'
                WHEN package_id LIKE '%linkedin%' THEN 'LinkedIn'
                WHEN package_id LIKE '%slack%' THEN 'Slack'
                WHEN package_id = 'android' THEN 'Android System'
                ELSE package_id
            END
            WHERE app_name = '' OR app_name IS NULL;
        """)
    }

    init {
        // Force lazy initialization
        database
    }

    @Synchronized
    override fun persistObservation(observation: ObservedNotification): PersistResult {
        return try {
            database.beginTransaction()
            try {
                persistObservationRow(database, observation)
                database.setTransactionSuccessful()
                PersistResult.Success(observation.id, observation.revision)
            } finally {
                database.endTransaction()
            }
        } catch (e: Throwable) {
            PersistResult.Error(e.message ?: e::class.simpleName ?: "Database error")
        }
    }

    @Synchronized
    override fun persistBatch(observations: List<ObservedNotification>): Int {
        if (observations.isEmpty()) return 0
        var count = 0
        database.beginTransaction()
        try {
            for (obs in observations) {
                persistObservationRow(database, obs)
                count++
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return count
    }

    @Synchronized
    override fun persistBatchWithCursors(
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult {
        if (observations.isEmpty() && cursors.isEmpty()) {
            return BatchPersistResult(0, 0, emptyList())
        }
        return try {
            database.beginTransaction()
            try {
                for (obs in observations) {
                    persistObservationRow(database, obs)
                }
                for (cursor in cursors) {
                    persistCursorRow(database, cursor)
                }
                database.setTransactionSuccessful()
                BatchPersistResult(observations.size, 0, emptyList())
            } finally {
                database.endTransaction()
            }
        } catch (e: Throwable) {
            BatchPersistResult(0, observations.size, listOf(e.message ?: "Database error"))
        }
    }

    @Synchronized
    override fun persistAtomicBatch(
        removals: List<Pair<String, UtcInstant>>,
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult {
        if (removals.isEmpty() && observations.isEmpty() && cursors.isEmpty()) {
            return BatchPersistResult(0, 0, emptyList())
        }
        return try {
            database.beginTransaction()
            try {
                // Writes precede removals so an insert/update followed by a removal in
                // the same drain call ends in the removed state, all within this transaction.
                for (obs in observations) {
                    persistObservationRow(database, obs)
                }
                for ((notificationKey, removedAt) in removals) {
                    markRemovedRow(database, notificationKey, removedAt)
                }
                for (cursor in cursors) {
                    persistCursorRow(database, cursor)
                }
                database.setTransactionSuccessful()
                BatchPersistResult(removals.size + observations.size, 0, emptyList())
            } finally {
                database.endTransaction()
            }
        } catch (e: Throwable) {
            BatchPersistResult(
                successCount = 0,
                failedCount = removals.size + observations.size,
                errors = listOf(e.message ?: "Database error"),
            )
        }
    }

    @Synchronized
    override fun persistObservationWithCursor(
        observation: ObservedNotification,
        cursor: IngestCursor,
    ): PersistResult {
        return try {
            database.beginTransaction()
            try {
                persistObservationRow(database, observation)
                persistCursorRow(database, cursor)
                database.setTransactionSuccessful()
                PersistResult.Success(observation.id, observation.revision)
            } finally {
                database.endTransaction()
            }
        } catch (e: Throwable) {
            PersistResult.Error(e.message ?: e::class.simpleName ?: "Database error")
        }
    }

    @Synchronized
    override fun getObservationByNotificationKey(notificationKey: String): ObservedNotification? {
        return database.query(
            "notification_observations",
            null,
            "notification_key = ? AND is_removed = 0",
            arrayOf(notificationKey),
            null,
            null,
            "observed_at DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) mapObservation(cursor) else null
        }
    }

    @Synchronized
    override fun getObservationByNotificationKeyIncludingRemoved(notificationKey: String): ObservedNotification? {
        return database.query(
            "notification_observations",
            null,
            "notification_key = ?",
            arrayOf(notificationKey),
            null,
            null,
            "observed_at DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) mapObservation(cursor) else null
        }
    }

    @Synchronized
    override fun getObservationByNotificationIdIncludingRemoved(packageId: String, notificationId: Int): ObservedNotification? {
        return database.query(
            "notification_observations",
            null,
            "package_id = ? AND notification_id = ?",
            arrayOf(packageId, notificationId.toString()),
            null,
            null,
            "observed_at DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) mapObservation(cursor) else null
        }
    }

    /**
     * Writes one observation row and its conversation summary row.
     * Must be called inside an active transaction.
     */
    private fun persistObservationRow(db: SQLiteDatabase, obs: ObservedNotification) {
        val values = ContentValues().apply {
            put("id", obs.id.key)
            put("account_key", obs.accountId.key)
            put("conversation_key", obs.conversationId.key)
            put("package_id", obs.packageId)
            put("notification_key", obs.notificationKey)
            put("notification_id", obs.notificationId)
            put("tag", obs.tag)
            put("sender_hint", obs.senderHint)
            put("title", obs.title)
            put("text_fragments_json", obs.textFragments.joinToString("\u001F"))
            put("raw_text_digest", obs.rawTextDigest)
            put("observed_at", obs.observedAt.epochMillis)
            put("post_time", obs.postTime.epochMillis)
            put("revision", obs.revision)
            put("is_removed", if (obs.isRemoved) 1 else 0)
            put("is_group_summary", if (obs.isGroupSummary) 1 else 0)
            put("truncation_state", obs.truncationState.name)
            put("correlation_confidence", obs.correlationConfidence.name)
            put("provenance", obs.provenance.name)
        }
        db.insertWithOnConflict("notification_observations", null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val cursorGap = queryCursorGap(db, obs.packageId)
        val coverageExplanation = coverageExplanationFor(obs.provenance, cursorGap)

        val appName = when {
            obs.packageId.contains("twitter", ignoreCase = true) -> "X"
            obs.packageId.contains("whoop", ignoreCase = true) -> "WHOOP"
            obs.packageId.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            obs.packageId.contains("android.gm", ignoreCase = true) -> "Gmail"
            obs.packageId.contains("facebook.orca", ignoreCase = true) -> "Messenger"
            obs.packageId.contains("facebook", ignoreCase = true) -> "Facebook"
            obs.packageId.contains("instagram", ignoreCase = true) -> "Instagram"
            obs.packageId.contains("linkedin", ignoreCase = true) -> "LinkedIn"
            obs.packageId.contains("slack", ignoreCase = true) -> "Slack"
            obs.packageId == "android" -> "Android System"
            obs.packageId.startsWith("com.android.systemui") -> "System UI"
            else -> obs.packageId.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        val notifCategory = when {
            obs.isOngoing -> NotificationCategory.OngoingService
            obs.isGroupSummary -> NotificationCategory.GroupMessage
            obs.packageId.contains("twitter") || obs.packageId.contains("instagram") -> NotificationCategory.SocialUpdate
            obs.packageId == "android" || obs.packageId.startsWith("com.android.systemui") -> NotificationCategory.SystemStatus
            obs.packageId.contains("whatsapp") || obs.packageId.contains("orca") -> NotificationCategory.DirectMessage
            else -> NotificationCategory.GeneralNotification
        }

        val convValues = ContentValues().apply {
            put("conversation_key", obs.conversationId.key)
            put("account_key", obs.accountId.key)
            put("title", obs.title ?: obs.senderHint)
            put("app_name", appName)
            put("sender_hint", obs.senderHint)
            put("package_id", obs.packageId)
            put("latest_snippet", obs.textFragments.lastOrNull() ?: obs.title ?: "")
            put("category", notifCategory.name)
            put("coverage", coverageFor(obs.provenance).name)
            put("coverage_explanation", coverageExplanation)
            put("latest_event_at", obs.observedAt.epochMillis)
            // Derive counts from the stored rows. A constant 1 here made every conversation report a
            // single message regardless of how many observations it contains.
            put("observation_count", countRows(db, "conversation_key = ?", obs.conversationId.key))
            put("unread_count", countRows(db, "conversation_key = ? AND is_removed = 0", obs.conversationId.key))
            put("is_removed", if (obs.isRemoved) 1 else 0)
        }
        db.insertWithOnConflict("conversation_summaries", null, convValues, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun countRows(db: SQLiteDatabase, where: String, arg: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM notification_observations WHERE $where", arrayOf(arg)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Writes one cursor row. Must be called inside an active transaction.
     */
    private fun persistCursorRow(db: SQLiteDatabase, cursor: IngestCursor) {
        val values = ContentValues().apply {
            put("source", cursor.source)
            put("last_processed_time_millis", cursor.lastProcessedTimeMillis)
            put("last_processed_key", cursor.lastProcessedKey)
            put("processed_count", cursor.processedCount)
            put("gap_count", cursor.gapCount)
        }
        db.insertWithOnConflict("ingest_cursors", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun getObservation(id: ScopedId): ObservedNotification? {
        return database.query(
            "notification_observations",
            null,
            "id = ?",
            arrayOf(id.key),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) mapObservation(cursor) else null
        }
    }

    @Synchronized
    override fun getObservationsForConversation(conversationId: ScopedId, limit: Int): List<ObservedNotification> {
        return database.query(
            "notification_observations",
            null,
            "conversation_key = ?",
            arrayOf(conversationId.key),
            null,
            null,
            "observed_at DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(mapObservation(cursor))
                }
            }
        }
    }

    @Synchronized
    override fun getConversationSummaries(accountId: AccountId?): List<ConversationObservationSummary> {
        val selection = if (accountId != null) "account_key = ?" else null
        val selectionArgs = if (accountId != null) arrayOf(accountId.key) else null

        return database.query(
            "conversation_summaries",
            null,
            selection,
            selectionArgs,
            null,
            null,
            "latest_event_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ConversationObservationSummary(
                            conversationId = ScopedId.parse(cursor.getString(cursor.getColumnIndexOrThrow("conversation_key"))),
                            accountId = AccountId.parse(cursor.getString(cursor.getColumnIndexOrThrow("account_key"))),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name")),
                            senderHint = cursor.getString(cursor.getColumnIndexOrThrow("sender_hint")),
                            packageId = cursor.getString(cursor.getColumnIndexOrThrow("package_id")),
                            latestSnippet = cursor.getString(cursor.getColumnIndexOrThrow("latest_snippet")),
                            category = NotificationCategory.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("category"))),
                            coverage = ConversationCoverage.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("coverage"))),
                            coverageExplanation = cursor.getString(cursor.getColumnIndexOrThrow("coverage_explanation")),
                            latestEventAt = UtcInstant(cursor.getLong(cursor.getColumnIndexOrThrow("latest_event_at"))),
                            observationCount = cursor.getInt(cursor.getColumnIndexOrThrow("observation_count")),
                            unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread_count")),
                            isRemoved = cursor.getInt(cursor.getColumnIndexOrThrow("is_removed")) != 0,
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun markRemoved(notificationKey: String, removedAt: UtcInstant): Int {
        return markRemovedRow(database, notificationKey, removedAt)
    }

    /** Must be called inside the caller's transaction. */
    private fun markRemovedRow(
        db: SQLiteDatabase,
        notificationKey: String,
        removedAt: UtcInstant,
    ): Int {
        val values = ContentValues().apply {
            put("is_removed", 1)
        }
        return db.update(
            "notification_observations",
            values,
            "notification_key = ?",
            arrayOf(notificationKey),
        )
    }

    @Synchronized
    override fun checkpointCursor(cursor: IngestCursor) {
        val values = ContentValues().apply {
            put("source", cursor.source)
            put("last_processed_time_millis", cursor.lastProcessedTimeMillis)
            put("last_processed_key", cursor.lastProcessedKey)
            put("processed_count", cursor.processedCount)
            put("gap_count", cursor.gapCount)
        }
        database.insertWithOnConflict("ingest_cursors", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun getCursor(source: String): IngestCursor? {
        return database.query(
            "ingest_cursors",
            null,
            "source = ?",
            arrayOf(source),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                IngestCursor(
                    source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                    lastProcessedTimeMillis = cursor.getLong(cursor.getColumnIndexOrThrow("last_processed_time_millis")),
                    lastProcessedKey = cursor.getString(cursor.getColumnIndexOrThrow("last_processed_key")),
                    processedCount = cursor.getLong(cursor.getColumnIndexOrThrow("processed_count")),
                    gapCount = cursor.getLong(cursor.getColumnIndexOrThrow("gap_count")),
                )
            } else null
        }
    }

    @Synchronized
    override fun recoverFromRestart(): RestartRecoveryReport {
        var obsCount = 0
        var convCount = 0
        database.rawQuery("SELECT COUNT(*) FROM notification_observations", null).use {
            if (it.moveToFirst()) obsCount = it.getInt(0)
        }
        database.rawQuery("SELECT COUNT(*) FROM conversation_summaries", null).use {
            if (it.moveToFirst()) convCount = it.getInt(0)
        }
        return RestartRecoveryReport(
            totalObservations = obsCount,
            totalConversations = convCount,
            lastCursor = null,
        )
    }

    private fun queryCursorGap(db: SQLiteDatabase, source: String): Long {
        return db.query(
            "ingest_cursors",
            arrayOf("gap_count"),
            "source = ?",
            arrayOf(source),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun mapObservation(cursor: android.database.Cursor): ObservedNotification {
        val fragmentsRaw = cursor.getString(cursor.getColumnIndexOrThrow("text_fragments_json"))
        val fragments = if (fragmentsRaw.isNullOrBlank()) emptyList() else fragmentsRaw.split("\u001F")

        return ObservedNotification(
            id = ScopedId.parse(cursor.getString(cursor.getColumnIndexOrThrow("id"))),
            accountId = AccountId.parse(cursor.getString(cursor.getColumnIndexOrThrow("account_key"))),
            conversationId = ScopedId.parse(cursor.getString(cursor.getColumnIndexOrThrow("conversation_key"))),
            packageId = cursor.getString(cursor.getColumnIndexOrThrow("package_id")),
            notificationKey = cursor.getString(cursor.getColumnIndexOrThrow("notification_key")),
            notificationId = cursor.getInt(cursor.getColumnIndexOrThrow("notification_id")),
            tag = cursor.getString(cursor.getColumnIndexOrThrow("tag")),
            senderHint = cursor.getString(cursor.getColumnIndexOrThrow("sender_hint")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            textFragments = fragments,
            rawTextDigest = cursor.getString(cursor.getColumnIndexOrThrow("raw_text_digest")),
            observedAt = UtcInstant(cursor.getLong(cursor.getColumnIndexOrThrow("observed_at"))),
            postTime = UtcInstant(cursor.getLong(cursor.getColumnIndexOrThrow("post_time"))),
            revision = cursor.getLong(cursor.getColumnIndexOrThrow("revision")),
            isRemoved = cursor.getInt(cursor.getColumnIndexOrThrow("is_removed")) != 0,
            isGroupSummary = cursor.getInt(cursor.getColumnIndexOrThrow("is_group_summary")) != 0,
            truncationState = TruncationState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("truncation_state"))),
            correlationConfidence = CorrelationConfidence.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("correlation_confidence"))),
            provenance = ObservationProvenance.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("provenance"))),
        )
    }
}

private object SqlCipherObservationRuntime {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

private class KeystoreObservationDatabaseKey(
    private val context: Context,
    databaseName: String,
) {
    private val alias = "mini.hub.sqlcipher.obs.${Base64.encodeToString(databaseName.toByteArray(), Base64.NO_WRAP)}"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_obs", Context.MODE_PRIVATE)
    private val ciphertextKey = "$alias.ciphertext"
    private val ivKey = "$alias.iv"

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString(ciphertextKey, null)
        val iv = preferences.getString(ivKey, null)
        if ((ciphertext == null) != (iv == null)) error("incomplete SQLCipher key envelope")
        if (ciphertext == null || iv == null) {
            val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encrypted = cipher.doFinal(databaseKey)
            check(preferences.edit()
                .putString(ciphertextKey, b64(encrypted))
                .putString(ivKey, b64(cipher.iv))
                .commit()) { "could not persist SQLCipher key envelope" }
            return databaseKey
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        } catch (error: Exception) {
            throw IllegalStateException("SQLCipher key envelope could not be decrypted", error)
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}
