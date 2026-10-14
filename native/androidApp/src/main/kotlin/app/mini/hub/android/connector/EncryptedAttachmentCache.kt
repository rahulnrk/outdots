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
 */
class EncryptedAttachmentCache(context: Context) {
    private val appContext = context.applicationContext
    private val encRoot = File(appContext.cacheDir, "media-enc")
    private val exportRoot = File(appContext.cacheDir, "wa-media")

    fun export(
        accountId: AccountId,
        messageId: String,
        attachmentId: String,
        fileName: String,
        fetch: () -> ByteArray?,
    ): File? {
        sweepExports()
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

    private fun sweepExports() {
        val cutoff = System.currentTimeMillis() - EXPORT_TTL_MS
        exportRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("exp_") && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "truncated attachment envelope" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
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
        private const val IV_BYTES = 12
        private const val EXPORT_TTL_MS = 10 * 60 * 1000L
    }
}
