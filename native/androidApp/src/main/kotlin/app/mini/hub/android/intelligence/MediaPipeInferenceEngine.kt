package app.mini.hub.android.intelligence

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import app.mini.hub.intelligence.InferenceEngine
import app.mini.hub.intelligence.InferenceTask
import app.mini.hub.intelligence.ModelProposal
import app.mini.hub.intelligence.ProposedAction
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
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
    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    override fun load(modelId: String): Boolean {
        synchronized(lock) {
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
        val model = synchronized(lock) { inference } ?: error("model is not loaded")
        check(task.context.inputTokens <= task.budget.inputTokens) { "input budget exceeded" }
        // MediaPipe counts prompt and output together. Check with the real tokenizer so an
        // oversized prompt is reported clearly instead of surfacing as an empty proposal.
        val promptTokens = model.sizeInTokens(task.prompt)
        check(promptTokens + MIN_OUTPUT_TOKENS <= MAX_MODEL_TOKENS) {
            "Prompt is $promptTokens tokens; limit is ${MAX_MODEL_TOKENS - MIN_OUTPUT_TOKENS}."
        }
        if (activeTask.get() != null) error("another inference is active")
        check(activeTask.compareAndSet(null, task.id)) { "another inference is active" }
        val future = model.generateResponseAsync(task.prompt)
        activeFuture.set(future)
        return try {
            val text = future.get(MAX_INFERENCE_MILLIS, TimeUnit.MILLISECONDS).trim()
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
        } finally {
            activeFuture.compareAndSet(future, null)
            activeTask.compareAndSet(task.id, null)
        }
    }

    override fun cancel(taskId: String): Boolean {
        if (activeTask.get() != taskId) return false
        activeTask.compareAndSet(taskId, null)
        return activeFuture.getAndSet(null)?.cancel(true) == true
    }

    override fun unload() {
        activeFuture.getAndSet(null)?.cancel(true)
        activeTask.set(null)
        synchronized(lock) {
            inference?.close()
            inference = null
            loadedModelId = null
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
