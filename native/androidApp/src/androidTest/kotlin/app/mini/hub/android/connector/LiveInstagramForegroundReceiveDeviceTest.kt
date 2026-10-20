package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInstagramForegroundReceiveDeviceTest {
    @Test
    fun persistedInboundAppearsWithoutManualRefreshThenDedupe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountKey = context.getSharedPreferences(
            InstagramLoginSession.PROBE_STATE_PREFS,
            android.content.Context.MODE_PRIVATE,
        ).getString(InstagramLoginSession.LAST_ACCOUNT_KEY, null)
        require(!accountKey.isNullOrBlank())
        val account = AccountId.parse(accountKey)
        val ingestor = InstagramReadIngestor(context)

        // Automatic receive should have already written encrypted observations.
        // This check deliberately does not call sync/Refresh first.
        val before = ingestor.loadPersisted(account)
        val inboundBefore = before.conversations.flatMap { it.messages }.filter { it.direction == "inbound" }
        assertTrue(inboundBefore.isNotEmpty())
        val newest = inboundBefore.maxBy { it.timestampMillis }
        val ageMillis = (System.currentTimeMillis() - newest.timestampMillis).coerceAtLeast(0L)
        val ageBucket = when {
            ageMillis < 2 * 60 * 1000L -> "under_2m"
            ageMillis < 5 * 60 * 1000L -> "2_to_5m"
            ageMillis < 15 * 60 * 1000L -> "5_to_15m"
            ageMillis < 60 * 60 * 1000L -> "15_to_60m"
            else -> "over_60m"
        }
        context.getSharedPreferences("instagram_read_test_evidence", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("auto_arrival_age_bucket", ageBucket)
            .putString("auto_arrival_mode", "persisted_without_refresh")
            .apply()

        val after = ingestor.sync(account, maxThreads = 20, maxMessages = 20)
        assertTrue(after is InstagramReadIngestor.Result.Success)
        val persisted = ingestor.loadPersisted(account)
        val ids = persisted.conversations.flatMap { it.messages }.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
    }
}
