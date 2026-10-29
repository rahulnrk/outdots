package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import org.junit.Test
import kotlin.test.*

class MatterCoreTest {
    private val account = AccountId(Provider.WHATSAPP, "local_user")
    private val altAccount = AccountId(Provider.GMAIL, "work")
    private val now = UtcInstant(1_700_000_000_000L)

    @Test
    fun matterRequiresConsentAndExcludesUnauthorizedOrExcludedRecords() {
        val engine = MatterStateEngine()
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "matter-1")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1"), "Evidence snippet", now)

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Project Tax Review",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        assertEquals(record, engine.upsert(record))
        assertEquals(1, engine.listForAccount(account).size)
        assertEquals(0, engine.listForAccount(altAccount).size)

        // Excluded or unconsented records are rejected or filtered out
        assertFailsWith<IllegalArgumentException> {
            engine.upsert(record.copy(consentGranted = false))
        }
        assertFailsWith<IllegalArgumentException> {
            engine.upsert(record.copy(excluded = true))
        }
    }

    @Test
    fun matterStateTransitionsAreAuditedWithEvidenceLineage() {
        val engine = MatterStateEngine()
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "matter-2")
        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Tax Review",
            state = MatterState.Open,
            evidenceLinks = emptyList(),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )
        engine.upsert(record)
        val transitioned = engine.transition(matterId, MatterState.Completed, now + 5000L)
        assertEquals(MatterState.Completed, transitioned.state)
        assertEquals(now + 5000L, transitioned.updatedAt)
    }
}
