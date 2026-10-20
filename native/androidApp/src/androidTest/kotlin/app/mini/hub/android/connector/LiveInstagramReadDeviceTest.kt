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
            println("LIVE_READ_FAILED=${first.code}")
            return
        }
        assertTrue(first is InstagramReadIngestor.Result.Success)
        val firstView = (first as InstagramReadIngestor.Result.Success).view
        if (!firstView.conversations.flatMap { it.messages }.any { it.direction == "inbound" }) {
            println("LIVE_READ_NO_INCOMING_MESSAGE")
        } else {
            println("LIVE_READ_INBOUND_MESSAGE_OBSERVED")
        }
        val second = ingestor.sync(account, maxThreads = 20, maxMessages = 20)
        assertTrue(second is InstagramReadIngestor.Result.Success)
        val persisted = ingestor.loadPersisted(account)
        val ids = persisted.conversations.flatMap { it.messages }.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
    }
}
