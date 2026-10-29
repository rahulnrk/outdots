package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.matter.*
import app.mini.hub.observation.*
import app.mini.hub.persistence.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JdbcMatterStoreTest {
    private val account = AccountId(Provider.WHATSAPP, "user_jdbc")
    private val altAccount = AccountId(Provider.GMAIL, "user_jdbc_alt")
    private val now = UtcInstant(1_700_000_000_000L)

    @Test
    fun testJdbcMatterStorePersistenceReopenAndBoundaries() {
        val tmpFile = File.createTempFile("matter_test_", ".db")
        tmpFile.deleteOnExit()

        val store = JdbcMatterStore(tmpFile)
        store.setConsent(account, true, false)
        store.setConsent(altAccount, true, false)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-jdbc-1")
        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-jdbc-1")
        val link = EvidenceLink(obsId, "JDBC Evidence snippet", now)

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "JDBC Durable Matter",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        store.upsert(record)
        assertEquals(record, store.get(matterId))

        // Validate account boundary and ID kind validation where evidence observation ID matches matter ID account or matter account
        val invalidMatter = record.copy(id = ScopedId(altAccount, ScopedId.Kind.ACTION_ITEM, "m-invalid"), accountId = altAccount)
        assertFailsWith<IllegalArgumentException> {
            store.upsert(invalidMatter)
        }

        // Reopen store to verify persistence across restart
        val reopenedStore = JdbcMatterStore(tmpFile)
        val fetched = reopenedStore.get(matterId)
        assertNotNull(fetched)
        assertEquals("JDBC Durable Matter", fetched.title)
        assertEquals(1, fetched.evidenceLinks.size)

        // Test independent account-level consent revocation and verify stale writes / reads remain blocked
        reopenedStore.setConsent(account, granted = false, excluded = true)
        assertNull(reopenedStore.get(matterId))
        assertTrue(reopenedStore.listForAccount(account).isEmpty())
    }

    @Test
    fun testConsentTicketAndReenableSequenceWithJdbcReopen() {
        val tmpFile = File.createTempFile("matter_ticket_", ".db")
        tmpFile.deleteOnExit()

        val store = JdbcMatterStore(tmpFile)
        store.setConsent(account, true, false)
        store.setConsent(altAccount, true, false)

        val ticket1 = store.issueConsentTicket(account)
        assertEquals(1L, ticket1.revision)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-ticket-1")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-ticket"), "snippet", now)
        val record = MatterRecord(matterId, account, "Ticket Matter", MatterState.Open, listOf(link), true, false, now)

        store.upsertWithTicket(record, ticket1)
        assertNotNull(store.get(matterId))

        // Revoke consent -> increments revision. Issuing ticket on revoked account must fail.
        store.setConsent(account, false, true)
        assertFailsWith<IllegalArgumentException> {
            store.issueConsentTicket(account)
        }
        val ticket2 = ConsentTicket(account, 2L)

        // Re-enable consent -> increments revision again
        store.setConsent(account, true, false)
        val ticket3 = store.issueConsentTicket(account)
        assertEquals(3L, ticket3.revision)

        // Submitting OLD work with ticket1 or ticket2 must be rejected
        val oldRecord = record.copy(title = "Old Result", updatedAt = now + 1000L)
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

        // Close and reopen store (JDBC persistent restart) to verify isolation and persistence
        val reopenedStore = JdbcMatterStore(tmpFile)
        assertNotNull(reopenedStore.get(matterId))
        assertTrue(reopenedStore.listForAccount(altAccount).isEmpty())
    }

    @Test
    fun testJdbcProductionFocusCoordinatorLifecycleAndInterruptionRecovery() {
        val tmpFile = File.createTempFile("matter_lifecycle_", ".db")
        tmpFile.deleteOnExit()
        val obsFile = File.createTempFile("obs_lifecycle_", ".db")
        obsFile.deleteOnExit()

        val store = JdbcMatterStore(tmpFile)
        val obsStore = JdbcObservationStore(obsFile.absolutePath)
        val coordinator = ProductionFocusCoordinator(store, obsStore)

        store.setConsent(account, true, false)
        store.setConsent(altAccount, true, false)

        // Seed 105 records to test bounded event paging (> 100 records)
        val obsList = (1..105).map { i ->
            val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-jdbc-bulk-$i")
            ObservedNotification(
                id = id,
                accountId = account,
                conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "conv-jdbc-bulk"),
                packageId = "com.whatsapp",
                notificationKey = if (i == 2 || i == 3) "provider:whatsapp:bulk-$i" else "com.whatsapp:notif-$i",
                notificationId = i,
                tag = null,
                senderHint = "Sender $i",
                title = "Title $i",
                textFragments = listOf("Bulk message $i"),
                rawTextDigest = "digest-$i",
                observedAt = now,
                postTime = now,
                revision = 1L,
                isRemoved = false,
                isGroupSummary = false,
                isOngoing = false,
                truncationState = TruncationState.Complete,
                correlationConfidence = CorrelationConfidence.High,
                provenance = ObservationProvenance.AndroidNotificationListener,
                notificationCategory = NotificationCategory.DirectMessage,
            )
        }
        obsStore.persistBatch(obsList)

        val matterId1 = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-bulk-1")
        val matterId2 = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-bulk-2")
        val ticket = coordinator.startWork(account)

        val record1 = MatterRecord(matterId1, account, "Provider Matter", MatterState.Open, listOf(EvidenceLink(obsList[1].id, "snippet 1", now)), true, false, now)
        val record2 = MatterRecord(matterId2, account, "Dismissed Matter", MatterState.Open, listOf(EvidenceLink(obsList[2].id, "snippet 2", now)), true, false, now)

        coordinator.commitMatter(record1, ticket)
        coordinator.commitMatter(record2, ticket)

        assertNotNull(store.get(matterId1))
        assertNotNull(store.get(matterId2))

        // Reopen both stores and invoke resumePendingReconciliation startup worker with batchSize = 50
        val reopenedStore = JdbcMatterStore(tmpFile)
        val reopenedObsStore = JdbcObservationStore(obsFile.absolutePath)
        val reopenedCoordinator = ProductionFocusCoordinator(reopenedStore, reopenedObsStore)

        assertNotNull(reopenedStore.get(matterId1), "Matter is present before startup recovery worker runs")
        
        // Test production deletion using persistProviderDeletionWithEvent (which logs event and tombstones observation)
        reopenedObsStore.persistProviderDeletionWithEvent(obsList[1], now)
        reopenedObsStore.markRemoved(obsList[2].notificationKey, now) // Dismissed notification preserved

        // Interruption recovery test: verify pending invalidation event is stored before cleanup completes
        val pendingBefore = reopenedObsStore.listPendingInvalidations(account)
        assertEquals(1, pendingBefore.size)
        assertEquals(obsList[1].id, pendingBefore.single().observationId)

        // Invoke startup recovery worker
        reopenedCoordinator.resumePendingReconciliation(account, batchSize = 50)

        // Provider-deleted observation matter must be removed and event acknowledged; dismissed notification matter must be preserved
        assertNull(reopenedStore.get(matterId1), "Startup recovery worker successfully cleaned up provider deletion")
        assertNotNull(reopenedStore.get(matterId2), "Dismissed notification preservation: non-provider dismissal preserves permitted evidence")
        assertTrue(reopenedObsStore.listPendingInvalidations(account).isEmpty(), "Acknowledge only after cleanup commits")

        // Run recovery again to verify repeat-safe idempotency
        reopenedCoordinator.resumePendingReconciliation(account, batchSize = 50)
        assertNull(reopenedStore.get(matterId1))
        assertNotNull(reopenedStore.get(matterId2))

        // Ensure unaffected accounts and independently supported matters remain intact
        val altMatterId = ScopedId(altAccount, ScopedId.Kind.ACTION_ITEM, "m-jdbc-alt-1")
        val altObs = obsList.first().copy(id = ScopedId(altAccount, ScopedId.Kind.OBSERVATION, "obs-alt"), accountId = altAccount, conversationId = ScopedId(altAccount, ScopedId.Kind.CONVERSATION, "conv-alt"), notificationKey = "provider:gmail:alt")
        reopenedObsStore.persistObservation(altObs)
        val altTicket = reopenedCoordinator.startWork(altAccount)
        val altRecord = MatterRecord(altMatterId, altAccount, "JDBC Alt Matter", MatterState.Open, listOf(EvidenceLink(altObs.id, "Alt snippet", now)), true, false, now)
        reopenedCoordinator.commitMatter(altRecord, altTicket)
        assertNotNull(reopenedStore.get(altMatterId), "Independently supported matter on unaffected account remains intact")
    }

    @Test
    fun testFocusReconciliationSchedulerIntegrationAndBatchDraining() {
        val tmpFile = File.createTempFile("scheduler_test_", ".db")
        tmpFile.deleteOnExit()
        val obsFile = File.createTempFile("obs_scheduler_", ".db")
        obsFile.deleteOnExit()

        val store = JdbcMatterStore(tmpFile)
        val obsStore = JdbcObservationStore(obsFile.absolutePath)
        val coordinator = ProductionFocusCoordinator(store, obsStore)
        store.setConsent(account, true, false)

        // Seed 105 observations and commit matters to test 105 events draining across multiple batches
        val bulkObs = (1..105).map { i ->
            val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-sched-$i")
            ObservedNotification(
                id = id,
                accountId = account,
                conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "conv-sched"),
                packageId = "com.whatsapp",
                notificationKey = "provider:whatsapp:sched-$i",
                notificationId = i,
                tag = null,
                senderHint = "Sender $i",
                title = "Title $i",
                textFragments = listOf("Sched message $i"),
                rawTextDigest = "digest-sched-$i",
                observedAt = now,
                postTime = now,
                revision = 1L,
                isRemoved = false,
                isGroupSummary = false,
                isOngoing = false,
                truncationState = TruncationState.Complete,
                correlationConfidence = CorrelationConfidence.High,
                provenance = ObservationProvenance.AndroidNotificationListener,
                notificationCategory = NotificationCategory.DirectMessage,
            )
        }
        obsStore.persistBatch(bulkObs)

        val ticket = coordinator.startWork(account)
        bulkObs.forEach { obs ->
            val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-${obs.id.key}")
            val record = MatterRecord(matterId, account, "Matter ${obs.id.key}", MatterState.Open, listOf(EvidenceLink(obs.id, "snippet", now)), true, false, now)
            coordinator.commitMatter(record, ticket)
            assertNotNull(store.get(matterId))
        }

        // Simulate 105 deletions committed via atomic batch persistence (which writes pending invalidation events)
        val removals = bulkObs.map { it.notificationKey to now }
        obsStore.persistAtomicBatch(removals, emptyList(), emptyList())
        assertEquals(105, obsStore.listPendingInvalidations(account, limit = 200).size)

        // Test scheduler initialization / startup recovery draining 105 events across multiple batches (batchSize = 25)
        val scheduler = FocusReconciliationScheduler(coordinator, batchSize = 25)
        scheduler.startStartupRecovery(account)

        // Wait for drain completion
        var attempts = 0
        while (obsStore.listPendingInvalidations(account).isNotEmpty() && attempts < 50) {
            Thread.sleep(50)
            attempts++
        }
        assertTrue(obsStore.listPendingInvalidations(account).isEmpty(), "All 105 events must drain across batches")
        bulkObs.forEach { obs ->
            val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-${obs.id.key}")
            assertNull(store.get(matterId), "Matter for deleted observation must be invalidated")
        }

        // Test failure stopping retries safely and explicit retry resuming using a faulty wrapper where MatterStore throws
        val faultyMatterStore = object : MatterStore {
            var throwOnInvalidate = true
            override fun invalidateForObservation(observationId: ScopedId) {
                if (throwOnInvalidate) throw IllegalStateException("SimulatedMatterStoreFailure")
                store.invalidateForObservation(observationId)
            }
            override fun upsert(matter: MatterRecord) = store.upsert(matter)
            override fun get(id: ScopedId) = store.get(id)
            override fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant) = store.transition(id, nextState, now)
            override fun listForAccount(accountId: AccountId) = store.listForAccount(accountId)
            override fun invalidateForAccount(accountId: AccountId) = store.invalidateForAccount(accountId)
            override fun setConsent(accountId: AccountId, granted: Boolean, excluded: Boolean) = store.setConsent(accountId, granted, excluded)
            override fun isConsentGranted(accountId: AccountId) = store.isConsentGranted(accountId)
            override fun isExcluded(accountId: AccountId) = store.isExcluded(accountId)
            override fun currentConsentRevision(accountId: AccountId) = store.currentConsentRevision(accountId)
            override fun issueConsentTicket(accountId: AccountId) = store.issueConsentTicket(accountId)
            override fun upsertWithTicket(matter: MatterRecord, ticket: ConsentTicket) = store.upsertWithTicket(matter, ticket)
        }
        val faultyCoordinator = ProductionFocusCoordinator(faultyMatterStore, obsStore)
        val faultyScheduler = FocusReconciliationScheduler(faultyCoordinator, batchSize = 10)

        val failObs = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-fail-1")
        val failNotif = ObservedNotification(
            id = failObs,
            accountId = account,
            conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "conv-fail"),
            packageId = "com.whatsapp",
            notificationKey = "provider:whatsapp:fail-1",
            notificationId = 999,
            tag = null,
            senderHint = "Sender Fail",
            title = "Title Fail",
            textFragments = listOf("Fail message"),
            rawTextDigest = "digest-fail",
            observedAt = now,
            postTime = now,
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = NotificationCategory.DirectMessage,
        )
        obsStore.persistObservation(failNotif)
        obsStore.persistAtomicBatch(listOf(failNotif.notificationKey to now), emptyList(), emptyList())

        assertEquals(1, obsStore.listPendingInvalidations(account).size)

        // Trigger scheduler; cleanup fails
        faultyScheduler.startStartupRecovery(account)
        Thread.sleep(200)

        assertEquals("IllegalStateException", faultyScheduler.failureState, "failureState is populated")
        assertEquals(1, obsStore.listPendingInvalidations(account).size, "failed event remains pending")

        // Fix fault and trigger explicit retry
        faultyMatterStore.throwOnInvalidate = false
        faultyScheduler.explicitRetry(account)

        // Wait for retry completion
        var attemptsRetry = 0
        while (obsStore.listPendingInvalidations(account).isNotEmpty() && attemptsRetry < 50) {
            Thread.sleep(50)
            attemptsRetry++
        }
        assertTrue(obsStore.listPendingInvalidations(account).isEmpty(), "explicit retry succeeds")
        assertNull(faultyScheduler.failureState, "explicit retry clears failure state")
    }
}
