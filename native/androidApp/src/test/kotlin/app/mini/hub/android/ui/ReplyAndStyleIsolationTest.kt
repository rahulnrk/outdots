package app.mini.hub.android.ui

import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.attention.InMemoryAttentionStore
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InMemoryConnectedAccountStore
import app.mini.hub.connector.MessageDirection
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.retrieval.ScopedRetrievalQuery
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleConsent
import app.mini.hub.style.TextAuthorship
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 2 T3:
 * 1. Reply targets the newest inbound message in a thread (not the oldest, not own messages).
 * 2. AdaptiveStyleEngine keys every profile and sample with the account id; two accounts never share style/examples; old un-accounted category profiles are discarded on restore.
 * 3. ScopedRetrievalEngine rejects compartments that do not match the conversation's account.
 */
class ReplyAndStyleIsolationTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private var idCounter = 1L
    private val idGen = IdGenerator { prefix -> "$prefix-${idCounter++}" }
    private val digest = DigestProvider { payload -> "hash_${payload.hashCode()}" }

    private val account = AccountId(Provider.GMAIL, "work")
    private val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "thread_3")

    private fun gmailAccount() = ConnectedAccount(
        account, "me@work.com", "Me Work", AccountType.GoogleConsumer,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy,
    )

    private fun threadMessage(id: String, postMillis: Long, dir: MessageDirection, sender: String) = ObservedNotification(
        id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs_$id"),
        accountId = account,
        conversationId = convId,
        packageId = "com.google.android.gm",
        notificationKey = "provider:${account.key}:$id",
        notificationId = id.hashCode(),
        tag = null,
        senderHint = sender,
        title = "Sprint Planning",
        textFragments = listOf("body $id"),
        rawTextDigest = "digest_$id",
        observedAt = UtcInstant(postMillis),
        postTime = UtcInstant(postMillis),
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = ObservationProvenance.OfficialProviderApi,
        direction = dir,
        replyAnchor = "<$id@mail.test>",
    )

    @Test(timeout = 30_000)
    fun prepareDraftPicksNewestInboundMessageInThreeMessageThread() {
        val obsStore = InMemoryObservationStore()
        // Three messages in order:
        // 1. Inbound from Alice (10:00)
        // 2. Outbound reply from me (10:05)
        // 3. Newest inbound from Alice (10:10)
        obsStore.persistObservation(threadMessage("m1", 1_700_000_000_000L, MessageDirection.Inbound, "Alice <alice@example.com>"))
        obsStore.persistObservation(threadMessage("m2", 1_700_000_005_000L, MessageDirection.Outbound, "Me <me@work.com>"))
        obsStore.persistObservation(threadMessage("m3", 1_700_000_010_000L, MessageDirection.Inbound, "Alice <alice@example.com>"))

        val accountStore = InMemoryConnectedAccountStore().apply { upsert(gmailAccount()) }
        val vm = InboxViewModel(
            observationStore = obsStore,
            retrievalEngine = ScopedRetrievalEngine(obsStore),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, digest, DomainActionIdGenerator(idGen)),
            actionStore = InMemoryActionStore(),
            clock = clock,
            attentionStore = InMemoryAttentionStore(),
            accountStore = accountStore,
            apiReplyAllowed = { true },
        )
        vm.refresh(true, true)
        val conv = obsStore.getConversationSummaries().first()
        vm.selectConversation(conv)

        vm.prepareDraft("Sure, see you then.", "")
        val payload = vm.state.value.activeReviewPayload
        assertNotNull(payload)
        // Must target m3 (newest inbound), NOT m1 (oldest inbound, which lastOrNull on a descending list picked)
        assertEquals("m3", payload.replyTarget, "reply target must be newest inbound message id")
    }

    @Test(timeout = 30_000)
    fun twoAccountsDoNotShareCategoryProfilesOrExampleSnippets() {
        val engine = AdaptiveStyleEngine(
            consent = StyleConsent(
                isEnabled = true,
                grantedCategories = setOf(ContextCategory.Professional),
            ),
        )
        val acctA = AccountId(Provider.GMAIL, "work_a")
        val acctB = AccountId(Provider.GMAIL, "personal_b")
        val now = clock.now()

        // Learn professional text on Account A (2 samples to reach recipient confidence threshold >= 0.40)
        assertTrue(
            engine.learnFromText(
                text = "Regarding the quarterly deliverables, please find attached the reviewed statement.",
                authorship = TextAuthorship.UserAuthoredSentMessage,
                category = ContextCategory.Professional,
                recipientId = "client@corp.test",
                now = now,
                sampleId = "sample_a_1",
                account = acctA,
            ),
        )
        assertTrue(
            engine.learnFromText(
                text = "Thank you for the prompt update on the deliverables.",
                authorship = TextAuthorship.UserAuthoredSentMessage,
                category = ContextCategory.Professional,
                recipientId = "client@corp.test",
                now = now,
                sampleId = "sample_a_2",
                account = acctA,
            ),
        )

        // Account A resolves professional profile: trained, has example
        val profileA = engine.resolveProfile(ContextCategory.Professional, "client@corp.test", now, account = acctA)
        assertTrue(profileA.observationCount > 0)
        assertTrue(profileA.exampleSnippets.isNotEmpty())

        // Account B resolves professional profile for same category/recipient name: must be neutral default, 0 samples
        val profileB = engine.resolveProfile(ContextCategory.Professional, "client@corp.test", now, account = acctB)
        assertEquals(0, profileB.observationCount, "Account B must not inherit Account A's category profile")
        assertTrue(profileB.exampleSnippets.isEmpty(), "Account B must not receive Account A's example snippets")

        // Account B's category-fallback profile must also be empty
        val catProfileB = engine.resolveProfile(ContextCategory.Professional, null, now, account = acctB)
        assertEquals(0, catProfileB.observationCount)
        assertTrue(catProfileB.exampleSnippets.isEmpty())
    }

    @Test(timeout = 30_000)
    fun restoreDiscardsOldAccountlessCategoryProfiles() {
        // Build export string with a legacy "cat:Professional" profile (no account prefix in id)
        val engine = AdaptiveStyleEngine(
            consent = StyleConsent(isEnabled = true, grantedCategories = setOf(ContextCategory.Professional)),
        )
        val now = clock.now()
        val acct = AccountId(Provider.GMAIL, "work")
        engine.learnFromText(
            text = "Legacy style text that was saved before.",
            authorship = TextAuthorship.UserAuthoredSentMessage,
            category = ContextCategory.Professional,
            recipientId = "recip_1",
            now = now,
            account = acct,
        )

        // Synthesize an export that includes an old un-accounted profile key "cat:Professional"
        val exported = engine.exportState() + "p|6361743a50726f66657373696f6e616c|Professional||0.8|0.5|0.5|0.0|4e65757472616c|4e65757472616c|4e6f6e65|4c6567616379|5|0.8|1|1700000000000\n"

        val restored = AdaptiveStyleEngine()
        assertTrue(restored.importState(exported))

        // Querying for any account should NOT find the legacy accountless category profile
        val profileOther = restored.resolveProfile(ContextCategory.Professional, null, now, account = AccountId(Provider.OUTLOOK, "other"))
        assertEquals(0, profileOther.observationCount, "legacy accountless profile must be discarded")
    }

    @Test(timeout = 30_000)
    fun scopedRetrievalRejectsMismatchedCompartment() {
        val obsStore = InMemoryObservationStore()
        obsStore.persistObservation(threadMessage("m1", 1_700_000_000_000L, MessageDirection.Inbound, "Alice"))

        val engine = ScopedRetrievalEngine(obsStore)
        val foreignCompartment = CompartmentId("gmail|other_user")

        // Mismatched compartment must be rejected with IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            engine.retrieve(
                ScopedRetrievalQuery(
                    conversationId = convId, // account = gmail|work
                    compartment = foreignCompartment,
                ),
            )
        }
    }
}
