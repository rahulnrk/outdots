package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

interface MatterStore {
    fun upsert(matter: MatterRecord): MatterRecord
    fun get(id: ScopedId): MatterRecord?
    fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant): MatterRecord
    fun listForAccount(accountId: AccountId): List<MatterRecord>
    fun invalidateForAccount(accountId: AccountId)
    fun invalidateForObservation(observationId: ScopedId)

    // Independent account-level consent/exclusion state persistence and checking with revision tickets
    fun setConsent(accountId: AccountId, granted: Boolean, excluded: Boolean)
    fun isConsentGranted(accountId: AccountId): Boolean
    fun isExcluded(accountId: AccountId): Boolean
    fun currentConsentRevision(accountId: AccountId): Long
    fun issueConsentTicket(accountId: AccountId): ConsentTicket
    fun upsertWithTicket(matter: MatterRecord, ticket: ConsentTicket): MatterRecord
}

data class ConsentTicket(
    val accountId: AccountId,
    val revision: Long,
)

class InMemoryMatterStore : MatterStore {
    private val matters = mutableMapOf<String, MatterRecord>()
    private val consentMap = mutableMapOf<String, Triple<Boolean, Boolean, Long>>() // accountKey -> (granted, excluded, revision)

    override fun upsert(matter: MatterRecord): MatterRecord {
        val (granted, excluded, _) = consentMap[matter.accountId.key] ?: Triple(false, false, 0L)
        MatterValidation.validateMatter(matter, granted, excluded)

        matters[matter.id.key] = matter
        return matter
    }

    override fun upsertWithTicket(matter: MatterRecord, ticket: ConsentTicket): MatterRecord {
        require(ticket.accountId == matter.accountId) { "Consent ticket account must match matter accountId" }
        val (granted, excluded, currentRevision) = consentMap[matter.accountId.key] ?: Triple(false, false, 0L)
        require(granted && !excluded && ticket.revision == currentRevision) {
            "Consent ticket is stale or account consent is revoked/excluded"
        }
        return upsert(matter)
    }

    override fun get(id: ScopedId): MatterRecord? {
        val matter = matters[id.key] ?: return null
        val (granted, excluded, _) = consentMap[matter.accountId.key] ?: Triple(false, false, 0L)
        if (!granted || excluded) return null
        return matter
    }

    override fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant): MatterRecord {
        val existing = get(id) ?: error("Matter not found")
        val updated = existing.copy(state = nextState, updatedAt = now)
        upsert(updated)
        return updated
    }

    override fun listForAccount(accountId: AccountId): List<MatterRecord> {
        val (granted, excluded, _) = consentMap[accountId.key] ?: Triple(false, false, 0L)
        if (!granted || excluded) return emptyList()
        return matters.values.filter { it.accountId == accountId }
    }

    override fun invalidateForAccount(accountId: AccountId) {
        val (_, _, currentRev) = consentMap[accountId.key] ?: Triple(false, false, 0L)
        consentMap[accountId.key] = Triple(false, true, currentRev + 1L)
        val keysToRemove = matters.values.filter { it.accountId == accountId }.map { it.id.key }
        keysToRemove.forEach { matters.remove(it) }
    }

    override fun invalidateForObservation(observationId: ScopedId) {
        val keysToUpdate = mutableListOf<String>()
        val updatedRecords = mutableMapOf<String, MatterRecord>()

        matters.forEach { (key, matter) ->
            if (matter.accountId == observationId.account) {
                val filteredEvidence = matter.evidenceLinks.filter { it.observationId != observationId }
                if (filteredEvidence.size != matter.evidenceLinks.size) {
                    if (filteredEvidence.isEmpty()) {
                        keysToUpdate.add(key)
                    } else {
                        updatedRecords[key] = matter.copy(evidenceLinks = filteredEvidence)
                    }
                }
            }
        }
        keysToUpdate.forEach { matters.remove(it) }
        updatedRecords.forEach { (k, v) -> matters[k] = v }
    }

    override fun setConsent(accountId: AccountId, granted: Boolean, excluded: Boolean) {
        val (_, _, currentRev) = consentMap[accountId.key] ?: Triple(false, false, 0L)
        consentMap[accountId.key] = Triple(granted, excluded, currentRev + 1L)
    }

    override fun isConsentGranted(accountId: AccountId): Boolean =
        consentMap[accountId.key]?.first ?: false

    override fun isExcluded(accountId: AccountId): Boolean =
        consentMap[accountId.key]?.second ?: false

    override fun currentConsentRevision(accountId: AccountId): Long =
        consentMap[accountId.key]?.third ?: 0L

    override fun issueConsentTicket(accountId: AccountId): ConsentTicket =
        ConsentTicket(accountId, currentConsentRevision(accountId))
}
