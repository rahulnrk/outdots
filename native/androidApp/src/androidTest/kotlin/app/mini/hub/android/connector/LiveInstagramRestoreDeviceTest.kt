package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInstagramRestoreDeviceTest {
    @Test
    fun encryptedSessionRestoresAndAuthenticatedReadSucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountKey = context.getSharedPreferences(
            InstagramLoginSession.PROBE_STATE_PREFS,
            android.content.Context.MODE_PRIVATE,
        ).getString(InstagramLoginSession.LAST_ACCOUNT_KEY, null)
        require(!accountKey.isNullOrBlank())
        val result = InstagramSessionRestore(context).restoreAndVerify(AccountId.parse(accountKey))
        assertTrue(result is InstagramSessionRestore.Result.Verified)
        assertTrue((result as InstagramSessionRestore.Result.Verified).userId.isNotBlank())
    }
}
