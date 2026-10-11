package app.mini.hub.android.connector

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.mini.hub.connector.EncryptedBridgeSessionStore
import app.mini.hub.connector.WhatsAppSessionEnvelope
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher table of WhatsApp companion snapshots. The SQLCipher key is wrapped in Android
 * Keystore. The Go runtime keeps the live session in memory; this is the durable copy.
 */
class SqlCipherWhatsAppSessionStore(context: Context) : EncryptedBridgeSessionStore {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(DB_NAME)
    private val keyEnvelope = KeystoreSqlCipherKey(appContext, "whatsapp-session")

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, keyEnvelope.databaseKey(), null, null, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS wa_session (account_key TEXT PRIMARY KEY NOT NULL, snapshot BLOB NOT NULL, updated_at INTEGER NOT NULL)")
        db
    }

    override fun save(accountId: AccountId, envelope: WhatsAppSessionEnvelope) {
        val values = ContentValues().apply {
            put("account_key", accountId.key)
            put("snapshot", envelope.ciphertext)
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict("wa_session", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun load(accountId: AccountId): WhatsAppSessionEnvelope? {
        database.rawQuery("SELECT snapshot FROM wa_session WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytes = cursor.getBlob(0) ?: return null
            if (bytes.isEmpty()) return null
            return WhatsAppSessionEnvelope(bytes)
        }
    }

    override fun clear(accountId: AccountId): Boolean {
        return database.delete("wa_session", "account_key = ?", arrayOf(accountId.key)) > 0
    }

    fun loadFirstWhatsApp(): Pair<AccountId, WhatsAppSessionEnvelope>? {
        database.rawQuery("SELECT account_key, snapshot FROM wa_session LIMIT 1", emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val key = cursor.getString(0) ?: return null
            val bytes = cursor.getBlob(1) ?: return null
            if (bytes.isEmpty()) return null
            val local = key.substringAfter('|', key)
            return AccountId(Provider.WHATSAPP, local) to WhatsAppSessionEnvelope(bytes)
        }
    }

    companion object {
        private const val DB_NAME = "mini-whatsapp-session.db"
    }
}

private class KeystoreSqlCipherKey(context: Context, name: String) {
    private val alias = "mini.hub.sqlcipher.$name"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_wa", Context.MODE_PRIVATE)

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString("$alias.ciphertext", null)
        val iv = preferences.getString("$alias.iv", null)
        if ((ciphertext == null) != (iv == null)) error("incomplete SQLCipher key envelope")
        if (ciphertext == null || iv == null) {
            val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encrypted = cipher.doFinal(databaseKey)
            check(
                preferences.edit()
                    .putString("$alias.ciphertext", b64(encrypted))
                    .putString("$alias.iv", b64(cipher.iv))
                    .commit(),
            ) { "could not persist SQLCipher key envelope" }
            return databaseKey
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
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

    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
}
