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

    @BeforeTest
    fun setUp() {
        MiniNotificationListenerService.scheduler = testScheduler
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 0L
        MiniNotificationListenerService.lastRebindAttemptElapsedRealtime = 0L
    }

    @AfterTest
    fun tearDown() {
        testScheduler.cancel()
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
    fun deferredRecovery_schedulesSingleAttempt_whenWithinCooldown() {
        MiniNotificationListenerService.lastTransitionElapsedRealtime = 10_000L
        MiniNotificationListenerService.lastRebindAttemptElapsedRealtime = 0L

        // Call at 12s (2s after transition -> 13s cooldown remaining out of 15s)
        val now = 12_000L
        val allowImmediate = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionElapsedRealtime = 10_000L,
            lastRebindAttemptElapsedRealtime = 0L,
            nowElapsedRealtime = now,
        )
        assertFalse(allowImmediate)

        // Simulate scheduling via scheduler
        val remaining = 15_000L - (now - 10_000L)
        testScheduler.postDelayed(remaining) {}
        assertTrue(testScheduler.isScheduled())
        assertEquals(13_000L, testScheduler.scheduledDelay)
    }

    @Test
    fun deferredRecovery_suppressesDuplicateScheduling() {
        testScheduler.postDelayed(10_000L) {}
        val firstScheduleCount = testScheduler.scheduleCallCount
        assertEquals(1, firstScheduleCount)

        // When scheduler.isScheduled() is true, another trigger should not post again
        assertTrue(testScheduler.isScheduled())
        if (!testScheduler.isScheduled()) {
            testScheduler.postDelayed(5_000L) {}
        }
        assertEquals(1, testScheduler.scheduleCallCount, "Duplicate attempt must be suppressed while one is scheduled")
    }

    @Test
    fun deferredRecovery_cancelsOnConnection() {
        testScheduler.postDelayed(10_000L) {}
        assertTrue(testScheduler.isScheduled())

        // When listener connects, pending recovery is cancelled
        MiniNotificationListenerService.cancelPendingRecovery()
        assertFalse(testScheduler.isScheduled())
        assertNull(testScheduler.pendingAction)
    }

    @Test
    fun deferredRecovery_cancelsOnPermissionLoss() {
        testScheduler.postDelayed(10_000L) {}
        assertTrue(testScheduler.isScheduled())

        // When permission is revoked, pending recovery is cancelled
        MiniNotificationListenerService.cancelPendingRecovery()
        assertFalse(testScheduler.isScheduled())
        assertNull(testScheduler.pendingAction)
    }

    @Test
    fun deferredRecovery_executesOnceWithoutLooping() {
        var executionCount = 0
        testScheduler.postDelayed(100L) {
            executionCount++
            // Single execution: does not reschedule itself
        }

        assertEquals(1, testScheduler.scheduleCallCount)
        testScheduler.trigger()
        assertEquals(1, executionCount)
        assertFalse(testScheduler.isScheduled(), "Deferred recovery must not reschedule automatically into a loop")
    }
}
