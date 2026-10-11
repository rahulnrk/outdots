package app.mini.hub.retrieval

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.intelligence.ContextBundle
import app.mini.hub.intelligence.EvidenceRef
import app.mini.hub.intelligence.SourceId
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.persistence.ObservationStore

data class ScopedRetrievalQuery(
    val conversationId: ScopedId,
    val compartment: CompartmentId,
    val tokenBudget: Int = 650,
    val maxResults: Int = 50,
) {
    init {
        require(tokenBudget in 50..4096) { "tokenBudget must be in 50..4096" }
        require(maxResults in 1..200) { "maxResults must be in 1..200" }
    }
}

data class ScopedRetrievalResult(
    val contextBundle: ContextBundle,
    val retrievedObservations: List<ObservedNotification>,
    val tokenBudgetUsed: Int,
    val omittedCount: Int,
    val coverageWarning: String,
)

/**
 * Lean, bounded retrieval engine enforcing compartment boundaries and token budgets.
 */
class ScopedRetrievalEngine(
    private val observationStore: ObservationStore,
) {
    fun retrieve(query: ScopedRetrievalQuery): ScopedRetrievalResult {
        // Enforce conversation compartment matches query
        require(query.compartment.value == query.conversationId.account.key) {
            "Compartment ${query.compartment.value} does not match conversation account ${query.conversationId.account.key}"
        }
        val conversationKey = query.conversationId.key

        // Get observations for this conversation
        // Removed (deleted/dismissed) messages stay stored but never become model context.
        val observations = observationStore.getObservationsForConversation(query.conversationId, query.maxResults)
            .filterNot { it.isRemoved }

        val selected = mutableListOf<ObservedNotification>()
        val evidenceList = mutableListOf<EvidenceRef>()
        var tokensUsed = 0
        var omitted = 0

        for (obs in observations) {
            val textCost = estimateTokens(obs.textFragments.joinToString(" ") + " " + (obs.title ?: ""))
            if (tokensUsed + textCost > query.tokenBudget) {
                omitted += (observations.size - selected.size)
                break
            }
            selected.add(obs)
            tokensUsed += textCost
            evidenceList.add(
                EvidenceRef(
                    sourceId = SourceId(obs.id.key),
                    revision = obs.revision,
                    compartment = query.compartment,
                ),
            )
        }

        val hasOmitted = omitted > 0
        val coverageWarning = if (hasOmitted) "Some recent context was omitted due to token budget limits." else ""

        val bundle = ContextBundle(
            compartment = query.compartment,
            evidence = evidenceList,
            inputTokens = tokensUsed,
            hasMissingContext = hasOmitted,
        )

        return ScopedRetrievalResult(
            contextBundle = bundle,
            retrievedObservations = selected,
            tokenBudgetUsed = tokensUsed,
            omittedCount = omitted,
            coverageWarning = coverageWarning,
        )
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val words = text.trim().split(Regex("\\s+")).size
        return (words * 1.35).toInt().coerceAtLeast(1)
    }
}
