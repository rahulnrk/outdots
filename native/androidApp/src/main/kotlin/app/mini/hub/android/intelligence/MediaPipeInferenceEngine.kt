package app.mini.hub.android.intelligence

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import app.mini.hub.intelligence.InferenceEngine
import app.mini.hub.intelligence.InferenceTask
import app.mini.hub.intelligence.ModelProposal
import app.mini.hub.intelligence.ProposedAction
import app.mini.hub.intelligence.UnloadOutcome
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Real Android inference adapter behind the shared InferenceEngine boundary.
 * The model is app-private, loaded only by ResourceSupervisor, and never receives network access.
 */
class MediaPipeInferenceEngine(
    private val context: Context,
    private val modelFile: File,
) : InferenceEngine {
    private val lock = Any()
    private val activeTask = AtomicReference<String?>(null)
    private val activeFuture = AtomicReference<Future<String>?>(null)
    private val activeSession = AtomicReference<LlmInferenceSession?>(null)
    @Volatile private var unsafeToReuse = false
    @Volatile private var generationStartedListener: ((String) -> Unit)? = null
    internal var generationTimeoutMillis: Long = MAX_INFERENCE_MILLIS
    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    override fun load(modelId: String): Boolean {
        synchronized(lock) {
            check(!unsafeToReuse) { "Local runtime did not stop cleanly. Restart the app before drafting." }
            if (loadedModelId == modelId && inference != null) return true
            if (!modelFile.isFile || modelFile.length() <= 0L) return false
            inference?.close()
            inference = null
            loadedModelId = null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_MODEL_TOKENS)
                .setMaxTopK(MAX_TOP_K)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            loadedModelId = modelId
            return true
        }
    }

    override fun run(task: InferenceTask): ModelProposal {
        check(!unsafeToReuse) { "Local runtime did not stop cleanly. Restart the app before drafting." }
        val model = synchronized(lock) { inference } ?: error("model is not loaded")
        check(task.context.inputTokens <= task.budget.inputTokens) { "input budget exceeded" }
        if (activeTask.get() != null) error("another inference is active")
        check(activeTask.compareAndSet(null, task.id)) { "another inference is active" }
        var session: LlmInferenceSession? = null
        var future: Future<String>? = null
        return try {
            session = LlmInferenceSession.createFromOptions(
                model,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build(),
            )
            activeSession.set(session)
            if (activeTask.get() != task.id) throw CancellationException("inference cancelled")
            // Count on this same session; model.sizeInTokens would create a second implicit
            // session. Prompt and output still share the verified total context limit.
            val promptTokens = session.sizeInTokens(task.prompt)
            check(promptTokens + MIN_OUTPUT_TOKENS <= MAX_MODEL_TOKENS) {
                "Prompt is $promptTokens tokens; limit is ${MAX_MODEL_TOKENS - MIN_OUTPUT_TOKENS}."
            }
            session.addQueryChunk(task.prompt)
            val startedSignalled = java.util.concurrent.atomic.AtomicBoolean(false)
            future = session.generateResponseAsync { partial, done ->
                // First streamed output proves native generation is running for this task.
                if (!done && partial.isNotEmpty() && startedSignalled.compareAndSet(false, true)) {
                    generationStartedListener?.invoke(task.id)
                }
            }
            activeFuture.set(future)
            // Cancellation may arrive between publishing the session and starting generation.
            if (activeTask.get() != task.id) session.cancelGenerateResponseAsync()
            val runningSession = session
            val text = NativeGenerationWait.await(future, generationTimeoutMillis) {
                runningSession.cancelGenerateResponseAsync()
            }.trim()
            if (Thread.currentThread().isInterrupted || activeTask.get() != task.id) {
                throw CancellationException("inference cancelled")
            }
            check(text.isNotEmpty()) { "The local model returned no text." }
            ModelProposal(
                taskId = task.id,
                compartment = task.compartment,
                text = text,
                evidence = task.context.evidence,
                proposedAction = ProposedAction.Draft(),
            )
        } catch (e: Throwable) {
            // After cancel() the task is no longer active. Whatever MediaPipe reports for the
            // stopped generation is a cancellation, not a model failure.
            if (activeTask.get() != task.id && e !is CancellationException) {
                throw CancellationException("inference cancelled").apply { initCause(e) }
            }
            throw e
        } finally {
            // Never delete a session while its native callback is still active. Quarantine
            // the runtime if cancellation did not reach done=true within the drain deadline.
            if (future == null || future.isDone) {
                try {
                    session?.close()
                } catch (e: Throwable) {
                    unsafeToReuse = true
                    throw e
                } finally {
                    activeSession.compareAndSet(session, null)
                    activeFuture.compareAndSet(future, null)
                    activeTask.compareAndSet(task.id, null)
                }
            } else {
                unsafeToReuse = true
            }
            activeTask.compareAndSet(task.id, null)
        }
    }

    override fun cancel(taskId: String): Boolean {
        if (activeTask.get() != taskId) return false
        activeTask.compareAndSet(taskId, null)
        try {
            activeSession.get()?.cancelGenerateResponseAsync()
        } catch (_: IllegalStateException) {
            // The worker may have completed and closed the session after our task check.
            return false
        }
        return true
    }

    override fun unload() {
        unloadWithOutcome()
    }

    override val isQuarantined: Boolean get() = unsafeToReuse

    override fun setGenerationStartedListener(listener: ((taskId: String) -> Unit)?) {
        generationStartedListener = listener
    }

    override fun unloadWithOutcome(): UnloadOutcome {
        activeTask.get()?.let { cancel(it) }
        synchronized(lock) {
            val pending = activeFuture.get()
            if (pending != null && !pending.isDone) {
                // Closing now would delete a session whose native callback is still running.
                unsafeToReuse = true
                return UnloadOutcome.Quarantined("Native generation had not stopped; resources kept until restart.")
            }
            return try {
                activeSession.getAndSet(null)?.close()
                activeFuture.set(null)
                inference?.close()
                inference = null
                loadedModelId = null
                unsafeToReuse = false
                UnloadOutcome.Released
            } catch (e: Throwable) {
                unsafeToReuse = true
                UnloadOutcome.Failed(e.message ?: e::class.simpleName ?: "close failed")
            }
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "qwen2.5-0.5b-instruct-q8.task"
        const val MODEL_ID = "qwen2.5-0.5b-instruct-q8-mediapipe"
        // MediaPipe counts input and output together. The selected artifact is built with a
        // 1280-entry KV cache (ekv1280), so 1024 stays inside it while fitting a multi-message
        // conversation. The previous 320 overflowed once a thread had about four messages.
        const val MAX_MODEL_TOKENS = 1024
        const val MIN_OUTPUT_TOKENS = 128
        private const val MAX_TOP_K = 20
        private const val MAX_INFERENCE_MILLIS = 60_000L

        fun defaultModelFile(context: Context): File = File(
            context.getExternalFilesDir("models") ?: File(context.filesDir, "models"),
            MODEL_FILE_NAME,
        )
    }
}

/** A Java Future cancellation does not stop MediaPipe's native session or release its lock. */
internal object NativeGenerationWait {
    fun await(
        future: Future<String>,
        timeoutMillis: Long,
        drainMillis: Long = 5_000L,
        cancelNative: () -> Unit,
    ): String = try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        cancelAndDrain(future, drainMillis, cancelNative)
        throw TimeoutException("Local inference timed out after $timeoutMillis ms.")
    } catch (e: InterruptedException) {
        cancelAndDrain(future, drainMillis, cancelNative)
        Thread.currentThread().interrupt()
        throw e
    }

    private fun cancelAndDrain(future: Future<String>, drainMillis: Long, cancelNative: () -> Unit) {
        cancelNative()
        // Keep the future alive so MediaPipe's done=true callback can release the native lock.
        try {
            future.get(drainMillis, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.ExecutionException) {
            // A terminal failure is safe to close; the generation is no longer active.
        } catch (_: CancellationException) {
            // Caller cancellation also leaves the future terminal.
        } catch (_: TimeoutException) {
            // The adapter checks isDone and quarantines this engine rather than closing it.
        }
    }
}
