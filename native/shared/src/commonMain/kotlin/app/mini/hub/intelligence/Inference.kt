package app.mini.hub.intelligence

/** A stable identifier for evidence already admitted by trusted retrieval code. */
@JvmInline
value class SourceId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class CompartmentId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class EvidenceRef(val sourceId: SourceId, val revision: Long, val compartment: CompartmentId)

data class ContextBundle(
    val compartment: CompartmentId,
    val evidence: List<EvidenceRef>,
    val inputTokens: Int,
    val hasMissingContext: Boolean = false,
)

data class InferenceBudget(val inputTokens: Int, val outputTokens: Int) {
    init {
        require(inputTokens in 1..4096)
        require(outputTokens in 1..256)
    }
}

data class InferenceTask(
    val id: String,
    val compartment: CompartmentId,
    val context: ContextBundle,
    val budget: InferenceBudget,
    val prompt: String,
) {
    init { require(id.isNotBlank()); require(prompt.isNotBlank()) }
}

data class ModelProposal(
    val taskId: String,
    val compartment: CompartmentId,
    val text: String,
    val evidence: List<EvidenceRef>,
    val proposedAction: ProposedAction = ProposedAction.None,
)

sealed class ProposedAction {
    data object None : ProposedAction()
    data class Draft(val recipient: String? = null) : ProposedAction()
}

enum class ProposalRejection { EmptyText, MissingEvidence, UnknownEvidence, CrossCompartment, BudgetExceeded, ForbiddenAction }

data class ProposalValidation(val accepted: Boolean, val rejection: ProposalRejection? = null)

/** Validates untrusted model output. It never authorizes or executes an action. */
class ProposalValidator {
    fun validate(proposal: ModelProposal, task: InferenceTask): ProposalValidation {
        if (proposal.taskId != task.id || proposal.compartment != task.compartment) return ProposalValidation(false, ProposalRejection.CrossCompartment)
        if (proposal.text.isBlank()) return ProposalValidation(false, ProposalRejection.EmptyText)
        if (proposal.evidence.isEmpty()) return ProposalValidation(false, ProposalRejection.MissingEvidence)
        val allowed = task.context.evidence.toSet()
        if (proposal.evidence.any { it !in allowed }) return ProposalValidation(false, ProposalRejection.UnknownEvidence)
        if (proposal.evidence.any { it.compartment != task.compartment }) return ProposalValidation(false, ProposalRejection.CrossCompartment)
        return ProposalValidation(true)
    }
}

interface InferenceEngine {
    fun load(modelId: String): Boolean
    fun run(task: InferenceTask): ModelProposal
    fun cancel(taskId: String): Boolean
    fun unload()
}

/** Deterministic backend-free engine useful for wiring and tests, not a real model. */
open class DeterministicInferenceEngine : InferenceEngine {
    private val cancelled = mutableSetOf<String>()
    var loadedModel: String? = null
        private set
    open override fun load(modelId: String): Boolean {
        if (loadedModel == null) loadedModel = modelId
        return loadedModel == modelId
    }
    override fun run(task: InferenceTask): ModelProposal {
        check(loadedModel != null) { "model is not loaded" }
        check(task.context.inputTokens <= task.budget.inputTokens) { "input budget exceeded" }
        check(task.id !in cancelled) { "task cancelled" }
        return ModelProposal(task.id, task.compartment, "Deterministic proposal for ${task.id}", task.context.evidence)
    }
    override fun cancel(taskId: String): Boolean = cancelled.add(taskId)
    override fun unload() { loadedModel = null; cancelled.clear() }
}
