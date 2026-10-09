package app.mini.hub.android.intelligence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.*
import app.mini.hub.observation.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Observable fake-engine tests proving foreground eligibility, lazy recovery,
 * and background/pressure blocking behaviors.
 */
class ForegroundRecoveryTest {

    /** Counts load() and run() calls so tests can assert the model was never touched. */
    private class ObservableEngine : DeterministicInferenceEngine() {
        val loads = AtomicInteger()
        val runs = AtomicInteger()
        var throwOnRun = false
        override fun load(modelId: String): Boolean { loads.incrementAndGet(); return super.load(modelId) }
        override fun run(task: InferenceTask): ModelProposal {
            runs.incrementAndGet()
            if (throwOnRun) throw IllegalStateException("engine failure")
            return ModelProposal(task.id, task.compartment, "Thanks for the update.", task.context.evidence)
        }
    }

    private fun experiment(engine: ObservableEngine): LocalDraftExperiment {
        val executor = Executors.newSingleThreadExecutor()
        return LocalDraftExperiment(engine, "synthetic-model", executor, System::currentTimeMillis)
    }

    private fun request(): LocalDraftRequest {
        val account = AccountId(Provider.WHATSAPP, "fg-recovery-test")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "c1")
        val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "1")
        val compartment = CompartmentId(account.key)
        val observation = ObservedNotification(
            id, account, conversation, "fg.local", "fg-key", 1, null,
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

    // ── Test 1: Initial background requests never load or run the model ──────

    @Test
    fun initialBackgroundRequestNeverLoadsOrRunsModel() {
        val engine = ObservableEngine()
        val exp = experiment(engine)
        // Default state is background (isForeground = false).
        // A draft request must be refused without touching the engine.
        val result = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Refused>(result)
        assertTrue(result.reason.contains("background"))
        assertEquals(0, engine.loads.get(), "engine.load must not be called while in background")
        assertEquals(0, engine.runs.get(), "engine.run must not be called while in background")
        exp.release()
    }

    // ── Test 2: Becoming foreground does not load the model ──────────────────

    @Test
    fun becomingForegroundDoesNotLoadModel() {
        val engine = ObservableEngine()
        val exp = experiment(engine)
        exp.setForeground(true)
        // Drain the serial worker so any queued foreground transition has executed.
        assertFalse(exp.recoverFromDegraded())
        assertEquals(0, engine.loads.get(), "setForeground(true) must not eagerly load the model")
        assertEquals(0, engine.runs.get())
        exp.release()
    }

    // ── Test 3: Repeated failures stop ordinary retries ──────────────────────

    @Test
    fun repeatedFailuresBlockSubsequentDrafts() {
        val engine = ObservableEngine()
        engine.throwOnRun = true
        val exp = experiment(engine)
        exp.setForeground(true)

        // Drive enough failures to push the supervisor into Degraded.
        // The supervisor's maxFailureThreshold defaults to 3.
        for (i in 1..3) {
            val r = exp.draftBlocking(request())
            assertIs<LocalDraftResult.Failed>(r)
        }
        assertTrue(engine.runs.get() >= 3, "engine.run should have been called at least 3 times")

        // Now the supervisor is Degraded. Further requests fail without running.
        val runsBefore = engine.runs.get()
        val blocked = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Failed>(blocked)
        assertEquals(runsBefore, engine.runs.get(), "Degraded supervisor must not run additional tasks")
        exp.release()
    }

    // ── Test 4: Explicit eligible recovery does not load immediately ─────────

    @Test
    fun explicitRecoveryDoesNotLoadModel() {
        val engine = ObservableEngine()
        engine.throwOnRun = true
        val exp = experiment(engine)
        exp.setForeground(true)

        // Push into Degraded
        for (i in 1..3) exp.draftBlocking(request())

        // Reset engine to healthy, then recover
        engine.throwOnRun = false
        val loadsBefore = engine.loads.get()
        assertTrue(exp.recoverFromDegraded(), "recoverFromDegraded should succeed while foreground and Degraded")
        assertEquals(loadsBefore, engine.loads.get(), "recoverFromDegraded must not load the model")
        exp.release()
    }

    // ── Test 5: The next valid request after recovery successfully generates ─

    @Test
    fun nextRequestAfterRecoverySucceeds() {
        val engine = ObservableEngine()
        engine.throwOnRun = true
        val exp = experiment(engine)
        exp.setForeground(true)

        // Push into Degraded
        for (i in 1..3) exp.draftBlocking(request())

        // Recover (lazy), then fix the engine
        engine.throwOnRun = false
        assertTrue(exp.recoverFromDegraded())

        // Next request should trigger exactly one lazy load and run successfully
        val loadsBefore = engine.loads.get()
        val result = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Success>(result)
        assertEquals(loadsBefore + 1, engine.loads.get(), "next request must lazily load once")
        assertTrue(engine.runs.get() > 3, "engine.run should have been called for the successful draft")
        exp.release()
    }

    // ── Test 6: Background/pressured recovery stays blocked ──────────────────

    @Test
    fun pressuredRecoveryWhileDegradedStaysBlocked() {
        val engine = ObservableEngine()
        engine.throwOnRun = true
        val exp = experiment(engine)
        exp.setForeground(true)
        for (i in 1..3) exp.draftBlocking(request())
        engine.throwOnRun = false

        exp.onMemoryPressure()
        val loadsBefore = engine.loads.get()
        val runsBefore = engine.runs.get()
        assertFalse(exp.recoverFromDegraded(), "recovery must be blocked under memory pressure")
        val draft = exp.draftBlocking(request())
        assertIs<LocalDraftResult.Failed>(draft)
        assertTrue(draft.reason.contains("pressured"))
        assertEquals(loadsBefore, engine.loads.get(), "blocked recovery must not load the model")
        assertEquals(runsBefore, engine.runs.get(), "pressured draft must not run the model")
        exp.release()
    }

    @Test
    fun backgroundRecoveryStaysBlocked() {
        val engine = ObservableEngine()
        val exp = experiment(engine)
        // Initial background, then an explicit foreground-to-background transition.
        assertFalse(exp.recoverFromDegraded(), "recovery must be blocked in initial background")
        exp.setForeground(true)
        exp.setForeground(false)
        assertFalse(exp.recoverFromDegraded(), "recovery must be blocked after backgrounding")
        assertIs<LocalDraftResult.Refused>(exp.draftBlocking(request()))
        assertEquals(0, engine.loads.get(), "background recovery must not load the model")
        assertEquals(0, engine.runs.get(), "background draft must not run the model")
        exp.release()
    }
}
