package app.mini.hub.android.intelligence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.*
import app.mini.hub.observation.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LocalDraftRuntimeTest {
    @Test
    fun timeoutRequestsNativeStopAndWaitsForTerminalCallback() {
        val future = CompletableFuture<String>()
        var stopRequests = 0
        val failure = assertFailsWith<TimeoutException> {
            NativeGenerationWait.await(future, 1L, 10L) {
                stopRequests++
                future.complete("partial output")
            }
        }
        assertEquals(1, stopRequests)
        assertTrue(future.isDone)
        assertFalse(future.isCancelled, "Java cancellation must not replace native stop and done callback")
        assertTrue(failure.message!!.contains("timed out"))
    }

    @Test
    fun nonStoppingRuntimeRemainsNonTerminalForQuarantine() {
        val future = CompletableFuture<String>()
        var stopRequests = 0
        assertFailsWith<TimeoutException> {
            NativeGenerationWait.await(future, 1L, 1L) { stopRequests++ }
        }
        assertEquals(1, stopRequests)
        assertFalse(future.isDone, "Adapter must detect this and avoid deleting the active session")
    }

    @Test
    fun completedGenerationDoesNotRequestCancellation() {
        var stopRequests = 0
        val text = NativeGenerationWait.await(CompletableFuture.completedFuture("reply"), 10L) { stopRequests++ }
        assertEquals("reply", text)
        assertEquals(0, stopRequests)
    }

    @Test
    fun blockingEvaluatorAndUiDraftShareOneSerialWorker() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val startedSecondCaller = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val calls = AtomicInteger()
        val engine = object : DeterministicInferenceEngine() {
            override fun run(task: InferenceTask): ModelProposal {
                val active = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, active) }
                val call = calls.incrementAndGet()
                try {
                    if (call == 1) {
                        entered.countDown()
                        check(proceed.await(5, TimeUnit.SECONDS))
                    }
                    return ModelProposal(task.id, task.compartment, "Thanks for the update.", task.context.evidence)
                } finally { concurrent.decrementAndGet() }
            }
        }
        val ownerExecutor = Executors.newSingleThreadExecutor()
        val callerExecutor = Executors.newSingleThreadExecutor()
        val owner = LocalDraftExperiment(engine, "synthetic-model", ownerExecutor, System::currentTimeMillis)
        owner.setForeground(true)
        val uiResult = CompletableFuture<LocalDraftResult>()
        try {
            owner.draftAsync(request()) { uiResult.complete(it) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val evaluation = callerExecutor.submit<LocalDraftResult> {
                startedSecondCaller.countDown()
                owner.draftBlocking(request())
            }
            assertTrue(startedSecondCaller.await(5, TimeUnit.SECONDS))
            assertFalse(evaluation.isDone)
            proceed.countDown()
            assertIs<LocalDraftResult.Success>(uiResult.get(5, TimeUnit.SECONDS))
            assertIs<LocalDraftResult.Success>(evaluation.get(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals(1, maxConcurrent.get())
            owner.release()
        } finally {
            proceed.countDown()
            callerExecutor.shutdownNow()
            ownerExecutor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeWorkerStartsSkipsModelLoading() {
        val workerBlocked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val loads = AtomicInteger()
        val engine = object : DeterministicInferenceEngine() {
            override fun load(modelId: String): Boolean {
                loads.incrementAndGet()
                return super.load(modelId)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val owner = LocalDraftExperiment(engine, "synthetic-model", executor, System::currentTimeMillis)
        owner.setForeground(true)
        val result = CompletableFuture<LocalDraftResult>()
        try {
            executor.submit {
                workerBlocked.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
            assertTrue(workerBlocked.await(5, TimeUnit.SECONDS))
            owner.draftAsync(request()) { result.complete(it) }
            owner.cancel()
            proceed.countDown()
            assertIs<LocalDraftResult.Failed>(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, loads.get())
            owner.release()
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationDuringColdLoadDoesNotStartGenerationAndNextRequestWorks() {
        val enteredLoad = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val generated = AtomicInteger()
        val engine = object : DeterministicInferenceEngine() {
            override fun load(modelId: String): Boolean {
                enteredLoad.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                return super.load(modelId)
            }
            override fun run(task: InferenceTask): ModelProposal {
                generated.incrementAndGet()
                return ModelProposal(task.id, task.compartment, "Thanks for the update.", task.context.evidence)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val owner = LocalDraftExperiment(engine, "synthetic-model", executor, System::currentTimeMillis)
        owner.setForeground(true)
        val result = CompletableFuture<LocalDraftResult>()
        try {
            owner.draftAsync(request()) { result.complete(it) }
            assertTrue(enteredLoad.await(5, TimeUnit.SECONDS))
            assertTrue(owner.cancel())
            proceed.countDown()
            assertIs<LocalDraftResult.Failed>(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, generated.get())
            assertIs<LocalDraftResult.Success>(owner.draftBlocking(request()))
            assertEquals(1, generated.get())
            owner.release()
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }

    private fun request(): LocalDraftRequest {
        val account = AccountId(Provider.WHATSAPP, "synthetic-runtime-test")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "synthetic")
        val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "1")
        val compartment = CompartmentId(account.key)
        val observation = ObservedNotification(
            id, account, conversation, "synthetic.local", "synthetic-key", 1, null,
            "Synthetic Sender", "Synthetic", listOf("Thanks for the update."), "synthetic-digest",
            UtcInstant(1_760_000_000_000L), UtcInstant(1_760_000_000_000L), 1L, false, false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.SyntheticFixture,
        )
        return LocalDraftRequest(
            "Synthetic", "Synthetic Sender", compartment,
            ContextBundle(compartment, listOf(EvidenceRef(SourceId(id.key), 1L, compartment)), 30, false),
            listOf(observation), "", "Draft a brief acknowledgement.",
        )
    }
}
