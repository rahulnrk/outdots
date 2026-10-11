package app.mini.hub.android.connector

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T4: the periodic sync survives reboot and is never double-scheduled. */
class SyncSchedulingTest {

    @Test(timeout = 30_000)
    fun periodicJobIsPersistedAcrossReboot() {
        assertTrue(GmailSyncJobService.PERIODIC_PERSISTED, "periodic job must be persisted (setPersisted(true))")
    }

    @Test(timeout = 30_000)
    fun noPendingJobSchedules() {
        assertEquals(
            GmailSyncJobService.ScheduleDecision.Schedule,
            GmailSyncJobService.periodicDecision(pendingPersisted = null),
        )
    }

    @Test(timeout = 30_000)
    fun persistedPendingJobIsLeftAloneSoItIsNotDoubleScheduled() {
        assertEquals(
            GmailSyncJobService.ScheduleDecision.KeepExisting,
            GmailSyncJobService.periodicDecision(pendingPersisted = true),
        )
    }

    @Test(timeout = 30_000)
    fun legacyNonPersistedJobIsReplacedOnceWithPersistedOne() {
        // Upgrading from the old setPersisted(false) build: replace it (same job ID), do not add a second.
        assertEquals(
            GmailSyncJobService.ScheduleDecision.ReplaceWithPersisted,
            GmailSyncJobService.periodicDecision(pendingPersisted = false),
        )
    }
}
