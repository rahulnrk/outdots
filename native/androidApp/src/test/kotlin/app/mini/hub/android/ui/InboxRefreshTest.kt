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
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** T5: refresh reads off the main thread, drops stale results, avoids repeated reads; VM survives rotation. */
class InboxRefreshTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }
    private val account = AccountId(Provider.WHATSAPP, "local_user")

    private class CountingStore(private val inner: InMemoryObservationStore = InMemoryObservationStore()) : ObservationStore by inner {
        var conversationReads = 0
        override fun getObservationsForConversation(conversationId: ScopedId, limit: Int): List<ObservedNotification> {
            conversationReads++
            return inner.getObservationsForConversation(conversationId, limit)
        }
    }

    private fun obs(conv: String, n: Int) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "o-$conv-$n"), accountId = account,
        conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, conv), packageId = "com.whatsapp",
        notificationKey = "k-$conv-$n", notificationId = n, tag = null, senderHint = conv, title = conv,
        textFragments = listOf("message $n"), rawTextDigest = "d$conv$n", observedAt = UtcInstant(1_700_000_000_000L + n),
        postTime = UtcInstant(1_700_000_000_000L + n), revision = 1L, isRemoved = false, isGroupSummary = false,
        truncationState = TruncationState.Complete, correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.AndroidNotificationListener,
    )

    private fun vm(store: ObservationStore, sources: () -> Set<CommunicationSource>, runner: (Runnable) -> Unit) = InboxViewModel(
        observationStore = store, retrievalEngine = ScopedRetrievalEngine(store), styleEngine = AdaptiveStyleEngine(),
        actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)), actionStore = InMemoryActionStore(),
        clock = clock, attentionStore = InMemoryAttentionStore(), selectedSources = sources, refreshRunner = runner,
    )

    @Test(timeout = 30_000)
    fun refreshRunsStoreReadsOnTheRefreshRunner() {
        val store = InMemoryObservationStore().apply { persistObservation(obs("Alice", 1)) }
        val queued = mutableListOf<Runnable>()
        val model = vm(store, { CommunicationSource.defaultSelection }) { queued += it }
        model.refresh(true, true)
        assertEquals(0, model.state.value.conversations.size, "nothing applied before the worker runs")
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(1, model.state.value.conversations.size)
    }

    @Test(timeout = 30_000)
    fun unchangedConversationsAreNotReReadOnEveryRefresh() {
        val store = CountingStore().apply {
            persistObservation(obs("Alice", 1)); persistObservation(obs("Bob", 2)); persistObservation(obs("Carol", 3))
        }
        val model = vm(store, { CommunicationSource.defaultSelection }) { it.run() }
        model.refresh(true, true)
        val afterFirst = store.conversationReads
        model.refresh(true, true)
        model.refresh(true, true)
        assertEquals(afterFirst, store.conversationReads, "no per-conversation reads when nothing changed")

        store.persistObservation(obs("Bob", 4))
        model.refresh(true, true)
        assertEquals(afterFirst + 1, store.conversationReads, "only the changed conversation is read")
        assertEquals(3, model.state.value.understanding.size)
    }

    @Test(timeout = 30_000)
    fun viewModelAndDraftSurviveRotation() {
        val store = InMemoryObservationStore()
        val viewModelStore = ViewModelStore() // retained by the Activity across configuration changes
        var created = 0
        val factory = InboxViewModelHolder.factory { created++; vm(store, { CommunicationSource.defaultSelection }) { it.run() } }

        val first = ViewModelProvider(viewModelStore, factory)[InboxViewModelHolder::class.java].viewModel
        first.updateLocalDraftText("half-typed reply")
        // Rotation: a new Activity instance asks the same retained store again.
        val second = ViewModelProvider(viewModelStore, factory)[InboxViewModelHolder::class.java].viewModel

        assertSame(first, second)
        assertEquals(1, created)
        assertEquals("half-typed reply", second.state.value.localDraftText)
    }
}
