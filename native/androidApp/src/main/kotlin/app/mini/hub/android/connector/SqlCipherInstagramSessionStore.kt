package app.mini.hub.android.connector

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * Account-isolated Instagram session table. The SQLCipher key is wrapped by an
 * Android Keystore AES-GCM key. No normal Accounts UI binds this store yet.
 */
class SqlCipherInstagramSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(DB_NAME)
    private val keyEnvelope = InstagramKeystoreSqlCipherKey(appContext)

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        SQLiteDatabase.openOrCreateDatabase(databaseFile, keyEnvelope.databaseKey(), null, null, null).also { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS ig_session (account_key TEXT PRIMARY KEY NOT NULL, snapshot BLOB NOT NULL, updated_at INTEGER NOT NULL)",
            )
        }
    }

    fun save(accountId: AccountId, envelope: InstagramSessionEnvelope) {
        require(accountId.provider == Provider.INSTAGRAM) { "Instagram store received a non-Instagram account" }
        val values = ContentValues().apply {
            put("account_key", accountId.key)
            put("snapshot", envelope.ciphertext)
            put("updated_at", System.currentTimeMillis())
        }
        check(database.insertWithOnConflict("ig_session", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "could not save Instagram session"
        }
    }

    fun load(accountId: AccountId): InstagramSessionEnvelope? {
        require(accountId.provider == Provider.INSTAGRAM) { "Instagram store received a non-Instagram account" }
        database.rawQuery("SELECT snapshot FROM ig_session WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytes = cursor.getBlob(0) ?: return null
            return InstagramSessionEnvelope.fromEncoded(bytes)
        }
    }

    fun clear(accountId: AccountId): Boolean {
        require(accountId.provider == Provider.INSTAGRAM) { "Instagram store received a non-Instagram account" }
        return database.delete("ig_session", "account_key = ?", arrayOf(accountId.key)) > 0
    }

    companion object {
        private const val DB_NAME = "mini-instagram-session.db"
    }
}

private class InstagramKeystoreSqlCipherKey(context: Context) {
    private val alias = "mini.hub.sqlcipher.instagram-session"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_instagram", Context.MODE_PRIVATE)

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString("$alias.ciphertext", null)
        val iv = preferences.getString("$alias.iv", null)
        if ((ciphertext == null) != (iv == null)) error("incomplete Instagram SQLCipher key envelope")
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
            ) { "could not persist Instagram SQLCipher key envelope" }
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
