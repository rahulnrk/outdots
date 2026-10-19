package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstagramSessionStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountA = AccountId(Provider.INSTAGRAM, "owner-a")
    private val accountB = AccountId(Provider.INSTAGRAM, "owner-b")

    @Test
    fun saveReopenIsolatesAccountsAndLogoutClearsOnlyOne() {
        val first = SqlCipherInstagramSessionStore(context)
        first.clear(accountA)
        first.clear(accountB)
        val encodedA = InstagramSessionEnvelopeCodec.wrapPayloadForTest("account-a".encodeToByteArray())
        val encodedB = InstagramSessionEnvelopeCodec.wrapPayloadForTest("account-b".encodeToByteArray())
        first.save(accountA, requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedA)))
        first.save(accountB, requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedB)))

        val reopened = SqlCipherInstagramSessionStore(context)
        assertArrayEquals(encodedA, requireNotNull(reopened.load(accountA)).ciphertext)
        assertArrayEquals(encodedB, requireNotNull(reopened.load(accountB)).ciphertext)

        assertTrue(reopened.clear(accountA))
        assertNull(reopened.load(accountA))
        assertArrayEquals(encodedB, requireNotNull(reopened.load(accountB)).ciphertext)
        assertFalse(reopened.clear(accountA))
        reopened.clear(accountB)
    }

    @Test
    fun invalidAndCorruptEnvelopeIsRejectedBeforeStoreUse() {
        val valid = InstagramSessionEnvelopeCodec.wrapPayloadForTest("valid".encodeToByteArray())
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        assertTrue(InstagramSessionEnvelopeCodec.isValid(valid))
        assertFalse(InstagramSessionEnvelopeCodec.isValid(ByteArray(1)))
        assertFalse(InstagramSessionEnvelopeCodec.isValid(corrupt))
        assertNull(InstagramSessionEnvelope.fromEncoded(corrupt))
    }
}
