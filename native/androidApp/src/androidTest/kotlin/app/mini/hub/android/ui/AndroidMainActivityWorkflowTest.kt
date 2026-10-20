package app.mini.hub.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mini.hub.action.*
import app.mini.hub.attention.*
import app.mini.hub.domain.*
import app.mini.hub.observation.*
import app.mini.hub.persistence.*
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidComponentIntegrationTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private val account = AccountId(Provider.WHATSAPP, "local_user")

    @Test
    fun syntheticComponentIntegrationStartupSwitchingDraftLoadingSearchFailureAndExpiry() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val backing = InMemoryObservationStore()
        val store = object : ObservationStore by backing {
            override fun search(query: app.mini.hub.search.SearchQuery): List<app.mini.hub.search.SearchResult> =
                throw app.mini.hub.search.SearchUnavailableException()
        }
        val actionStore = InMemoryActionStore()
        val attentionStore = InMemoryAttentionStore()
        val work = ArrayDeque<Runnable>()
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }
        fun drain() {
            while (work.isNotEmpty()) {
                onMain { work.removeFirst().run() }
                instrumentation.waitForIdleSync()
            }
            instrumentation.waitForIdleSync()
        }

        val vm = InboxViewModel(
            observationStore = store,
            retrievalEngine = ScopedRetrievalEngine(store),
            styleEngine = AdaptiveStyleEngine(),
            actionController = ActionController(clock, DigestProvider { it }, ActionIdGenerator { "act-$it" }),
            actionStore = actionStore,
            clock = clock,
            attentionStore = attentionStore,
            refreshRunner = { work.add(it) },
            sendRunner = { work.add(it) },
            gateway = ProviderGateway { _, _ -> GatewayResult.Accepted("receipt") },
        )

        // 1. Startup & draft loading resilience
        val summary = ConversationObservationSummary(
            conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "Priya"),
            accountId = account,
            title = "Priya",
            appName = "WhatsApp",
            senderHint = "Priya",
            packageId = "com.whatsapp",
            latestSnippet = "Hello",
            category = NotificationCategory.DirectMessage,
            coverage = ConversationCoverage.NotificationOnly,
            coverageExplanation = "Notification only",
            latestEventAt = clock.now(),
            observationCount = 1,
            unreadCount = 1,
            isRemoved = false,
        )
        actionStore.saveDraft(ConversationDraft(summary.conversationId.key, account.key, "Saved draft", updatedAtMillis = 1_700_000_000_000L))

        onMain { vm.selectConversation(summary) }
        assertEquals("Priya", vm.state.value.selectedConversation?.title, "selection is immediate while loading")
        onMain { vm.updateLocalDraftText("Typing during draft load") }
        drain()
        assertEquals("Typing during draft load", vm.state.value.localDraftText)

        // 2. Rapid conversation switching isolation
        val summaryBob = summary.copy(conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, "Bob"), title = "Bob", senderHint = "Bob")
        actionStore.saveDraft(ConversationDraft(summaryBob.conversationId.key, account.key, "Bob's draft", updatedAtMillis = 1_700_000_000_000L))
        onMain {
            vm.selectConversation(summaryBob)
            vm.selectConversation(summary)
        }
        drain()
        assertEquals("Bob's draft", actionStore.loadDraft(summaryBob.conversationId.key)?.body, "rapid switching does not lose or corrupt sibling drafts")

        // 3. Search failure handling
        onMain { vm.onSearchQueryChanged("synthetic-query") }
        drain()
        assertNotNull(vm.state.value.searchError)
        assertFalse(vm.state.value.isSearching)
        assertEquals("Typing during draft load", vm.state.value.localDraftText)

        // 4. Approval expiry
        var now = 1_700_000_000_000L
        val mutableClock = Clock { UtcInstant(now) }
        val controller = ActionController(mutableClock, DigestProvider { it }, ActionIdGenerator { "exp-$it" })
        val payload = ExactActionPayload(
            accountId = account.key,
            conversationId = "Priya",
            route = ActionRoute.Api,
            recipients = listOf("Priya"),
            subject = null,
            body = "Approved body",
            attachments = emptyList(),
            sourceVersions = emptyList(),
            grantVersion = "g",
            policyVersion = "p",
        )
        val (approved, command) = controller.approve(ActionState(), payload)
        now += 120_001L // expire
        val checks = LiveDispatchChecks("g", "p", emptyList())
        val dispatchResult = controller.beginDispatch(approved, command.id, payload, checks)
        assertTrue(dispatchResult.outbox.single { it.id == command.id }.state == OutboxState.ExpiredOrInvalidated || controller.executionStillAuthorized(dispatchResult, command.id, payload, checks).let { !it })
    }
}
