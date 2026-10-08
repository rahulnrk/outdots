package app.mini.hub.intelligence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
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
        assertTrue(supervisor.resumeAfterPressure())
    }
}
