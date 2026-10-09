package app.mini.hub.intelligence

import kotlin.coroutines.cancellation.CancellationException

sealed class SupervisorState {
    data object Unloaded : SupervisorState()
    data object Ready : SupervisorState()
    data class Degraded(val consecutiveFailures: Int, val reason: String) : SupervisorState()
    data object Pressured : SupervisorState()
}

enum class SubmitResult { Accepted, QueueFull, Duplicate, ScopeRejected, Cancelled, Pressured }
data class Job(val task: InferenceTask)

/** Owns the sole engine, admission queue, and bounded proposal cache. */
class ResourceSupervisor(
    private val engine: InferenceEngine,
    private val modelId: String,
    val budget: DeviceResourceBudget = DeviceResourceBudget.Tier2MidRange,
    private val maxFailureThreshold: Int = 3,
    /** User or lifecycle cancellation is not an engine failure and never counts toward Degraded. */
    private val isCancellation: (Throwable) -> Boolean = { it is CancellationException },
) {
    private val maxQueue: Int = budget.maxQueueSize
    private val maxCacheEntries: Int = budget.maxProposalCacheEntries

    init { require(maxQueue > 0); require(maxCacheEntries > 0) }

    /** Backward-compatible constructor for custom queue and cache sizes. */
    constructor(engine: InferenceEngine, modelId: String, maxQueue: Int, maxCacheEntries: Int) : this(
        engine = engine,
        modelId = modelId,
        budget = DeviceResourceBudget("Custom", 1024, maxQueue, maxCacheEntries, 4096, 256, 5000L),
    )

    var state: SupervisorState = SupervisorState.Unloaded
        private set
    var consecutiveFailures: Int = 0
        private set
    var lastError: String? = null
        private set
    /** True when the most recent runNext ended because the task was cancelled. */
    var lastRunCancelled: Boolean = false
        private set

    var activeScope: CompartmentId? = null
        private set
    val queuedCount: Int get() = queue.size
    val cachedCount: Int get() = cache.size
    private val queue = ArrayDeque<Job>()
    private val cache = LinkedHashMap<String, ModelProposal>()
    private val cancelled = mutableSetOf<String>()
    // Repeated failures stay paused through memory pressure and only clear on explicit recovery.
    private var degradedBeforePressure: SupervisorState.Degraded? = null

    fun ensureReady(): Boolean {
        if (state is SupervisorState.Degraded) return false
        if (state == SupervisorState.Pressured) return false
        if (state == SupervisorState.Unloaded) {
            if (!engine.load(modelId)) return false
            state = SupervisorState.Ready
        }
        return true
    }

    fun submit(task: InferenceTask): SubmitResult {
        if (state == SupervisorState.Pressured) return SubmitResult.Pressured
        if (state is SupervisorState.Degraded) return SubmitResult.Pressured
        if (task.context.compartment != task.compartment) return SubmitResult.ScopeRejected
        if (task.context.inputTokens > task.budget.inputTokens) return SubmitResult.ScopeRejected
        if (queue.any { it.task.id == task.id } || cache.containsKey(task.id)) return SubmitResult.Duplicate
        if (queue.size >= maxQueue) return SubmitResult.QueueFull
        if (!ensureReady()) return SubmitResult.Pressured
        queue.addLast(Job(task))
        return SubmitResult.Accepted
    }

    fun runNext(): ModelProposal? {
        lastRunCancelled = false
        val job = queue.removeFirstOrNull() ?: return null
        if (job.task.id in cancelled) {
            lastRunCancelled = true
            return null
        }
        val scope = activeScope
        if (scope != null && scope != job.task.compartment) return null
        activeScope = job.task.compartment
        return try {
            val proposal = engine.run(job.task)
            cache[job.task.id] = proposal
            consecutiveFailures = 0
            while (cache.size > maxCacheEntries) cache.remove(cache.entries.first().key)
            proposal
        } catch (e: Throwable) {
            if (isCancellation(e)) {
                lastRunCancelled = true
            } else {
                consecutiveFailures++
                lastError = e.message ?: e::class.simpleName ?: "unknown error"
                if (consecutiveFailures >= maxFailureThreshold) {
                    state = SupervisorState.Degraded(consecutiveFailures, lastError ?: "repeated failures")
                    engine.unload()
                }
            }
            null
        } finally {
            activeScope = null
        }
    }

    fun cancel(taskId: String): Boolean {
        val found = queue.any { it.task.id == taskId }
        if (found) cancelled += taskId
        return found || engine.cancel(taskId)
    }

    fun onMemoryPressure(): UnloadOutcome {
        (state as? SupervisorState.Degraded)?.let { degradedBeforePressure = it }
        state = SupervisorState.Pressured
        if (degradedBeforePressure == null) consecutiveFailures = 0
        queue.removeAll { cancelled += it.task.id; true }
        cache.clear()
        activeScope = null
        return engine.unloadWithOutcome()
    }

    /** Leaves Pressured without loading. A failure pause that existed before pressure is restored. */
    fun resumeAfterPressure() {
        if (state == SupervisorState.Pressured) {
            state = degradedBeforePressure ?: SupervisorState.Unloaded
            degradedBeforePressure = null
        }
    }

    /**
     * Clears a failure pause without loading the model; the next submit() loads lazily.
     * Refused while memory pressure is active.
     */
    fun recoverFromFailure(): Boolean {
        if (state == SupervisorState.Pressured) return false
        consecutiveFailures = 0
        lastError = null
        degradedBeforePressure = null
        state = SupervisorState.Unloaded
        return true
    }

    /** Frees resources. A failure pause or memory pressure survives release. */
    fun release(): UnloadOutcome {
        queue.clear(); cache.clear(); cancelled.clear(); activeScope = null
        val outcome = engine.unloadWithOutcome()
        val current = state
        if (current !is SupervisorState.Degraded && current != SupervisorState.Pressured) {
            consecutiveFailures = 0
            lastError = null
            state = SupervisorState.Unloaded
        }
        return outcome
    }
}
