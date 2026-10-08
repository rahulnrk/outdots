package app.mini.hub.intelligence

sealed class SupervisorState {
    data object Unloaded : SupervisorState()
    data object Ready : SupervisorState()
    data object Pressured : SupervisorState()
}

enum class SubmitResult { Accepted, QueueFull, Duplicate, ScopeRejected, Cancelled, Pressured }
data class Job(val task: InferenceTask)

/** Owns the sole engine, admission queue, and bounded proposal cache. */
class ResourceSupervisor(
    private val engine: InferenceEngine,
    private val modelId: String,
    private val maxQueue: Int = 256,
    private val maxCacheEntries: Int = 32,
) {
    init { require(maxQueue > 0); require(maxCacheEntries > 0) }

    var state: SupervisorState = SupervisorState.Unloaded
        private set
    var activeScope: CompartmentId? = null
        private set
    val queuedCount: Int get() = queue.size
    val cachedCount: Int get() = cache.size
    private val queue = ArrayDeque<Job>()
    private val cache = LinkedHashMap<String, ModelProposal>()
    private val cancelled = mutableSetOf<String>()

    fun ensureReady(): Boolean {
        if (state == SupervisorState.Pressured) return false
        if (state == SupervisorState.Unloaded) {
            if (!engine.load(modelId)) return false
            state = SupervisorState.Ready
        }
        return true
    }

    fun submit(task: InferenceTask): SubmitResult {
        if (state == SupervisorState.Pressured) return SubmitResult.Pressured
        if (task.context.compartment != task.compartment) return SubmitResult.ScopeRejected
        if (task.context.inputTokens > task.budget.inputTokens) return SubmitResult.ScopeRejected
        if (queue.any { it.task.id == task.id } || cache.containsKey(task.id)) return SubmitResult.Duplicate
        if (queue.size >= maxQueue) return SubmitResult.QueueFull
        if (!ensureReady()) return SubmitResult.Pressured
        queue.addLast(Job(task))
        return SubmitResult.Accepted
    }

    fun runNext(): ModelProposal? {
        val job = queue.removeFirstOrNull() ?: return null
        if (job.task.id in cancelled) return null
        val scope = activeScope
        if (scope != null && scope != job.task.compartment) return null
        activeScope = job.task.compartment
        return try {
            val proposal = engine.run(job.task)
            cache[job.task.id] = proposal
            while (cache.size > maxCacheEntries) cache.remove(cache.entries.first().key)
            proposal
        } finally {
            activeScope = null
        }
    }

    fun cancel(taskId: String): Boolean {
        val found = queue.any { it.task.id == taskId }
        if (found) cancelled += taskId
        return found || engine.cancel(taskId)
    }

    fun onMemoryPressure() {
        state = SupervisorState.Pressured
        queue.removeAll { cancelled += it.task.id; true }
        cache.clear()
        activeScope = null
        engine.unload()
    }

    fun resumeAfterPressure(): Boolean {
        if (state != SupervisorState.Pressured) return true
        state = SupervisorState.Unloaded
        return ensureReady()
    }

    fun release() {
        queue.clear(); cache.clear(); cancelled.clear(); activeScope = null
        engine.unload(); state = SupervisorState.Unloaded
    }
}
