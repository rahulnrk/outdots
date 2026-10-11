package app.mini.hub.android

import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T2: an encrypted-store open failure must be explicit, blocking and inert. */
class RuntimeStorageTest {

    @Test(timeout = 30_000)
    fun observationStoreOpenFailureIsExplicitAndExposesNoFallbackStore() {
        val state = RuntimeStorage.open(
            openObservations = { throw IllegalStateException("keystore unavailable") },
            openActions = { InMemoryActionStore() },
            openAttention = { InMemoryAttentionStore() },
        )
        val failed = assertIs<RuntimeStorage.Failed>(state)
        // Plain-language, blocking message for the UI; no exception text leaks into it.
        assertTrue(failed.userMessage.contains("encrypted storage", ignoreCase = true))
        assertFalse(failed.userMessage.contains("keystore unavailable"))
    }

    @Test(timeout = 30_000)
    fun actionStoreOpenFailureIsAlsoFailed() {
        val state = RuntimeStorage.open(
            openObservations = { InMemoryObservationStore() },
            openActions = { throw RuntimeException("sqlcipher load") },
            openAttention = { InMemoryAttentionStore() },
        )
        assertIs<RuntimeStorage.Failed>(state)
    }

    @Test(timeout = 30_000)
    fun allStoresOpenGivesReady() {
        val state = RuntimeStorage.open(
            openObservations = { InMemoryObservationStore() },
            openActions = { InMemoryActionStore() },
            openAttention = { InMemoryAttentionStore() },
        )
        assertIs<RuntimeStorage.Ready>(state)
    }

    @Test(timeout = 30_000)
    fun backgroundWorkIsSkippedWithCountFreeLogWhenStorageFailed() {
        val failed = RuntimeStorage.open(
            openObservations = { throw IllegalStateException("x") },
            openActions = { InMemoryActionStore() },
            openAttention = { InMemoryAttentionStore() },
        )
        val logs = mutableListOf<String>()
        var ran = 0
        val executed = RuntimeStorage.runIfReady(failed, "sync_job", logs::add) { ran++ }
        assertFalse(executed)
        assertEquals(0, ran)
        assertEquals(listOf("work=sync_job skipped storage=unavailable"), logs)
        assertFalse(logs.single().any { it.isDigit() }, "state line must carry no counts")

        val ready = RuntimeStorage.open({ InMemoryObservationStore() }, { InMemoryActionStore() }, { InMemoryAttentionStore() })
        assertTrue(RuntimeStorage.runIfReady(ready, "sync_job", logs::add) { ran++ })
        assertEquals(1, ran)
    }
}
