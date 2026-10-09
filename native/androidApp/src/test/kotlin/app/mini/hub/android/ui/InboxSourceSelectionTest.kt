package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class InboxSourceSelectionTest {
    private val now = UtcInstant(1_700_000_000_000L)
    private val clock = Clock { now }
    private var id = 0
    private val ids = IdGenerator { prefix -> "$prefix-${id++}" }
    private val digest = DigestProvider { it }

    private fun observation(account: AccountId, conversation: String, packageId: String) =
        ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs-${id++}"),
            accountId = account,
            conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, conversation),
            packageId = packageId,
            notificationKey = "key-$conversation",
            notificationId = id,
            tag = null,
            senderHint = conversation,
            title = conversation,
            textFragments = listOf("message"),
            rawTextDigest = "digest-$conversation",
            observedAt = now,
            postTime = now,
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
        )

    @Test
    fun hiddenSourceStaysStoredAndAttentionChoiceSurvivesSelectionChange() {
        val observations = InMemoryObservationStore()
        val attention = InMemoryAttentionStore()
        val whatsapp = AccountId(Provider.WHATSAPP, "local_user")
        val gmail = AccountId(Provider.GMAIL, "local_user")
        val gmailConversation = ScopedId(gmail, ScopedId.Kind.CONVERSATION, "GmailThread")
        observations.persistObservation(observation(whatsapp, "WhatsAppChat", "com.whatsapp"))
        observations.persistObservation(observation(gmail, "GmailThread", "com.google.android.gm"))

        var selected = CommunicationSource.defaultSelection
        val vm = InboxViewModel(
            observationStore = observations,
            retrievalEngine = ScopedRetrievalEngine(observations),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(ids)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = attention,
            selectedSources = { selected },
        )
        vm.refresh(true, true)
        assertEquals(2, vm.state.value.conversations.size)

        vm.applyAttentionAction(gmailConversation, AttentionAction.SetPriority(ConversationPriority.Important))
        assertEquals(ConversationPriority.Important, vm.state.value.attentionRecords[gmailConversation.key]?.priority)

        selected = setOf(CommunicationSource.WhatsApp)
        vm.refresh(true, true)

        assertEquals(listOf("com.whatsapp"), vm.state.value.conversations.map { it.packageId })
        assertEquals(
            ConversationPriority.Important,
            vm.state.value.attentionRecords[gmailConversation.key]?.priority,
            "Filtering must hide data without deleting attention choices",
        )
        assertEquals(2, observations.recoverFromRestart().totalObservations)
    }
}
