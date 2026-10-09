package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.android.connector.GmailOAuthClient
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GmailOAuthConfigurationDeviceTest {
    @Test
    fun documentationPlaceholderIsBlockedBeforeBrowserLaunch() {
        val placeholder = "1234567890-abcdef.apps.googleusercontent.com"
        assumeTrue(BuildConfig.GOOGLE_OAUTH_CLIENT_ID == placeholder)

        val client = GmailOAuthClient(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )

        assertFalse("Documentation placeholder must not enable OAuth", client.isConfigured)
        assertTrue(
            "The setup message must identify the missing Google configuration",
            client.setupStatus.startsWith("Google connection setup incomplete"),
        )
    }
}
