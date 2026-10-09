package app.mini.hub.domain

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

/** Uses System.nanoTime() which is monotonic. Converts to milliseconds. */
actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
