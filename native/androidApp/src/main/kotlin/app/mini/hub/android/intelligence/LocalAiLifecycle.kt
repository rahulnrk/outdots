package app.mini.hub.android.intelligence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Completion state of an asynchronous local AI resource release. */
sealed class ReleaseStatus(val label: String) {
    /** Cleanup is waiting on the serial inference worker. Nothing has been freed yet. */
    data object Queued : ReleaseStatus("QUEUED")

    /** The engine confirmed native and managed resources were closed. */
    data object Released : ReleaseStatus("RELEASED")

    /** Native generation had not stopped, so resources were kept rather than unsafely deleted. */
    data class Quarantined(val reason: String) : ReleaseStatus("QUARANTINED")

    /** Closing failed. The runtime must not be reused in this process. */
    data class Failed(val reason: String) : ReleaseStatus("FAILED")
}

/** Handle for one queued release. Completes exactly once on the inference worker. */
class ReleaseTicket internal constructor(
    val id: Long,
    val reason: String,
    private val isMainThread: () -> Boolean,
) {
    private val done = CountDownLatch(1)

    @Volatile
    var status: ReleaseStatus = ReleaseStatus.Queued
        private set

    /** Milliseconds from queueing to completion, or -1 while queued. */
    @Volatile
    var completedAfterMillis: Long = -1L
        private set

    internal fun complete(result: ReleaseStatus, elapsedMillis: Long) {
        if (done.count == 0L) return
        status = result
        completedAfterMillis = elapsedMillis
        done.countDown()
    }

    /**
     * Blocks until cleanup completes or the timeout passes, then returns the current status
     * ([ReleaseStatus.Queued] on timeout). Refuses to run on the Android main thread.
     */
    fun await(timeoutMillis: Long): ReleaseStatus {
        check(!isMainThread()) { "ReleaseTicket.await must not block the Android main thread." }
        done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return status
    }
}

/** Outcome of the explicit "Retry local AI" action. None of these load the model. */
enum class RecoveryResult {
    /** Failure pause cleared. The model loads lazily on the next valid draft request. */
    Recovered,

    /** Local AI was not paused, so nothing changed. */
    NotNeeded,

    /** Refused: recovery is foreground-only. */
    Background,

    /** Refused: memory pressure is active. */
    MemoryPressure,

    /** Refused: native generation never stopped; only a process restart reclaims it safely. */
    RestartRequired,
}
