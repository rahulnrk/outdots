package app.mini.hub.android.connector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.mini.hub.connector.GmailAttachmentRef
import app.mini.hub.domain.AccountId
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Account-partitioned AES-GCM cache for Gmail attachments.
 *
 * Durable bytes stay encrypted. FileProvider export is a short-lived plaintext file under
 * `wa-media/` so the existing provider path can open it; those files are not the cache.
 * Export cleanup runs on construction, not only when another attachment is opened.
 */
class EncryptedAttachmentCache(context: Context) {
    private val appContext = context.applicationContext
    private val encRoot = File(appContext.cacheDir, "media-enc")
    private val exportRoot = File(appContext.cacheDir, "wa-media")

    init {
        sweepStaleExports()
    }

    fun export(
        accountId: AccountId,
        messageId: String,
        attachmentId: String,
        fileName: String,
        fetch: () -> ByteArray?,
    ): File? {
        sweepStaleExports()
        val partition = GmailAttachmentRef.cachePartition(accountId)
        val encDir = File(encRoot, partition).apply { mkdirs() }
        val encFile = File(encDir, "${safe(messageId)}_${safe(attachmentId)}.bin")
        val plaintext = when {
            encFile.exists() -> runCatching { decrypt(encFile.readBytes()) }.getOrElse {
                encFile.delete()
                null
            }
            else -> null
        } ?: fetch()?.also { bytes ->
            encDir.mkdirs()
            encFile.writeBytes(encrypt(bytes))
        } ?: return null
        val exportName = "exp_${partition}_${safe(messageId)}_${safe(attachmentId)}_${safe(fileName)}"
        val export = File(exportRoot.apply { mkdirs() }, exportName)
        export.writeBytes(plaintext)
        return export
    }

    /** Deletes expired plaintext exports. Safe to call at process start. */
    fun sweepStaleExports(): Int {
        val cutoff = System.currentTimeMillis() - EXPORT_TTL_MS
        var removed = 0
        exportRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("exp_") && file.lastModified() < cutoff) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        require(iv.isNotEmpty()) { "Keystore GCM IV missing" }
        val ciphertext = cipher.doFinal(plain)
        val envelope = ByteArray(2 + iv.size + ciphertext.size)
        envelope[0] = ((iv.size ushr 8) and 0xFF).toByte()
        envelope[1] = (iv.size and 0xFF).toByte()
        System.arraycopy(iv, 0, envelope, 2, iv.size)
        System.arraycopy(ciphertext, 0, envelope, 2 + iv.size, ciphertext.size)
        return envelope
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val keyed = parseKeyedEnvelope(payload)
        if (keyed != null) return decryptWith(keyed.first, keyed.second)
        // Previous build prefixed a 12-byte IV with no length. Read that once, then rewrite on next save.
        require(payload.size > LEGACY_IV_BYTES) { "truncated attachment envelope" }
        return decryptWith(payload.copyOfRange(0, LEGACY_IV_BYTES), payload.copyOfRange(LEGACY_IV_BYTES, payload.size))
    }

    private fun parseKeyedEnvelope(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size < 3) return null
        val ivLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (ivLen !in MIN_IV_BYTES..MAX_IV_BYTES) return null
        if (payload.size <= 2 + ivLen) return null
        val iv = payload.copyOfRange(2, 2 + ivLen)
        val ciphertext = payload.copyOfRange(2 + ivLen, payload.size)
        return iv to ciphertext
    }

    private fun decryptWith(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "x" }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mini.hub.media.cache.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val LEGACY_IV_BYTES = 12
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 16
        private const val EXPORT_TTL_MS = 10 * 60 * 1000L
    }
}
