package app.mini.hub.android.connector

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Opaque post-authentication Instagram bytes produced by the Go runtime.
 * SQLCipher is the confidentiality boundary. This framing supplies corruption
 * detection and prevents arbitrary blobs from being treated as sessions.
 */
data class InstagramSessionEnvelope private constructor(val ciphertext: ByteArray) {
    init {
        require(InstagramSessionEnvelopeCodec.isValid(ciphertext)) { "invalid Instagram session envelope" }
    }

    override fun equals(other: Any?): Boolean =
        other is InstagramSessionEnvelope && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = ciphertext.contentHashCode()

    companion object {
        fun fromEncoded(bytes: ByteArray): InstagramSessionEnvelope? =
            if (InstagramSessionEnvelopeCodec.isValid(bytes)) InstagramSessionEnvelope(bytes.copyOf()) else null
    }
}

object InstagramSessionEnvelopeCodec {
    private val magic = "MINI-IG-SESSION\u0000".toByteArray(Charsets.US_ASCII)
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

    /** Test fixture helper. Production records come from EncodeSessionEnvelope in Go. */
    fun wrapPayloadForTest(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())
        val result = ByteArray(magic.size + 4 + payload.size + digestSize)
        magic.copyInto(result)
        ByteBuffer.wrap(result, magic.size, 4).putInt(payload.size)
        payload.copyInto(result, magic.size + 4)
        MessageDigest.getInstance("SHA-256").digest(payload).copyInto(result, magic.size + 4 + payload.size)
        return result
    }
}
