package app.mini.hub.style

/**
 * Deterministic, on-device draft realization used to prove personalization changes the text.
 * Facts come only from supplied evidence and the user intent. Dates, prices and commitments
 * are never invented from style examples.
 */
object StyleAwareDraftComposer {
    fun compose(
        userIntent: String,
        evidenceTexts: List<String>,
        profile: CompactStyleProfile?,
        understandingNote: String? = null,
    ): String {
        if (evidenceTexts.none { it.isNotBlank() }) {
            return "I need more of the conversation before I can draft a reply."
        }
        val intent = userIntent.trim()
        val traits = profile?.traits
        val formality = traits?.formality ?: 0.5
        val casual = formality <= 0.35
        val formal = formality >= 0.6
        val brief = (traits?.brevity ?: 0.5) >= 0.7
        val emoji = (traits?.emojiFrequency ?: 0.0) >= 0.4 && casual
        val timingNote = if (understandingNote?.contains("deadline", ignoreCase = true) == true) {
            " I noticed a timing mention in the latest message and will not confirm a date that is not in the evidence."
        } else {
            ""
        }
        val intentClause = if (intent.isBlank()) {
            "I'll reply based on the latest message."
        } else {
            intent.trimEnd('.')
        }
        val body = when {
            formal -> {
                val open = if (brief) "Thank you for the update." else "Thank you for the update. I have read the latest message."
                "$open $intentClause. I will follow up with confirmed details only."
            }
            casual -> {
                val open = if (brief) "Hey, thanks for the ping" else "Hey, thanks for sending this over"
                "$open - $intentClause and I'll get back to you."
            }
            else -> "Thanks. $intentClause."
        }
        val grounded = "$body$timingNote".replace(Regex("\\s+"), " ").trim()
        return if (emoji) "$grounded 😊" else grounded
    }

    fun promptLines(profile: CompactStyleProfile): List<String> = listOf(
        "Learned local style (use as a preference, not as facts):",
        "category=${profile.category.name} confidence=${"%.2f".format(profile.confidence)} observations=${profile.observationCount}",
        "formality=${"%.2f".format(profile.traits.formality)} brevity=${"%.2f".format(profile.traits.brevity)} warmth=${"%.2f".format(profile.traits.warmth)} emojiFrequency=${"%.2f".format(profile.traits.emojiFrequency)}",
        "intro=${profile.traits.introStyle} signoff=${profile.traits.signoffStyle}",
    ) + profile.exampleSnippets.take(2).mapIndexed { index, example -> "style example ${index + 1}: $example" }
}
