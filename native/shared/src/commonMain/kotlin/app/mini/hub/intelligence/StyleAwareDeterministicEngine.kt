package app.mini.hub.intelligence

import app.mini.hub.domain.UtcInstant
import app.mini.hub.style.CompactStyleProfile
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleAwareDraftComposer
import app.mini.hub.style.StyleTraits

/** Test and fixture engine whose output changes with the learned style encoded in the prompt. */
class StyleAwareDeterministicEngine : DeterministicInferenceEngine() {
    override fun run(task: InferenceTask): ModelProposal {
        check(loadedModel != null) { "model is not loaded" }
        check(task.context.inputTokens <= task.budget.inputTokens) { "input budget exceeded" }
        val formality = numberAfter(task.prompt, "formality=") ?: 0.5
        val brevity = numberAfter(task.prompt, "brevity=") ?: 0.5
        val warmth = numberAfter(task.prompt, "warmth=") ?: 0.5
        val emoji = numberAfter(task.prompt, "emojiFrequency=") ?: 0.0
        val category = if (formality >= 0.6) ContextCategory.Professional else ContextCategory.Friends
        val profile = CompactStyleProfile(
            id = "prompt-style",
            category = category,
            recipientId = null,
            traits = StyleTraits(formality, brevity, warmth, emoji),
            updatedAt = UtcInstant(0L),
        )
        val evidence = task.prompt.lineSequence()
            .filter { it.startsWith("[") && "]" in it }
            .map { it.substringAfter(": ").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val intent = task.prompt.substringAfter("User reply intent:", "")
            .substringBefore("Return only")
            .trim()
        val understanding = task.prompt.lineSequence()
            .firstOrNull { it.startsWith("Local understanding") }
            ?.substringAfter(':')
            ?.trim()
        val text = StyleAwareDraftComposer.compose(intent, evidence, profile, understanding)
        return ModelProposal(task.id, task.compartment, text, task.context.evidence)
    }

    private fun numberAfter(text: String, key: String): Double? =
        Regex("$key([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
}
