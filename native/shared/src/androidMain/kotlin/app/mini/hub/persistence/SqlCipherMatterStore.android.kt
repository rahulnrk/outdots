package app.mini.hub.persistence

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.matter.*
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WP05 SQLCipher persistent Matter store for Android.
 * Uses an Android Keystore-wrapped random database key and enforces account boundaries and durable consent/exclusions.
 */
class SqlCipherMatterStore(
    context: Context,
    databaseName: String = "mini-hub-matters.db",
) : MatterStore {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val keyEnvelope = KeystoreMatterDatabaseKey(appContext, databaseName)

    companion object {
        const val DATABASE_VERSION = 1
    }

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        SqlCipherMatterRuntime.ensureLoaded()
        val db = SQLiteDatabase.openOrCreateDatabase(
            databaseFile,
            keyEnvelope.databaseKey(),
            null,
            null,
            null,
        )
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS matters (
                id TEXT PRIMARY KEY,
                account_key TEXT NOT NULL,
                title TEXT NOT NULL,
                state TEXT NOT NULL,
                consent_integer INTEGER NOT NULL,
                excluded_integer INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS matter_evidence (
                matter_id TEXT NOT NULL,
                observation_id TEXT NOT NULL,
                snippet TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                FOREIGN KEY(matter_id) REFERENCES matters(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS account_consent (
                account_key TEXT PRIMARY KEY,
                consent_integer INTEGER NOT NULL,
                excluded_integer INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.rawQuery("PRAGMA table_info(account_consent)", null).use { cursor ->
            var hasRevision = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name.equals("revision", ignoreCase = true)) {
                    hasRevision = true
                    break
                }
            }
            if (!hasRevision) {
                db.execSQL("ALTER TABLE account_consent ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_matters_account ON matters(account_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_matter ON matter_evidence(matter_id)")
        if (db.version < DATABASE_VERSION) {
            db.version = DATABASE_VERSION
        }
        db
    }

    init {
        database
    }

    @Synchronized
    override fun upsert(matter: MatterRecord): MatterRecord {
        database.beginTransaction()
        try {
            val granted = isConsentGrantedInternal(matter.accountId)
            val excluded = isExcludedInternal(matter.accountId)
            MatterValidation.validateMatter(matter, granted, excluded)

            val cv = ContentValues().apply {
                put("id", matter.id.key)
                put("account_key", matter.accountId.key)
                put("title", matter.title)
                put("state", matter.state.name)
                put("consent_integer", if (matter.consentGranted) 1 else 0)
                put("excluded_integer", if (matter.excluded) 1 else 0)
                put("updated_at", matter.updatedAt.epochMillis)
            }
            val rowId = database.insertWithOnConflict("matters", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId < 0) error("Failed to insert or replace matter record")

            database.delete("matter_evidence", "matter_id = ?", arrayOf(matter.id.key))
            matter.evidenceLinks.forEach { link ->
                val evCv = ContentValues().apply {
                    put("matter_id", matter.id.key)
                    put("observation_id", link.observationId.key)
                    put("snippet", link.snippet)
                    put("captured_at", link.capturedAt.epochMillis)
                }
                val evRowId = database.insert("matter_evidence", null, evCv)
                if (evRowId < 0) error("Failed to insert matter evidence")
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return matter
    }

    @Synchronized
    override fun get(id: ScopedId): MatterRecord? {
        database.beginTransactionNonExclusive()
        try {
            database.rawQuery("SELECT id, account_key, title, state, consent_integer, excluded_integer, updated_at FROM matters WHERE id = ?", arrayOf(id.key)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    database.setTransactionSuccessful()
                    return null
                }
                val matterId = ScopedId.parse(cursor.getString(0))
                val accountId = AccountId.parse(cursor.getString(1))
                val title = cursor.getString(2)
                val state = MatterState.valueOf(cursor.getString(3))
                val consent = cursor.getInt(4) == 1
                val excluded = cursor.getInt(5) == 1
                val updatedAt = UtcInstant(cursor.getLong(6))

                if (!isConsentGrantedInternal(accountId) || isExcludedInternal(accountId) || !consent || excluded) {
                    database.setTransactionSuccessful()
                    return null
                }

                val evidenceLinks = mutableListOf<EvidenceLink>()
                database.rawQuery("SELECT observation_id, snippet, captured_at FROM matter_evidence WHERE matter_id = ?", arrayOf(id.key)).use { evCursor ->
                    while (evCursor.moveToNext()) {
                        evidenceLinks.add(
                            EvidenceLink(
                                observationId = ScopedId.parse(evCursor.getString(0)),
                                snippet = evCursor.getString(1),
                                capturedAt = UtcInstant(evCursor.getLong(2))
                            )
                        )
                    }
                }
                database.setTransactionSuccessful()
                return MatterRecord(
                    id = matterId,
                    accountId = accountId,
                    title = title,
                    state = state,
                    evidenceLinks = evidenceLinks,
                    consentGranted = consent,
                    excluded = excluded,
                    updatedAt = updatedAt
                )
            }
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant): MatterRecord {
        val existing = get(id) ?: error("Matter not found")
        val updated = existing.copy(state = nextState, updatedAt = now)
        upsert(updated)
        return updated
    }

    @Synchronized
    override fun listForAccount(accountId: AccountId): List<MatterRecord> {
        database.beginTransactionNonExclusive()
        try {
            if (!isConsentGrantedInternal(accountId) || isExcludedInternal(accountId)) {
                database.setTransactionSuccessful()
                return emptyList()
            }
            val ids = mutableListOf<ScopedId>()
            database.rawQuery("SELECT id FROM matters WHERE account_key = ? AND consent_integer = 1 AND excluded_integer = 0", arrayOf(accountId.key)).use { cursor ->
                while (cursor.moveToNext()) {
                    ids.add(ScopedId.parse(cursor.getString(0)))
                }
            }
            val records = ids.mapNotNull { getInternal(it) }
            database.setTransactionSuccessful()
            return records
        } finally {
            database.endTransaction()
        }
    }

    private fun getInternal(id: ScopedId): MatterRecord? {
        database.rawQuery("SELECT id, account_key, title, state, consent_integer, excluded_integer, updated_at FROM matters WHERE id = ?", arrayOf(id.key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val matterId = ScopedId.parse(cursor.getString(0))
            val accountId = AccountId.parse(cursor.getString(1))
            val title = cursor.getString(2)
            val state = MatterState.valueOf(cursor.getString(3))
            val consent = cursor.getInt(4) == 1
            val excluded = cursor.getInt(5) == 1
            val updatedAt = UtcInstant(cursor.getLong(6))

            if (!isConsentGrantedInternal(accountId) || isExcludedInternal(accountId) || !consent || excluded) return null

            val evidenceLinks = mutableListOf<EvidenceLink>()
            database.rawQuery("SELECT observation_id, snippet, captured_at FROM matter_evidence WHERE matter_id = ?", arrayOf(id.key)).use { evCursor ->
                while (evCursor.moveToNext()) {
                    evidenceLinks.add(
                        EvidenceLink(
                            observationId = ScopedId.parse(evCursor.getString(0)),
                            snippet = evCursor.getString(1),
                            capturedAt = UtcInstant(evCursor.getLong(2))
                        )
                    )
                }
            }
            return MatterRecord(
                id = matterId,
                accountId = accountId,
                title = title,
                state = state,
                evidenceLinks = evidenceLinks,
                consentGranted = consent,
                excluded = excluded,
                updatedAt = updatedAt
            )
        }
    }

    @Synchronized
    override fun invalidateForAccount(accountId: AccountId) {
        setConsent(accountId, granted = false, excluded = true)
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM matter_evidence WHERE matter_id IN (SELECT id FROM matters WHERE account_key = ?)", arrayOf(accountId.key))
            database.execSQL("DELETE FROM matters WHERE account_key = ?", arrayOf(accountId.key))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun invalidateForObservation(observationId: ScopedId) {
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM matter_evidence WHERE observation_id = ? AND matter_id IN (SELECT id FROM matters WHERE account_key = ?)", arrayOf(observationId.key, observationId.account.key))
            // Scope source invalidation to affected matters only (do not delete unrelated unsupported matters across accounts)
            database.execSQL("DELETE FROM matters WHERE account_key = ? AND id NOT IN (SELECT DISTINCT matter_id FROM matter_evidence)", arrayOf(observationId.account.key))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun isConsentGrantedInternal(accountId: AccountId): Boolean {
        database.rawQuery("SELECT consent_integer FROM account_consent WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return cursor.getInt(0) == 1
        }
    }

    private fun isExcludedInternal(accountId: AccountId): Boolean {
        database.rawQuery("SELECT excluded_integer FROM account_consent WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return true
            return cursor.getInt(0) == 1
        }
    }

    @Synchronized
    override fun upsertWithTicket(matter: MatterRecord, ticket: ConsentTicket): MatterRecord {
        require(ticket.accountId == matter.accountId) { "Consent ticket account must match matter accountId" }
        database.beginTransaction()
        try {
            val granted = isConsentGrantedInternal(matter.accountId)
            val excluded = isExcludedInternal(matter.accountId)
            val rev = currentConsentRevisionInternal(matter.accountId)
            require(granted && !excluded && ticket.revision == rev) {
                "Consent ticket is stale or account consent is revoked/excluded"
            }
            MatterValidation.validateMatter(matter, granted, excluded)

            val cv = ContentValues().apply {
                put("id", matter.id.key)
                put("account_key", matter.accountId.key)
                put("title", matter.title)
                put("state", matter.state.name)
                put("consent_integer", if (matter.consentGranted) 1 else 0)
                put("excluded_integer", if (matter.excluded) 1 else 0)
                put("updated_at", matter.updatedAt.epochMillis)
            }
            val rowId = database.insertWithOnConflict("matters", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId < 0) error("Failed to insert or replace matter record")

            database.delete("matter_evidence", "matter_id = ?", arrayOf(matter.id.key))
            matter.evidenceLinks.forEach { link ->
                val evCv = ContentValues().apply {
                    put("matter_id", matter.id.key)
                    put("observation_id", link.observationId.key)
                    put("snippet", link.snippet)
                    put("captured_at", link.capturedAt.epochMillis)
                }
                val evRowId = database.insert("matter_evidence", null, evCv)
                if (evRowId < 0) error("Failed to insert matter evidence")
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return matter
    }

    private fun currentConsentRevisionInternal(accountId: AccountId): Long {
        database.rawQuery("SELECT revision FROM account_consent WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return 0L
            return cursor.getLong(0)
        }
    }

    @Synchronized
    override fun setConsent(accountId: AccountId, granted: Boolean, excluded: Boolean) {
        database.beginTransaction()
        try {
            val currentRev = currentConsentRevisionInternal(accountId)
            val cv = ContentValues().apply {
                put("account_key", accountId.key)
                put("consent_integer", if (granted) 1 else 0)
                put("excluded_integer", if (excluded) 1 else 0)
                put("revision", currentRev + 1L)
            }
            val rowId = database.insertWithOnConflict("account_consent", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId < 0) error("Failed to persist account consent")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun isConsentGranted(accountId: AccountId): Boolean {
        database.beginTransactionNonExclusive()
        try {
            val granted = isConsentGrantedInternal(accountId)
            database.setTransactionSuccessful()
            return granted
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun isExcluded(accountId: AccountId): Boolean {
        database.beginTransactionNonExclusive()
        try {
            val excluded = isExcludedInternal(accountId)
            database.setTransactionSuccessful()
            return excluded
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun currentConsentRevision(accountId: AccountId): Long {
        database.beginTransactionNonExclusive()
        try {
            val rev = currentConsentRevisionInternal(accountId)
            database.setTransactionSuccessful()
            return rev
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun issueConsentTicket(accountId: AccountId): ConsentTicket {
        database.beginTransactionNonExclusive()
        try {
            val granted = isConsentGrantedInternal(accountId)
            val excluded = isExcludedInternal(accountId)
            require(granted && !excluded) {
                "Cannot issue consent ticket for account without active unexcluded Focus consent"
            }
            val ticket = ConsentTicket(accountId, currentConsentRevisionInternal(accountId))
            database.setTransactionSuccessful()
            return ticket
        } finally {
            database.endTransaction()
        }
    }
}

private object SqlCipherMatterRuntime {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

private class KeystoreMatterDatabaseKey(
    private val context: Context,
    databaseName: String,
) {
    private val alias = "mini.hub.sqlcipher.matters.${Base64.encodeToString(databaseName.toByteArray(), Base64.NO_WRAP)}"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_matters", Context.MODE_PRIVATE)
    private val ciphertextKey = "$alias.ciphertext"
    private val ivKey = "$alias.iv"

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString(ciphertextKey, null)
        val iv = preferences.getString(ivKey, null)
        if ((ciphertext == null) != (iv == null)) error("incomplete SQLCipher matter key envelope")
        if (ciphertext == null || iv == null) {
            val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encrypted = cipher.doFinal(databaseKey)
            check(preferences.edit()
                .putString(ciphertextKey, b64(encrypted))
                .putString(ivKey, b64(cipher.iv))
                .commit()) { "could not persist SQLCipher matter key envelope" }
            return databaseKey
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        } catch (error: Exception) {
            throw IllegalStateException("SQLCipher matter key envelope could not be decrypted", error)
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
