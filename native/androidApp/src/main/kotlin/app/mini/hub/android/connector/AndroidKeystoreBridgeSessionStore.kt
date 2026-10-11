package app.mini.hub.android.connector

import android.content.Context
import android.util.Base64
import app.mini.hub.connector.EncryptedBridgeSessionStore
import app.mini.hub.connector.WhatsAppSessionEnvelope
import app.mini.hub.domain.AccountId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opaque WhatsApp companion-session bytes encrypted with a device-bound Keystore key.
 * Never logs ciphertext, phone numbers or tokens.
 */
class AndroidKeystoreBridgeSessionStore(context: Context) : EncryptedBridgeSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun save(accountId: AccountId, envelope: WhatsAppSessionEnvelope) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(envelope.ciphertext), Base64.NO_WRAP)
        check(prefs.edit().putString(prefKey(accountId), packed).commit()) { "Could not persist WhatsApp session envelope" }
    }

    override fun load(accountId: AccountId): WhatsAppSessionEnvelope? {
        val encoded = prefs.getString(prefKey(accountId), null) ?: return null
        return runCatching {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val ciphertext = Base64.decode(parts[1], Base64.DEFAULT)
            val plain = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            }.doFinal(ciphertext)
            WhatsAppSessionEnvelope(plain)
        }.getOrNull()
    }

    override fun clear(accountId: AccountId): Boolean {
        val key = prefKey(accountId)
        val existed = prefs.contains(key)
        prefs.edit().remove(key).commit()
        return existed
    }

    private fun prefKey(accountId: AccountId): String =
        "wa_" + Base64.encodeToString(accountId.key.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build(),
            )
        }.generateKey()
    }

    companion object {
        private const val FILE = "mini_whatsapp_phone_session"
        private const val KEY_ALIAS = "mini_whatsapp_phone_session_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
