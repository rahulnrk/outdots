package app.mini.hub.inbox

import app.mini.hub.attention.ConversationAttention
import app.mini.hub.attention.isHandledDone
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.persistence.ConversationDraft
import app.mini.hub.search.SearchResult

/**
 * One Inbox filter contract for conversation rows, message search, people routes,
 * and associated drafts. Changing any field without changing search text still
 * applies the same scope.
 */
data class InboxScope(
    val accountKey: String? = null,
    val categoryLabel: String? = null,
    val unseenOnly: Boolean = false,
    val showDone: Boolean = false,
)

fun interface UnseenLookup {
    fun isUnseen(conversationKey: String, latestEventMillis: Long): Boolean
}

enum class ContactLookupKind {
    InScopeConversation,
    NewContact,
}

data class ContactLookupDecision(
    val include: Boolean,
    val kind: ContactLookupKind,
)

object InboxScopeFilter {
    fun matchesActiveOrDone(isDone: Boolean, showDone: Boolean): Boolean =
        if (showDone) isDone else !isDone

    fun isStandaloneDraft(draft: ConversationDraft): Boolean =
        draft.isNewEmail ||
            ":compose:" in draft.conversationKey ||
            ":conversation:new:" in draft.conversationKey ||
            draft.conversationKey.contains(":new:")

    fun matchingConversation(
        conversations: List<ConversationObservationSummary>,
        accountKey: String,
        address: String,
    ): ConversationObservationSummary? = conversations.firstOrNull { conv ->
        conv.accountId.key == accountKey &&
            (
                conv.conversationId.value.equals(address, ignoreCase = true) ||
                    conv.senderHint.contains(address, ignoreCase = true)
                )
    }

    fun matchesConversation(
        summary: ConversationObservationSummary,
        labels: Map<String, String>,
        records: Map<String, ConversationAttention>,
        unseen: UnseenLookup,
        scope: InboxScope,
    ): Boolean {
        if (!matchesActiveOrDone(records[summary.conversationId.key].isHandledDone(), scope.showDone)) return false
        if (scope.accountKey != null && summary.accountId.key != scope.accountKey) return false
        if (scope.categoryLabel != null && labels[summary.conversationId.key] != scope.categoryLabel) return false
        if (scope.unseenOnly && !unseen.isUnseen(summary.conversationId.key, summary.latestEventAt.epochMillis)) return false
        return true
    }

    fun matchesSearchResult(
        result: SearchResult,
        labels: Map<String, String>,
        records: Map<String, ConversationAttention>,
        unseen: UnseenLookup,
        scope: InboxScope,
        conversations: List<ConversationObservationSummary> = emptyList(),
    ): Boolean {
        val summary = conversations.firstOrNull { it.conversationId.key == result.conversationId.key }
        if (summary != null) return matchesConversation(summary, labels, records, unseen, scope)
        if (!matchesActiveOrDone(records[result.conversationId.key].isHandledDone(), scope.showDone)) return false
        if (scope.accountKey != null && result.accountId.key != scope.accountKey) return false
        if (scope.categoryLabel != null && labels[result.conversationId.key] != scope.categoryLabel) return false
        if (scope.unseenOnly && !unseen.isUnseen(result.conversationId.key, result.eventAt.epochMillis)) return false
        return true
    }

    fun matchesDraft(
        draft: ConversationDraft,
        listedConversationKeys: Set<String>,
        labels: Map<String, String>,
        records: Map<String, ConversationAttention>,
        unseen: UnseenLookup,
        conversations: List<ConversationObservationSummary>,
        scope: InboxScope,
    ): Boolean {
        if (scope.accountKey != null && draft.accountId != scope.accountKey) return false
        if (isStandaloneDraft(draft)) {
            // New-email and new-contact drafts live in the active Inbox. They have no
            // conversation category or unseen watermark yet.
            return !scope.showDone && !scope.unseenOnly
        }
        if (draft.conversationKey in listedConversationKeys) return false
        val conv = conversations.firstOrNull { it.conversationId.key == draft.conversationKey }
        return if (conv != null) {
            matchesConversation(conv, labels, records, unseen, scope)
        } else {
            if (!matchesActiveOrDone(records[draft.conversationKey].isHandledDone(), scope.showDone)) return false
            if (scope.categoryLabel != null && labels[draft.conversationKey] != scope.categoryLabel) return false
            if (scope.unseenOnly) return false
            true
        }
    }

    /**
     * Existing conversations honor the full scope. New contacts honor account (caller-side)
     * and active/Done, ignore category, and stay hidden on unseen-only. Label them as new.
     */
    fun decideContact(
        existing: ConversationObservationSummary?,
        labels: Map<String, String>,
        records: Map<String, ConversationAttention>,
        unseen: UnseenLookup,
        scope: InboxScope,
    ): ContactLookupDecision {
        if (existing != null) {
            return ContactLookupDecision(
                include = matchesConversation(existing, labels, records, unseen, scope),
                kind = ContactLookupKind.InScopeConversation,
            )
        }
        return ContactLookupDecision(
            include = !scope.showDone && !scope.unseenOnly,
            kind = ContactLookupKind.NewContact,
        )
    }
}
