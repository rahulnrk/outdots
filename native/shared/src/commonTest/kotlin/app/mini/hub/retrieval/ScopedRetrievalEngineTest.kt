package app.mini.hub.retrieval

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedRetrievalEngineTest {
    private val account = AccountId(Provider.WHATSAPP, "user_1")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "client_project")
    private val scope = CompartmentId("client:project")

    @Test
    fun testRetrievalWithinBudget() {
        val store = InMemoryObservationStore()
        val obs1 = createObs(1, "Meeting scheduled for Monday at 10 AM")
        val obs2 = createObs(2, "Please bring the project deck")
        store.persistObservation(obs1)
        store.persistObservation(obs2)

        val engine = ScopedRetrievalEngine(store)
        val result = engine.retrieve(
            ScopedRetrievalQuery(
                conversationId = convId,
                compartment = scope,
                tokenBudget = 500,
            )
        )

        assertEquals(2, result.retrievedObservations.size)
        assertEquals(0, result.omittedCount)
        assertEquals(2, result.contextBundle.evidence.size)
        assertFalse(result.contextBundle.hasMissingContext)
        assertEquals("", result.coverageWarning)
    }

    @Test
    fun testRetrievalExceedingBudgetEmitsOmissionWarning() {
        val store = InMemoryObservationStore()
        for (i in 1..20) {
            store.persistObservation(createObs(i, "Long message description for item number $i with many additional details"))
        }

        val engine = ScopedRetrievalEngine(store)
        val result = engine.retrieve(
            ScopedRetrievalQuery(
                conversationId = convId,
                compartment = scope,
                tokenBudget = 50,
            )
        )

        assertTrue(result.retrievedObservations.size in 1..5)
        assertTrue(result.omittedCount > 0)
        assertTrue(result.contextBundle.hasMissingContext)
        assertTrue(result.coverageWarning.contains("omitted"))
    }

    private fun createObs(idx: Int, text: String): ObservedNotification {
        return ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_$idx"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "key_$idx",
            notificationId = idx,
            tag = null,
            senderHint = "Client",
            title = "Project",
            textFragments = listOf(text),
            rawTextDigest = "hash_$idx",
            observedAt = UtcInstant(1_700_000_000_000L + idx * 1000L),
            postTime = UtcInstant(1_700_000_000_000L + idx * 1000L),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )
    }
}
