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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Release completion signals, asynchronous "Retry local AI", and cancellation semantics.
 * Fake engines count load/run/unload so every claim is observable.
 */
class LocalAiLifecycleTest {

    private open class CountingEngine : DeterministicInferenceEngine() {
        val loads = AtomicInteger()
        val runs = AtomicInteger()
        val unloads = AtomicInteger()
        @Volatile var failRuns = false
        override fun load(modelId: String): Boolean { loads.incrementAndGet(); return super.load(modelId) }
        override fun run(task: InferenceTask): ModelProposal {
            runs.incrementAndGet()
            if (failRuns) throw IllegalStateException("engine failure")
            return ModelProposal(task.id, task.compartment, "Thanks for the update.", task.context.evidence)
        }
        override fun unload() { unloads.incrementAndGet(); super.unload() }
    }

    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @AfterTest
    fun shutdown() { executors.forEach { it.shutdownNow() } }

    private fun experiment(engine: InferenceEngine, isMain: () -> Boolean = { false }): LocalDraftExperiment {
        val executor = Executors.newSingleThreadExecutor().also { executors += it }
        return LocalDraftExperiment(engine, "synthetic-model", executor, System::currentTimeMillis, isMain)
    }

    private fun degrade(exp: LocalDraftExperiment, engine: CountingEngine) {
        engine.failRuns = true
        repeat(3) { exp.draftBlocking(request()) }
        assertTrue(exp.needsRecovery, "three real failures must pause local AI")
        engine.failRuns = false
    }

    // ── Release completion signal ────────────────────────────────────────────

    @Test
    fun releaseTicketIsQueuedUntilWorkerConfirmsRelease() {
        val gate = CountDownLatch(1)
        val workerBusy = CountDownLatch(1)
        // A draft that holds the serial worker, so the release must wait in the queue.
        val engine = object : CountingEngine() {
            override fun run(task: InferenceTask): ModelProposal {
                workerBusy.countDown()
                check(gate.await(5, TimeUnit.SECONDS))
                return super.run(task)
            }
        }
        val exp = experiment(engine)
        exp.setForeground(true)
        val draft = CompletableFuture<LocalDraftResult>()
        exp.draftAsync(request()) { draft.complete(it) }
        assertTrue(workerBusy.await(5, TimeUnit.SECONDS))

        val ticket = exp.release()
        assertEquals(ReleaseStatus.Queued, ticket.status, "release must report QUEUED while the worker is busy")
        assertEquals(0, engine.unloads.get(), "nothing is freed before the worker processes the release")

        gate.countDown()
        assertEquals(ReleaseStatus.Released, ticket.await(5_000))
        assertTrue(ticket.completedAfterMillis >= 0)
        assertEquals(1, engine.unloads.get())
        draft.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun quarantinedAndFailedReleasesAreDistinguished() {
        val quarantining = object : CountingEngine() {
            override fun unloadWithOutcome(): UnloadOutcome = UnloadOutcome.Quarantined("native still running")
            override val isQuarantined: Boolean get() = true
        }
        val q = experiment(quarantining)
        val qStatus = q.release().await(5_000)
        assertIs<ReleaseStatus.Quarantined>(qStatus)
        assertEquals("QUARANTINED", qStatus.label)

        val failing = object : CountingEngine() {
            override fun unload() { throw IllegalStateException("close failed") }
        }
        val f = experiment(failing)
        val fStatus = f.release().await(5_000)
        assertIs<ReleaseStatus.Failed>(fStatus)
        assertTrue(fStatus.reason.contains("close failed"))
    }

    @Test
    fun releaseListenerReceivesCompletion() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        val delivered = CompletableFuture<ReleaseTicket>()
        exp.setReleaseListener { delivered.complete(it) }
        val ticket = exp.release()
        val got = delivered.get(5, TimeUnit.SECONDS)
        assertSame(ticket, got)
        assertEquals(ReleaseStatus.Released, got.status)
    }

    @Test
    fun awaitingReleaseOnMainThreadIsRejected() {
        val exp = experiment(CountingEngine(), isMain = { true })
        val ticket = exp.release()
        assertFailsWith<IllegalStateException> { ticket.await(10) }
    }

    @Test
    fun releaseKeepsFailurePause() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)
        assertEquals(ReleaseStatus.Released, exp.release().await(5_000))
        assertTrue(exp.needsRecovery, "release must not silently clear repeated-failure protection")
        val runsBefore = engine.runs.get()
        val blocked = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Failed>(blocked)
        assertTrue(blocked.needsRecovery)
        assertEquals(runsBefore, engine.runs.get())
    }

    @Test
    fun backgroundingKeepsFailurePause() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)
        exp.setForeground(false)
        exp.setForeground(true)
        assertEquals(ReleaseStatus.Released, exp.lastRelease!!.await(5_000))
        assertTrue(exp.needsRecovery, "leaving and returning must not bypass Retry local AI")
    }

    // ── Asynchronous Retry local AI ──────────────────────────────────────────

    @Test
    fun retryRunsOnWorkerNotCallerAndDoesNotLoad() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)
        val loadsBefore = engine.loads.get()
        val callerThread = Thread.currentThread()
        val callbackThread = CompletableFuture<Thread>()
        val result = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { callbackThread.complete(Thread.currentThread()); result.complete(it) }
        assertEquals(RecoveryResult.Recovered, result.get(5, TimeUnit.SECONDS))
        assertNotSame(callerThread, callbackThread.get(), "recovery must run on the inference worker")
        assertEquals(loadsBefore, engine.loads.get(), "recovery must not load the model")
        assertFalse(exp.needsRecovery)

        val draft = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Success>(draft)
        assertEquals(loadsBefore + 1, engine.loads.get(), "next valid draft loads lazily, once")
    }

    @Test
    fun retryIsRefusedInBackgroundAndUnderPressure() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)

        exp.setForeground(false)
        val bg = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { bg.complete(it) }
        assertEquals(RecoveryResult.Background, bg.get(5, TimeUnit.SECONDS))

        exp.setForeground(true)
        exp.onMemoryPressure()
        val mp = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { mp.complete(it) }
        assertEquals(RecoveryResult.MemoryPressure, mp.get(5, TimeUnit.SECONDS))
        assertTrue(exp.needsRecovery || engine.runs.get() >= 3)
    }

    @Test
    fun pausePersistsThroughPressureAndClearsOnlyWithRetry() {
        val engine = CountingEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)
        assertEquals(ReleaseStatus.Released, exp.onMemoryPressure().await(5_000))
        exp.setForeground(false)
        exp.setForeground(true)
        val state = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { state.complete(it) }
        assertEquals(RecoveryResult.Recovered, state.get(5, TimeUnit.SECONDS),
            "pause must survive pressure and be cleared only by an eligible retry")
    }

    @Test
    fun retryWhenHealthyIsNotNeeded() {
        val exp = experiment(CountingEngine())
        exp.setForeground(true)
        val r = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { r.complete(it) }
        assertEquals(RecoveryResult.NotNeeded, r.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun quarantinedRuntimeRequiresRestartAndIsNeverRecovered() {
        val engine = object : CountingEngine() {
            @Volatile var quarantined = false
            override val isQuarantined: Boolean get() = quarantined
        }
        val exp = experiment(engine)
        exp.setForeground(true)
        degrade(exp, engine)
        engine.quarantined = true
        val r = CompletableFuture<RecoveryResult>()
        exp.retryLocalAi { r.complete(it) }
        assertEquals(RecoveryResult.RestartRequired, r.get(5, TimeUnit.SECONDS))
        val draft = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Failed>(draft)
        assertTrue(draft.restartRequired)
    }

    // ── Cancellation is not a model failure ──────────────────────────────────

    @Test
    fun repeatedCancellationsNeverPauseLocalAi() {
        val engine = object : CountingEngine() {
            override fun run(task: InferenceTask): ModelProposal {
                runs.incrementAndGet()
                throw java.util.concurrent.CancellationException("inference cancelled")
            }
        }
        val exp = experiment(engine)
        exp.setForeground(true)
        repeat(5) {
            val r = exp.draftBlocking(request())
            assertIs<LocalDraftResult.Failed>(r)
            assertTrue(r.cancelled, "cancellation must be reported as cancelled")
            assertFalse(r.needsRecovery)
        }
        assertFalse(exp.needsRecovery, "cancellations must not count toward repeated-failure protection")
    }

    @Test
    fun missingModelIsNotReportedAsRecoverableOrSuccessful() {
        val engine = object : CountingEngine() {
            override fun load(modelId: String): Boolean { loads.incrementAndGet(); return false }
        }
        val exp = experiment(engine)
        exp.setForeground(true)
        val r = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Failed>(r)
        assertTrue(r.reason.contains("not installed"))
        assertFalse(r.cancelled)
        assertEquals(0, engine.runs.get())
    }

    private fun request(): LocalDraftRequest {
        val account = AccountId(Provider.WHATSAPP, "lifecycle-test")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "c1")
        val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "1")
        val compartment = CompartmentId(account.key)
        val observation = ObservedNotification(
            id, account, conversation, "lc.local", "lc-key", 1, null,
            "Sender", "Chat", listOf("Hello"), "digest",
            UtcInstant(1_760_000_000_000L), UtcInstant(1_760_000_000_000L), 1L, false, false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.SyntheticFixture,
        )
        return LocalDraftRequest(
            "Chat", "Sender", compartment,
            ContextBundle(compartment, listOf(EvidenceRef(SourceId(id.key), 1L, compartment)), 30, false),
            listOf(observation), "", "Acknowledge.",
        )
    }
}
