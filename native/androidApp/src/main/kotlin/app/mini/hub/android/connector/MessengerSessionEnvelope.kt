package app.mini.hub.android.connector

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Opaque Messenger cookie-session bytes for SQLCipher storage.
 * SQLCipher+Keystore is the confidentiality boundary; this framing detects corruption.
 */
data class MessengerSessionEnvelope private constructor(val ciphertext: ByteArray) {
    init {
        require(MessengerSessionEnvelopeCodec.isValid(ciphertext)) { "invalid Messenger session envelope" }
    }

    override fun equals(other: Any?): Boolean =
        other is MessengerSessionEnvelope && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = ciphertext.contentHashCode()

    companion object {
        fun fromEncoded(bytes: ByteArray): MessengerSessionEnvelope? =
            if (MessengerSessionEnvelopeCodec.isValid(bytes)) MessengerSessionEnvelope(bytes.copyOf()) else null

        fun fromSessionJson(json: String): MessengerSessionEnvelope {
            require(json.isNotBlank()) { "empty Messenger session json" }
            return MessengerSessionEnvelope(MessengerSessionEnvelopeCodec.wrapPayload(json.toByteArray(Charsets.UTF_8)))
        }
    }

    fun sessionJsonOrNull(): String? {
        val payload = MessengerSessionEnvelopeCodec.payload(ciphertext) ?: return null
        return payload.toString(Charsets.UTF_8)
    }
}

object MessengerSessionEnvelopeCodec {
    private val magic = "MINI-FB-SESSION\u0000".toByteArray(Charsets.US_ASCII)
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
