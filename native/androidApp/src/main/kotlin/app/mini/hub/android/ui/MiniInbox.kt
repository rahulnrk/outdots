package app.mini.hub.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mini.hub.android.ConversationLabels
import app.mini.hub.android.LocalSeenStore
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.inbox.ContactLookupKind
import app.mini.hub.inbox.InboxScope
import app.mini.hub.inbox.InboxScopeFilter
import app.mini.hub.inbox.UnseenLookup
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.SourceAppNames
import app.mini.hub.persistence.ConversationDraft
import app.mini.hub.search.SearchResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class InboxUiActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onSearchSourceFilterChanged: (String?) -> Unit = {},
    val setShowArchived: (Boolean) -> Unit = {},
    val setInboxAccountKey: (String?) -> Unit = {},
    val applyAttentionAction: (ScopedId, AttentionAction) -> Unit = { _, _ -> },
    val reportUserMessage: (String) -> Unit = {},
    val openCompose: (AccountId, String, String) -> Unit = { _, _, _ -> },
    val selectConversationFromSearch: (SearchResult) -> Unit = {},
    val openSavedDraft: (ConversationDraft) -> Unit = {},
    val selectConversation: (ConversationObservationSummary) -> Unit = {},
    val undoLastAttention: () -> Unit = {},
    val laterPresets: () -> List<Pair<String, UtcInstant>> = { emptyList() },
) {
    companion object {
        fun from(viewModel: InboxViewModel) = InboxUiActions(
            onSearchQueryChanged = viewModel::onSearchQueryChanged,
            onSearchSourceFilterChanged = viewModel::onSearchSourceFilterChanged,
            setShowArchived = viewModel::setShowArchived,
            setInboxAccountKey = viewModel::setInboxAccountKey,
            applyAttentionAction = viewModel::applyAttentionAction,
            reportUserMessage = viewModel::reportUserMessage,
            openCompose = viewModel::openCompose,
            selectConversationFromSearch = viewModel::selectConversationFromSearch,
            openSavedDraft = viewModel::openSavedDraft,
            selectConversation = viewModel::selectConversation,
            undoLastAttention = viewModel::undoLastAttention,
            laterPresets = viewModel::laterPresets,
        )
    }
}

@Composable
internal fun MiniInboxPane(
    state: InboxViewState,
    actions: InboxUiActions,
    accountStore: ConnectedAccountStore,
    labels: Map<String, String>,
    onCompose: () -> Unit,
    onRequestPermission: () -> Unit,
    onCheckSource: (app.mini.hub.action.ReviewedActionSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    val seen = remember { LocalSeenStore(context) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var unseenOnly by rememberSaveable { mutableStateOf(false) }
    var accountFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    val accounts = remember(state.conversations) { state.conversations.map { it.accountId }.distinct() }
    val scope = InboxScope(
        accountKey = accountFilter,
        categoryLabel = category,
        unseenOnly = unseenOnly,
        showDone = showArchived,
    )
    val unseen = UnseenLookup { key, at -> seen.isUnseen(key, at) }
    val chats = state.conversations.filter { conv ->
        InboxScopeFilter.matchesConversation(conv, labels, state.attentionRecords, unseen, scope)
    }
    val unseenCount = state.conversations.count { conv ->
        InboxScopeFilter.matchesConversation(
            conv,
            labels,
            state.attentionRecords,
            unseen,
            scope.copy(unseenOnly = true, showDone = false),
        )
    }
    val listedKeys = chats.map { it.conversationId.key }.toSet()
    val drafts = state.savedDrafts.filter { draft ->
        InboxScopeFilter.matchesDraft(
            draft,
            listedKeys,
            labels,
            state.attentionRecords,
            unseen,
            state.conversations,
            scope,
        )
    }
    val liveActivity = state.linkedinActivity.filter {
        state.attentionRecords[it.conversationId.key]?.status != FollowUpStatus.Done
    }
    val archivedActivity = state.linkedinActivity.filter {
        state.attentionRecords[it.conversationId.key]?.status == FollowUpStatus.Done
    }
    if (showActivity) {
        MiniActivityPane(
            items = liveActivity,
            notice = state.outboxStatusMessage,
            onBack = { showActivity = false },
            onAcknowledge = { actions.applyAttentionAction(it.conversationId, AttentionAction.MarkDone) },
            onOpen = { summary ->
                if (openLinkedInApp(context)) {
                    actions.applyAttentionAction(summary.conversationId, AttentionAction.MarkDone)
                    actions.reportUserMessage("")
                } else {
                    actions.reportUserMessage("Couldn't open LinkedIn. The item is still here.")
                }
            },
            onDismissNotice = { actions.reportUserMessage("") },
        )
        return
    }
    val contactRoutes by androidx.compose.runtime.produceState(
        emptyList<app.mini.hub.android.connector.AndroidConnectorRegistry.ContactRoute>(),
        state.searchQuery,
        accountFilter,
    ) {
        if (state.searchQuery.isBlank()) {
            value = emptyList()
            return@produceState
        }
        kotlinx.coroutines.delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { app.mini.hub.android.MiniHubRuntime.connectors.findContacts(state.searchQuery) }
                .getOrDefault(emptyList())
                .filter { accountFilter == null || it.accountId.key == accountFilter }
        }
    }
    val scopedPeople = contactRoutes.mapNotNull { route ->
        val existing = InboxScopeFilter.matchingConversation(state.conversations, route.accountId.key, route.address)
        val decision = InboxScopeFilter.decideContact(existing, labels, state.attentionRecords, unseen, scope)
        if (decision.include) route to decision.kind else null
    }
    val searchHits = state.searchResults.filter { result ->
        InboxScopeFilter.matchesSearchResult(
            result,
            labels,
            state.attentionRecords,
            unseen,
            scope,
            state.conversations,
        )
    }
    val broaderLookup = scopedPeople.any { it.second == ContactLookupKind.NewContact } &&
        (scope.categoryLabel != null || scope.unseenOnly || scope.accountKey != null || scope.showDone)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MiniInk.gutter.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (showArchived) "Done" else "Inbox", style = MaterialTheme.typography.headlineLarge)
                Text(
                    if (showArchived) "Handled conversations stay here." else "Conversations, not notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onCompose,
                modifier = Modifier.height(MiniInk.primaryTarget.dp),
            ) { Text("Compose") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { actions.onSearchQueryChanged(it) },
            placeholder = { Text("Search people and messages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MiniInk.gutter.dp),
            shape = RoundedCornerShape(MiniInk.controlRadius.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MiniInk.gutter.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = category == null && !unseenOnly && accountFilter == null && !showArchived, onClick = {
                category = null; unseenOnly = false; accountFilter = null; showArchived = false
                actions.onSearchSourceFilterChanged(null)
                actions.setShowArchived(false)
                actions.setInboxAccountKey(null)
            }, label = { Text("All") })
            FilterChip(selected = unseenOnly, onClick = { unseenOnly = !unseenOnly; showArchived = false; actions.setShowArchived(false) }, label = { Text("Unseen in MINI $unseenCount") })
            FilterChip(selected = showArchived, onClick = {
                showArchived = !showArchived
                unseenOnly = false
                actions.setShowArchived(showArchived)
            }, label = { Text("Done") })
            if (liveActivity.isNotEmpty() || archivedActivity.isNotEmpty()) {
                FilterChip(selected = false, onClick = { showActivity = true }, label = { Text("Activity ${liveActivity.size}") })
            }
            ConversationLabels.DEFAULTS.forEach { label ->
                FilterChip(selected = category == label, onClick = { category = if (category == label) null else label }, label = { Text(label) })
            }
            accounts.forEach { id ->
                val alias = accountStore.get(id)?.displayName ?: accountStore.get(id)?.providerAccountId ?: id.provider.wire
                FilterChip(
                    selected = accountFilter == id.key,
                    onClick = {
                        accountFilter = if (accountFilter == id.key) null else id.key
                        actions.setInboxAccountKey(accountFilter)
                    },
                    label = { Text(alias) },
                )
            }
        }
        if (!state.isPermissionGranted) {
            MiniBanner(
                title = "Notification access needed",
                body = "MINI can still show connected accounts. Grant access to capture notifications.",
                action = "Open settings",
                onAction = onRequestPermission,
            )
        } else if (!state.isListenerConnected) {
            MiniBanner(
                title = "Notification listener idle",
                body = "New notifications aren't being saved until the listener is connected.",
                action = "Fix",
                onAction = onRequestPermission,
            )
        }
        state.storageFailure?.let {
            MiniBanner(title = "Storage error", body = it, action = null, onAction = {})
        }
        state.outboxStatusMessage?.takeIf { !showActivity }?.let {
            MiniBanner(title = "Notice", body = it, action = "Dismiss", onAction = { actions.reportUserMessage("") })
        }
        val visibleOutcomes = state.reviewedSnapshots.filter { snap ->
            snap.state == app.mini.hub.action.OutboxState.OutcomeUnknown ||
                snap.state == app.mini.hub.action.OutboxState.Rejected ||
                snap.state == app.mini.hub.action.OutboxState.HandoffOpened
        }.takeLast(5)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MiniInk.gutter.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(MiniInk.rowGap.dp),
        ) {
            if (visibleOutcomes.isNotEmpty() && state.searchQuery.isBlank()) {
                item {
                    Text("Action history", style = MaterialTheme.typography.titleMedium)
                }
                items(visibleOutcomes, key = { "out-${it.commandId}" }) { snap ->
                    MiniOutcomeCard(snap, onCheckSource = { onCheckSource(snap) })
                }
            }
            if (state.searchQuery.isNotBlank()) {
                if (scopedPeople.isNotEmpty()) {
                    item {
                        Text("People", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    if (broaderLookup) {
                        item {
                            Text(
                                "New contacts look beyond this filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(scopedPeople.groupBy { it.first.name.lowercase() }.entries.take(6).toList(), key = { it.key }) { (_, routes) ->
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text(routes.first().first.name, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                routes.forEach { (r, kind) ->
                                    OutlinedButton(onClick = { actions.openCompose(r.accountId, r.address, r.name) }) {
                                        Text(
                                            if (kind == ContactLookupKind.NewContact) "${r.label} · New contact" else r.label,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Text("Messages", style = MaterialTheme.typography.titleMedium) }
                }
                if (state.isSearching) {
                    item { Text("Searching…", style = MaterialTheme.typography.bodySmall) }
                } else if (searchHits.isEmpty()) {
                    item {
                        Text(
                            "No matching conversations.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(searchHits, key = { it.conversationId.key + it.eventAt.epochMillis }) { res ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    seen.markSeen(res.conversationId.key, res.eventAt.epochMillis)
                                    actions.selectConversationFromSearch(res)
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(res.conversationTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${res.accountId.provider.wire} · ${SourceAppNames.forPackage(res.packageId)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(res.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                if (drafts.isNotEmpty()) {
                    item { Text("Drafts", style = MaterialTheme.typography.titleMedium) }
                    items(drafts, key = { "draft-${it.conversationKey}" }) { draft ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MiniInk.cardRadius.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .clickable { actions.openSavedDraft(draft) }
                                .padding(12.dp),
                        ) {
                            Text(
                                if (InboxScopeFilter.isStandaloneDraft(draft)) "New email draft" else "Draft",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (draft.to.isNotBlank()) Text("To: ${draft.to}", style = MaterialTheme.typography.bodySmall)
                            Text(draft.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleSmall)
                            if (draft.body.isNotBlank()) Text(draft.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (showArchived) "Done and archived" else "Latest conversations", style = MaterialTheme.typography.titleMedium)
                        Text("${chats.size} conversations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(chats, key = { it.conversationId.key }) { conv ->
                    MiniPeopleRow(
                        conv = conv,
                        label = labels[conv.conversationId.key],
                        attention = state.attentionRecords[conv.conversationId.key],
                        accountAlias = inboxAccountAlias(accountStore.get(conv.accountId), conv),
                        unseen = seen.isUnseen(conv.conversationId.key, conv.latestEventAt.epochMillis),
                        onClick = {
                            seen.markSeen(conv.conversationId.key, conv.latestEventAt.epochMillis)
                            actions.selectConversation(conv)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (showArchived && archivedActivity.isNotEmpty()) {
                    item { Text("Handled activity", style = MaterialTheme.typography.titleMedium) }
                    items(archivedActivity, key = { "arch-${it.conversationId.key}" }) { item ->
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.latestSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (chats.isEmpty() && drafts.isEmpty()) {
                    item {
                        Text(
                            when {
                                showArchived -> "Nothing is marked done."
                                state.conversations.isEmpty() && accountStore.list().isEmpty() -> "No accounts yet. Open Console to connect one."
                                state.conversations.isEmpty() -> "No conversations in MINI yet."
                                else -> "Nothing matches these filters."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniActivityPane(
    items: List<ConversationObservationSummary>,
    notice: String? = null,
    onBack: () -> Unit,
    onAcknowledge: (ConversationObservationSummary) -> Unit,
    onOpen: (ConversationObservationSummary) -> Unit,
    onDismissNotice: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = MiniInk.gutter.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.height(MiniInk.primaryTarget.dp)) { Text("Back to Inbox") }
        Text("Activity", style = MaterialTheme.typography.headlineLarge)
        Text(
            "LinkedIn posts, jobs, and other non-message alerts. Messages stay in Inbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.takeIf { it.isNotBlank() }?.let {
            MiniBanner(title = "Couldn't open LinkedIn", body = it, action = "Dismiss", onAction = onDismissNotice)
        }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text("No LinkedIn activity waiting.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(items, key = { it.conversationId.key }) { summary ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MiniInk.cardRadius.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                    ) {
                        Text(summary.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(summary.latestSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("LinkedIn activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onAcknowledge(summary) },
                                modifier = Modifier.height(MiniInk.primaryTarget.dp),
                            ) { Text("Acknowledge") }
                            OutlinedButton(
                                onClick = { onOpen(summary) },
                                modifier = Modifier.height(MiniInk.primaryTarget.dp),
                            ) { Text("Open in LinkedIn") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniPeopleRow(
    conv: ConversationObservationSummary,
    label: String?,
    attention: app.mini.hub.attention.ConversationAttention?,
    accountAlias: String,
    unseen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(inboxInitials(conv.title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(inboxTimeLabel(conv.latestEventAt.epochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(previewText(conv.latestSnippet), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniAttentionChip(attention)
                Text("$accountAlias${label?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unseen) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
internal fun MiniAttentionChip(attention: app.mini.hub.attention.ConversationAttention?) {
    val (text, fill, tint) = when (attention?.status) {
        FollowUpStatus.NeedsAttention -> Triple("Needs you", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary)
        FollowUpStatus.Waiting -> Triple("Waiting", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        FollowUpStatus.Snoozed -> Triple("Later", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        FollowUpStatus.Done -> Triple("Done", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary)
        null -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun MiniBanner(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiniInk.gutter.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(MiniInk.cardRadius.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(14.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun MiniOutcomeCard(
    snap: app.mini.hub.action.ReviewedActionSnapshot,
    onCheckSource: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                when (snap.state) {
                    app.mini.hub.action.OutboxState.OutcomeUnknown -> "Send outcome unknown"
                    app.mini.hub.action.OutboxState.Rejected -> "Not sent"
                    app.mini.hub.action.OutboxState.HandoffOpened -> "Opened in another app. Not sent by MINI."
                    else -> snap.state.name
                },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text("To: ${snap.recipients.joinToString().ifBlank { "this conversation" }}", style = MaterialTheme.typography.bodySmall)
            if (snap.cc.isNotEmpty()) Text("Cc: ${snap.cc.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (snap.bcc.isNotEmpty()) Text("Bcc: ${snap.bcc.joinToString()}", style = MaterialTheme.typography.bodySmall)
            snap.subject?.let { Text("Subject: $it", style = MaterialTheme.typography.bodySmall) }
            Text(snap.body, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            snap.failureReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onCheckSource, modifier = Modifier.height(MiniInk.primaryTarget.dp)) {
                Text("Check in source app")
            }
        }
    }
}

internal fun openLinkedInApp(context: android.content.Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/"))
        .setPackage("com.linkedin.android")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) == null) return false
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

internal fun inboxInitials(title: String): String =
    title.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "?" }

internal fun inboxTimeLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - millis < 24 * 3600_000L) "HH:mm" else "dd MMM"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(millis))
}

internal fun inboxAccountAlias(account: ConnectedAccount?, conv: ConversationObservationSummary): String {
    val source = SourceAppNames.forPackage(conv.packageId)
    val alias = account?.displayName ?: account?.providerAccountId
    return if (alias.isNullOrBlank() || alias.equals(source, ignoreCase = true)) source else "$source · $alias"
}
