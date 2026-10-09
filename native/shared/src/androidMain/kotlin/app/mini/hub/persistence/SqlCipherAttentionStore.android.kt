package app.mini.hub.persistence

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.mini.hub.attention.AttentionStore
import app.mini.hub.attention.AttentionStoreResult
import app.mini.hub.attention.ConversationAttention
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.attention.ResurfaceReason
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Encrypted Android store for user-controlled conversation priority and follow-up state.
 *
 * Separate SQLCipher database with its own random key, wrapped by an Android Keystore AES-GCM key,
 * matching the existing action and observation stores. Rows are keyed by the full account-scoped
 * conversation key, so identical contact names in different accounts never share a row.
 * Failures are returned, never swallowed: callers must show them instead of claiming a save.
 */
class SqlCipherAttentionStore(
    context: Context,
    databaseName: String = "mini-hub-attention.db",
) : AttentionStore {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val keyEnvelope = KeystoreAttentionDatabaseKey(appContext, databaseName)

    override val qualification: StorageQualification = StorageQualification.SqlCipherAndroidKeystorePending

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        SqlCipherAttentionRuntime.ensureLoaded()
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, keyEnvelope.databaseKey(), null, null, null)
        db.execSQL(SCHEMA)
        if (db.version < DATABASE_VERSION) db.version = DATABASE_VERSION
        db
    }

    init {
        // Open eagerly so an unusable key or database fails at construction, not at the first tap.
        database
    }

    @Synchronized
    override fun loadAll(): AttentionStoreResult<Map<String, ConversationAttention>> = try {
        val result = LinkedHashMap<String, ConversationAttention>()
        database.query("conversation_attention", null, null, null, null, null, "conversation_key ASC").use { c ->
            while (c.moveToNext()) {
                val record = ConversationAttention(
                    conversationId = ScopedId.parse(c.getString(c.getColumnIndexOrThrow("conversation_key"))),
                    priority = ConversationPriority.valueOf(c.getString(c.getColumnIndexOrThrow("priority"))),
                    status = FollowUpStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))),
                    snoozedUntil = c.optLong("snoozed_until")?.let(::UtcInstant),
                    acknowledgedActivityAt = c.optLong("acknowledged_activity_at")?.let(::UtcInstant),
                    resurfacedFrom = c.optString("resurfaced_from")?.let(FollowUpStatus::valueOf),
                    resurfaceReason = c.optString("resurface_reason")?.let(ResurfaceReason::valueOf),
                    activityWhileSnoozed = c.getInt(c.getColumnIndexOrThrow("activity_while_snoozed")) != 0,
                    updatedAt = UtcInstant(c.getLong(c.getColumnIndexOrThrow("updated_at"))),
                    isUserSet = c.getInt(c.getColumnIndexOrThrow("is_user_set")) != 0,
                )
                check(record.conversationId.account.key == c.getString(c.getColumnIndexOrThrow("account_key"))) {
                    "attention row account does not match its conversation key"
                }
                result[record.key] = record
            }
        }
        AttentionStoreResult.Ok(result)
    } catch (e: Throwable) {
        AttentionStoreResult.Failed("Could not read saved follow-up choices: ${e.message ?: e::class.simpleName}")
    }

    @Synchronized
    override fun saveAll(records: List<ConversationAttention>): AttentionStoreResult<Unit> {
        if (records.isEmpty()) return AttentionStoreResult.Ok(Unit)
        return try {
            database.beginTransaction()
            try {
                records.forEach { r ->
                    database.insertWithOnConflict("conversation_attention", null, ContentValues().apply {
                        put("conversation_key", r.key)
                        put("account_key", r.conversationId.account.key)
                        put("priority", r.priority.name)
                        put("status", r.status.name)
                        r.snoozedUntil?.let { put("snoozed_until", it.epochMillis) } ?: putNull("snoozed_until")
                        r.acknowledgedActivityAt?.let { put("acknowledged_activity_at", it.epochMillis) } ?: putNull("acknowledged_activity_at")
                        r.resurfacedFrom?.let { put("resurfaced_from", it.name) } ?: putNull("resurfaced_from")
                        r.resurfaceReason?.let { put("resurface_reason", it.name) } ?: putNull("resurface_reason")
                        put("activity_while_snoozed", if (r.activityWhileSnoozed) 1 else 0)
                        put("updated_at", r.updatedAt.epochMillis)
                        put("is_user_set", if (r.isUserSet) 1 else 0)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "write rejected for one conversation" } }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            AttentionStoreResult.Ok(Unit)
        } catch (e: Throwable) {
            AttentionStoreResult.Failed("Could not save follow-up choice: ${e.message ?: e::class.simpleName}")
        }
    }

    private companion object {
        const val DATABASE_VERSION = 1
        const val SCHEMA = """
            CREATE TABLE IF NOT EXISTS conversation_attention (
                conversation_key TEXT NOT NULL PRIMARY KEY,
                account_key TEXT NOT NULL,
                priority TEXT NOT NULL,
                status TEXT NOT NULL,
                snoozed_until INTEGER,
                acknowledged_activity_at INTEGER,
                resurfaced_from TEXT,
                resurface_reason TEXT,
                activity_while_snoozed INTEGER NOT NULL CHECK (activity_while_snoozed IN (0, 1)),
                updated_at INTEGER NOT NULL,
                is_user_set INTEGER NOT NULL CHECK (is_user_set IN (0, 1)),
                CHECK ((status = 'Snoozed') = (snoozed_until IS NOT NULL))
            )
        """
    }
}

private fun android.database.Cursor.optLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

private fun android.database.Cursor.optString(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private object SqlCipherAttentionRuntime {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

private class KeystoreAttentionDatabaseKey(
    private val context: Context,
    databaseName: String,
) {
    private val alias = "mini.hub.sqlcipher.attention.${Base64.encodeToString(databaseName.toByteArray(), Base64.NO_WRAP)}"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_attention", Context.MODE_PRIVATE)
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
            check(
                preferences.edit()
                    .putString(ciphertextKey, b64(encrypted))
                    .putString(ivKey, b64(cipher.iv))
                    .commit(),
            ) { "could not persist SQLCipher key envelope" }
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
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}
