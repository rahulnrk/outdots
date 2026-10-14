package app.mini.hub.attention

import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.persistence.StorageQualification

/** Explicit storage outcome. Callers must not report a choice as saved unless this is [Ok]. */
sealed class AttentionStoreResult<out T> {
    data class Ok<T>(val value: T) : AttentionStoreResult<T>()
    data class Failed(val message: String) : AttentionStoreResult<Nothing>()
}

/**
 * Durable attention-state boundary keyed by account-scoped conversation key.
 * Production Android uses SQLCipher with a Keystore-wrapped key. Implementations return
 * [AttentionStoreResult.Failed] instead of throwing, and [saveAll] is all-or-nothing.
 */
interface AttentionStore {
    val qualification: StorageQualification
    fun loadAll(): AttentionStoreResult<Map<String, ConversationAttention>>
    fun saveAll(records: List<ConversationAttention>): AttentionStoreResult<Unit>
}

/** Used when encrypted storage could not be opened. Every operation fails visibly. */
class UnavailableAttentionStore(private val reason: String) : AttentionStore {
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    override fun loadAll(): AttentionStoreResult<Map<String, ConversationAttention>> = AttentionStoreResult.Failed(reason)
    override fun saveAll(records: List<ConversationAttention>): AttentionStoreResult<Unit> = AttentionStoreResult.Failed(reason)
}

/** Test-only store. It advertises that it is not durable and supports failure injection. */
class InMemoryAttentionStore : AttentionStore {
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    private val records = LinkedHashMap<String, ConversationAttention>()
    var failReads: String? = null
    var failWrites: String? = null
    var writeCount: Int = 0
        private set

    override fun loadAll(): AttentionStoreResult<Map<String, ConversationAttention>> {
        failReads?.let { return AttentionStoreResult.Failed(it) }
        return AttentionStoreResult.Ok(LinkedHashMap(records))
    }

    override fun saveAll(records: List<ConversationAttention>): AttentionStoreResult<Unit> {
        failWrites?.let { return AttentionStoreResult.Failed(it) }
        records.forEach { this.records[it.key] = it }
        writeCount++
        return AttentionStoreResult.Ok(Unit)
    }
}

/** Result of reconciling stored attention with current inbox activity at open/refresh time. */
data class AttentionReconciliation(
    val records: Map<String, ConversationAttention>,
    val changed: List<ConversationAttention>,
)

object AttentionReconciler {
    /**
     * Produces a record for every summary, applying due snoozes and new-activity rules.
     * Records for conversations not currently listed are retained unchanged.
     */
    fun reconcile(
        stored: Map<String, ConversationAttention>,
        summaries: List<ConversationObservationSummary>,
        now: UtcInstant,
        resurfaceOnNewActivity: (ConversationObservationSummary) -> Boolean = { true },
    ): AttentionReconciliation {
        val result = LinkedHashMap(stored)
        val changed = mutableListOf<ConversationAttention>()
        summaries.forEach { summary ->
            val key = summary.conversationId.key
            val existing = stored[key]
            val base = existing ?: AttentionRules.default(summary.conversationId, now)
            val next = AttentionRules.reconcile(base, summary.latestEventAt, now, resurfaceOnNewActivity(summary))
            result[key] = next
            // Defaults are not written until something meaningful changes or the user chooses.
            if (existing != null && next != existing) changed += next
        }
        return AttentionReconciliation(result, changed)
    }
}
