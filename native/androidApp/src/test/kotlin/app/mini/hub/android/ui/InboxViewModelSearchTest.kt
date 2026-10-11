package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxViewModelSearchTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }
    private val acct = AccountId(Provider.GMAIL, "work")

    private fun obs(conv: String, id: String, text: String, pkg: String = "com.google.android.gm") = ObservedNotification(
        id = ScopedId(acct, ScopedId.Kind.OBSERVATION, "obs_$id"),
        accountId = acct,
        conversationId = ScopedId(acct, ScopedId.Kind.CONVERSATION, conv),
        packageId = pkg,
        notificationKey = "key_$id",
        notificationId = id.hashCode(),
        tag = null,
        senderHint = "Sender",
        title = conv,
        textFragments = listOf(text),
        rawTextDigest = "digest_$id",
        observedAt = clock.now(),
        postTime = clock.now(),
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.OfficialProviderApi,
    )

    @Test(timeout = 30_000)
    fun searchExecutesOffMainThreadAndDropsStaleResults() {
        val store = InMemoryObservationStore()
        store.persistObservation(obs("AlphaConv", "1", "Project alpha status update"))
        store.persistObservation(obs("BetaConv", "2", "Project beta status update"))

        val queued = mutableListOf<Runnable>()
        val vm = InboxViewModel(
            observationStore = store,
            retrievalEngine = ScopedRetrievalEngine(store),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            selectedSources = { setOf(CommunicationSource.Gmail, CommunicationSource.WhatsApp) },
            refreshRunner = { queued += it },
        )

        // Type query 1: "alpha"
        vm.onSearchQueryChanged("alpha")
        assertEquals("alpha", vm.state.value.searchQuery)
        assertTrue(vm.state.value.isSearching)

        // Type query 2: "beta" before worker for "alpha" ran
        vm.onSearchQueryChanged("beta")
        assertEquals(2, queued.size)

        // Run worker 2 (newer) first
        queued[1].run()
        assertEquals(1, vm.state.value.searchResults.size)
        assertEquals("BetaConv", vm.state.value.searchResults.single().conversationTitle)
        assertFalse(vm.state.value.isSearching)

        // Run worker 1 (stale, older) late -> must be dropped
        queued[0].run()
        assertEquals(1, vm.state.value.searchResults.size)
        assertEquals("BetaConv", vm.state.value.searchResults.single().conversationTitle)
    }

    @Test(timeout = 30_000)
    fun selectingSearchResultOpensConversation() {
        val store = InMemoryObservationStore()
        store.persistObservation(obs("TargetConv", "10", "Important decision reached"))

        val vm = InboxViewModel(
            observationStore = store,
            retrievalEngine = ScopedRetrievalEngine(store),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            selectedSources = { setOf(CommunicationSource.Gmail) },
            refreshRunner = { it.run() },
        )
        vm.refresh(true, true)

        vm.onSearchQueryChanged("decision")
        val result = vm.state.value.searchResults.single()

        vm.selectConversationFromSearch(result)
        assertEquals("TargetConv", vm.state.value.selectedConversation?.title)
    }
}
