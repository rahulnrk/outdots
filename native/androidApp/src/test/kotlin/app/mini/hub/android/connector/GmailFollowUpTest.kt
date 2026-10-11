package app.mini.hub.android.connector

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T1 host follow-up: a truncated Gmail pass requests a prompt pass instead of waiting 15 minutes. */
class GmailFollowUpTest {

    @Test(timeout = 30_000)
    fun moreAvailableRequestsPromptFollowUp() {
        assertTrue(GmailSyncJobService.shouldRequestFollowUp(moreAvailable = true, followUpsSoFar = 0))
    }

    @Test(timeout = 30_000)
    fun noFollowUpWhenHistoryIsDrained() {
        assertFalse(GmailSyncJobService.shouldRequestFollowUp(moreAvailable = false, followUpsSoFar = 0))
    }

    @Test(timeout = 30_000)
    fun followUpChainIsBounded() {
        var depth = 0
        while (GmailSyncJobService.shouldRequestFollowUp(moreAvailable = true, followUpsSoFar = depth)) {
            depth++
            assertTrue(depth <= 1_000, "follow-up chain must be bounded")
        }
        assertEquals(GmailSyncJobService.MAX_FOLLOW_UPS, depth)
    }

    @Test(timeout = 30_000)
    fun summaryReportsMoreAvailableFromAnyGmailAccount() {
        val healthy = app.mini.hub.connector.ConnectionState.Healthy
        val cp = app.mini.hub.connector.SyncCheckpoint()
        val reports = listOf(
            GmailSyncReport("a", cp, 50, 50, healthy, false, moreAvailable = false),
            GmailSyncReport("b", cp, 50, 50, healthy, false, moreAvailable = true),
        )
        assertTrue(reports.any { it.moreAvailable })
        assertTrue(reports[1].logLine("t").contains("more=true"))
        assertFalse(reports[1].logLine("t").contains("@"), "log line must carry no addresses")
    }
}
