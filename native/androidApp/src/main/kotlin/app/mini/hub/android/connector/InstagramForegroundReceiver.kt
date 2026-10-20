package app.mini.hub.android.connector

import app.mini.hub.domain.AccountId
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only receiver bound to the visible rtprobe Activity lifecycle.
 * It is deliberately not a background service and makes no claim about
 * receiving while the app is stopped or backgrounded.
 */
class InstagramForegroundReceiver(
    private val ingestor: InstagramReadIngestor,
    private val onResult: (InstagramReadIngestor.Result) -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "instagram-foreground-receive").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var accountId: AccountId? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var generation = 0L
    private var retryDelayMillis = INITIAL_DELAY_MILLIS

    @Synchronized
    fun start(account: AccountId) {
        if (running.get() && accountId == account) return
        stopLocked()
        accountId = account
        retryDelayMillis = INITIAL_DELAY_MILLIS
        generation++
        running.set(true)
        scheduleLocked(0L, generation)
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    @Synchronized
    fun close() {
        stopLocked()
        executor.shutdownNow()
    }

    private fun stopLocked() {
        generation++
        running.set(false)
        scheduled?.cancel(false)
        scheduled = null
        accountId = null
    }

    @Synchronized
    private fun scheduleLocked(delayMillis: Long, expectedGeneration: Long) {
        if (!running.get() || expectedGeneration != generation || scheduled != null) return
        scheduled = executor.schedule({ runOnce(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun runOnce(expectedGeneration: Long) {
        synchronized(this) {
            scheduled = null
            if (!running.get() || expectedGeneration != generation || !inFlight.compareAndSet(false, true)) return
        }
        val result = try {
            val account = synchronized(this) { accountId }
            if (account == null) InstagramReadIngestor.Result.Failed("receiver_cancelled")
            else ingestor.sync(account)
        } finally {
            inFlight.set(false)
        }
        val shouldContinue: Boolean
        val nextDelay: Long
        synchronized(this) {
            shouldContinue = running.get() && expectedGeneration == generation
            nextDelay = when (result) {
                is InstagramReadIngestor.Result.Success -> {
                    retryDelayMillis = INITIAL_DELAY_MILLIS
                    SUCCESS_DELAY_MILLIS
                }
                is InstagramReadIngestor.Result.Failed -> when (result.code) {
                    "session_expired", "account_provider_mismatch", "receiver_cancelled" -> Long.MAX_VALUE
                    "throttled", "upstream_http_status" -> {
                        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                        retryDelayMillis
                    }
                    "network_error" -> {
                        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                        retryDelayMillis
                    }
                    else -> MAX_RETRY_DELAY_MILLIS
                }
            }
            if (shouldContinue && nextDelay != Long.MAX_VALUE) {
                scheduleLocked(nextDelay, expectedGeneration)
            }
        }
        if (shouldContinue) onResult(result)
    }

    companion object {
        private const val INITIAL_DELAY_MILLIS = 30_000L
        private const val SUCCESS_DELAY_MILLIS = 30_000L
        private const val MAX_RETRY_DELAY_MILLIS = 5 * 60_000L
    }
}
