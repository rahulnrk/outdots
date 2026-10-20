package app.mini.hub.android.persistence

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.AndroidKeystoreAccessTokenStore
import app.mini.hub.android.connector.AndroidGmailConnector
import app.mini.hub.android.connector.GmailSyncReport
import app.mini.hub.connector.*
import app.mini.hub.domain.*
import app.mini.hub.persistence.SqlCipherObservationStore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidGmailOverlapInstrumentationTest {
    private fun database(store: Any): net.zetetic.database.sqlcipher.SQLiteDatabase = store.javaClass
        .getDeclaredMethod("getDatabase").apply { isAccessible = true }.invoke(store) as net.zetetic.database.sqlcipher.SQLiteDatabase

    @Test
    fun testOverlappingAndroidGmailSyncRequestsCoalesce() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "gmail-test-${UUID.randomUUID()}"
        val dbName = "test-gmail-obs.db"

        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("$prefix-$name", mode)
            override fun getDatabasePath(name: String): File {
                val baseFile = super.getDatabasePath(name)
                return File(baseFile.parentFile, "$prefix-$name")
            }
            override fun deleteDatabase(name: String): Boolean {
                val dbFile = getDatabasePath(name)
                var deleted = dbFile.delete()
                val shm = File(dbFile.path + "-shm")
                if (shm.exists()) deleted = shm.delete() || deleted
                val wal = File(dbFile.path + "-wal")
                if (wal.exists()) deleted = wal.delete() || deleted
                return deleted
            }
        }

        val accounts = AndroidConnectedAccountStore(context)
        val tokens = AndroidKeystoreAccessTokenStore(context)
        val store = SqlCipherObservationStore(context, dbName)

        val account = ConnectedAccount(
            id = AccountId(Provider.GMAIL, "android-gmail-coalesce"),
            providerAccountId = "android-coalesce@gmail.com",
            displayName = "Android Coalesce",
            accountType = AccountType.GoogleConsumer,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Healthy,
        )
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("android-token", null))

        val profileAttempts = AtomicInteger(0)
        val messageListAttempts = AtomicInteger(0)
        val metadataAttempts = AtomicInteger(0)

        val enterLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val transport = ProviderHttpTransport.of { req ->
            when {
                req.url.contains("/profile") -> {
                    profileAttempts.incrementAndGet()
                    enterLatch.countDown()
                    assertTrue(releaseLatch.await(5, TimeUnit.SECONDS))
                    ProviderHttpResponse(200, "{\"emailAddress\":\"android-coalesce@gmail.com\",\"historyId\":\"2000\"}")
                }
                req.url.contains("/messages?") -> {
                    messageListAttempts.incrementAndGet()
                    ProviderHttpResponse(200, "{\"messages\":[{\"id\":\"m1\",\"threadId\":\"t1\"}],\"nextPageToken\":null}")
                }
                req.url.contains("/messages/m1") -> {
                    metadataAttempts.incrementAndGet()
                    ProviderHttpResponse(200, "{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"1700000000000\",\"snippet\":\"Inbox message\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"Sender <s@ex.com>\"},{\"name\":\"Subject\",\"value\":\"Hello\"}]}}")
                }
                else -> fail("Unexpected HTTP endpoint: ${req.url}")
            }
        }

        val gmailConnector = AndroidGmailConnector(
            context = context,
            observationStore = store,
            digest = DigestProvider { "dig-$it" },
            transport = transport,
        )

        val pool = Executors.newFixedThreadPool(2)
        var reopenedStore: SqlCipherObservationStore? = null
        try {
            val f1: Future<List<GmailSyncReport>> = pool.submit<List<GmailSyncReport>> { gmailConnector.syncAllConnected(1_700_000_000_000L, "trigger-1") }
            assertTrue(enterLatch.await(5, TimeUnit.SECONDS), "first sync must enter profile call")

            val f2: Future<List<GmailSyncReport>> = pool.submit<List<GmailSyncReport>> { gmailConnector.syncAllConnected(1_700_000_000_000L, "trigger-2") }
            val reports2 = f2.get(5, TimeUnit.SECONDS)
            val r2 = reports2.first { report -> report.accountLocalId == account.id.localId }

            assertEquals("coalesced", r2.skippedReason, "second concurrent sync must coalesce and return skippedReason=coalesced")

            releaseLatch.countDown()
            val reports1 = f1.get(5, TimeUnit.SECONDS)
            val r1 = reports1.first { report -> report.accountLocalId == account.id.localId }

            assertEquals(ConnectionState.Healthy, r1.state)
            assertEquals(1, r1.inserted)
            assertEquals(1, profileAttempts.get(), "exactly one profile request executed")
            assertEquals(1, messageListAttempts.get(), "exactly one message list request executed")
            assertEquals(1, metadataAttempts.get(), "exactly one message metadata request executed")

            // 1. Use the actual production observation ID convention "provider:${account.id.key}:m1"
            val expectedObsId = ScopedId(account.id, ScopedId.Kind.OBSERVATION, "provider:${account.id.key}:m1")
            val obs = store.getObservation(expectedObsId)
            assertNotNull(obs)
            assertEquals("Inbox message", obs.textFragments.single())

            // 2. Assert store.getCursor contains lastProcessedKey == "2000", both before and after close/reopen
            val cursorKey = "gmail:${account.id.key}"
            val cursorBefore = store.getCursor(cursorKey)
            assertNotNull(cursorBefore)
            assertEquals("2000", cursorBefore.lastProcessedKey)

            // 3. Close actual store handle before reopening
            database(store).close()

            reopenedStore = SqlCipherObservationStore(context, dbName)
            assertEquals("Inbox message", reopenedStore.getObservation(expectedObsId)!!.textFragments.single())
            val cursorAfter = reopenedStore.getCursor(cursorKey)
            assertNotNull(cursorAfter)
            assertEquals("2000", cursorAfter.lastProcessedKey)
        } catch (e: Throwable) {
            // Ensure failure paths also close open database handles and stop worker threads
            try { database(store).close() } catch (_: Throwable) {}
            try { reopenedStore?.let { database(it).close() } } catch (_: Throwable) {}
            throw e
        } finally {
            releaseLatch.countDown()
            pool.shutdownNow()
            try { database(store).close() } catch (_: Throwable) {}
            try { reopenedStore?.let { database(it).close() } } catch (_: Throwable) {}
            accounts.remove(account.id)
            tokens.remove(account.id)
            context.deleteDatabase(dbName)
        }
    }
}
