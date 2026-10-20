package app.mini.hub.android.connector

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Opaque LinkedIn cookie-jar bytes for SQLCipher storage.
 * SQLCipher+Keystore is the confidentiality boundary; this framing detects corruption.
 */
data class LinkedInSessionEnvelope private constructor(val ciphertext: ByteArray) {
    init {
        require(LinkedInSessionEnvelopeCodec.isValid(ciphertext)) { "invalid LinkedIn session envelope" }
    }

    override fun equals(other: Any?): Boolean =
        other is LinkedInSessionEnvelope && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = ciphertext.contentHashCode()

    companion object {
        fun fromEncoded(bytes: ByteArray): LinkedInSessionEnvelope? =
            if (LinkedInSessionEnvelopeCodec.isValid(bytes)) LinkedInSessionEnvelope(bytes.copyOf()) else null

        fun fromCookieJson(json: String): LinkedInSessionEnvelope {
            require(json.isNotBlank()) { "empty LinkedIn cookie json" }
            return LinkedInSessionEnvelope(LinkedInSessionEnvelopeCodec.wrapPayload(json.toByteArray(Charsets.UTF_8)))
        }
    }

    fun cookieJsonOrNull(): String? {
        val payload = LinkedInSessionEnvelopeCodec.payload(ciphertext) ?: return null
        return payload.toString(Charsets.UTF_8)
    }
}

object LinkedInSessionEnvelopeCodec {
    private val magic = "MINI-LI-SESSION\u0000".toByteArray(Charsets.US_ASCII)
    private const val digestSize = 32

    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < magic.size + 4 + digestSize) return false
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) return false
        val payloadSize = ByteBuffer.wrap(bytes, magic.size, 4).int
        if (payloadSize <= 0 || magic.size + 4 + payloadSize + digestSize != bytes.size) return false
        val payloadStart = magic.size + 4
        val payloadEnd = payloadStart + payloadSize
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(payloadStart, payloadEnd))
        return MessageDigest.isEqual(digest, bytes.copyOfRange(payloadEnd, bytes.size))
    }

    fun wrapPayload(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())
        val result = ByteArray(magic.size + 4 + payload.size + digestSize)
        magic.copyInto(result)
        ByteBuffer.wrap(result, magic.size, 4).putInt(payload.size)
        payload.copyInto(result, magic.size + 4)
        MessageDigest.getInstance("SHA-256").digest(payload).copyInto(result, magic.size + 4 + payload.size)
        return result
    }

    fun payload(bytes: ByteArray): ByteArray? {
        if (!isValid(bytes)) return null
        val payloadSize = ByteBuffer.wrap(bytes, magic.size, 4).int
        val payloadStart = magic.size + 4
        return bytes.copyOfRange(payloadStart, payloadStart + payloadSize)
    }
}
