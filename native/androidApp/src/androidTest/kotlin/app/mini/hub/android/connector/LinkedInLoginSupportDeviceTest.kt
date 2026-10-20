package app.mini.hub.android.connector

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device coverage for LinkedIn probe support pieces that need Android APIs but no
 * owner credentials: HTTPS allowlist, encrypted store isolation, envelope framing.
 */
@RunWith(AndroidJUnit4::class)
class LinkedInLoginSupportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun webViewAllowlistRejectsNonLinkedInAndHttp() {
        assertTrue(LinkedInWebViewLoginController.isAllowedTopLevel(Uri.parse("https://www.linkedin.com/feed/")))
        assertTrue(LinkedInWebViewLoginController.isAllowedTopLevel(Uri.parse("https://linkedin.com/login")))
        assertFalse(LinkedInWebViewLoginController.isAllowedTopLevel(Uri.parse("http://www.linkedin.com/feed/")))
        assertFalse(LinkedInWebViewLoginController.isAllowedTopLevel(Uri.parse("https://evil.example/linkedin.com")))
        assertFalse(LinkedInWebViewLoginController.isAllowedTopLevel(Uri.parse("https://user@www.linkedin.com/")))
    }

    @Test
    fun encryptedStoreSavesReopensAndIsolatesAccounts() {
        val accountA = AccountId(Provider.LINKEDIN, "aaa")
        val accountB = AccountId(Provider.LINKEDIN, "bbb")
        val envelopeA = LinkedInSessionEnvelope.fromSessionJson(
            """{"cookies_json":"[{\"name\":\"JSESSIONID\",\"value\":\"a\"}]","x_li_page_instance":"urn:li:page:a","x_li_track":"{\"clientVersion\":\"1\"}"}""",
        )
        val envelopeB = LinkedInSessionEnvelope.fromSessionJson(
            """{"cookies_json":"[{\"name\":\"JSESSIONID\",\"value\":\"b\"}]","x_li_page_instance":"urn:li:page:b","x_li_track":"{\"clientVersion\":\"1\"}"}""",
        )
        val first = SqlCipherLinkedInSessionStore(context)
        first.save(accountA, envelopeA)
        first.save(accountB, envelopeB)

        val reopened = SqlCipherLinkedInSessionStore(context)
        assertEquals(envelopeA, reopened.load(accountA))
        assertEquals(envelopeB, reopened.load(accountB))
        assertTrue(reopened.clear(accountA))
        assertNull(reopened.load(accountA))
        assertNotNull(reopened.load(accountB))
        reopened.clear(accountB)
    }

    @Test
    fun sessionEnvelopeRejectsTampering() {
        val good = LinkedInSessionEnvelope.fromSessionJson("""{"cookies_json":"[]","x_li_page_instance":"urn:li:page:x","x_li_track":"{\"clientVersion\":\"1\"}"}""")
        val bytes = good.ciphertext.copyOf()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        assertNull(LinkedInSessionEnvelope.fromEncoded(bytes))
    }
}
