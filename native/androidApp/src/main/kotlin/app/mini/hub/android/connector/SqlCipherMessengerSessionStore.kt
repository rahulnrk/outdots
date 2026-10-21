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
 * Account-isolated Messenger cookie session table.
 * Not bound to normal Accounts UI until the isolated cookie-login probe succeeds.
 */
class SqlCipherMessengerSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(DB_NAME)
    private val keyEnvelope = MessengerKeystoreSqlCipherKey(appContext)

    private val database: SQLiteDatabase
        get() = sharedDatabase(databaseFile, keyEnvelope)

    fun save(accountId: AccountId, envelope: MessengerSessionEnvelope) {
        require(accountId.provider == Provider.FACEBOOK) { "Messenger store received a non-Facebook account" }
        val values = ContentValues().apply {
            put("account_key", accountId.key)
            put("snapshot", envelope.ciphertext)
            put("updated_at", System.currentTimeMillis())
        }
        check(database.insertWithOnConflict("fb_session", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "could not save Messenger session"
        }
    }

    fun load(accountId: AccountId): MessengerSessionEnvelope? {
        require(accountId.provider == Provider.FACEBOOK) { "Messenger store received a non-Facebook account" }
        database.rawQuery("SELECT snapshot FROM fb_session WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytes = cursor.getBlob(0) ?: return null
            return MessengerSessionEnvelope.fromEncoded(bytes)
        }
    }

    fun clear(accountId: AccountId): Boolean {
        require(accountId.provider == Provider.FACEBOOK) { "Messenger store received a non-Facebook account" }
        database.delete("fb_device_key", "account_key = ?", arrayOf(accountId.key))
        return database.delete("fb_session", "account_key = ?", arrayOf(accountId.key)) > 0
    }

    fun loadDeviceKey(accountId: AccountId): ByteArray? {
        require(accountId.provider == Provider.FACEBOOK) { "Messenger store received a non-Facebook account" }
        database.rawQuery("SELECT device_key FROM fb_device_key WHERE account_key = ?", arrayOf(accountId.key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytes = cursor.getBlob(0) ?: return null
            return if (bytes.size == 32) bytes else null
        }
    }

    fun getOrCreateDeviceKey(accountId: AccountId): ByteArray {
        loadDeviceKey(accountId)?.let { return it }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        saveDeviceKey(accountId, key)
        return key
    }

    fun saveDeviceKey(accountId: AccountId, key: ByteArray) {
        require(accountId.provider == Provider.FACEBOOK) { "Messenger store received a non-Facebook account" }
        require(key.size == 32) { "Messenger device key must be 32 bytes" }
        val values = ContentValues().apply {
            put("account_key", accountId.key)
            put("device_key", key)
            put("updated_at", System.currentTimeMillis())
        }
        check(database.insertWithOnConflict("fb_device_key", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "could not save Messenger device key"
        }
    }

    companion object {
        private const val DB_NAME = "mini-messenger-session.db"
        private val DB_LOCK = Any()
        @Volatile private var shared: SQLiteDatabase? = null

        private fun sharedDatabase(
            databaseFile: java.io.File,
            keyEnvelope: MessengerKeystoreSqlCipherKey,
        ): SQLiteDatabase {
            shared?.let { return it }
            return synchronized(DB_LOCK) {
                shared?.let { return it }
                databaseFile.parentFile?.mkdirs()
                System.loadLibrary("sqlcipher")
                SQLiteDatabase.openOrCreateDatabase(databaseFile, keyEnvelope.databaseKey(), null, null, null).also { db ->
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS fb_session (account_key TEXT PRIMARY KEY NOT NULL, snapshot BLOB NOT NULL, updated_at INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS fb_device_key (account_key TEXT PRIMARY KEY NOT NULL, device_key BLOB NOT NULL, updated_at INTEGER NOT NULL)",
                    )
                    shared = db
                }
            }
        }
    }
}

private class MessengerKeystoreSqlCipherKey(context: Context) {
    private val alias = "mini.hub.sqlcipher.messenger-session"
    private val preferences = context.getSharedPreferences("mini_hub_keystore_messenger", Context.MODE_PRIVATE)

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString("$alias.ciphertext", null)
        val iv = preferences.getString("$alias.iv", null)
        if ((ciphertext == null) != (iv == null)) error("incomplete Messenger SQLCipher key envelope")
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
            ) { "could not persist Messenger SQLCipher key envelope" }
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
