package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import org.junit.Test
import kotlin.test.*

class MatterStoreTest {
    private val account = AccountId(Provider.WHATSAPP, "user_1")
    private val altAccount = AccountId(Provider.GMAIL, "user_2")
    private val now = UtcInstant(1_700_000_000_000L)

    @Test
    fun testMatterStoreIsolationAndConsentBoundaries() {
        val store: MatterStore = InMemoryMatterStore()
        store.setConsent(account, true, false)
        store.setConsent(altAccount, true, false)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-1")
        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1")
        val link = EvidenceLink(obsId, "Evidence snippet", now)

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Secure Tax Audit",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        store.upsert(record)
        assertEquals(record, store.get(matterId))
        assertEquals(1, store.listForAccount(account).size)
        assertEquals(0, store.listForAccount(altAccount).size)

        // Cross-account boundary enforcement where evidence observation ID matches matter account
        val foreignObsId = ScopedId(altAccount, ScopedId.Kind.OBSERVATION, "obs-foreign")
        val foreignMatter = record.copy(
            id = ScopedId(altAccount, ScopedId.Kind.ACTION_ITEM, "m-foreign"),
            accountId = altAccount,
            evidenceLinks = listOf(EvidenceLink(foreignObsId, "Foreign snippet", now))
        )
        store.upsert(foreignMatter)
        assertEquals(1, store.listForAccount(account).size)
        assertEquals(1, store.listForAccount(altAccount).size)
    }

    @Test
    fun testDefaultConsentDeniedAndRevocationSafety() {
        val store: MatterStore = InMemoryMatterStore()
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-consent-test")
        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1")
        val link = EvidenceLink(obsId, "Evidence snippet", now)

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Consent Test",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        // 1. Absent consent blocks access / upsert
        assertFailsWith<IllegalArgumentException> {
            store.upsert(record)
        }

        // Explicit consent permits access
        store.setConsent(account, true, false)
        store.upsert(record)
        assertNotNull(store.get(matterId))

        // Revocation survives / blocks stale writer and prevents reads
        store.setConsent(account, false, true)
        assertNull(store.get(matterId))
        assertFailsWith<IllegalArgumentException> {
            store.upsert(record)
        }
    }

    @Test
    fun testRevocationAndSourceDeletionInvalidation() {
        val store: MatterStore = InMemoryMatterStore()
        store.setConsent(account, true, false)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-revocation")
        val obsId1 = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1")
        val obsId2 = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-2")

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Revocation Test",
            state = MatterState.Open,
            evidenceLinks = listOf(EvidenceLink(obsId1, "E1", now), EvidenceLink(obsId2, "E2", now)),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )
        store.upsert(record)
        assertEquals(2, store.get(matterId)?.evidenceLinks?.size)

        // Deleting source observation obsId1 invalidates linked evidence; if all evidence removed, matter is invalidated
        store.invalidateForObservation(obsId2)
        assertEquals(1, store.get(matterId)?.evidenceLinks?.size)

        // Invalidate remaining evidence obsId1 -> matter should be removed entirely due to zero evidence
        store.invalidateForObservation(obsId1)
        assertNull(store.get(matterId))

        // Re-create and test account-level consent revocation
        val record2 = record.copy(id = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-consent"))
        store.upsert(record2)
        assertNotNull(store.get(record2.id))

        store.invalidateForAccount(account)
        assertNull(store.get(record2.id))
        assertTrue(store.listForAccount(account).isEmpty())
    }

    @Test
    fun testRevocationAndReenableConsentFlow() {
        val store: MatterStore = InMemoryMatterStore()
        store.setConsent(account, true, false)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-flow")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1"), "snippet", now)
        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Consent Flow Matter",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        store.upsert(record)
        assertNotNull(store.get(matterId))

        // Revoke consent: work started before revocation must be blocked / hidden, and old queued result cannot be persisted
        store.invalidateForAccount(account)
        assertNull(store.get(matterId))
        assertTrue(store.listForAccount(account).isEmpty())

        val lateQueuedRecord = record.copy(title = "Late Result", updatedAt = now + 1000L)
        assertFailsWith<IllegalArgumentException> {
            store.upsert(lateQueuedRecord)
        }
        assertNull(store.get(matterId))

        // Re-enabling Focus must not automatically authorize old results unless re-granted and freshly upserted
        store.setConsent(account, true, false)
        assertNull(store.get(matterId), "Re-enabling alone must not resurrect revoked matter")

        val freshRecord = record.copy(title = "Fresh Re-enabled Matter", updatedAt = now + 2000L)
        store.upsert(freshRecord)
        val fetched = store.get(matterId)
        assertNotNull(fetched)
        assertEquals("Fresh Re-enabled Matter", fetched.title)

        // Ensure other accounts remain completely unaffected
        assertTrue(store.listForAccount(altAccount).isEmpty())
    }

    @Test
    fun testConsentTicketCaptureRevocationAndReenableSequence() {
        val store: MatterStore = InMemoryMatterStore()
        store.setConsent(account, true, false)

        val ticket1 = store.issueConsentTicket(account)
        assertEquals(1L, ticket1.revision)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-ticket-seq")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-seq"), "snippet", now)
        val record = MatterRecord(matterId, account, "Ticket Sequence Matter", MatterState.Open, listOf(link), true, false, now)

        store.upsertWithTicket(record, ticket1)
        assertNotNull(store.get(matterId))

        // Revoke consent -> increments revision
        store.invalidateForAccount(account)
        val ticket2 = store.issueConsentTicket(account)
        assertEquals(2L, ticket2.revision)

        // Re-enable consent -> increments revision again
        store.setConsent(account, true, false)
        val ticket3 = store.issueConsentTicket(account)
        assertEquals(3L, ticket3.revision)

        // Submitting OLD work with ticket1 or ticket2 must be rejected
        val oldRecord = record.copy(title = "Stale Result", updatedAt = now + 1000L)
        assertFailsWith<IllegalArgumentException> {
            store.upsertWithTicket(oldRecord, ticket1)
        }
        assertFailsWith<IllegalArgumentException> {
            store.upsertWithTicket(oldRecord, ticket2)
        }

        // Submitting with current ticket3 succeeds
        val freshRecord = record.copy(title = "Fresh Result", updatedAt = now + 2000L)
        store.upsertWithTicket(freshRecord, ticket3)
        val fetched = store.get(matterId)
        assertNotNull(fetched)
        assertEquals("Fresh Result", fetched.title)

        // Ensure other accounts remain unaffected
        assertTrue(store.listForAccount(altAccount).isEmpty())
    }

    @Test
    fun testProductionFocusCoordinatorLifecycleEndToEnd() {
        val store: MatterStore = InMemoryMatterStore()
        val obsStore = app.mini.hub.persistence.InMemoryObservationStore()
        val coordinator = ProductionFocusCoordinator(store, obsStore)

        store.setConsent(account, true, false)
        store.setConsent(altAccount, true, false)

        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-lifecycle-1")
        val altObsId = ScopedId(altAccount, ScopedId.Kind.OBSERVATION, "obs-alt-1")
        val obs = app.mini.hub.observation.ObservedNotification(
            id = obsId,
            accountId = account,
            conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "conv-1"),
            packageId = "com.whatsapp",
            notificationKey = obsId.key,
            notificationId = 1,
            tag = null,
            senderHint = "Sender",
            title = "Title",
            textFragments = listOf("Lifecycle message"),
            rawTextDigest = "digest",
            observedAt = now,
            postTime = now,
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = app.mini.hub.observation.TruncationState.Complete,
            correlationConfidence = app.mini.hub.observation.CorrelationConfidence.High,
            provenance = app.mini.hub.observation.ObservationProvenance.AndroidNotificationListener,
            notificationCategory = app.mini.hub.observation.NotificationCategory.DirectMessage,
        )
        obsStore.persistObservation(obs)

        // 1. Start work and capture consent ticket
        val ticket = coordinator.startWork(account)
        assertEquals(1L, ticket.revision)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-lifecycle-1")
        val link = EvidenceLink(obsId, "Lifecycle snippet", now)
        val record = MatterRecord(matterId, account, "Lifecycle Matter", MatterState.Open, listOf(link), true, false, now)

        // Commit matter through coordinator
        coordinator.commitMatter(record, ticket)
        assertNotNull(store.get(matterId))

        // 2. Source deletion / removal -> dependent access removed
        obsStore.markRemoved(obsId.key, now)
        coordinator.reconcileObservationRemoval(obsId)
        assertNull(store.get(matterId), "Matter with removed observation source must be invalidated")

        // Re-adding observation and committing fresh matter
        obsStore.persistObservation(obs.copy(isRemoved = false))
        val freshTicket = coordinator.startWork(account)
        val freshRecord = record.copy(title = "Fresh Lifecycle Matter", updatedAt = now + 1000L)
        coordinator.commitMatter(freshRecord, freshTicket)
        assertNotNull(store.get(matterId))

        // 3. Focus revocation -> derived access removed, restart/reopen safe
        coordinator.reconcileAccountRevocation(account)
        assertNull(store.get(matterId))
        assertTrue(store.listForAccount(account).isEmpty())

        // Ensure unaffected accounts and independently supported matters remain intact
        val altMatterId = ScopedId(altAccount, ScopedId.Kind.ACTION_ITEM, "m-alt-1")
        val altObs = obs.copy(id = altObsId, accountId = altAccount, conversationId = ScopedId(altAccount, ScopedId.Kind.CONVERSATION, "conv-alt"), notificationKey = altObsId.key)
        obsStore.persistObservation(altObs)
        val altTicket = coordinator.startWork(altAccount)
        val altRecord = MatterRecord(altMatterId, altAccount, "Alt Supported Matter", MatterState.Open, listOf(EvidenceLink(altObsId, "Alt snippet", now)), true, false, now)
        coordinator.commitMatter(altRecord, altTicket)
        assertNotNull(store.get(altMatterId), "Independently supported matter on unaffected account remains intact")
    }
}
