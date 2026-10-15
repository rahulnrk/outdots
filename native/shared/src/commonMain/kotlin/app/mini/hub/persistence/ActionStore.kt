package app.mini.hub.persistence

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionState

/**
 * Durable action state boundary. Implementations must commit approvals and outbox transitions atomically.
 * The production Android implementation must use SQLCipher with an Android Keystore-wrapped key.
 */
interface ActionStore {
    val qualification: StorageQualification
    fun snapshot(): ActionState
    fun transaction(update: (ActionState) -> ActionState): ActionState
    fun recoverInterrupted(controller: ActionController): ActionState
}

enum class StorageQualification {
    InMemoryTestOnly,
    SqlCipherAndroidKeystorePending,
    SqlCipherAndroidKeystoreQualified,
}

/** Reference to a message actually included in AI context for a draft. */
data class DraftSourceReference(
    val messageId: String,
    val senderHint: String,
    val timestampMillis: Long,
    val snippet: String,
    val isIncludedAsText: Boolean = true,
)

/** Provenance and explanation of inputs actually supplied for a specific generated draft. */
data class DraftProvenance(
    val accountId: String,
    val accountLabel: String,
    val userInstruction: String,
    val sourceMessageReferences: List<DraftSourceReference> = emptyList(),
    val appliedStyleSummary: String? = null,
    val coverageWarning: String? = null,
    val excludedAttachments: List<String> = emptyList(),
    val previousDraft: String? = null,
    val understandingNote: String? = null,
    val generatedAtMillis: Long = 0L,
)

/**
 * Coupled draft version holding text, its exact provenance (if AI generated),
 * and the baseline original AI proposal text (to distinguish unedited AI text from user edits).
 */
data class DraftVersion(
    val id: String = "",
    val text: String,
    val provenance: DraftProvenance? = null,
    val originalAiText: String? = null,
    val isAiGenerated: Boolean = provenance != null || originalAiText != null,
)

/** Editable composer text keyed by conversation. Approvals are never stored here. */
data class ConversationDraft(
    val conversationKey: String,
    val accountId: String,
    val body: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val isNewEmail: Boolean = false,
    val originalGeneratedDraft: String? = null,
    val updatedAtMillis: Long,
    val provenance: DraftProvenance? = null,
    val versions: List<DraftVersion> = emptyList(),
)

object DraftProvenanceSerializer {
    fun serialize(p: DraftProvenance): String = buildString {
        append("{")
        append("\"accountId\":\"").append(escape(p.accountId)).append("\",")
        append("\"accountLabel\":\"").append(escape(p.accountLabel)).append("\",")
        append("\"userInstruction\":\"").append(escape(p.userInstruction)).append("\",")
        append("\"appliedStyleSummary\":").append(p.appliedStyleSummary?.let { "\"${escape(it)}\"" } ?: "null").append(",")
        append("\"coverageWarning\":").append(p.coverageWarning?.let { "\"${escape(it)}\"" } ?: "null").append(",")
        append("\"previousDraft\":").append(p.previousDraft?.let { "\"${escape(it)}\"" } ?: "null").append(",")
        append("\"understandingNote\":").append(p.understandingNote?.let { "\"${escape(it)}\"" } ?: "null").append(",")
        append("\"generatedAtMillis\":").append(p.generatedAtMillis).append(",")
        append("\"excludedAttachments\":[").append(p.excludedAttachments.joinToString(",") { "\"${escape(it)}\"" }).append("],")
        append("\"sourceMessageReferences\":[")
        p.sourceMessageReferences.forEachIndexed { i, ref ->
            if (i > 0) append(",")
            append("{")
            append("\"messageId\":\"").append(escape(ref.messageId)).append("\",")
            append("\"senderHint\":\"").append(escape(ref.senderHint)).append("\",")
            append("\"timestampMillis\":").append(ref.timestampMillis).append(",")
            append("\"snippet\":\"").append(escape(ref.snippet)).append("\",")
            append("\"isIncludedAsText\":").append(ref.isIncludedAsText)
            append("}")
        }
        append("]")
        append("}")
    }

    fun deserialize(raw: String): DraftProvenance? = runCatching {
        if (raw.isBlank()) return null
        val obj = SimpleJsonParser(raw).parseObject() ?: return null
        val accountId = obj["accountId"] as? String ?: return null
        val accountLabel = obj["accountLabel"] as? String ?: accountId
        val userInstruction = obj["userInstruction"] as? String ?: ""
        val appliedStyleSummary = obj["appliedStyleSummary"] as? String
        val coverageWarning = obj["coverageWarning"] as? String
        val previousDraft = obj["previousDraft"] as? String
        val understandingNote = obj["understandingNote"] as? String
        val generatedAtMillis = (obj["generatedAtMillis"] as? Number)?.toLong() ?: 0L
        val excludedAttachments = (obj["excludedAttachments"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val refs = (obj["sourceMessageReferences"] as? List<*>)?.mapNotNull { item ->
            val refObj = item as? Map<*, *> ?: return@mapNotNull null
            val messageId = refObj["messageId"] as? String ?: return@mapNotNull null
            val senderHint = refObj["senderHint"] as? String ?: ""
            val timestampMillis = (refObj["timestampMillis"] as? Number)?.toLong() ?: 0L
            val snippet = refObj["snippet"] as? String ?: ""
            val isIncludedAsText = (refObj["isIncludedAsText"] as? Boolean) ?: true
            DraftSourceReference(
                messageId = messageId,
                senderHint = senderHint,
                timestampMillis = timestampMillis,
                snippet = snippet,
                isIncludedAsText = isIncludedAsText,
            )
        } ?: emptyList()

        DraftProvenance(
            accountId = accountId,
            accountLabel = accountLabel,
            userInstruction = userInstruction,
            sourceMessageReferences = refs,
            appliedStyleSummary = appliedStyleSummary,
            coverageWarning = coverageWarning,
            excludedAttachments = excludedAttachments,
            previousDraft = previousDraft,
            understandingNote = understandingNote,
            generatedAtMillis = generatedAtMillis,
        )
    }.getOrNull()

    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}

object DraftVersionSerializer {
    fun serializeList(versions: List<DraftVersion>): String = buildString {
        append("[")
        versions.forEachIndexed { i, v ->
            if (i > 0) append(",")
            append("{")
            append("\"id\":\"").append(DraftProvenanceSerializer.escape(v.id)).append("\",")
            append("\"text\":\"").append(DraftProvenanceSerializer.escape(v.text)).append("\",")
            append("\"originalAiText\":").append(v.originalAiText?.let { "\"${DraftProvenanceSerializer.escape(it)}\"" } ?: "null").append(",")
            append("\"isAiGenerated\":").append(v.isAiGenerated).append(",")
            append("\"provenance\":").append(v.provenance?.let { DraftProvenanceSerializer.serialize(it) } ?: "null")
            append("}")
        }
        append("]")
    }

    fun deserializeList(raw: String): List<DraftVersion> = runCatching {
        if (raw.isBlank()) return emptyList()
        val arr = SimpleJsonParser(raw).parseArray() ?: return emptyList()
        arr.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val id = obj["id"] as? String ?: ""
            val text = obj["text"] as? String ?: return@mapNotNull null
            val originalAiText = obj["originalAiText"] as? String
            val isAiGenerated = (obj["isAiGenerated"] as? Boolean) ?: (originalAiText != null)
            val provRaw = obj["provenance"]
            val provenance = when (provRaw) {
                is Map<*, *> -> {
                    val subRaw = DraftProvenanceSerializer.serialize(
                        DraftProvenance(
                            accountId = provRaw["accountId"] as? String ?: "",
                            accountLabel = provRaw["accountLabel"] as? String ?: "",
                            userInstruction = provRaw["userInstruction"] as? String ?: "",
                            appliedStyleSummary = provRaw["appliedStyleSummary"] as? String,
                            coverageWarning = provRaw["coverageWarning"] as? String,
                            previousDraft = provRaw["previousDraft"] as? String,
                            understandingNote = provRaw["understandingNote"] as? String,
                            generatedAtMillis = (provRaw["generatedAtMillis"] as? Number)?.toLong() ?: 0L,
                            excludedAttachments = (provRaw["excludedAttachments"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                            sourceMessageReferences = (provRaw["sourceMessageReferences"] as? List<*>)?.mapNotNull { r ->
                                val rObj = r as? Map<*, *> ?: return@mapNotNull null
                                DraftSourceReference(
                                    messageId = rObj["messageId"] as? String ?: return@mapNotNull null,
                                    senderHint = rObj["senderHint"] as? String ?: "",
                                    timestampMillis = (rObj["timestampMillis"] as? Number)?.toLong() ?: 0L,
                                    snippet = rObj["snippet"] as? String ?: "",
                                    isIncludedAsText = (rObj["isIncludedAsText"] as? Boolean) ?: true,
                                )
                            } ?: emptyList(),
                        )
                    )
                    DraftProvenanceSerializer.deserialize(subRaw)
                }
                else -> null
            }
            DraftVersion(
                id = id,
                text = text,
                provenance = provenance,
                originalAiText = originalAiText,
                isAiGenerated = isAiGenerated,
            )
        }
    }.getOrElse { emptyList() }
}

internal class SimpleJsonParser(private val src: String) {
        private var idx = 0

        fun parseObject(): Map<String, Any?>? {
            skipWhitespace()
            if (!consume('{')) return null
            val result = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                idx++
                return result
            }
            while (idx < src.length) {
                skipWhitespace()
                val key = parseString() ?: break
                skipWhitespace()
                if (!consume(':')) break
                skipWhitespace()
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                if (consume(',')) {
                    continue
                } else if (consume('}')) {
                    return result
                } else {
                    break
                }
            }
            return result
        }

        fun parseArray(): List<Any?>? {
            skipWhitespace()
            if (!consume('[')) return null
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                idx++
                return result
            }
            while (idx < src.length) {
                skipWhitespace()
                val value = parseValue()
                result.add(value)
                skipWhitespace()
                if (consume(',')) {
                    continue
                } else if (consume(']')) {
                    return result
                } else {
                    break
                }
            }
            return result
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= src.length) return null
            return when (val c = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber() else null
            }
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val sb = StringBuilder()
            while (idx < src.length) {
                val c = src[idx++]
                if (c == '"') return sb.toString()
                if (c == '\\' && idx < src.length) {
                    when (val esc = src[idx++]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseBoolean(): Boolean? {
            return if (src.startsWith("true", idx)) {
                idx += 4
                true
            } else if (src.startsWith("false", idx)) {
                idx += 5
                false
            } else null
        }

        private fun parseNull(): Any? {
            return if (src.startsWith("null", idx)) {
                idx += 4
                null
            } else null
        }

        private fun parseNumber(): Number? {
            val start = idx
            if (idx < src.length && (src[idx] == '-' || src[idx] == '+')) idx++
            while (idx < src.length && src[idx] in '0'..'9') idx++
            val isFloat = idx < src.length && src[idx] == '.'
            if (isFloat) {
                idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            val numStr = src.substring(start, idx)
            return if (isFloat) numStr.toDoubleOrNull() else numStr.toLongOrNull()
        }

        private fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) idx++
        }

        private fun consume(expected: Char): Boolean {
            if (idx < src.length && src[idx] == expected) {
                idx++
                return true
            }
            return false
        }

        private fun peek(): Char? = if (idx < src.length) src[idx] else null
    }

interface ConversationDraftStore {
    fun saveDraft(draft: ConversationDraft)
    fun loadDraft(conversationKey: String): ConversationDraft?
    fun deleteDraft(conversationKey: String)
    fun listDrafts(): List<ConversationDraft>
}

/**
 * Deterministic test repository. It deliberately advertises that it is not durable or encrypted and must never be
 * wired into a release Android build.
 */
class InMemoryActionStore(initial: ActionState = ActionState()) : ActionStore, ConversationDraftStore {
    private var state = initial
    private val drafts = LinkedHashMap<String, ConversationDraft>()
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    override fun snapshot(): ActionState = state
    override fun transaction(update: (ActionState) -> ActionState): ActionState {
        val next = update(state)
        state = next
        return next
    }
    override fun recoverInterrupted(controller: ActionController): ActionState {
        state = controller.recoverInterrupted(state)
        return state
    }
    override fun saveDraft(draft: ConversationDraft) {
        drafts[draft.conversationKey] = draft
    }
    override fun loadDraft(conversationKey: String): ConversationDraft? = drafts[conversationKey]
    override fun deleteDraft(conversationKey: String) {
        drafts.remove(conversationKey)
    }
    override fun listDrafts(): List<ConversationDraft> = drafts.values.sortedByDescending { it.updatedAtMillis }
}

/** Contract for the Android SQLCipher implementation. No implementation is claimed until key lifecycle and migration tests pass. */
interface EncryptedActionStore : ActionStore, ConversationDraftStore {
    override val qualification: StorageQualification
        get() = StorageQualification.SqlCipherAndroidKeystorePending
}
