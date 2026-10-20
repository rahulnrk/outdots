package app.mini.hub.android.persistence

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.AndroidKeystoreAccessTokenStore
import app.mini.hub.connector.*
import app.mini.hub.domain.*
import app.mini.hub.persistence.*
import app.mini.hub.search.SearchQuery
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidSqlCipherMigrationTest {
    // Test-only reflection reuses production encryption without exposing keys.
    private fun database(store: Any): SQLiteDatabase = store.javaClass
        .getDeclaredMethod("getDatabase").apply { isAccessible = true }.invoke(store) as SQLiteDatabase

    @Test
    fun encryptedDatabaseV6ToV7MigrationInterruptedRollbackAndCredentials() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "stabilization-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-observations.db"
        val account = AccountId(Provider.WHATSAPP, "synthetic-a")
        val other = AccountId(Provider.WHATSAPP, "synthetic-b")
        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "migration")
        val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "synthetic")
        val token = StoredAccessToken("synthetic-token", 1_800_000_000_000L, "synthetic-refresh")
        val connection = ConnectedAccount(account, "synthetic-provider-id", "Synthetic",
            AccountType.Unknown, ConnectorRoute.NotificationOnly, ConnectionState.Disconnected)
        AndroidKeystoreAccessTokenStore(context).put(account, token)
        AndroidConnectedAccountStore(context).upsert(connection)
        val actions = SqlCipherActionStore(context, "$prefix-actions.db")
        val draft = ConversationDraft(convId.key, account.key, "Synthetic saved draft", updatedAtMillis = 1000L,
            versions = listOf(DraftVersion(id = "synthetic-v1", text = "Synthetic saved draft")))
        actions.saveDraft(draft)
        database(actions).close()

        val seed = SqlCipherObservationStore(context, dbName)
        val db = database(seed)
        listOf("obs_fts", "notification_observations", "conversation_summaries", "ingest_cursors")
            .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        db.execSQL(ObservationSchema.CREATE_OBSERVATIONS_V1)
        db.execSQL(ObservationSchema.CREATE_CONVERSATIONS_V1)
        db.execSQL(ObservationSchema.CREATE_CURSORS_V1)
        ObservationSchema.version3Statements(emptySet()).forEach(db::execSQL)
        ObservationSchema.version4Statements(emptySet()).forEach(db::execSQL)
        db.execSQL(ObservationSchema.CREATE_FTS_OBSERVATIONS)
        db.execSQL("INSERT INTO notification_observations (id,account_key,conversation_key,package_id,notification_key,notification_id,sender_hint,title,text_fragments_json,raw_text_digest,observed_at,post_time,revision,is_removed,is_group_summary,truncation_state,correlation_confidence,provenance,notification_category) VALUES (?,?,?,?,?,1,'Synthetic','Synthetic','migrationneedle','digest',1000,1000,1,0,0,'Complete','High','AndroidNotificationListener','DirectMessage')",
            arrayOf(obsId.key, account.key, convId.key, "com.whatsapp", "synthetic-key"))
        db.execSQL("INSERT INTO conversation_summaries VALUES (?,?,'Synthetic','WhatsApp','Synthetic','com.whatsapp','migrationneedle','DirectMessage','NotificationOnly','Synthetic',1000,1,1,0)", arrayOf(convId.key, account.key))
        db.execSQL(ObservationSchema.POPULATE_FTS_FROM_EXISTING)
        db.version = 6
        // Roll back actual v7 changes, including user_version. This is a
        // controlled transaction failure, not a simulated OS process kill.
        db.beginTransaction()
        try {
            ObservationSchema.version7Statements(emptySet(), emptySet()).forEach(db::execSQL)
            db.version = 7
            assertFails { db.execSQL("INSERT INTO deliberately_missing_table VALUES (1)") }
        } finally { db.endTransaction() }
        assertEquals(6, db.version)
        db.rawQuery("PRAGMA table_info(notification_observations)", null).use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
            assertFalse("messaging_evidence" in names)
            assertFalse("source_message_time" in names)
        }
        db.close()
        repeat(2) {
            val store = SqlCipherObservationStore(context, dbName)
            try {
                val observation = assertNotNull(store.getObservation(obsId))
                assertEquals(listOf("migrationneedle"), observation.textFragments)
                assertFalse(observation.messagingEvidence)
                assertEquals(1, store.getConversationSummaries(account).size)
                assertFalse(store.getConversationSummaries(account).single().messagingEvidence)
                assertTrue(store.getConversationSummaries(other).isEmpty())
                assertNull(store.getObservation(ScopedId(other, ScopedId.Kind.OBSERVATION, "migration")))
                assertEquals(listOf(convId), store.search(SearchQuery("migrationneedle")).map { it.conversationId })
                assertEquals(7, database(store).version)
            } finally { database(store).close() }
        }
        assertEquals(token, AndroidKeystoreAccessTokenStore(context).get(account))
        assertNull(AndroidKeystoreAccessTokenStore(context).get(other))
        assertEquals(connection, AndroidConnectedAccountStore(context).get(account))
        assertNull(AndroidConnectedAccountStore(context).get(other))
        val reopenedActions = SqlCipherActionStore(context, "$prefix-actions.db")
        try {
            assertEquals(draft, reopenedActions.loadDraft(convId.key))
            assertTrue(reopenedActions.snapshot().outbox.isEmpty())
        } finally { database(reopenedActions).close() }
        // Only these unique synthetic database files are removed.
        context.deleteDatabase(dbName)
        context.deleteDatabase("$prefix-actions.db")
    }
}
