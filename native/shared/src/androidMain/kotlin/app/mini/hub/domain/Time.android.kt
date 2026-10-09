package app.mini.hub.domain

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

/** Uses SystemClock.elapsedRealtime() which is monotonic and not affected by NTP or user adjustments. */
actual fun monotonicMillis(): Long = android.os.SystemClock.elapsedRealtime()
