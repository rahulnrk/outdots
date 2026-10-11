package app.mini.hub.search

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

data class SearchResult(
    val conversationId: ScopedId,
    val accountId: AccountId,
    val packageId: String,
    val conversationTitle: String,
    val senderHint: String,
    val snippet: String,
    val eventAt: UtcInstant,
    val messageCount: Int,
)

data class SearchQuery(
    val queryText: String,
    val sourceFilter: String? = null,
    val limit: Int = 50,
)
