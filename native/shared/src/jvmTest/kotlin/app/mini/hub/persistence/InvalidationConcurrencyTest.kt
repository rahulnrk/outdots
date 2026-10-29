package app.mini.hub.persistence

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class InvalidationConcurrencyTest {
    @Test
    fun pollingAndAcknowledgingInvalidationsShareTheConnectionSafely() {
        val database = Files.createTempFile("mini-invalidation-concurrency", ".db").toFile()
        database.deleteOnExit()
        val store = JdbcObservationStore(database.absolutePath)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val workers = (0 until 4).map { worker ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(100) {
                        if (worker % 2 == 0) {
                            check(store.listPendingInvalidations(null, 25).isEmpty())
                        } else {
                            // A missing event is a valid idempotent acknowledgement.
                            store.acknowledgeInvalidations(listOf(-1L))
                        }
                    }
                }
            }
            start.countDown()
            workers.forEach { it.get(15, TimeUnit.SECONDS) }
            assertTrue(store.listPendingInvalidations(null, 25).isEmpty())
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
