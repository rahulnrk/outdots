package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.domain.Provider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GmailHistoryAfterRestartDeviceTest {
    @Test
    fun twoAccountHistorySurvivesProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val accounts = AndroidConnectedAccountStore(context).list()
            .filter { it.id.provider == Provider.GMAIL }
        val prefs = context.getSharedPreferences(GmailLiveSyncDeviceTest.PREFS, android.content.Context.MODE_PRIVATE)
        assertEquals("The two-account sync baseline must exist", 2, prefs.getInt(GmailLiveSyncDeviceTest.KEY_ACCOUNT_COUNT, 0))
        assertEquals("Expected the two Gmail accounts after restart", 2, accounts.size)

        accounts.forEach { account ->
            val key = "count_${hash(account.id.key)}"
            val expected = prefs.getLong(key, -1L)
            val actual = MiniHubRuntime.observationStore
                .getConversationSummaries(account.id)
                .sumOf { it.observationCount }
            assertTrue("History baseline is missing for one account", expected >= 0L)
            assertEquals("Account-scoped history changed across restart", expected.toInt(), actual)
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
