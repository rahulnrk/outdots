package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Focus reconciliation scheduler enforcing a single active worker, bounded batches,
 * continuation until drained, and visible content-free failure state.
 * On cleanup failure, leaves failed events pending, exposes failureState,
 * and stops automatic retries until explicit retry.
 */
class FocusReconciliationScheduler(
    private val coordinator: ProductionFocusCoordinator,
    private val batchSize: Int = 100,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mini-focus-reconciliation").apply { isDaemon = true } }
    private val isRunning = AtomicBoolean(false)
    private val hasPendingWork = AtomicBoolean(false)

    @Volatile
    private var lastFailureClassName: String? = null

    val failureState: String? get() = lastFailureClassName

    fun notifyDeletionCommitted(accountId: AccountId? = null) {
        hasPendingWork.set(true)
        triggerDrain(accountId)
    }

    fun startStartupRecovery(accountId: AccountId? = null) {
        hasPendingWork.set(true)
        triggerDrain(accountId)
    }

    fun explicitRetry(accountId: AccountId? = null) {
        lastFailureClassName = null
        hasPendingWork.set(true)
        triggerDrain(accountId, forceBypassFailureGate = true)
    }

    private fun triggerDrain(accountId: AccountId?, forceBypassFailureGate: Boolean = false) {
        if (!forceBypassFailureGate && lastFailureClassName != null) {
            // Stop automatic retries until explicit retry
            return
        }
        if (isRunning.compareAndSet(false, true)) {
            executor.submit {
                try {
                    var drainedAny: Boolean
                    do {
                        drainedAny = false
                        try {
                            val result = coordinator.resumePendingReconciliation(accountId, batchSize)
                            if (result.failureClassName != null) {
                                lastFailureClassName = result.failureClassName
                                break
                            }
                            if (result.processedCount > 0) {
                                drainedAny = true
                                lastFailureClassName = null
                            }
                        } catch (e: Throwable) {
                            lastFailureClassName = e::class.simpleName ?: "UnknownException"
                            break
                        }
                    } while (drainedAny && lastFailureClassName == null)
                } finally {
                    isRunning.set(false)
                    if (hasPendingWork.get() && lastFailureClassName == null) {
                        hasPendingWork.set(false)
                        triggerDrain(accountId)
                    }
                }
            }
        } else {
            hasPendingWork.set(true)
        }
    }
}
