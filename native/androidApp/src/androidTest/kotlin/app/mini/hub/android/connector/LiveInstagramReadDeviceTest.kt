package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInstagramReadDeviceTest {
    @Test
    fun boundedReadPersistsInboundAndRepeatedSyncIsIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountKey = context.getSharedPreferences(
            InstagramLoginSession.PROBE_STATE_PREFS,
            android.content.Context.MODE_PRIVATE,
        ).getString(InstagramLoginSession.LAST_ACCOUNT_KEY, null)
        require(!accountKey.isNullOrBlank())
        val account = AccountId.parse(accountKey)
        val ingestor = InstagramReadIngestor(context)
        val first = ingestor.sync(account, maxThreads = 20, maxMessages = 20)
        if (first is InstagramReadIngestor.Result.Failed) {
            assertTrue("LIVE_READ_FAILED=${first.code}", false)
        }
        assertTrue(first is InstagramReadIngestor.Result.Success)
        val firstView = (first as InstagramReadIngestor.Result.Success).view
        val firstInbound = firstView.conversations.flatMap { it.messages }.filter { it.direction == "inbound" }
        assertTrue(firstInbound.isNotEmpty())
        val newestInbound = firstInbound.maxBy { it.timestampMillis }
        val latencyMillis = (System.currentTimeMillis() - newestInbound.timestampMillis).coerceAtLeast(0L)
        val latencyBucket = when {
            latencyMillis < 5 * 60 * 1000L -> "under_5m"
            latencyMillis < 15 * 60 * 1000L -> "5_to_15m"
            latencyMillis < 60 * 60 * 1000L -> "15_to_60m"
            else -> "over_60m"
        }
        context.getSharedPreferences("instagram_read_test_evidence", android.content.Context.MODE_PRIVATE)
            .edit().putString("arrival_latency_bucket", latencyBucket).apply()
        val second = ingestor.sync(account, maxThreads = 20, maxMessages = 20)
        assertTrue(second is InstagramReadIngestor.Result.Success)
        val persisted = ingestor.loadPersisted(account)
        val ids = persisted.conversations.flatMap { it.messages }.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
        assertTrue(ids.size >= firstInbound.size)
    }
}
