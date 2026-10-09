package app.mini.hub.domain

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

// Not compiled on this Windows host. Requires macOS/Xcode.
internal actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Uses NSProcessInfo.processInfo.systemUptime which is monotonic. */
actual fun monotonicMillis(): Long = (platform.Foundation.NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
