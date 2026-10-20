package app.mini.hub.android.persistence

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.matter.*
import app.mini.hub.persistence.SqlCipherMatterStore
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidSqlCipherMatterTest {
    private val account = AccountId(Provider.WHATSAPP, "user_android")
    private val altAccount = AccountId(Provider.GMAIL, "user_android_alt")
    private val now = UtcInstant(1_700_000_000_000L)

    private fun database(store: Any): SQLiteDatabase = store.javaClass
        .getDeclaredMethod("getDatabase").apply { isAccessible = true }.invoke(store) as SQLiteDatabase

    @Test
    fun testSqlCipherMatterStoreDefaultDeniedPersistenceReopenAndBoundaries() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store = SqlCipherMatterStore(context, dbName)
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-android-1")
        val obsId = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-android-1")
        val link = EvidenceLink(obsId, "Android Evidence snippet", now)

        val record = MatterRecord(
            id = matterId,
            accountId = account,
            title = "Android Durable Matter",
            state = MatterState.Open,
            evidenceLinks = listOf(link),
            consentGranted = true,
            excluded = false,
            updatedAt = now,
        )

        // 1. Default missing account consent is DENIED (false), so upsert without explicit consent fails
        assertFailsWith<IllegalArgumentException> {
            store.upsert(record)
        }

        // Grant explicit consent for account
        store.setConsent(account, true, false)
        store.upsert(record)
        assertEquals(record, store.get(matterId))

        // Close store handle cleanly before reopening
        database(store).close()

        // Reopen store to verify persistence across restart
        val reopenedStore = SqlCipherMatterStore(context, dbName)
        val fetched = reopenedStore.get(matterId)
        assertNotNull(fetched)
        assertEquals("Android Durable Matter", fetched.title)

        // Revocation survives restart and blocks reads/writes
        reopenedStore.setConsent(account, false, true)
        assertNull(reopenedStore.get(matterId))
        assertTrue(reopenedStore.listForAccount(account).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            reopenedStore.upsert(record.copy(updatedAt = now + 1000L))
        }

        // Close reopenedStore cleanly before second reopen after re-granting consent
        database(reopenedStore).close()

        // Reopen again AFTER revocation and re-grant to prove permission survives restart
        val reGrantedStore = SqlCipherMatterStore(context, dbName)
        reGrantedStore.setConsent(account, true, false)
        val reGrantedRecord = record.copy(updatedAt = now + 2000L)
        reGrantedStore.upsert(reGrantedRecord)
        assertNotNull(reGrantedStore.get(matterId))
        assertEquals(1, reGrantedStore.listForAccount(account).size)

        database(reGrantedStore).close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testDeterministicRevocationAndWriteOrderingAcrossSqlCipherStores() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-ordered-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store1 = SqlCipherMatterStore(context, dbName)
        val store2 = SqlCipherMatterStore(context, dbName)

        store1.setConsent(account, true, false)
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-ordered")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1"), "snippet", now)
        val record = MatterRecord(matterId, account, "Ordered Matter", MatterState.Open, listOf(link), true, false, now)

        store1.upsert(record)

        // Case A: Revocation commits first -> subsequent stale write must fail
        store1.setConsent(account, false, true)
        val staleWriteResult = runCatching { store2.upsert(record.copy(title = "Stale Update", updatedAt = now + 1000L)) }
        assertTrue(staleWriteResult.isFailure, "Stale write after revocation must fail")
        assertNull(store2.get(matterId))

        // Case B: Write commits first (re-grant consent and upsert), then revocation commits -> revocation hides/blocks matter
        store2.setConsent(account, true, false)
        store2.upsert(record)
        assertNotNull(store2.get(matterId))

        // Now revoke consent
        store1.setConsent(account, false, true)
        assertNull(store2.get(matterId))
        assertTrue(store2.listForAccount(account).isEmpty())

        // Reopen store to prove revocation persisted across restart
        database(store1).close()
        database(store2).close()
        val reopenedStore = SqlCipherMatterStore(context, dbName)
        assertNull(reopenedStore.get(matterId))
        assertTrue(reopenedStore.listForAccount(account).isEmpty())

        database(reopenedStore).close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testUpsertTriggerFailureRollsBackMatterAndEvidenceTogether() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-trigger-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store = SqlCipherMatterStore(context, dbName)
        store.setConsent(account, true, false)
        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-trigger")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1"), "snippet", now)
        val record = MatterRecord(matterId, account, "Trigger Matter", MatterState.Open, listOf(link), true, false, now)

        store.upsert(record)
        assertNotNull(store.get(matterId))

        val db = database(store)
        // Create a test-only SQLite trigger that aborts an evidence insert after matter update
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS fail_evidence_insert
            BEFORE INSERT ON matter_evidence
            BEGIN
                SELECT RAISE(ABORT, 'forced trigger failure during evidence insert');
            END;
        """)

        val updatedRecord = record.copy(title = "Should Not Persist", updatedAt = now + 1000L)
        val upsertResult = runCatching { store.upsert(updatedRecord) }
        assertTrue(upsertResult.isFailure, "Upsert should fail due to trigger abortion")

        // Drop trigger so subsequent operations work normally
        db.execSQL("DROP TRIGGER IF EXISTS fail_evidence_insert")

        // Close and reopen store to verify original matter and evidence remain unchanged
        db.close()
        val reopenedStore = SqlCipherMatterStore(context, dbName)
        val fetched = reopenedStore.get(matterId)
        assertNotNull(fetched)
        assertEquals("Trigger Matter", fetched.title)
        assertEquals(1, fetched.evidenceLinks.size)

        database(reopenedStore).close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testFailedConsentWriteIsReported() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-consent-fail-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store = SqlCipherMatterStore(context, dbName)
        val db = database(store)

        // Drop account_consent table to simulate write failure / store corruption
        db.execSQL("DROP TABLE IF EXISTS account_consent")

        val consentResult = runCatching { store.setConsent(account, true, false) }
        assertTrue(consentResult.isFailure, "Writing consent when table is missing must throw an error")

        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testSqlCipherAdditiveConsentRevisionMigrationAndReopen() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-migration-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        // Seed legacy database with v1 account_consent table missing 'revision' column
        val store = SqlCipherMatterStore(context, dbName)
        val db = database(store)
        db.execSQL("DROP TABLE IF EXISTS account_consent")
        db.execSQL("""
            CREATE TABLE account_consent (
                account_key TEXT PRIMARY KEY,
                consent_integer INTEGER NOT NULL,
                excluded_integer INTEGER NOT NULL
            )
        """)
        db.execSQL("INSERT INTO account_consent VALUES (?, 1, 0)", arrayOf(account.key))
        db.close()

        // Reopen via SqlCipherMatterStore, which should run the additive migration successfully
        val migratedStore = SqlCipherMatterStore(context, dbName)
        assertTrue(migratedStore.isConsentGranted(account))
        assertFalse(migratedStore.isExcluded(account))
        assertEquals(0L, migratedStore.currentConsentRevision(account))

        // Ensure tickets can be issued and revision increments on setConsent
        migratedStore.setConsent(account, true, false)
        assertEquals(1L, migratedStore.currentConsentRevision(account))
        val ticket = migratedStore.issueConsentTicket(account)
        assertEquals(1L, ticket.revision)

        database(migratedStore).close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testDeniedTicketIssuanceAndStaleResultRejectionAcrossRevocation() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-tickets-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store = SqlCipherMatterStore(context, dbName)

        // Denied account cannot issue consent ticket
        assertFailsWith<IllegalArgumentException> {
            store.issueConsentTicket(account)
        }

        // Grant consent and issue ticket
        store.setConsent(account, true, false)
        val ticket1 = store.issueConsentTicket(account)

        val matterId = ScopedId(account, ScopedId.Kind.ACTION_ITEM, "m-ticket-test")
        val link = EvidenceLink(ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-1"), "snippet", now)
        val record = MatterRecord(matterId, account, "Ticket Matter", MatterState.Open, listOf(link), true, false, now)

        store.upsertWithTicket(record, ticket1)
        assertNotNull(store.get(matterId))

        // Revoke consent -> increments revision and revokes
        store.setConsent(account, false, true)
        assertFailsWith<IllegalArgumentException> {
            store.issueConsentTicket(account) // revoked account cannot issue ticket
        }

        // Re-enable consent -> increments revision again
        store.setConsent(account, true, false)
        val ticket3 = store.issueConsentTicket(account)
        assertEquals(3L, ticket3.revision)

        // Submitting OLD work with ticket1 or ticket2 must be rejected
        val staleRecord = record.copy(title = "Stale Result", updatedAt = now + 1000L)
        assertFailsWith<IllegalArgumentException> {
            store.upsertWithTicket(staleRecord, ticket1)
        }

        // Submitting with current ticket3 succeeds
        val freshRecord = record.copy(title = "Fresh Result", updatedAt = now + 2000L)
        store.upsertWithTicket(freshRecord, ticket3)
        val fetched = store.get(matterId)
        assertNotNull(fetched)
        assertEquals("Fresh Result", fetched.title)

        database(store).close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testRapidToggleOffLastWinsSequencing() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "matter-rapid-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val dbName = "$prefix-matters.db"

        val store = SqlCipherMatterStore(context, dbName)

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(2)

        // Rapid ON then OFF submitted sequentially
        executor.execute {
            store.setConsent(account, true, false)
            latch.countDown()
        }
        executor.execute {
            store.invalidateForAccount(account)
            latch.countDown()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        executor.shutdown()

        // Durable permission must finish OFF (revoked / excluded)
        assertFalse(store.isConsentGranted(account))
        assertTrue(store.isExcluded(account))

        database(store).close()
        context.deleteDatabase(dbName)
    }
}
