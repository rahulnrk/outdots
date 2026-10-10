package app.mini.hub.android.intelligence

import android.content.Context
import android.util.Base64
import app.mini.hub.style.AdaptiveStyleEngine
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores compact style profiles encrypted with a device-bound Android Keystore key. */
class EncryptedStyleProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun restoreInto(engine: AdaptiveStyleEngine): Boolean {
        val encoded = prefs.getString(KEY_STATE, null) ?: return false
        return runCatching {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val ciphertext = Base64.decode(parts[1], Base64.DEFAULT)
            val plain = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            }.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            engine.importState(plain)
        }.getOrDefault(false)
    }

    fun save(engine: AdaptiveStyleEngine) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(engine.exportState().toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(KEY_STATE, payload).commit()) { "Could not persist encrypted style state" }
    }

    fun clear() {
        prefs.edit().remove(KEY_STATE).commit()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }

    companion object {
        private const val FILE = "mini_local_style_profiles"
        private const val KEY_STATE = "encrypted_state_v1"
        private const val KEY_ALIAS = "mini_local_style_profiles_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
