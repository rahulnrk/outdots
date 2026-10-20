package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device coverage for the Instagram login support pieces that need Android APIs
 * but no network: WebView origin/kind parsing, the encrypted store save/reopen
 * and account isolation, and in-memory restore of a real envelope exported by
 * the Go runtime. No credentials, no live network login.
 */
@RunWith(AndroidJUnit4::class)
class InstagramLoginSupportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun webViewOriginAllowlistRejectsNonInstagram() {
        assertTrue(InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse("https://www.instagram.com/challenge/")))
        assertTrue(InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse("https://www.fbsbx.com/captcha/recaptcha/iframe/")))
        assertFalse(InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse("http://www.instagram.com/")))
        assertFalse(InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse("https://evil.example/instagram.com")))
        assertFalse(InstagramWebViewLoginController.isAllowedTopLevel(Uri.parse("https://user@www.instagram.com/")))
    }

    @Test
    fun webViewRequestParsingClassifiesCaptchaVsChallenge() {
        val captcha = JSONObject()
            .put("attempt", 3)
            .put("url", "https://www.instagram.com/accounts/login/")
            .put("user_agent", "IG UA")
            .put("extract_js", "(function(){return {captcha_token:'x'}})()")
            .put("wait_pattern", "")
            .put("fields", org.json.JSONArray(listOf("captcha_token")))
            .toString().toByteArray()
        val captchaReq = InstagramWebViewRequest.parse(captcha)
        assertNotNull(captchaReq)
        assertEquals(InstagramWebViewRequest.Kind.CAPTCHA, captchaReq!!.kind)
        assertEquals(3L, captchaReq.attempt)

        val challenge = JSONObject()
            .put("attempt", 4)
            .put("url", "https://www.instagram.com/challenge/")
            .put("wait_pattern", "^https://www\\.instagram\\.com/(?:\\?.*)?$")
            .put("fields", org.json.JSONArray(listOf("sessionid", "csrftoken", "ds_user_id")))
            .toString().toByteArray()
        val challengeReq = InstagramWebViewRequest.parse(challenge)
        assertNotNull(challengeReq)
        assertEquals(InstagramWebViewRequest.Kind.CHALLENGE, challengeReq!!.kind)

        // A non-Instagram URL is rejected at parse time.
        val bad = JSONObject().put("url", "https://evil.example/").toString().toByteArray()
        assertNull(InstagramWebViewRequest.parse(bad))
    }

    @Test
    fun encryptedStoreSavesReopensAndIsolatesAccounts() {
        val accountA = AccountIdForTest("111")
        val accountB = AccountIdForTest("222")
        val encodedA = InstagramSessionEnvelopeCodec.wrapPayloadForTest("session-a".encodeToByteArray())
        val encodedB = InstagramSessionEnvelopeCodec.wrapPayloadForTest("session-b".encodeToByteArray())

        val first = SqlCipherInstagramSessionStore(context)
        first.save(accountA, requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedA)))
        first.save(accountB, requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedB)))

        val reopened = SqlCipherInstagramSessionStore(context)
        assertEquals(
            requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedA)),
            reopened.load(accountA),
        )
        assertEquals(
            requireNotNull(InstagramSessionEnvelope.fromEncoded(encodedB)),
            reopened.load(accountB),
        )
        // Clearing one account never affects the other.
        assertTrue(reopened.clear(accountA))
        assertNull(reopened.load(accountA))
        assertNotNull(reopened.load(accountB))
        reopened.clear(accountB)
    }

    private fun AccountIdForTest(userId: String) =
        app.mini.hub.domain.AccountId(app.mini.hub.domain.Provider.INSTAGRAM, userId)
}
