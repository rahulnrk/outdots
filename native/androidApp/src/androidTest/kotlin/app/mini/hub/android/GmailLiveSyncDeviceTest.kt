package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.AndroidGmailConnector
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.SyncCheckpoint
import app.mini.hub.domain.Provider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GmailLiveSyncDeviceTest {
    @Test
    fun syncsTwoAccountsAndReplayingTheSamePageAddsNoDuplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val accounts = AndroidConnectedAccountStore(context).list()
            .filter { it.id.provider == Provider.GMAIL }
        assertEquals("Expected exactly two authorized Gmail accounts", 2, accounts.size)
        assertEquals("The two Gmail accounts must remain distinct", 2, accounts.map { it.id.key }.distinct().size)
        assertEquals("The two provider identities must remain distinct", 2, accounts.map { it.providerAccountId }.distinct().size)

        val connector = AndroidGmailConnector(
            context = context,
            observationStore = MiniHubRuntime.observationStore,
            digest = digestProvider(),
        )
        val firstResults = accounts.map { account ->
            connector.sync(account, SyncCheckpoint(), System.currentTimeMillis())
        }
        assertTrue("Each account must return at least one Gmail message for duplicate verification", firstResults.all { it.fetched > 0 })
        val firstCounts = accounts.associateWith { observationCount(it) }
        assertTrue("Each account must retain account-scoped history", firstCounts.values.all { it > 0 })

        accounts.forEach { account ->
            connector.sync(account, SyncCheckpoint(), System.currentTimeMillis())
        }
        val replayCounts = accounts.associateWith { observationCount(it) }
        assertEquals("Replaying the same Gmail page must not add duplicates", firstCounts, replayCounts)
        assertTrue(
            "Every stored Gmail summary must remain under its owning account",
            accounts.all { account ->
                MiniHubRuntime.observationStore.getConversationSummaries(account.id).all { it.accountId == account.id }
            },
        )
        saveBaseline(firstCounts)
    }

    private fun observationCount(account: ConnectedAccount): Int =
        MiniHubRuntime.observationStore.getConversationSummaries(account.id).sumOf { it.observationCount }

    private fun saveBaseline(counts: Map<ConnectedAccount, Int>) {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit().clear().putInt(KEY_ACCOUNT_COUNT, counts.size)
        counts.forEach { (account, count) ->
            editor.putLong("count_${hash(account.id.key)}", count.toLong())
        }
        // commit(), not apply(): the restart test kills this process immediately afterwards.
        assertTrue("Baseline must be persisted before restart", editor.commit())
    }

    private fun digestProvider() = DigestProvider { payload ->
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFS = "mini_live_gmail_verification"
        const val KEY_ACCOUNT_COUNT = "account_count"
    }
}
