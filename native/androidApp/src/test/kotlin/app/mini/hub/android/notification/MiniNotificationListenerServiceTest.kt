package app.mini.hub.android.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniNotificationListenerServiceTest {

    @Test
    fun shouldAttemptRebind_returnsFalse_whenAlreadyConnected() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = true,
            lastTransitionAtMillis = 1000L,
            lastRebindAttemptAtMillis = 0L,
            nowMillis = 20_000L,
        )
        assertFalse(allow, "Connected listener must not attempt rebind")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenPermissionNotGranted() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = false,
            isConnected = false,
            lastTransitionAtMillis = 1000L,
            lastRebindAttemptAtMillis = 0L,
            nowMillis = 20_000L,
        )
        assertFalse(allow, "Rebind must not be attempted when permission is not granted")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenWithinTransitionCooldown() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionAtMillis = 10_000L,
            lastRebindAttemptAtMillis = 0L,
            nowMillis = 15_000L, // 5s elapsed < 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertFalse(allow, "Rebind must not be attempted immediately after a disconnect transition")
    }

    @Test
    fun shouldAttemptRebind_returnsFalse_whenWithinAttemptCooldown() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionAtMillis = 0L,
            lastRebindAttemptAtMillis = 10_000L,
            nowMillis = 18_000L, // 8s elapsed < 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertFalse(allow, "Rebind must not be attempted repeatedly in a tight loop")
    }

    @Test
    fun shouldAttemptRebind_returnsTrue_whenDisconnectedPastCooldownWithPermission() {
        val allow = MiniNotificationListenerService.shouldAttemptRebind(
            isPermissionGranted = true,
            isConnected = false,
            lastTransitionAtMillis = 10_000L,
            lastRebindAttemptAtMillis = 5_000L,
            nowMillis = 30_000L, // 20s elapsed >= 15s cooldown
            cooldownMillis = 15_000L,
        )
        assertTrue(allow, "Rebind should be permitted when permission is granted and cooldown has elapsed")
    }
}
