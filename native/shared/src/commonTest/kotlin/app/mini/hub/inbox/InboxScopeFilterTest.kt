package app.mini.hub.inbox

import app.mini.hub.attention.AttentionRules
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.persistence.ConversationDraft
import app.mini.hub.search.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxScopeFilterTest {
    private val now = UtcInstant(1_700_000_000_000L)
    private val gmail = AccountId(Provider.GMAIL, "work")
    private val whatsapp = AccountId(Provider.WHATSAPP, "phone")
    private val unseen = UnseenLookup { key, _ -> key.contains("Alice") }

    private fun conv(account: AccountId, name: String) = ConversationObservationSummary(
        conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, name),
        accountId = account,
        title = name,
        appName = "preview",
        senderHint = name,
        packageId = if (account.provider == Provider.GMAIL) "com.google.android.gm" else "com.whatsapp",
        latestSnippet = "hello from $name",
        category = NotificationCategory.DirectMessage,
        coverage = ConversationCoverage.NotificationOnly,
        coverageExplanation = "test",
        latestEventAt = now,
        observationCount = 1,
        unreadCount = 1,
        isRemoved = false,
    )

    @Test
    fun changingFiltersWithoutChangingSearchTextAppliesTheSameScope() {
        val alice = conv(whatsapp, "Alice")
        val pat = conv(gmail, "Pat")
        val doneSam = conv(whatsapp, "Sam")
        val conversations = listOf(alice, pat, doneSam)
        val labels = mapOf(alice.conversationId.key to "Work", pat.conversationId.key to "Work", doneSam.conversationId.key to "Personal")
        val records = mapOf(
            alice.conversationId.key to AttentionRules.default(alice.conversationId, now),
            pat.conversationId.key to AttentionRules.default(pat.conversationId, now),
            doneSam.conversationId.key to AttentionRules.default(doneSam.conversationId, now)
                .copy(status = FollowUpStatus.Done, acknowledgedActivityAt = now, isUserSet = true),
        )
        val hits = listOf(alice, pat, doneSam).map {
            SearchResult(
                conversationId = it.conversationId,
                accountId = it.accountId,
                packageId = it.packageId,
                conversationTitle = it.title,
                senderHint = it.senderHint,
                snippet = it.latestSnippet,
                eventAt = it.latestEventAt,
                messageCount = 1,
            )
        }
        val associatedDraft = ConversationDraft(
            conversationKey = pat.conversationId.key,
            accountId = gmail.key,
            body = "draft for Pat",
            updatedAtMillis = now.epochMillis,
        )
        val newEmail = ConversationDraft(
            conversationKey = "${gmail.key}:conversation:compose:1",
            accountId = gmail.key,
            body = "new note",
            to = "new@example.com",
            isNewEmail = true,
            updatedAtMillis = now.epochMillis,
        )
        val query = "hello"

        fun visible(scope: InboxScope): Triple<List<String>, List<String>, List<String>> {
            val chats = conversations.filter { InboxScopeFilter.matchesConversation(it, labels, records, unseen, scope) }
            val listed = chats.map { it.conversationId.key }.toSet()
            val search = hits.filter { InboxScopeFilter.matchesSearchResult(it, labels, records, unseen, scope, conversations) }
            val drafts = listOf(associatedDraft, newEmail).filter {
                InboxScopeFilter.matchesDraft(it, listed, labels, records, unseen, conversations, scope)
            }
            return Triple(chats.map { it.title }, search.map { it.conversationTitle }, drafts.map { it.conversationKey })
        }

        val all = visible(InboxScope())
        assertEquals(listOf("Alice", "Pat"), all.first)
        assertEquals(listOf("Alice", "Pat"), all.second)
        assertEquals(listOf(newEmail.conversationKey), all.third)
        assertEquals(query, "hello")

        val workOnly = visible(InboxScope(categoryLabel = "Work"))
        assertEquals(listOf("Alice", "Pat"), workOnly.first)
        assertEquals(listOf("Alice", "Pat"), workOnly.second)
        assertTrue(associatedDraft.conversationKey !in workOnly.third)

        val gmailOnly = visible(InboxScope(accountKey = gmail.key))
        assertEquals(listOf("Pat"), gmailOnly.first)
        assertEquals(listOf("Pat"), gmailOnly.second)
        assertEquals(listOf(newEmail.conversationKey), gmailOnly.third)

        val unseenOnly = visible(InboxScope(unseenOnly = true))
        assertEquals(listOf("Alice"), unseenOnly.first)
        assertEquals(listOf("Alice"), unseenOnly.second)
        assertTrue(unseenOnly.third.isEmpty())

        val doneOnly = visible(InboxScope(showDone = true))
        assertEquals(listOf("Sam"), doneOnly.first)
        assertEquals(listOf("Sam"), doneOnly.second)
        assertTrue(doneOnly.third.isEmpty())
    }

    @Test
    fun newContactIsLabeledAndIgnoresCategoryWhileHonoringActiveScope() {
        val alice = conv(whatsapp, "Alice")
        val labels = mapOf(alice.conversationId.key to "Work")
        val records = mapOf(alice.conversationId.key to AttentionRules.default(alice.conversationId, now))
        val existing = InboxScopeFilter.matchingConversation(listOf(alice), whatsapp.key, "Alice")
        val inScope = InboxScopeFilter.decideContact(existing, labels, records, unseen, InboxScope(categoryLabel = "Work"))
        assertEquals(ContactLookupKind.InScopeConversation, inScope.kind)
        assertTrue(inScope.include)

        val newContact = InboxScopeFilter.decideContact(null, labels, records, unseen, InboxScope(categoryLabel = "Work"))
        assertEquals(ContactLookupKind.NewContact, newContact.kind)
        assertTrue(newContact.include)

        val hiddenWhenUnseen = InboxScopeFilter.decideContact(null, labels, records, unseen, InboxScope(unseenOnly = true))
        assertFalse(hiddenWhenUnseen.include)
        val hiddenWhenDone = InboxScopeFilter.decideContact(null, labels, records, unseen, InboxScope(showDone = true))
        assertFalse(hiddenWhenDone.include)
    }
}
