package app.mini.hub.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.AndroidGmailConnector
import app.mini.hub.android.connector.AndroidKeystoreAccessTokenStore
import app.mini.hub.android.connector.GmailSyncJobService
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.SyncCheckpoint
import app.mini.hub.domain.Provider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live Gmail reliability steps. Each test is one manual-matrix step and is run individually with
 * `am instrument -e class ...#method -e accountA <localId>`. Only counts, local IDs and states are
 * logged (tag MiniGmailLive). No addresses, subjects, bodies or tokens are logged.
 *
 * Account A is selected by local ID (not address) so test arguments never contain an address.
 */
@RunWith(AndroidJUnit4::class)
class GmailReliabilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val accounts by lazy { AndroidConnectedAccountStore(context) }
    private val tokens by lazy { AndroidKeystoreAccessTokenStore(context) }
    private val connector by lazy {
        MiniHubRuntime.init(context)
        AndroidGmailConnector(context, MiniHubRuntime.observationStore, sha256())
    }
    private val prefs by lazy { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }

    private fun gmailAccounts() = accounts.list().filter { it.id.provider == Provider.GMAIL }.sortedBy { it.id.localId }
    private fun accountA(): ConnectedAccount {
        val id = args.getString("accountA") ?: prefs.getString(KEY_A, null) ?: gmailAccounts().first().id.localId
        return gmailAccounts().first { it.id.localId == id }
    }
    private fun accountB(): ConnectedAccount = gmailAccounts().first { it.id.localId != accountA().id.localId }
    private fun count(a: ConnectedAccount) = MiniHubRuntime.observationStore.getConversationSummaries(a.id).sumOf { it.observationCount }
    private fun log(msg: String) = Log.i(TAG, msg)

    @org.junit.Before
    fun initRuntime() = MiniHubRuntime.init(context)

    @Test
    fun step0_inventory() {
        MiniHubRuntime.init(context)
        val list = gmailAccounts()
        list.forEach { log("inventory account=${it.id.localId} state=${it.state} hasToken=${tokens.get(it.id) != null} hasRefresh=${tokens.get(it.id)?.refreshToken != null} observations=${count(it)}") }
        assertEquals("Expected two Gmail accounts", 2, list.size)
        prefs.edit().putString(KEY_A, args.getString("accountA") ?: list.first().id.localId).commit()
        log("selected accountA=${accountA().id.localId} accountB=${accountB().id.localId}")
    }

    /** Disconnect A. B must still sync. A's history must remain and A must be skipped. */
    @Test
    fun step1_disconnectA_BStillSyncs() {
        val a = accountA(); val b = accountB()
        val aBefore = count(a)
        assertTrue("A needs history before disconnect", aBefore > 0)
        assertTrue(connector.connectionManager().disconnect(a.id))

        val aAfter = accounts.get(a.id)
        assertNotNull("A identity record is kept for reconnect", aAfter)
        assertEquals(ConnectionState.Disconnected, aAfter!!.state)
        assertNull("A credential removed", tokens.get(a.id))

        val reports = connector.syncAllConnected(System.currentTimeMillis(), "live_disconnect_step")
        val aReport = reports.first { it.accountLocalId == a.id.localId }
        val bReport = reports.first { it.accountLocalId == b.id.localId }
        log("disconnect: aBefore=$aBefore aAfter=${count(a)} aSkipped=${aReport.skippedReason} bState=${bReport.state} bFetched=${bReport.fetched}")
        assertEquals("disconnected", aReport.skippedReason)
        assertEquals("A history unchanged", aBefore, count(a))
        assertEquals(ConnectionState.Healthy, bReport.state)
        assertTrue("B still returns messages", bReport.fetched > 0)
        prefs.edit().putInt(KEY_A_COUNT, aBefore).putString(KEY_A_LOCAL, a.id.localId).commit()
    }

    /** After the user reconnects A in the UI: same local ID, history kept, re-sync adds no dupes. */
    @Test
    fun step2_reconnectedA_sameIdentityNoDuplicates() {
        val savedId = prefs.getString(KEY_A_LOCAL, null)
        val savedCount = prefs.getInt(KEY_A_COUNT, -1)
        val a = accountA()
        val b = accountB()
        val bBeforeId = prefs.getString(KEY_B_LOCAL, null)
        val bBeforeCount = prefs.getInt(KEY_B_COUNT, -1)
        log("reconnect: savedId=$savedId currentId=${a.id.localId} state=${a.state} accountsTotal=${gmailAccounts().size} storedBeforeSync=${storedRows(a)}")
        assertEquals("Reconnect reused the same local account", savedId, a.id.localId)
        assertEquals("Still exactly two Gmail accounts", 2, gmailAccounts().size)
        assertNotNull("A has a new credential", tokens.get(a.id))
        assertEquals("History preserved through disconnect/reconnect", savedCount, count(a))
        assertEquals("Stored rows match before sync", savedCount, storedRows(a))

        val first = connector.sync(a, SyncCheckpoint(), System.currentTimeMillis())
        val afterFirst = count(a)
        val second = connector.sync(a, SyncCheckpoint(), System.currentTimeMillis())
        val mismatches = countMismatches(a)
        log("reconnect sync: fetched=${first.fetched} inserted1=${first.inserted} inserted2=${second.inserted} before=$savedCount afterFirst=$afterFirst afterSecond=${count(a)} storedRows=${storedRows(a)} conversations=${conversations(a).size} countMismatches=$mismatches")
        assertEquals("Re-syncing the same page adds nothing", 0, second.inserted)
        assertEquals(afterFirst, count(a))
        assertEquals("Every conversation count equals its stored rows", 0, mismatches)
        assertEquals("Summed counts equal stored rows", storedRows(a), count(a))
        assertEquals(ConnectionState.Healthy, accounts.get(a.id)?.state)

        // B must be untouched by A's disconnect/reconnect.
        val bNow = accounts.get(b.id)
        log("B check: sameId=${bBeforeId == b.id.localId} state=${bNow?.state} hasToken=${tokens.get(b.id) != null} countBefore=$bBeforeCount countNow=${count(b)} bMismatches=${countMismatches(b)} bRowsUnderA=${rowsOwnedElsewhere(b, a)}")
        assertEquals(bBeforeId, b.id.localId)
        assertTrue(bNow?.state == ConnectionState.Healthy || bNow?.state == ConnectionState.Authorized)
        assertNotNull(tokens.get(b.id))
        assertTrue("B history not reduced", count(b) >= bBeforeCount)
        assertEquals(0, countMismatches(b))
        assertEquals("No B conversation stored under A", 0, rowsOwnedElsewhere(b, a))
    }

    /** Records B's baseline before A is reconnected or revoked. */
    @Test
    fun step1b_recordB() {
        val b = accountB()
        prefs.edit().putString(KEY_B_LOCAL, b.id.localId).putInt(KEY_B_COUNT, count(b)).commit()
        log("B baseline: account=${b.id.localId} state=${b.state} count=${count(b)} storedRows=${storedRows(b)} mismatches=${countMismatches(b)}")
    }

    /** Masked identity hint so the user can tell which mailbox is A and which is B. */
    @Test
    fun step0b_identityHint() {
        gmailAccounts().forEach { acct ->
            val label = if (acct.id.localId == accountA().id.localId) "A" else "B"
            log("identity $label account=${acct.id.localId} hint=${mask(acct.providerAccountId)}")
        }
    }

    /** After the user re-authorizes A following a revoke: same record, Healthy, history kept. */
    @Test
    fun step3b_reauthorizedAfterRevoke() {
        val savedId = prefs.getString(KEY_A_LOCAL, null)
        val a = accountA()
        val before = count(a)
        val report = connector.sync(a, SyncCheckpoint(), System.currentTimeMillis())
        log("reauth: sameId=${savedId == a.id.localId} state=${report.state} fetched=${report.fetched} inserted=${report.inserted} before=$before after=${count(a)} mismatches=${countMismatches(a)} accountsTotal=${gmailAccounts().size}")
        assertEquals(savedId, a.id.localId)
        assertEquals(2, gmailAccounts().size)
        assertEquals(ConnectionState.Healthy, report.state)
        assertNull(accounts.get(a.id)?.userActionRequired)
        assertEquals(0, countMismatches(a))
    }

    private fun conversations(a: ConnectedAccount) = MiniHubRuntime.observationStore.getConversationSummaries(a.id)
    private fun storedRows(a: ConnectedAccount) =
        conversations(a).sumOf { MiniHubRuntime.observationStore.getObservationsForConversation(it.conversationId, 10_000).size }
    private fun countMismatches(a: ConnectedAccount) = conversations(a).count {
        it.observationCount != MiniHubRuntime.observationStore.getObservationsForConversation(it.conversationId, 10_000).size
    }
    private fun rowsOwnedElsewhere(owner: ConnectedAccount, other: ConnectedAccount) =
        conversations(other).count { it.accountId == owner.id } + conversations(owner).count { it.accountId != owner.id }
    private fun mask(address: String): String {
        val at = address.indexOf('@')
        return if (at <= 1) "***" else address.take(2) + "***" + address.substring(at)
    }

    /** After the user revokes A at Google: A shows Revoked with a clear action, B unaffected. */
    @Test
    fun step3_revokedA_clearRecoveryBUnaffected() {
        val a = accountA(); val b = accountB()
        val aBefore = count(a)
        val reports = connector.syncAllConnected(System.currentTimeMillis(), "live_revoke_step")
        val aReport = reports.first { it.accountLocalId == a.id.localId }
        val bReport = reports.first { it.accountLocalId == b.id.localId }
        val aState = accounts.get(a.id)
        log("revoke: aState=${aState?.state} aSkipped=${aReport.skippedReason} aRefreshed=${aReport.refreshed} bState=${bReport.state} bFetched=${bReport.fetched} aHistory=$aBefore->${count(a)}")
        assertEquals(ConnectionState.Revoked, aState?.state)
        assertNotNull("A shows a user action", aState?.userActionRequired)
        assertNull("Dead credential dropped", tokens.get(a.id))
        assertEquals("A history kept", aBefore, count(a))
        assertEquals(ConnectionState.Healthy, bReport.state)
        assertTrue(bReport.fetched > 0)
    }

    /**
     * SIMULATED expiry: rewrites only the stored expiry time of A's real access token to the past.
     * Proves MINI refreshes with the real refresh token against Google. It does not prove behaviour
     * when Google itself expires the token; see step5.
     */
    @Test
    fun step4_simulatedExpiry_refreshesWithoutUserAction() {
        val a = accountA()
        val stored = tokens.get(a.id) ?: error("A has no credential")
        assertNotNull("A has a refresh token", stored.refreshToken)
        tokens.put(a.id, stored.copy(expiresAtMillis = System.currentTimeMillis() - 60_000L))
        val report = connector.sync(a, SyncCheckpoint(), System.currentTimeMillis())
        val after = tokens.get(a.id)!!
        log("simulated expiry: refreshed=${report.refreshed} state=${report.state} newExpiryInFuture=${(after.expiresAtMillis ?: 0) > System.currentTimeMillis()} tokenChanged=${after.value != stored.value}")
        assertTrue(report.refreshed)
        assertEquals(ConnectionState.Healthy, report.state)
        assertTrue((after.expiresAtMillis ?: 0) > System.currentTimeMillis())
        assertTrue(after.value != stored.value)
    }

    /**
     * LIVE (natural) expiry: nothing is rewritten. If B's stored access token has passed the
     * expiry Google issued, first prove Google itself rejects it (HTTP 401), then sync and expect a
     * silent refresh. If it has not expired yet, report minutes remaining and fail without changes.
     */
    @Test
    fun step5_liveExpiry_observeOnly() {
        val b = accountB()
        val now = System.currentTimeMillis()
        val t = tokens.get(b.id) ?: error("B has no credential")
        val expiresAt = t.expiresAtMillis ?: error("B token has no expiry")
        if (expiresAt > now) {
            log("live expiry: account=${b.id.localId} notExpiredYet minutesRemaining=${(expiresAt - now) / 60_000}")
            org.junit.Assert.fail("B token not naturally expired yet; rerun later")
        }
        val googleStatus = try {
            app.mini.hub.connector.GmailApiConnector()
                .discoverAccount(t, app.mini.hub.android.connector.AndroidProviderHttpTransport())
            200
        } catch (e: app.mini.hub.connector.ConnectorHttpException) { e.statusCode }
        val report = connector.sync(b, SyncCheckpoint(), now)
        log("live expiry: account=${b.id.localId} expiredMinutesAgo=${(now - expiresAt) / 60_000} googleStatusWithExpiredToken=$googleStatus refreshed=${report.refreshed} state=${report.state} fetched=${report.fetched}")
        assertEquals("Google rejected the naturally expired token", 401, googleStatus)
        assertTrue(report.refreshed)
        assertEquals(ConnectionState.Healthy, report.state)
    }

    /** Reports minutes until each account's access token naturally expires. Read-only. */
    @Test
    fun step5a_expiryClock() {
        val now = System.currentTimeMillis()
        gmailAccounts().forEach { acct ->
            val e = tokens.get(acct.id)?.expiresAtMillis
            log("expiry clock: account=${acct.id.localId} minutesUntilExpiry=${e?.let { (it - now) / 60_000 }}")
        }
    }

    /** Records a baseline before synthetic mail is sent. */
    @Test
    fun step6_backgroundBaseline() {
        gmailAccounts().forEach { acct ->
            prefs.edit().putInt("bg_${acct.id.localId}", count(acct)).commit()
            log("bg baseline account=${acct.id.localId} observations=${count(acct)}")
        }
        prefs.edit().putLong(KEY_BG_START, System.currentTimeMillis()).commit()
    }

    /**
     * Reads results only; does not sync. Proves the background job, not this test, stored the mail.
     * Matches the synthetic message by exact subject (passed as -e subject) without logging it.
     */
    @Test
    fun step7_backgroundResult_readOnly() {
        val start = prefs.getLong(KEY_BG_START, 0L)
        val subject = args.getString("subject") ?: error("pass -e subject")
        var found = false
        gmailAccounts().forEach { acct ->
            val before = prefs.getInt("bg_${acct.id.localId}", -1)
            val now = count(acct)
            val lastSync = accounts.get(acct.id)?.lastSuccessfulSyncMillis ?: 0L
            val match = conversations(acct)
                .flatMap { MiniHubRuntime.observationStore.getObservationsForConversation(it.conversationId, 10_000) }
                .firstOrNull { it.title == subject }
            val delaySec = match?.let { (it.observedAt.epochMillis - it.postTime.epochMillis) / 1000 }
            log("bg result account=${acct.id.localId} before=$before after=$now lastSyncAfterBaseline=${lastSync > start} subjectFound=${match != null} gmailReceivedToMiniStoredSec=$delaySec")
            if (match != null && match.observedAt.epochMillis > start) found = true
        }
        assertTrue("Synthetic message stored by background sync after baseline", found)
    }

    private fun sha256() = DigestProvider { payload ->
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "MiniGmailLive"
        const val PREFS = "mini_live_gmail_reliability"
        const val KEY_A = "account_a"
        const val KEY_A_LOCAL = "account_a_local_before_disconnect"
        const val KEY_A_COUNT = "account_a_count_before_disconnect"
        const val KEY_BG_START = "bg_start"
        const val KEY_B_LOCAL = "account_b_local"
        const val KEY_B_COUNT = "account_b_count"
        @Suppress("unused") private val keepJobRef = GmailSyncJobService::class
    }
}
