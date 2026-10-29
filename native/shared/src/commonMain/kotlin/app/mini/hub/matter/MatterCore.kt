package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

/**
 * WP05 First Slice: Matter records, state transitions, account-scoped Focus consent and exclusions, and evidence lineage.
 */
enum class MatterState {
    Open, Waiting, Completed, Dismissed
}

data class EvidenceLink(
    val observationId: ScopedId,
    val snippet: String,
    val capturedAt: UtcInstant,
)

data class MatterRecord(
    val id: ScopedId,
    val accountId: AccountId,
    val title: String,
    val state: MatterState,
    val evidenceLinks: List<EvidenceLink>,
    val consentGranted: Boolean,
    val excluded: Boolean,
    val updatedAt: UtcInstant,
) {
    init {
        require(title.isNotBlank()) { "Matter title must not be blank" }
    }
}

class MatterStateEngine {
    private val matters = mutableMapOf<String, MatterRecord>()

    fun upsert(matter: MatterRecord): MatterRecord {
        require(matter.consentGranted && !matter.excluded) { "Matter requires Focus consent and cannot be excluded" }
        matters[matter.id.key] = matter
        return matter
    }

    fun get(id: ScopedId): MatterRecord? = matters[id.key]

    fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant): MatterRecord {
        val existing = matters[id.key] ?: error("Matter not found")
        val updated = existing.copy(state = nextState, updatedAt = now)
        matters[id.key] = updated
        return updated
    }

    fun listForAccount(accountId: AccountId): List<MatterRecord> =
        matters.values.filter { it.accountId == accountId && it.consentGranted && !it.excluded }
}
