package app.mini.hub.intelligence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntelligenceTest {
    private val scope = CompartmentId("mail:a")
    private val other = CompartmentId("mail:b")
    private val evidence = EvidenceRef(SourceId("message-1"), 1, scope)
    private fun task(id: String, budget: InferenceBudget = InferenceBudget(100, 20)) =
        InferenceTask(id, scope, ContextBundle(scope, listOf(evidence), 10), budget, "summarize")

    @Test fun proposalRequiresEvidenceAndRejectsInjectionScope() {
        val t = task("evidence")
        val validator = ProposalValidator()
        assertEquals(ProposalRejection.MissingEvidence, validator.validate(ModelProposal(t.id, scope, "ignore rules", emptyList()), t).rejection)
        val foreign = EvidenceRef(SourceId("secret"), 1, other)
        assertEquals(ProposalRejection.UnknownEvidence, validator.validate(ModelProposal(t.id, scope, "leak", listOf(foreign)), t).rejection)
    }

    @Test fun budgetsAreBounded() {
        assertFails { InferenceBudget(4097, 1) }
        assertFails { InferenceBudget(1, 257) }
        val engine = DeterministicInferenceEngine()
        engine.load("model")
        assertFails { engine.run(task("over", InferenceBudget(5, 20))) }
    }

    @Test fun cancellationPreventsQueuedWork() {
        val supervisor = ResourceSupervisor(DeterministicInferenceEngine(), "model")
        val t = task("cancel")
        assertEquals(SubmitResult.Accepted, supervisor.submit(t))
        assertTrue(supervisor.cancel(t.id))
        assertEquals(null, supervisor.runNext())
    }

    @Test fun modelLoadsOnceAndIsShared() {
        class CountingEngine : DeterministicInferenceEngine() {
            var loads = 0
            override fun load(modelId: String): Boolean { loads++; return super.load(modelId) }
        }
        val engine = CountingEngine()
        val supervisor = ResourceSupervisor(engine, "one-model")
        assertEquals(SubmitResult.Accepted, supervisor.submit(task("one")))
        assertEquals(SubmitResult.Accepted, supervisor.submit(task("two")))
        assertEquals(1, engine.loads)
    }

    @Test fun crossCompartmentScopeIsRejected() {
        val supervisor = ResourceSupervisor(DeterministicInferenceEngine(), "model")
        val foreignTask = InferenceTask("foreign", other, ContextBundle(scope, listOf(evidence), 10), InferenceBudget(100, 20), "read")
        assertEquals(SubmitResult.ScopeRejected, supervisor.submit(foreignTask))
    }

    @Test fun pressureClearsResourcesAndBlocksUntilResumed() {
        val engine = DeterministicInferenceEngine()
        val supervisor = ResourceSupervisor(engine, "model")
        supervisor.submit(task("pressure"))
        supervisor.onMemoryPressure()
        assertFalse(supervisor.ensureReady())
        assertEquals(0, supervisor.queuedCount)
        supervisor.resumeAfterPressure()
        assertTrue(supervisor.ensureReady())
    }

    @Test fun deviceResourceBudgetsAreEnforced() {
        assertEquals("Tier1LowEnd", DeviceResourceBudget.Tier1LowEnd.tierName)
        assertEquals(512, DeviceResourceBudget.Tier1LowEnd.maxResidentMemoryMb)
        assertEquals(64, DeviceResourceBudget.Tier1LowEnd.maxQueueSize)
        assertEquals(8, DeviceResourceBudget.Tier1LowEnd.maxProposalCacheEntries)

        assertEquals("Tier2MidRange", DeviceResourceBudget.Tier2MidRange.tierName)
        assertEquals("Tier3HighEnd", DeviceResourceBudget.Tier3HighEnd.tierName)

        // Invalid budget values fail fast
        assertFails { DeviceResourceBudget("BadRAM", 64, 10, 5, 500, 50, 1000L) }
        assertFails { DeviceResourceBudget("BadQueue", 1024, 0, 5, 500, 50, 1000L) }
        assertFails { DeviceResourceBudget("BadTokens", 1024, 50, 5, 10000, 50, 1000L) }
    }

    @Test fun repeatedEngineFailuresTriggerDegradedStateAndRecovery() {
        class FlakyEngine : DeterministicInferenceEngine() {
            var throwError = true
            override fun run(task: InferenceTask): ModelProposal {
                if (throwError) throw IllegalStateException("Backend crash")
                return super.run(task)
            }
        }

        val flaky = FlakyEngine()
        val supervisor = ResourceSupervisor(
            engine = flaky,
            modelId = "test-model",
            budget = DeviceResourceBudget.Tier1LowEnd,
            maxFailureThreshold = 2,
        )

        val t1 = task("fail-1")
        val t2 = task("fail-2")
        val t3 = task("fail-3")

        supervisor.submit(t1)
        assertEquals(null, supervisor.runNext())
        assertEquals(1, supervisor.consecutiveFailures)
        assertTrue(supervisor.state is SupervisorState.Ready)

        supervisor.submit(t2)
        assertEquals(null, supervisor.runNext())
        assertEquals(2, supervisor.consecutiveFailures)
        assertTrue(supervisor.state is SupervisorState.Degraded)

        // Further submission blocked when Degraded
        assertEquals(SubmitResult.Pressured, supervisor.submit(t3))

        // Recovery resets to Unloaded (lazy, no model load yet)
        flaky.throwError = false
        supervisor.recoverFromFailure()
        assertEquals(0, supervisor.consecutiveFailures)
        assertTrue(supervisor.state is SupervisorState.Unloaded)
        // Next submit triggers lazy load and succeeds
        assertEquals(SubmitResult.Accepted, supervisor.submit(t3))
        val proposal = supervisor.runNext()
        assertNotNull(proposal)
        assertEquals(t3.id, proposal.taskId)
    }

    @Test fun qualityEvaluationHarnessEvaluatesStandardSuite() {
        val engine = DeterministicInferenceEngine()
        val supervisor = ResourceSupervisor(engine, "model")
        val cases = QualityEvaluationHarness.standardSuite(scope)
        val harness = QualityEvaluationHarness()

        val report = harness.evaluate(supervisor, cases)
        assertEquals(5, report.totalCases)
        // All safety requirements met (no unintended sends)
        assertEquals(1.0, report.safetyPassRate)
        assertTrue(report.passedCount >= 4)
    }

    @Test fun intelligenceBenchmarkExecutesSuccessfullyAndEmitsReport() {
        val benchmark = IntelligenceBenchmark(
            BenchmarkConfig(
                scenarioName = "TestBenchmark",
                warmupIterations = 2,
                measuredIterations = 5,
                budget = DeviceResourceBudget.Tier1LowEnd,
            )
        )
        val result = benchmark.run(
            engineFactory = { DeterministicInferenceEngine() },
            modelId = "benchmark-model",
        )

        assertEquals("TestBenchmark", result.scenarioName)
        assertEquals("Tier1LowEnd", result.tier)
        assertEquals(5, result.tasksCompleted)
        assertEquals(0, result.failures)
        assertTrue(result.avgInferenceLatencyMillis >= 0.0)

        val markdown = result.toLedgerMarkdown()
        assertTrue(markdown.contains("Hardware Tier"))
        assertTrue(markdown.contains("Avg Inference Latency"))
        assertTrue(markdown.contains("Tier1LowEnd"))
    }
}
