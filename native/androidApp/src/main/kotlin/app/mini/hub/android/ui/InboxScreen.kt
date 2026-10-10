package app.mini.hub.android.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.android.CommunicationSourcePreferences
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.AttentionView
import app.mini.hub.attention.ConversationAttention
import app.mini.hub.attention.ConversationPriority
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.attention.ResurfaceReason
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.ObservedNotification

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    handoffLauncher: HandoffLauncher,
    onRequestPermission: () -> Unit,
    sourcePreferences: CommunicationSourcePreferences,
    accountStore: ConnectedAccountStore,
    gmailCanConnect: Boolean,
    gmailSetupStatus: String,
    connectionStatus: String?,
    onConnectGmail: () -> Unit,
    onSourceSelectionChanged: () -> Unit,
    outlookCanConnect: Boolean = false,
    outlookSetupStatus: String = "",
    onConnectOutlook: () -> Unit = {},
    onDisconnectAccount: (ConnectedAccount) -> Unit = {},
    onSyncNow: () -> Unit = {},
    accountsVersion: Int = 0,
) {
    val state by viewModel.state
    var showSourceSettings by remember { mutableStateOf(false) }
    var showAccountSettings by remember { mutableStateOf(false) }
    // Returning from Google recreates the screen. Reopen Accounts so the connection result is visible.
    androidx.compose.runtime.LaunchedEffect(connectionStatus) {
        if (connectionStatus != null) showAccountSettings = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MINI Hub — Private Local Inbox") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                actions = {
                    TextButton(onClick = { showSourceSettings = true }) {
                        Text("Sources")
                    }
                    TextButton(onClick = { showAccountSettings = true }) {
                        Text("Accounts")
                    }
                    val statusText = if (state.isListenerConnected) "Listener Active" else "Listener Idle"
                    val statusColor = if (state.isListenerConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // Permission Banner if access is not granted
            if (!state.isPermissionGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Notification Access Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "To observe WhatsApp and Gmail messages locally on your device, please grant Notification Access permission.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Open Settings")
                        }
                    }
                }
            }

            state.storageFailure?.let { failure ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "Storage Error: $failure",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Status message (e.g. outbox confirmation)
            state.outboxStatusMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (state.selectedConversation != null) {
                // Conversation Detail View
                val record = state.attentionRecords[state.selectedConversation!!.conversationId.key] 
                    ?: app.mini.hub.attention.AttentionRules.default(state.selectedConversation!!.conversationId, app.mini.hub.domain.UtcInstant(System.currentTimeMillis()))
                ConversationDetailView(
                    summary = state.selectedConversation!!,
                    attention = record,
                    timeline = state.selectedTimeline,
                    localDraftText = state.localDraftText,
                    localDraftWarning = state.localDraftWarning,
                    localDraftStatus = state.localDraftStatus,
                    localDraftFeedback = state.localDraftFeedback,
                    isLocalDraftInProgress = state.isLocalDraftInProgress,
                    isLocalAiRetryAvailable = state.isLocalAiRetryAvailable,
                    isLocalAiRetryInProgress = state.isLocalAiRetryInProgress,
                    localAiReleaseStatus = state.localAiReleaseStatus,
                    onBack = { viewModel.clearSelection() },
                    onLocalDraftRequested = { intent -> viewModel.requestLocalDraft(intent) },
                    onLocalDraftChanged = { text -> viewModel.updateLocalDraftText(text) },
                    onLocalDraftCancelled = { viewModel.cancelLocalDraft() },
                    onDraftUsefulness = { feedback -> viewModel.recordDraftUsefulness(feedback) },
                    onRetryLocalAi = { viewModel.retryLocalAi() },
                    onDraftPrepared = { body, recip -> viewModel.prepareDraft(body, recip) },
                    onAttentionAction = { action -> viewModel.applyAttentionAction(state.selectedConversation!!.conversationId, action) },
                )
            } else {
                // Inbox List View
                ConversationListView(
                    conversations = state.conversations,
                    view = state.attentionView,
                    onSelect = { viewModel.selectConversation(it) },
                )
            }
        }
    }

    if (showSourceSettings) {
        CommunicationSourceSettingsDialog(
            preferences = sourcePreferences,
            onDismiss = { showSourceSettings = false },
            onChanged = onSourceSelectionChanged,
        )
    }

    if (showAccountSettings) {
        ConnectedAccountSettingsDialog(
            accounts = remember(accountsVersion, connectionStatus) { accountStore.list() },
            gmailCanConnect = gmailCanConnect,
            gmailSetupStatus = gmailSetupStatus,
            outlookCanConnect = outlookCanConnect,
            outlookSetupStatus = outlookSetupStatus,
            connectionStatus = connectionStatus,
            onConnectGmail = onConnectGmail,
            onConnectOutlook = onConnectOutlook,
            onDisconnect = onDisconnectAccount,
            onSyncNow = onSyncNow,
            onDismiss = { showAccountSettings = false },
        )
    }

    // Exact Action Review Dialog
    state.activeReviewPayload?.let { payload ->
        ExactActionReviewDialog(
            payload = payload,
            onConfirm = { viewModel.approveAndDispatch(handoffLauncher) },
            onDismiss = { viewModel.clearDraftReview() },
        )
    }
}

@Composable
private fun CommunicationSourceSettingsDialog(
    preferences: CommunicationSourcePreferences,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var selected by remember { mutableStateOf(preferences.selectedSources()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Communication sources") },
        text = {
            Column {
                Text(
                    "Choose notification apps for the inbox. This observes notifications only; it does not connect an account or create a login.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                CommunicationSource.entries.forEach { source ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = source in selected,
                            onCheckedChange = { enabled ->
                                preferences.setSelected(source, enabled)
                                selected = preferences.selectedSources()
                                onChanged()
                            },
                        )
                        Text(source.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ConnectedAccountSettingsDialog(
    accounts: List<ConnectedAccount>,
    gmailCanConnect: Boolean,
    gmailSetupStatus: String,
    outlookCanConnect: Boolean,
    outlookSetupStatus: String,
    connectionStatus: String?,
    onConnectGmail: () -> Unit,
    onConnectOutlook: () -> Unit,
    onDisconnect: (ConnectedAccount) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDisconnect by remember { mutableStateOf<ConnectedAccount?>(null) }
    pendingDisconnect?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text("Disconnect this account?") },
            text = {
                Text(
                    "MINI stops syncing ${target.displayName ?: target.providerAccountId} and deletes its stored access. " +
                        "Messages already in MINI are kept. Other accounts are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDisconnect(target); pendingDisconnect = null }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { pendingDisconnect = null }) { Text("Cancel") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connected accounts") },
        text = {
            Column {
                Text(
                    "Connected accounts use provider authorization and are separate from notification-source choices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (accounts.isEmpty()) {
                    Text("No authenticated accounts are connected yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    accounts.forEach { account ->
                        Text(
                            "${account.displayName ?: account.providerAccountId} · ${accountStateLabel(account.state)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${account.id.provider.wire.uppercase()} · ${account.accountType.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        account.userActionRequired?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (account.state != app.mini.hub.connector.ConnectionState.Disconnected) {
                            TextButton(onClick = { pendingDisconnect = account }) { Text("Disconnect") }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    TextButton(onClick = onSyncNow) { Text("Sync connected accounts now") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text("Gmail", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(gmailSetupStatus, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = onConnectGmail, enabled = gmailCanConnect) {
                    Text(if (gmailCanConnect) "Connect Gmail" else "Gmail setup required")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Outlook", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(outlookSetupStatus, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = onConnectOutlook, enabled = outlookCanConnect) {
                    Text(if (outlookCanConnect) "Connect Outlook Mail (personal or work)" else "Outlook setup required")
                }
                connectionStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Notification observations remain available without an authenticated account and may not identify the mailbox account.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun accountStateLabel(state: app.mini.hub.connector.ConnectionState): String = when (state) {
    app.mini.hub.connector.ConnectionState.Authorized,
    app.mini.hub.connector.ConnectionState.Healthy -> "Connected"
    app.mini.hub.connector.ConnectionState.Syncing -> "Syncing"
    app.mini.hub.connector.ConnectionState.Disconnected -> "Disconnected"
    app.mini.hub.connector.ConnectionState.Revoked -> "Access removed"
    app.mini.hub.connector.ConnectionState.Expired -> "Sign-in expired"
    app.mini.hub.connector.ConnectionState.AuthorizationRequired -> "Needs permission"
    app.mini.hub.connector.ConnectionState.Degraded -> "Temporarily unavailable"
}

@Composable
fun ConversationListView(
    conversations: List<ConversationObservationSummary>,
    view: AttentionView?,
    onSelect: (ConversationObservationSummary) -> Unit,
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No notification observations yet.\nMessages received from WhatsApp/Gmail will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (view == null || view.isEmpty) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.conversationId.key }) { conv ->
                ConversationItemCard(conversation = conv, attention = null, onClick = { onSelect(conv) })
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.needsAttention.isNotEmpty()) {
                item { SectionHeader("Needs Attention") }
                items(view.needsAttention, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, onClick = { onSelect(e.summary) })
                }
            }
            if (view.snoozed.isNotEmpty()) {
                item { SectionHeader("Snoozed") }
                items(view.snoozed, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, onClick = { onSelect(e.summary) })
                }
            }
            if (view.waiting.isNotEmpty()) {
                item { SectionHeader("Waiting on them") }
                items(view.waiting, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, onClick = { onSelect(e.summary) })
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun ConversationItemCard(
    conversation: ConversationObservationSummary,
    attention: ConversationAttention?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = conversation.appName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = conversation.latestSnippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )

            if (attention != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (attention.priority == ConversationPriority.Important) {
                        BadgeLabel("Important", MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    if (attention.activityWhileSnoozed) {
                        BadgeLabel("New Activity", MaterialTheme.colorScheme.errorContainer)
                    } else if (attention.resurfaceReason == ResurfaceReason.SnoozeDue) {
                        BadgeLabel("Snooze Due", MaterialTheme.colorScheme.secondaryContainer)
                    } else if (attention.resurfaceReason != null) {
                        BadgeLabel("New Activity", MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val categoryLabel = when (conversation.category) {
                    app.mini.hub.observation.NotificationCategory.DirectMessage -> "Direct Message"
                    app.mini.hub.observation.NotificationCategory.GroupMessage -> "Group Message"
                    app.mini.hub.observation.NotificationCategory.SocialUpdate -> "Social Alert"
                    app.mini.hub.observation.NotificationCategory.OngoingService -> "Active Service"
                    app.mini.hub.observation.NotificationCategory.SystemStatus -> "System Status"
                    app.mini.hub.observation.NotificationCategory.GeneralNotification -> "Notification"
                }

                // Truthful coverage & category badge
                val coverageLabel = when (conversation.coverage) {
                    app.mini.hub.observation.ConversationCoverage.FullProviderSync -> "Connected provider sync"
                    app.mini.hub.observation.ConversationCoverage.PartialApiSync -> "Partial provider sync"
                    app.mini.hub.observation.ConversationCoverage.UserImportedSnapshot -> "User import"
                    app.mini.hub.observation.ConversationCoverage.StaleOrDisconnected -> "Disconnected or stale"
                    app.mini.hub.observation.ConversationCoverage.NotificationOnly -> "Notification observation"
                }
                Text(
                    text = "$categoryLabel · $coverageLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (conversation.unreadCount > 0) {
                    Text(
                        text = "${conversation.unreadCount} unread",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun BadgeLabel(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ConversationDetailView(
    summary: ConversationObservationSummary,
    attention: ConversationAttention,
    timeline: List<ObservedNotification>,
    localDraftText: String,
    localDraftWarning: String?,
    localDraftStatus: String?,
    localDraftFeedback: DraftUsefulnessFeedback?,
    isLocalDraftInProgress: Boolean,
    isLocalAiRetryAvailable: Boolean,
    isLocalAiRetryInProgress: Boolean,
    localAiReleaseStatus: String?,
    onBack: () -> Unit,
    onLocalDraftRequested: (String) -> Unit,
    onLocalDraftChanged: (String) -> Unit,
    onLocalDraftCancelled: () -> Unit,
    onDraftUsefulness: (DraftUsefulnessFeedback) -> Unit,
    onRetryLocalAi: () -> Unit,
    onDraftPrepared: (String, String) -> Unit,
    onAttentionAction: (AttentionAction) -> Unit,
) {
    var replyIntent by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) {
                Text("← Back to Inbox")
            }
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        // Coverage limitation notice
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = "Notice: ${summary.coverageExplanation}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        // Attention Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Attention", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isImportant = attention.priority == ConversationPriority.Important
                    Button(
                        onClick = { onAttentionAction(AttentionAction.SetPriority(if (isImportant) ConversationPriority.Normal else ConversationPriority.Important)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isImportant) "Mark Normal" else "Mark Important")
                    }
                    if (attention.status != FollowUpStatus.Waiting) {
                        Button(
                            onClick = { onAttentionAction(AttentionAction.MarkWaiting) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Waiting")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (attention.status != FollowUpStatus.NeedsAttention) {
                        OutlinedButton(
                            onClick = { onAttentionAction(AttentionAction.MarkNeedsAttention) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Needs Attention")
                        }
                    }
                    if (attention.status != FollowUpStatus.Done) {
                        OutlinedButton(
                            onClick = { onAttentionAction(AttentionAction.MarkDone) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Done")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { 
                            // Hardcoded 4-hour snooze for MVP simplicity
                            onAttentionAction(AttentionAction.Snooze(UtcInstant(System.currentTimeMillis() + 4 * 60 * 60 * 1000L))) 
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Snooze 4h")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Timeline of observations
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(timeline, key = { it.id.key }) { obs ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = obs.senderHint,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (obs.isRemoved) {
                                Text(
                                    text = "Dismissed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Text(
                            text = obs.textFragments.joinToString("\n"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Small local drafting experiment. The generated text remains editable and enters the
        // existing exact-action review only when the user explicitly chooses it.
        OutlinedTextField(
            value = replyIntent,
            onValueChange = { replyIntent = it },
            label = { Text("Reply intent for local draft") },
            placeholder = { Text("e.g. acknowledge and ask for the missing time") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onLocalDraftRequested(replyIntent) },
                enabled = replyIntent.isNotBlank() && !isLocalDraftInProgress && !isLocalAiRetryAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Text("Draft on device")
            }
            if (isLocalDraftInProgress) {
                OutlinedButton(onClick = onLocalDraftCancelled) {
                    Text("Cancel")
                }
            }
            if (isLocalAiRetryAvailable) {
                OutlinedButton(
                    onClick = onRetryLocalAi,
                    enabled = !isLocalAiRetryInProgress,
                ) {
                    Text(if (isLocalAiRetryInProgress) "Retrying…" else "Retry local AI")
                }
            }
        }
        localDraftStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (localDraftText.isNotBlank() && !isLocalDraftInProgress) {
            Text("Was this draft useful?", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onDraftUsefulness(DraftUsefulnessFeedback.Useful) }) { Text("Useful") }
                OutlinedButton(onClick = { onDraftUsefulness(DraftUsefulnessFeedback.NeedsEditing) }) { Text("Needs edits") }
                OutlinedButton(onClick = { onDraftUsefulness(DraftUsefulnessFeedback.NotUseful) }) { Text("Not useful") }
            }
        }
        localDraftFeedback?.let { feedback ->
            Text(
                text = when (feedback) {
                    DraftUsefulnessFeedback.Useful -> "Feedback recorded: useful"
                    DraftUsefulnessFeedback.NeedsEditing -> "Feedback recorded: needs editing"
                    DraftUsefulnessFeedback.NotUseful -> "Feedback recorded: not useful"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        localAiReleaseStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        localDraftWarning?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text = "Context warning: $warning",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        OutlinedTextField(
            value = localDraftText,
            onValueChange = onLocalDraftChanged,
            label = { Text("Editable local draft") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = {
                if (localDraftText.isNotBlank()) {
                    onDraftPrepared(localDraftText, summary.senderHint)
                }
            },
            enabled = localDraftText.isNotBlank() && !isLocalDraftInProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Review local draft before opening")
        }
    }
}

@Composable
fun ExactActionReviewDialog(
    payload: app.mini.hub.action.ExactActionPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isApi = payload.route == app.mini.hub.action.ActionRoute.Api
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact Action Review (No Unintended Send)") },
        text = {
            Column {
                Text(
                    text = if (isApi) {
                        "Review the exact message, subject and destination before dispatching. This action will send directly via official provider API."
                    } else {
                        "Review the exact message and destination before proceeding. This action will open WhatsApp / email with this pre-filled draft."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Route: ${if (isApi) "Official Provider API (${payload.accountId})" else "Native Handoff"}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                payload.subject?.let {
                    Text("Subject: $it", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Text("Recipient: ${payload.recipients.joinToString().ifBlank { "Original Thread" }}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))

                Text("Draft Content:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        text = payload.body,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Policy: ${payload.policyVersion} | Grant: ${payload.grantVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (isApi) "Approve & Send via API" else "Approve & Open App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
