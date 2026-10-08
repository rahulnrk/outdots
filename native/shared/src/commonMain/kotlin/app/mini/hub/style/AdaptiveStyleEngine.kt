package app.mini.hub.style

import app.mini.hub.domain.UtcInstant

enum class ContextCategory {
    Professional,
    Family,
    Friends,
    Marketing,
    Negotiations,
    Custom,
}

enum class TextAuthorship {
    UserAuthoredSentMessage,
    LocallyEditedDraft,
    InboundMessage,
    UneditedAiDraft,
    Unknown,
}

data class StyleTraits(
    val formality: Double = 0.5,
    val brevity: Double = 0.5,
    val warmth: Double = 0.5,
    val emojiFrequency: Double = 0.0,
    val introStyle: String = "Neutral greeting",
    val signoffStyle: String = "Neutral signoff",
    val codeSwitchPreference: String = "None",
) {
    init {
        require(formality in 0.0..1.0)
        require(brevity in 0.0..1.0)
        require(warmth in 0.0..1.0)
        require(emojiFrequency in 0.0..1.0)
    }
}

data class CompactStyleProfile(
    val id: String,
    val category: ContextCategory,
    val recipientId: String?,
    val traits: StyleTraits,
    val exampleSnippets: List<String> = emptyList(),
    val observationCount: Int = 0,
    val confidence: Double = 0.0,
    val consentVersion: Int = 1,
    val updatedAt: UtcInstant,
)

data class StyleConsent(
    val isEnabled: Boolean = false,
    val consentVersion: Int = 1,
    val grantedCategories: Set<ContextCategory> = emptySet(),
    val allowCodeSwitchLearning: Boolean = false,
)

/**
 * Privacy-first adaptive personal communication style engine.
 * Only learns from authentic user-authored outgoing messages or locally edited drafts
 * with explicit user consent.
 */
class AdaptiveStyleEngine(
    private var consent: StyleConsent = StyleConsent(isEnabled = false),
) {
    private val lock = Any()
    private val profiles = LinkedHashMap<String, CompactStyleProfile>()

    companion object {
        const val MIN_RECIPIENT_CONFIDENCE: Double = 0.40
    }

    fun getConsent(): StyleConsent = synchronized(lock) { consent }

    fun updateConsent(newConsent: StyleConsent) = synchronized(lock) {
        consent = newConsent
        if (!newConsent.isEnabled) {
            profiles.clear()
        }
    }

    /**
     * Attempts to learn style traits from a message sample.
     * Rejects inbound messages, unedited drafts, unauthorized categories, or unconsented operations.
     */
    fun learnFromText(
        text: String,
        authorship: TextAuthorship,
        category: ContextCategory,
        recipientId: String?,
        now: UtcInstant,
    ): Boolean = synchronized(lock) {
        if (!consent.isEnabled) return false
        if (category !in consent.grantedCategories) return false
        if (authorship != TextAuthorship.UserAuthoredSentMessage && authorship != TextAuthorship.LocallyEditedDraft) {
            return false // Inbound messages and unedited AI drafts NEVER teach style!
        }
        if (text.isBlank()) return false

        // Redact potential secrets/sensitive numeric sequences
        val sanitized = redactSensitiveData(text)
        if (isAdversarialInjection(sanitized)) return false

        val profileKey = if (recipientId != null) "recip:$category:$recipientId" else "cat:$category"
        val existing = profiles[profileKey]

        val emojiCount = countEmojis(sanitized)
        val wordCount = sanitized.trim().split(Regex("\\s+")).size
        val hasEmoji = emojiCount > 0

        val newEmojiFreq = if (hasEmoji) 0.8 else 0.0
        val newBrevity = if (wordCount <= 15) 0.9 else if (wordCount <= 50) 0.5 else 0.2
        val newFormality = when (category) {
            ContextCategory.Professional -> 0.8
            ContextCategory.Family, ContextCategory.Friends -> 0.2
            ContextCategory.Marketing -> 0.6
            else -> 0.5
        }

        val updatedCount = (existing?.observationCount ?: 0) + 1
        val updatedConfidence = (updatedCount.toDouble() / (updatedCount + 3.0)).coerceIn(0.1, 0.95)

        val updatedTraits = if (existing != null) {
            StyleTraits(
                formality = (existing.traits.formality * 0.7 + newFormality * 0.3).coerceIn(0.0, 1.0),
                brevity = (existing.traits.brevity * 0.7 + newBrevity * 0.3).coerceIn(0.0, 1.0),
                warmth = existing.traits.warmth,
                emojiFrequency = (existing.traits.emojiFrequency * 0.7 + newEmojiFreq * 0.3).coerceIn(0.0, 1.0),
                introStyle = existing.traits.introStyle,
                signoffStyle = existing.traits.signoffStyle,
                codeSwitchPreference = existing.traits.codeSwitchPreference,
            )
        } else {
            StyleTraits(
                formality = newFormality,
                brevity = newBrevity,
                warmth = if (category == ContextCategory.Family || category == ContextCategory.Friends) 0.8 else 0.5,
                emojiFrequency = newEmojiFreq,
            )
        }

        val examples = buildList {
            if (existing != null) addAll(existing.exampleSnippets.take(2))
            if (sanitized.length in 10..150) add(sanitized)
        }.distinct().take(3)

        val updatedProfile = CompactStyleProfile(
            id = profileKey,
            category = category,
            recipientId = recipientId,
            traits = updatedTraits,
            exampleSnippets = examples,
            observationCount = updatedCount,
            confidence = updatedConfidence,
            consentVersion = consent.consentVersion,
            updatedAt = now,
        )

        profiles[profileKey] = updatedProfile
        return true
    }

    fun resolveProfile(category: ContextCategory, recipientId: String?, now: UtcInstant = UtcInstant(0L)): CompactStyleProfile = synchronized(lock) {
        if (!consent.isEnabled) {
            return defaultNeutralProfile(category, recipientId, now)
        }

        // Recipient profile requires sufficient evidence (confidence >= 0.40 / 2+ samples)
        if (recipientId != null) {
            val recipKey = "recip:$category:$recipientId"
            val recipProfile = profiles[recipKey]
            if (recipProfile != null && recipProfile.confidence >= MIN_RECIPIENT_CONFIDENCE) {
                return recipProfile
            }
        }

        val catKey = "cat:$category"
        val catProfile = profiles[catKey]
        if (catProfile != null) {
            return catProfile
        }

        return defaultNeutralProfile(category, recipientId, now)
    }

    fun getRecipientProfile(category: ContextCategory, recipientId: String): CompactStyleProfile? = synchronized(lock) {
        profiles["recip:$category:$recipientId"]
    }

    fun resetRecipient(recipientId: String) = synchronized(lock) {
        val keysToRemove = profiles.keys.filter { it.endsWith(":$recipientId") }
        keysToRemove.forEach { profiles.remove(it) }
    }

    fun clearAllStyleData() = synchronized(lock) {
        profiles.clear()
    }

    private fun defaultNeutralProfile(category: ContextCategory, recipientId: String?, now: UtcInstant): CompactStyleProfile {
        return CompactStyleProfile(
            id = "neutral:$category",
            category = category,
            recipientId = recipientId,
            traits = StyleTraits(
                formality = if (category == ContextCategory.Professional) 0.7 else 0.4,
                brevity = 0.6,
                warmth = 0.5,
                emojiFrequency = 0.0,
                introStyle = "Neutral greeting",
                signoffStyle = "Omit on short replies",
            ),
            exampleSnippets = emptyList(),
            observationCount = 0,
            confidence = 0.0,
            consentVersion = consent.consentVersion,
            updatedAt = now,
        )
    }

    private fun redactSensitiveData(text: String): String {
        return text
            .replace(Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"), "[CARD_REDACTED]")
            .replace(Regex("\\b\\d{6}\\b"), "[CODE_REDACTED]")
            .replace(Regex("(?i)(password|secret|pin)[:=]\\s*\\S+"), "$1=[REDACTED]")
    }

    private fun isAdversarialInjection(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("system override") ||
            lower.contains("ignore prior instructions") ||
            lower.contains("ignore previous instructions") ||
            lower.contains("change system prompt")
    }

    private fun countEmojis(text: String): Int {
        var count = 0
        for (ch in text) {
            val type = Character.getType(ch.code)
            if (type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()) {
                count++
            }
        }
        return count
    }
}
