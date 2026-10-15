package app.mini.hub.android.notification

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniNotificationListenerServiceTest {

    private class TestScheduler : MiniNotificationListenerService.RebindScheduler {
        var scheduledDelay: Long? = null
        var pendingAction: Runnable? = null
        var cancelCallCount: Int = 0
        var scheduleCallCount: Int = 0

        override fun postDelayed(delayMillis: Long, action: Runnable) {
            scheduleCallCount++
            scheduledDelay = delayMillis
            pendingAction = action
        }

        override fun cancel() {
            cancelCallCount++
            scheduledDelay = null
            pendingAction = null
        }

        override fun isScheduled(): Boolean = pendingAction != null

        fun trigger() {
            val a = pendingAction
            pendingAction = null
            scheduledDelay = null
            a?.run()
        }
    }

    private val testScheduler = TestScheduler()
    private var rebindCallCount: Int = 0
    private var isPermissionGranted: Boolean = true
    private var currentTimeElapsed: Long = 0L

    @BeforeTest
    fun setUp() {
        rebindCallCount = 0
        isPermissionGranted = true
        currentTimeElapsed = 0L
        MiniNotificationListenerService.resetTestingOverrides()
        MiniNotificationListenerService.scheduler = testScheduler
        MiniNotificationListenerService.elapsedRealtimeClock = { currentTimeElapsed }
        MiniNotificationListenerService.permissionChecker = { isPermissionGranted }
        MiniNotificationListenerService.rebindAction = {
            rebindCallCount++
            true
        }
        MiniNotificationListenerService.isConnected = false
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 0L
        MiniNotificationListenerService.lastRebindAttemptElapsedRealtime = 0L
    }

    @AfterTest
    fun tearDown() {
        testScheduler.cancel()
        MiniNotificationListenerService.resetTestingOverrides()
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenAlreadyConnected() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = true,
            lastTransitionElapsedRealtime = 1000L,
            lastRebindAttemptElapsedRealtime = 0L,
            nowElapsedRealtime = 20_000L,
        )
        assertFalse(allow, "Connected listener must not attempt rebind")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenPermissionNotGranted() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = false,
            isConnected = false,
            lastTransitionElapsedRealtime = 1000L,
            lastRebindAttemptElapsedRealtime = 0L,
            nowElapsedRealtime = 20_000L,
        )
        assertFalse(allow, "Rebind must not be attempted when permission is not granted")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenWithinTransitionCooldown() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionElapsedRealtime = 10_000L,
            lastRebindAttemptElapsedRealtime = 0L,
            nowElapsedRealtime = 15_000L, // 5s elapsed < 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertFalse(allow, "Rebind must not be attempted immediately after a disconnect transition")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenWithinAttemptCooldown() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionElapsedRealtime = 0L,
            lastRebindAttemptElapsedRealtime = 10_000L,
            nowElapsedRealtime = 18_000L, // 8s elapsed < 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertFalse(allow, "Rebind must not be attempted repeatedly in a tight loop")
    }

    @Test
    fun shouldAttemptRebind_returnsTrue_whenDisconnectedPastCooldownWithPermission() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionElapsedRealtime = 10_000L,
            lastRebindAttemptElapsedRealtime = 5_000L,
            nowElapsedRealtime = 30_000L, // 20s elapsed >= 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertTrue(allow, "Rebind should be permitted when permission is granted and cooldown has elapsed")
    }

    @Test
    fun deferredRecovery_schedulesSingleAttemptForRemainingInterval_whenInCooldown() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L // 2s elapsed, 13s remaining

        val immediate = MiniNotificationListenerService.rebindIfNeeded(null)
        assertFalse(immediate, "Immediate rebind must be rejected during cooldown")
        assertTrue(testScheduler.isScheduled(), "Deferred recovery attempt must be scheduled")
        assertEquals(13_000L, testScheduler.scheduledDelay, "Scheduled delay must match exact remaining cooldown")
        assertEquals(0, rebindCallCount, "No rebind must be executed immediately")
    }

    @Test
    fun deferredRecovery_suppressesDuplicateSchedulingOnRepeatedCalls() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L
        MiniNotificationListenerService.rebindIfNeeded(null)
        assertEquals(1, testScheduler.scheduleCallCount)

        currentTimeElapsed = 13_000L
        MiniNotificationListenerService.rebindIfNeeded(null)

        currentTimeElapsed = 14_000L
        MiniNotificationListenerService.rebindIfNeeded(null)

        assertEquals(1, testScheduler.scheduleCallCount, "Repeated calls during cooldown must suppress duplicate scheduling")
        assertEquals(0, rebindCallCount, "No rebind calls during cooldown")
    }

    @Test
    fun deferredRecovery_executesProductionCallbackOnceWithoutLooping() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L
        MiniNotificationListenerService.rebindIfNeeded(null)

        // Advance monotonic clock past cooldown and trigger the captured production callback
        currentTimeElapsed = 26_000L
        testScheduler.trigger()

        assertEquals(1, rebindCallCount, "Production callback must request rebind once")
        assertFalse(testScheduler.isScheduled(), "Callback must not reschedule itself in a loop")
        assertEquals(26_000L, MiniNotificationListenerService.lastRebindAttemptElapsedRealtime)
    }

    @Test
    fun deferredRecovery_cancelsPendingAttemptOnConnection() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L
        MiniNotificationListenerService.rebindIfNeeded(null)
        assertTrue(testScheduler.isScheduled())

        // Connect listener: rebindIfNeeded or cancelPendingRecovery cancels scheduled work
        MiniNotificationListenerService.isConnected = true
        MiniNotificationListenerService.rebindIfNeeded(null)

        assertFalse(testScheduler.isScheduled(), "Pending recovery must be cancelled when listener connects")
        assertNull(testScheduler.pendingAction)
    }

    @Test
    fun deferredRecovery_permissionLossPreventsRebindOnCallback() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L
        MiniNotificationListenerService.rebindIfNeeded(null)
        assertTrue(testScheduler.isScheduled())

        // Revoke permission before callback fires
        isPermissionGranted = false
        currentTimeElapsed = 26_000L
        testScheduler.trigger()

        assertEquals(0, rebindCallCount, "Permission loss must prevent deferred rebind request")
        assertFalse(testScheduler.isScheduled())
    }

    @Test
    fun immediatePermittedRequest_cancelsPendingDeferredAttempt() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        currentTimeElapsed = 12_000L
        MiniNotificationListenerService.rebindIfNeeded(null)
        assertTrue(testScheduler.isScheduled())

        // Advance past cooldown and call rebindIfNeeded: immediately permitted
        currentTimeElapsed = 26_000L
        val immediate = MiniNotificationListenerService.rebindIfNeeded(null)

        assertTrue(immediate, "Rebind should succeed immediately once cooldown expires")
        assertEquals(1, rebindCallCount, "Immediate rebind must be executed")
        assertFalse(testScheduler.isScheduled(), "Pending deferred attempt must be cancelled on immediate success")
    }
}
