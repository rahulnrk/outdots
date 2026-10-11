package app.mini.hub.style

import app.mini.hub.domain.AccountId
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
    private val learnedSampleIds = LinkedHashSet<String>()

    companion object {
        const val MIN_RECIPIENT_CONFIDENCE: Double = 0.40
        const val MAX_LEARNED_SAMPLE_IDS: Int = 500
    }

    fun getConsent(): StyleConsent = synchronized(lock) { consent }

    fun updateConsent(newConsent: StyleConsent) = synchronized(lock) {
        consent = newConsent
        if (!newConsent.isEnabled) {
            profiles.clear()
            learnedSampleIds.clear()
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
        sampleId: String? = null,
        account: AccountId? = null,
    ): Boolean = synchronized(lock) {
        if (!consent.isEnabled) return false
        if (category !in consent.grantedCategories) return false
        if (authorship != TextAuthorship.UserAuthoredSentMessage && authorship != TextAuthorship.LocallyEditedDraft) {
            return false // Inbound messages and unedited AI drafts NEVER teach style!
        }
        if (text.isBlank()) return false
        if (!sampleId.isNullOrBlank() && sampleId in learnedSampleIds) return false

        // Redact potential secrets/sensitive numeric sequences
        val sanitized = redactSensitiveData(text)
        if (isAdversarialInjection(sanitized)) return false

        val accountPrefix = account?.key ?: recipientId?.substringBefore('|')?.takeIf { '|' in recipientId }
        val effectiveRecipient = recipientId
        val profileKey = when {
            effectiveRecipient != null -> {
                val fullRecipientKey = if (accountPrefix != null && !effectiveRecipient.startsWith("$accountPrefix|")) {
                    "$accountPrefix|$effectiveRecipient"
                } else effectiveRecipient
                "recip:$category:$fullRecipientKey"
            }
            accountPrefix != null -> "cat:$accountPrefix:$category"
            else -> "cat:$category"
        }
        val existing = profiles[profileKey]

        val emojiCount = countEmojis(sanitized)
        val wordCount = sanitized.trim().split(Regex("\\s+")).size
        val hasEmoji = emojiCount > 0
        val lower = sanitized.lowercase()

        val newEmojiFreq = if (hasEmoji) 0.8 else 0.0
        val newBrevity = if (wordCount <= 15) 0.9 else if (wordCount <= 50) 0.5 else 0.2
        val categoryFormality = when (category) {
            ContextCategory.Professional -> 0.8
            ContextCategory.Family, ContextCategory.Friends -> 0.2
            ContextCategory.Marketing -> 0.6
            else -> 0.5
        }
        val newFormality = (categoryFormality + formalityDelta(lower, hasEmoji)).coerceIn(0.0, 1.0)
        val newWarmth = when {
            hasEmoji || sanitized.contains('!') || lower.contains("thanks") || lower.contains("hope you") -> 0.8
            category == ContextCategory.Family || category == ContextCategory.Friends -> 0.75
            else -> 0.45
        }
        val intro = introStyle(lower)
        val signoff = signoffStyle(sanitized)

        val updatedCount = (existing?.observationCount ?: 0) + 1
        val updatedConfidence = (updatedCount.toDouble() / (updatedCount + 3.0)).coerceIn(0.1, 0.95)

        val updatedTraits = if (existing != null) {
            StyleTraits(
                formality = (existing.traits.formality * 0.7 + newFormality * 0.3).coerceIn(0.0, 1.0),
                brevity = (existing.traits.brevity * 0.7 + newBrevity * 0.3).coerceIn(0.0, 1.0),
                warmth = (existing.traits.warmth * 0.7 + newWarmth * 0.3).coerceIn(0.0, 1.0),
                emojiFrequency = (existing.traits.emojiFrequency * 0.7 + newEmojiFreq * 0.3).coerceIn(0.0, 1.0),
                introStyle = intro,
                signoffStyle = signoff,
                codeSwitchPreference = existing.traits.codeSwitchPreference,
            )
        } else {
            StyleTraits(
                formality = newFormality,
                brevity = newBrevity,
                warmth = newWarmth,
                emojiFrequency = newEmojiFreq,
                introStyle = intro,
                signoffStyle = signoff,
            )
        }

        val examples = buildList {
            if (existing != null) addAll(existing.exampleSnippets.take(2))
            if (sanitized.length in 10..150) add(sanitized)
        }.distinct().take(3)

        val updatedProfile = CompactStyleProfile(
            id = profileKey,
            category = category,
            recipientId = if (effectiveRecipient != null && accountPrefix != null && !effectiveRecipient.startsWith("$accountPrefix|")) {
                "$accountPrefix|$effectiveRecipient"
            } else effectiveRecipient,
            traits = updatedTraits,
            exampleSnippets = examples,
            observationCount = updatedCount,
            confidence = updatedConfidence,
            consentVersion = consent.consentVersion,
            updatedAt = now,
        )

        profiles[profileKey] = updatedProfile
        if (!sampleId.isNullOrBlank()) {
            learnedSampleIds.add(sampleId)
            while (learnedSampleIds.size > MAX_LEARNED_SAMPLE_IDS) {
                learnedSampleIds.remove(learnedSampleIds.first())
            }
        }
        return true
    }

    fun resolveProfile(
        category: ContextCategory,
        recipientId: String?,
        now: UtcInstant = UtcInstant(0L),
        account: AccountId? = null,
    ): CompactStyleProfile = synchronized(lock) {
        if (!consent.isEnabled) {
            return defaultNeutralProfile(category, recipientId, now)
        }

        val accountPrefix = account?.key ?: recipientId?.substringBefore('|')?.takeIf { '|' in recipientId }

        // Recipient profile requires sufficient evidence (confidence >= 0.40 / 2+ samples)
        // If evidence is insufficient for recipient personalization, fall back to account category profile,
        // but if recipient profile exists (>= 1 sample) and no category profile has observations, return it.
        var lowConfidenceRecip: CompactStyleProfile? = null
        if (recipientId != null) {
            val fullRecipientKey = if (accountPrefix != null && !recipientId.startsWith("$accountPrefix|")) {
                "$accountPrefix|$recipientId"
            } else recipientId
            val recipKey = "recip:$category:$fullRecipientKey"
            val recipProfile = profiles[recipKey]
            if (recipProfile != null) {
                if (recipProfile.confidence >= MIN_RECIPIENT_CONFIDENCE) {
                    return recipProfile
                }
                lowConfidenceRecip = recipProfile
            }
        }

        if (accountPrefix != null) {
            val accountCatKey = "cat:$accountPrefix:$category"
            val accountCatProfile = profiles[accountCatKey]
            if (accountCatProfile != null && accountCatProfile.observationCount > 0) {
                return accountCatProfile
            }
        }

        // If the query was specific to a recipient that exists in this account, return its profile
        if (lowConfidenceRecip != null && accountPrefix != null) {
            return lowConfidenceRecip
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
        learnedSampleIds.clear()
    }

    fun hasLearnedSample(sampleId: String): Boolean = synchronized(lock) { sampleId in learnedSampleIds }

    fun learnedSampleCount(): Int = synchronized(lock) { learnedSampleIds.size }

    fun snapshot(): List<CompactStyleProfile> = synchronized(lock) { profiles.values.toList() }

    /**
     * Serializes only the compact local profile state. The Android host encrypts this blob with an
     * Android Keystore key before persistence. Keeping the codec here makes restore deterministic
     * and reusable by non-Android hosts without exposing raw message history.
     */
    fun exportState(): String = synchronized(lock) {
        buildString {
            append("v2|${if (consent.isEnabled) 1 else 0}|${consent.consentVersion}|")
            append(consent.grantedCategories.joinToString(",") { it.name })
            append("|${if (consent.allowCodeSwitchLearning) 1 else 0}\n")
            append("ids|")
            append(learnedSampleIds.joinToString(",") { StyleStateCodec.encode(it) })
            append('\n')
            profiles.values.forEach { profile ->
                append("p|")
                append(StyleStateCodec.encode(profile.id)).append('|')
                append(profile.category.name).append('|')
                append(StyleStateCodec.encode(profile.recipientId.orEmpty())).append('|')
                append(profile.traits.formality).append('|')
                append(profile.traits.brevity).append('|')
                append(profile.traits.warmth).append('|')
                append(profile.traits.emojiFrequency).append('|')
                append(StyleStateCodec.encode(profile.traits.introStyle)).append('|')
                append(StyleStateCodec.encode(profile.traits.signoffStyle)).append('|')
                append(StyleStateCodec.encode(profile.traits.codeSwitchPreference)).append('|')
                append(profile.exampleSnippets.joinToString(",") { StyleStateCodec.encode(it) }).append('|')
                append(profile.observationCount).append('|')
                append(profile.confidence).append('|')
                append(profile.consentVersion).append('|')
                append(profile.updatedAt.epochMillis).append('\n')
            }
        }
    }

    /** Restores compact profiles from trusted encrypted host storage. Invalid state is rejected. */
    fun importState(serialized: String): Boolean = synchronized(lock) {
        val lines = serialized.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split('|') ?: return false
        if (header.size < 5 || header[0] !in setOf("v1", "v2")) return false
        val restoredConsent = runCatching {
            StyleConsent(
                isEnabled = header[1] == "1",
                consentVersion = header[2].toInt(),
                grantedCategories = header[3].split(',').filter { it.isNotBlank() }.map { ContextCategory.valueOf(it) }.toSet(),
                allowCodeSwitchLearning = header[4] == "1",
            )
        }.getOrNull() ?: return false
        val restoredProfiles = linkedMapOf<String, CompactStyleProfile>()
        val restoredIds = LinkedHashSet<String>()
        for (line in lines.drop(1)) {
            val parts = line.split('|')
            when (parts[0]) {
                "ids" -> {
                    parts.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }.forEach { encoded ->
                        restoredIds.add(StyleStateCodec.decode(encoded))
                    }
                }
                "p" -> {
                    if (parts.size < 16) return false
                    val decodedId = StyleStateCodec.decode(parts[1])
                    // Discard old accountless category profiles (e.g. "cat:Professional"), which have no account prefix.
                    // An accounted category profile has format "cat:<provider>|<localId>:<category>".
                    if (decodedId.startsWith("cat:") && decodedId.count { it == ':' } < 2) {
                        continue
                    }
                    val profile = runCatching {
                        CompactStyleProfile(
                            id = StyleStateCodec.decode(parts[1]),
                            category = ContextCategory.valueOf(parts[2]),
                            recipientId = StyleStateCodec.decode(parts[3]).ifBlank { null },
                            traits = StyleTraits(
                                formality = parts[4].toDouble(),
                                brevity = parts[5].toDouble(),
                                warmth = parts[6].toDouble(),
                                emojiFrequency = parts[7].toDouble(),
                                introStyle = StyleStateCodec.decode(parts[8]),
                                signoffStyle = StyleStateCodec.decode(parts[9]),
                                codeSwitchPreference = StyleStateCodec.decode(parts[10]),
                            ),
                            exampleSnippets = parts[11].split(',').filter { it.isNotBlank() }.map(StyleStateCodec::decode).take(3),
                            observationCount = parts[12].toInt(),
                            confidence = parts[13].toDouble(),
                            consentVersion = parts[14].toInt(),
                            updatedAt = UtcInstant(parts[15].toLong()),
                        )
                    }.getOrNull() ?: return false
                    restoredProfiles[profile.id] = profile
                }
                else -> return false
            }
        }
        consent = restoredConsent
        profiles.clear()
        learnedSampleIds.clear()
        if (consent.isEnabled) {
            profiles.putAll(restoredProfiles)
            learnedSampleIds.addAll(restoredIds)
        }
        true
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

    private fun formalityDelta(lower: String, hasEmoji: Boolean): Double {
        var delta = 0.0
        if (lower.contains("dear ") || lower.contains("regards") || lower.contains("please find") || lower.contains("kindly")) delta += 0.15
        if (lower.contains("hey") || lower.contains("lol") || lower.contains("gonna") || lower.contains("wanna") || lower.contains("thx")) delta -= 0.2
        if (hasEmoji) delta -= 0.1
        return delta
    }

    private fun introStyle(lower: String): String = when {
        lower.startsWith("dear ") -> "Formal greeting"
        lower.startsWith("hi ") || lower.startsWith("hello ") -> "Brief greeting"
        lower.startsWith("hey") -> "Casual greeting"
        else -> "Neutral greeting"
    }

    private fun signoffStyle(text: String): String {
        val last = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        return when {
            last.startsWith("best") || last.startsWith("regards") || last.startsWith("sincerely") -> "Formal signoff"
            last.startsWith("thanks") || last.startsWith("cheers") -> "Warm signoff"
            else -> "Omit on short replies"
        }
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

private object StyleStateCodec {
    fun encode(value: String): String = value.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decode(value: String): String {
        if (value.isEmpty()) return ""
        require(value.length % 2 == 0)
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString()
    }
}
