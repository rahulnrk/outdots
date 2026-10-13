package app.mini.hub.android.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import app.mini.hub.observation.LinkedInActivityType
import app.mini.hub.understanding.MessageUnderstanding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    handoffLauncher: HandoffLauncher,
    onRequestPermission: () -> Unit,
    sourcePreferences: CommunicationSourcePreferences,
    accountStore: ConnectedAccountStore,
    routes: List<app.mini.hub.android.connector.ConnectRouteInfo>,
    connectionStatus: String?,
    onConnect: (app.mini.hub.domain.Provider) -> Unit,
    onSourceSelectionChanged: () -> Unit,
    capabilities: (ConnectedAccount) -> app.mini.hub.connector.CapabilitySnapshot? = { null },
    onConnectTelegram: (String) -> Unit = {},
    onConnectMatrix: (app.mini.hub.domain.Provider, String, String) -> Unit = { _, _, _ -> },
    onStartWhatsAppPairing: () -> app.mini.hub.connector.WhatsAppPairing = {
        app.mini.hub.connector.WhatsAppPairing.Unavailable(app.mini.hub.connector.UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
    },
    onStartWhatsAppPhonePairing: (String) -> app.mini.hub.connector.WhatsAppPairing = {
        app.mini.hub.connector.WhatsAppPairing.Unavailable(app.mini.hub.connector.UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
    },
    onPollWhatsAppPairing: () -> app.mini.hub.connector.WhatsAppPairing = {
        app.mini.hub.connector.WhatsAppPairing.Unavailable(app.mini.hub.connector.UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
    },
    onCancelWhatsAppPairing: () -> Unit = {},
    onWhatsAppLinked: (app.mini.hub.connector.WhatsAppPairing.Linked) -> Unit = {},
    onDisconnectAccount: (ConnectedAccount) -> Unit = {},
    onSyncNow: () -> Unit = {},
    accountsVersion: Int = 0,
    styleLearningEnabled: Boolean = true,
    onStyleLearningChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.state
    val context = androidx.compose.ui.platform.LocalContext.current
    val conversationLabels = remember { app.mini.hub.android.ConversationLabels(context) }
    var labels by remember { mutableStateOf(conversationLabels.all()) }
    var showSourceSettings by remember { mutableStateOf(false) }
    var showAccountSettings by remember { mutableStateOf(false) }
    var showLearningSettings by remember { mutableStateOf(false) }
    HubApp(
        viewModel = viewModel,
        handoffLauncher = handoffLauncher,
        onRequestPermission = onRequestPermission,
        sourcePreferences = sourcePreferences,
        accountStore = accountStore,
        connectionStatus = connectionStatus,
        onConnect = onConnect,
        onSourceSelectionChanged = onSourceSelectionChanged,
        styleLearningEnabled = styleLearningEnabled,
        onStyleLearningChanged = onStyleLearningChanged,
        onOpenAccounts = { showAccountSettings = true },
        onOpenSources = { showSourceSettings = true },
        onSyncNow = onSyncNow,
    )
    var hubTab by rememberSaveable { mutableStateOf(HubTab.Inbox.name) }
    val currentTab = HubTab.entries.firstOrNull { it.name == hubTab } ?: HubTab.Inbox
    val showingThread = state.selectedConversation != null
    // Returning from Google recreates the screen. Reopen Accounts so the connection result is visible.
    androidx.compose.runtime.LaunchedEffect(connectionStatus) {
        if (connectionStatus != null) showAccountSettings = true
    }

    if (false) Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!showingThread) {
                BrandBar(
                    statusText = if (state.isListenerConnected) "On this phone" else "Listener idle",
                    statusOk = state.isListenerConnected,
                    onStatusClick = { hubTab = HubTab.Console.name },
                    onCompose = {
                        val gmail = accountStore.list().firstOrNull {
                            it.id.provider == app.mini.hub.domain.Provider.GMAIL && it.state != app.mini.hub.connector.ConnectionState.Disconnected
                        }
                        if (gmail != null) viewModel.startComposeEmail(gmail.id) else {
                            hubTab = HubTab.Console.name
                            showAccountSettings = true
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!showingThread) {
                LineuppBottomBar(selected = currentTab, onSelect = { hubTab = it.name })
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            if (!state.isPermissionGranted) {
                NoticeBanner(
                    title = "Notification access needed",
                    body = "Android needs this so Lineupp can save new messages from the sources you choose.",
                    tone = PillTone.Attention,
                    action = "Open settings",
                    onAction = onRequestPermission,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else if (!state.isListenerConnected) {
                NoticeBanner(
                    title = "Listener is idle",
                    body = "New notifications aren't being saved right now.",
                    tone = PillTone.Attention,
                    action = "Fix",
                    onAction = { hubTab = HubTab.Console.name },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            state.storageFailure?.let { failure ->
                NoticeBanner(
                    title = "Storage error",
                    body = failure,
                    tone = PillTone.Error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            state.outboxStatusMessage?.let { msg ->
                NoticeBanner(
                    title = "Reply status",
                    body = msg,
                    tone = PillTone.Action,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (state.selectedConversation != null) {
                state.conversationLoadStatus?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                // Conversation Detail View
                val record = state.attentionRecords[state.selectedConversation!!.conversationId.key] 
                    ?: app.mini.hub.attention.AttentionRules.default(state.selectedConversation!!.conversationId, app.mini.hub.domain.UtcInstant(System.currentTimeMillis()))
                val selectedKey = state.selectedConversation!!.conversationId.key
                var newSubject by remember(selectedKey) { mutableStateOf("") }
                var newTo by remember(selectedKey) { mutableStateOf(state.composeNewTo.orEmpty()) }
                var newCc by remember(selectedKey) { mutableStateOf("") }
                var newBcc by remember(selectedKey) { mutableStateOf("") }
                var showCcBcc by remember(selectedKey) { mutableStateOf(false) }
                if (state.composeNewTo != null) {
                    val emailKb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)
                    OutlinedTextField(newTo, { newTo = it }, label = { Text("To (comma separated)") }, singleLine = true, keyboardOptions = emailKb, modifier = Modifier.fillMaxWidth())
                    if (showCcBcc) {
                        OutlinedTextField(newCc, { newCc = it }, label = { Text("Cc") }, singleLine = true, keyboardOptions = emailKb, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(newBcc, { newBcc = it }, label = { Text("Bcc") }, singleLine = true, keyboardOptions = emailKb, modifier = Modifier.fillMaxWidth())
                    } else {
                        TextButton(onClick = { showCcBcc = true }) { Text("+ Cc / Bcc") }
                    }
                    OutlinedTextField(
                        value = newSubject,
                        onValueChange = { newSubject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ThreadView(
                    summary = state.selectedConversation!!,
                    timeline = state.selectedTimeline,
                    label = labels[selectedKey],
                    onLabel = { l ->
                        conversationLabels.set(selectedKey, l)
                        labels = conversationLabels.all()
                        viewModel.applyAttentionAction(
                            state.selectedConversation!!.conversationId,
                            app.mini.hub.attention.AttentionAction.SetPriority(
                                if (l == "Priority") app.mini.hub.attention.ConversationPriority.Important else app.mini.hub.attention.ConversationPriority.Normal,
                            ),
                        )
                    },
                    draftText = state.localDraftText,
                    draftStatus = state.localDraftStatus,
                    draftVersions = state.draftVersions,
                    isDrafting = state.isLocalDraftInProgress,
                    onBack = { viewModel.clearSelection() },
                    onDraft = { instruction, revise -> viewModel.requestLocalDraft(instruction, revise) },
                    onCancelDraft = { viewModel.cancelLocalDraft() },
                    onDraftChanged = { text -> viewModel.updateLocalDraftText(text) },
                    onApprove = { body ->
                        if (state.composeNewTo != null) {
                            viewModel.prepareComposeEmail(state.selectedConversation!!.accountId, listOf(newTo), listOf(newCc), listOf(newBcc), newSubject, body)
                        } else viewModel.prepareDraft(body, "", newSubject)
                    },
                    onAttention = { a -> viewModel.applyAttentionAction(state.selectedConversation!!.conversationId, a) },
                )
                if (false) ConversationDetailView(
                    summary = state.selectedConversation!!,
                    understanding = state.understanding[state.selectedConversation!!.conversationId.key],
                    styleProfile = state.activeStyleProfile,
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
            } else when (currentTab) {
                HubTab.Focus -> FocusPane(
                    view = state.attentionView,
                    conversations = state.conversations,
                    onSelect = { viewModel.selectConversation(it) },
                    onAttention = { id, action -> viewModel.applyAttentionAction(id, action) },
                )
                HubTab.Console -> ConsolePane(
                    accounts = remember(accountsVersion, connectionStatus) { accountStore.list() },
                    isPermissionGranted = state.isPermissionGranted,
                    isListenerConnected = state.isListenerConnected,
                    styleLearningEnabled = styleLearningEnabled,
                    connectionStatus = connectionStatus,
                    onOpenAccounts = { showAccountSettings = true },
                    onOpenSources = { showSourceSettings = true },
                    onOpenStyle = { showLearningSettings = true },
                    onRequestPermission = onRequestPermission,
                    onSyncNow = onSyncNow,
                )
                HubTab.Inbox -> {
                PageTitle("Inbox", "Everything that's arrived.")
                // Unified search box across every enabled source
                androidx.compose.material3.OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Search people and messages") },
                    singleLine = true,
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            TextButton(onClick = { viewModel.clearSearch() }) {
                                Text("✕", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                if (state.searchQuery.isNotBlank()) {
                    // People: reach this person on any connected account
                    val contactRoutes by androidx.compose.runtime.produceState(emptyList<app.mini.hub.android.connector.AndroidConnectorRegistry.ContactRoute>(), state.searchQuery) {
                        kotlinx.coroutines.delay(250)
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { app.mini.hub.android.MiniHubRuntime.connectors.findContacts(state.searchQuery) }.getOrDefault(emptyList())
                        }
                    }
                    if (contactRoutes.isNotEmpty()) {
                        Text("People", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        contactRoutes.groupBy { it.name.lowercase() }.entries.take(6).forEach { (_, routes) ->
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(routes.first().name, fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        routes.forEach { r ->
                                            OutlinedButton(onClick = { viewModel.openCompose(r.accountId, r.address, r.name) }) {
                                                Text(r.label, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("Messages", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    // Search Results View
                    if (state.isSearching) {
                        Text("Searching…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    } else if (state.searchResults.isEmpty()) {
                        Text("No matching messages found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        Text("${state.searchResults.size} result(s) found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.searchResults, key = { it.conversationId.key }) { res ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.selectConversationFromSearch(res) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(res.conversationTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            BadgeLabel(
                                                text = "${res.accountId.provider.wire} · ${app.mini.hub.observation.SourceAppNames.forPackage(res.packageId)}",
                                                color = MaterialTheme.colorScheme.secondary,
                                            )
                                        }
                                        Text(res.senderHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(res.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Chat-style list for every source
                    ChatListView(
                        conversations = state.conversations,
                        labels = labels,
                        attention = state.attentionRecords,
                        onSelect = { viewModel.selectConversation(it) },
                        onAttention = { conversationId, action -> viewModel.applyAttentionAction(conversationId, action) },
                    )
                }
                }
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
            routes = routes,
            capabilities = capabilities,
            connectionStatus = connectionStatus,
            onRequestPermission = onRequestPermission,
            onConnect = onConnect,
            onConnectTelegram = onConnectTelegram,
            onConnectMatrix = onConnectMatrix,
            onStartWhatsAppPairing = onStartWhatsAppPairing,
            onStartWhatsAppPhonePairing = onStartWhatsAppPhonePairing,
            onPollWhatsAppPairing = onPollWhatsAppPairing,
            onCancelWhatsAppPairing = onCancelWhatsAppPairing,
            onWhatsAppLinked = onWhatsAppLinked,
            onDisconnect = onDisconnectAccount,
            onSyncNow = onSyncNow,
            onDismiss = { showAccountSettings = false },
        )
    }

    if (showLearningSettings) {
        AlertDialog(
            onDismissRequest = { showLearningSettings = false },
            title = { Text("Local writing learning") },
            text = {
                Column {
                    Text(
                        "When enabled, MINI learns compact professional, casual, and recipient-specific writing preferences from authorized provider messages authored by you and from text you approve. Received messages and unaccepted drafts are never used as your style.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = styleLearningEnabled,
                            onCheckedChange = onStyleLearningChanged,
                        )
                        Text(if (styleLearningEnabled) "Learning is enabled on this device" else "Learning is paused and profiles are cleared")
                    }
                    Text("Stored profiles are encrypted with Android Keystore. This setting does not send anything.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLearningSettings = false }) { Text("Done") } },
        )
    }

    // Exact Action Review Dialog
    if (false) state.activeReviewPayload?.let { payload ->
        ExactActionReviewDialog(
            payload = payload,
            context = state.reviewContext,
            isSending = state.isSending,
            onConfirm = { viewModel.approveAndDispatch(handoffLauncher) },
            onDismiss = { if (!state.isSending) viewModel.clearDraftReview() },
        )
    }
}

@Composable
private fun FocusPane(
    view: AttentionView?,
    conversations: List<ConversationObservationSummary>,
    onSelect: (ConversationObservationSummary) -> Unit,
    onAttention: (app.mini.hub.domain.ScopedId, AttentionAction) -> Unit,
) {
    val needs = view?.needsAttention.orEmpty()
    val waiting = view?.waiting.orEmpty()
    val later = view?.snoozed.orEmpty()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            PageTitle("A little focus.", if (needs.isEmpty()) "Nothing needs you right now." else "Here's what needs you today.")
            Text(needs.size.toString(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (needs.isEmpty() && waiting.isEmpty() && later.isEmpty()) {
            Text("New messages can still arrive. Your inbox is always here.", color = lineupp.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        needs.forEach { entry ->
            FocusCard(
                summary = entry.summary,
                status = "Needs reply",
                tone = PillTone.Attention,
                onSelect = { onSelect(entry.summary) },
                onLater = { onAttention(entry.summary.conversationId, AttentionAction.Snooze(UtcInstant(System.currentTimeMillis() + 4 * 3600_000L))) },
                onDone = { onAttention(entry.summary.conversationId, AttentionAction.MarkDone) },
            )
        }
        if (waiting.isNotEmpty()) {
            SectionLabel("Waiting on them", waiting.size.toString())
            waiting.take(5).forEach { entry ->
                FocusCard(summary = entry.summary, status = "Waiting", tone = PillTone.Neutral, onSelect = { onSelect(entry.summary) }, onLater = null, onDone = { onAttention(entry.summary.conversationId, AttentionAction.MarkDone) })
            }
        }
        if (later.isNotEmpty()) {
            SectionLabel("Later", later.size.toString())
            later.take(5).forEach { entry ->
                FocusCard(summary = entry.summary, status = "Later", tone = PillTone.Action, onSelect = { onSelect(entry.summary) }, onLater = null, onDone = { onAttention(entry.summary.conversationId, AttentionAction.MarkDone) })
            }
        }
        if (conversations.isEmpty() && needs.isEmpty()) {
            Text("No conversations yet.", color = lineupp.secondaryText, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun FocusCard(
    summary: ConversationObservationSummary,
    status: String,
    tone: PillTone,
    onSelect: () -> Unit,
    onLater: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val source = app.mini.hub.observation.SourceAppNames.forPackage(summary.packageId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(summary.title, source, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(source, style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText)
            }
            StatePill(status, tone)
        }
        Text(summary.latestSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSelect, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text("Reply") }
            if (onLater != null) OutlinedButton(onClick = onLater, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text("Later") }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun ConsolePane(
    accounts: List<ConnectedAccount>,
    isPermissionGranted: Boolean,
    isListenerConnected: Boolean,
    styleLearningEnabled: Boolean,
    connectionStatus: String?,
    onOpenAccounts: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenStyle: () -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Your console.", "A clear view of what's working.")
        NoticeBanner(
            title = "Private, by design.",
            body = "Messages are stored encrypted here. Your connected services still use the internet.",
            tone = PillTone.Settled,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val needs = accounts.count {
            it.state != app.mini.hub.connector.ConnectionState.Healthy &&
                it.state != app.mini.hub.connector.ConnectionState.Authorized &&
                it.state != app.mini.hub.connector.ConnectionState.Syncing
        }
        SectionLabel("Connected sources", if (accounts.isEmpty()) "None yet" else if (needs > 0) needs.toString() + " need attention" else "All active")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            if (accounts.isEmpty()) {
                SettingRow("No authenticated accounts", "Connect Gmail, Outlook or others to sync history.", onClick = onOpenAccounts)
            } else {
                accounts.forEachIndexed { index, account ->
                    if (index > 0) HorizontalDivider(color = lineupp.divider)
                    val ok = account.state == app.mini.hub.connector.ConnectionState.Healthy ||
                        account.state == app.mini.hub.connector.ConnectionState.Authorized ||
                        account.state == app.mini.hub.connector.ConnectionState.Syncing
                    SettingRow(
                        title = (account.displayName ?: account.providerAccountId) + " · " + account.id.provider.wire,
                        subtitle = account.userActionRequired ?: accountStateLabel(account.state),
                        trailing = { StatePill(accountStateLabel(account.state), if (ok) PillTone.Settled else PillTone.Attention) },
                        onClick = onOpenAccounts,
                    )
                }
            }
        }
        SectionLabel("Notification listener")
        Column(
            modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            SettingRow(
                title = "Notification access",
                subtitle = if (isPermissionGranted) "Android access is enabled" else "Needed to save new notifications",
                trailing = { StatePill(if (isPermissionGranted) "On" else "Off", if (isPermissionGranted) PillTone.Settled else PillTone.Attention) },
                onClick = onRequestPermission,
            )
            HorizontalDivider(color = lineupp.divider)
            SettingRow(
                title = "Save notifications",
                subtitle = if (isListenerConnected) "New selected-source notifications can be saved" else "Listener idle · new notifications aren't saved",
                trailing = { StatePill(if (isListenerConnected) "Active" else "Idle", if (isListenerConnected) PillTone.Settled else PillTone.Attention) },
                onClick = onOpenSources,
            )
        }
        SectionLabel("Your preferences")
        Column(
            modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            SettingRow("Sources", "Choose which notification apps appear in the inbox", onClick = onOpenSources)
            HorizontalDivider(color = lineupp.divider)
            SettingRow(
                "Writing style",
                if (styleLearningEnabled) "On for approved outgoing writing" else "Off · learns only with your permission",
                onClick = onOpenStyle,
            )
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Add or disconnect accounts", "Provider sign-in stays with that service", onClick = onOpenAccounts)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Sync connected accounts now", "Pull new mail and messages from signed-in accounts", onClick = onSyncNow)
        }
        connectionStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun LocalUnderstandingDashboard(
    conversations: List<ConversationObservationSummary>,
    understanding: Map<String, MessageUnderstanding>,
) {
    val signals = conversations.mapNotNull { understanding[it.conversationId.key]?.takeIf { signal -> signal.hasSignal } }
    if (signals.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Local understanding dashboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${signals.size} conversation(s) have timing or action signals. These are suggestions, not commitments.", style = MaterialTheme.typography.bodySmall)
            val unanswered = signals.count { it.unansweredRequest }
            val waiting = signals.count { it.waiting }
            val deadlines = signals.count { it.deadline != null }
            Text(
                "Unanswered requests: $unanswered · Waiting: $waiting · Deadline mentions: $deadlines",
                style = MaterialTheme.typography.labelSmall,
            )
            signals.take(2).forEach { signal ->
                Text("• ${signal.purpose} · ${signal.urgency}: ${signal.explanation}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                    if (source == CommunicationSource.LinkedIn && source in selected) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("LinkedIn activity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Messages always appear in Chats. Choose which non-message activity appears in Notifications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        var activities by remember { mutableStateOf(preferences.selectedLinkedInActivities()) }
                        LinkedInActivityType.entries.forEach { activity ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = activity in activities,
                                    onCheckedChange = { enabled ->
                                        preferences.setLinkedInActivitySelected(activity, enabled)
                                        activities = preferences.selectedLinkedInActivities()
                                        onChanged()
                                    },
                                )
                                Text(activity.displayName)
                            }
                        }
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
    routes: List<app.mini.hub.android.connector.ConnectRouteInfo>,
    capabilities: (ConnectedAccount) -> app.mini.hub.connector.CapabilitySnapshot?,
    connectionStatus: String?,
    onRequestPermission: () -> Unit,
    onConnect: (app.mini.hub.domain.Provider) -> Unit,
    onConnectTelegram: (String) -> Unit,
    onConnectMatrix: (app.mini.hub.domain.Provider, String, String) -> Unit,
    onStartWhatsAppPairing: () -> app.mini.hub.connector.WhatsAppPairing,
    onStartWhatsAppPhonePairing: (String) -> app.mini.hub.connector.WhatsAppPairing,
    onPollWhatsAppPairing: () -> app.mini.hub.connector.WhatsAppPairing,
    onCancelWhatsAppPairing: () -> Unit,
    onWhatsAppLinked: (app.mini.hub.connector.WhatsAppPairing.Linked) -> Unit,
    onDisconnect: (ConnectedAccount) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDisconnect by remember { mutableStateOf<ConnectedAccount?>(null) }
    var showTelegramEntry by remember { mutableStateOf(false) }
    var showInstagramEntry by remember { mutableStateOf(false) }
    var showWhatsAppPairing by remember { mutableStateOf(false) }
    var matrixEntryProvider by remember { mutableStateOf<app.mini.hub.domain.Provider?>(null) }
    
    if (showTelegramEntry) {
        TelegramTokenDialog(
            onSubmit = { onConnectTelegram(it); showTelegramEntry = false },
            onDismiss = { showTelegramEntry = false },
        )
    }
    if (showInstagramEntry) {
        InstagramTokenDialog(
            onSubmit = { onConnectTelegram(INSTAGRAM_TOKEN_PREFIX + it); showInstagramEntry = false },
            onDismiss = { showInstagramEntry = false },
        )
    }
    if (showWhatsAppPairing) {
        WhatsAppPairingDialog(
            onStartPhone = onStartWhatsAppPhonePairing,
            onPoll = onPollWhatsAppPairing,
            onLinked = { onWhatsAppLinked(it); showWhatsAppPairing = false },
            onDismiss = {
                onCancelWhatsAppPairing()
                showWhatsAppPairing = false
            },
        )
    }
    matrixEntryProvider?.let { provider ->
        MatrixBridgeDialog(
            onSubmit = { url, token -> onConnectMatrix(provider, url, token); matrixEntryProvider = null },
            onDismiss = { matrixEntryProvider = null },
        )
    }
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                        capabilities(account)?.let { snapshot ->
                            Text(
                                capabilitySummary(snapshot),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (account.state != app.mini.hub.connector.ConnectionState.Disconnected) {
                            TextButton(onClick = { pendingDisconnect = account }) { Text("Disconnect") }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    TextButton(onClick = onSyncNow) { Text("Sync connected accounts now") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                connectionStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Add an account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                routes.forEach { route ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(route.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(route.accountKinds, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (route.configured) route.status else "Setup required: ${route.status}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { 
                            if (route.usesMatrixEntry) matrixEntryProvider = route.provider 
                            else if (route.usesNotificationFallback) onRequestPermission()
                            else if (route.usesInstagramToken) showInstagramEntry = true
                            else if (route.usesTokenEntry) showTelegramEntry = true
                            else if (route.usesPhoneLocalPairing) showWhatsAppPairing = true
                            else onConnect(route.provider) 
                        },
                        enabled = route.configured,
                    ) {
                        Text(if (route.configured) "Connect ${route.title}" else "${route.title}: not set up in this build")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (routes.any { it.usesPhoneLocalPairing }) {
                        "Messenger, Instagram, LinkedIn, Google Messages, Discord, LINE, Signal and personal Telegram have no supported personal API. MINI reads their notifications and opens the app with your reviewed text."
                    } else {
                        "WhatsApp, Messenger, Instagram, LinkedIn, Google Messages, Discord, LINE, Signal and personal Telegram have no supported personal API. " +
                            "MINI reads their notifications (choose them under Sources) and opens the app with your reviewed text. It never sends for you there."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun capabilitySummary(snapshot: app.mini.hub.connector.CapabilitySnapshot): String {
    val on = snapshot.enabled.map { it.name }.sorted()
    val reply = snapshot.disabled[app.mini.hub.connector.Capability.Reply]
    return buildString {
        append(if (on.isEmpty()) "Nothing enabled yet" else "Enabled: " + on.joinToString(", "))
        reply?.let { append("\nReply via MINI: off (").append(it).append(")") }
    }
}

@Composable
private fun WhatsAppPairingDialog(
    onStartPhone: (String) -> app.mini.hub.connector.WhatsAppPairing,
    onPoll: () -> app.mini.hub.connector.WhatsAppPairing,
    onLinked: (app.mini.hub.connector.WhatsAppPairing.Linked) -> Unit,
    onDismiss: () -> Unit,
) {
    var phone by remember { mutableStateOf("+") }
    var status by remember { mutableStateOf("Enter the WhatsApp number on this phone, then tap Get code.") }
    var code by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link WhatsApp on this phone") },
        text = {
            Column {
                Text(
                    "The QR is in MINI, not in WhatsApp. On this same phone, use a pairing code: WhatsApp → Linked devices → Link a device → Link with phone number instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!started) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.trim() },
                        label = { Text("Your number (E.164)") },
                        singleLine = true,
                    )
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                code?.let { value ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            if (!started) {
                TextButton(
                    onClick = { started = true },
                    enabled = phone.length > 8,
                ) { Text("Get code") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    androidx.compose.runtime.LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        status = "Asking WhatsApp for a pairing code…"
        val startedState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { onStartPhone(phone) }
        when (startedState) {
            is app.mini.hub.connector.WhatsAppPairing.Linked -> {
                onLinked(startedState)
                return@LaunchedEffect
            }
            is app.mini.hub.connector.WhatsAppPairing.PhoneCode -> {
                code = startedState.code
                status = "In WhatsApp: Linked devices → Link a device → Link with phone number instead. Enter this code."
            }
            is app.mini.hub.connector.WhatsAppPairing.QrReady -> {
                status = "QR is ready, but this phone cannot scan itself. Use a pairing code instead."
            }
            is app.mini.hub.connector.WhatsAppPairing.Cancelled -> {
                onDismiss()
                return@LaunchedEffect
            }
            is app.mini.hub.connector.WhatsAppPairing.Unavailable -> {
                if (startedState.reason != "qr_pending") status = startedState.reason
            }
        }
        while (true) {
            kotlinx.coroutines.delay(500)
            when (val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { onPoll() }) {
                is app.mini.hub.connector.WhatsAppPairing.Linked -> {
                    onLinked(next)
                    return@LaunchedEffect
                }
                is app.mini.hub.connector.WhatsAppPairing.PhoneCode -> {
                    code = next.code
                    status = "In WhatsApp: Linked devices → Link a device → Link with phone number instead. Enter this code."
                }
                is app.mini.hub.connector.WhatsAppPairing.QrReady -> { }
                is app.mini.hub.connector.WhatsAppPairing.Cancelled -> {
                    onDismiss()
                    return@LaunchedEffect
                }
                is app.mini.hub.connector.WhatsAppPairing.Unavailable -> {
                    if (next.reason != "qr_pending") status = next.reason
                }
            }
        }
    }
}

@Composable
private fun WhatsAppQrImage(payload: String) {
    val bitmap = remember(payload) { encodeWhatsAppQr(payload) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "WhatsApp linked-device QR",
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
    } else {
        Text(payload, style = MaterialTheme.typography.labelSmall)
    }
}

private fun encodeWhatsAppQr(payload: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(512 * 512) { i ->
        val x = i % 512
        val y = i / 512
        if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val androidBitmap = android.graphics.Bitmap.createBitmap(pixels, 512, 512, android.graphics.Bitmap.Config.ARGB_8888)
    androidBitmap.asImageBitmap()
}.getOrNull()

const val INSTAGRAM_TOKEN_PREFIX = "instagram:"

@Composable
private fun InstagramTokenDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Instagram (official)") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Works for Business or Creator accounts. In your Meta app: Instagram → API setup with Instagram login → Add account → Generate token. Paste it here. MINI checks it with Instagram and stores it in Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Meta limits: 20 latest messages per chat; replies within 24h of their last message. After that, Send opens Instagram with your text.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Instagram access token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(token) }, enabled = token.length > 40) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TelegramTokenDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a Telegram bot") },
        text = {
            Column {
                Text(
                    "Paste the token BotFather gave you for a bot you own. MINI checks it with Telegram, stores it in Android Keystore and only sees chats the bot is in. " +
                        "This is not your personal Telegram account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Bot token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(token) }, enabled = token.contains(':')) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MatrixBridgeDialog(onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Matrix Bridge") },
        text = {
            Column {
                Text(
                    "Provide the URL of your Matrix homeserver (e.g. https://matrix.example.com) and an access token.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Homeserver URL") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Access Token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(url, token) }, 
                enabled = url.startsWith("http") && token.isNotBlank()
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    understanding: Map<String, MessageUnderstanding> = emptyMap(),
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
                ConversationItemCard(conversation = conv, attention = null, understanding = understanding[conv.conversationId.key], onClick = { onSelect(conv) })
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.needsAttention.isNotEmpty()) {
                item { SectionHeader("Needs Attention") }
                items(view.needsAttention, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, understanding = understanding[e.summary.conversationId.key], onClick = { onSelect(e.summary) })
                }
            }
            if (view.snoozed.isNotEmpty()) {
                item { SectionHeader("Snoozed") }
                items(view.snoozed, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, understanding = understanding[e.summary.conversationId.key], onClick = { onSelect(e.summary) })
                }
            }
            if (view.waiting.isNotEmpty()) {
                item { SectionHeader("Waiting on them") }
                items(view.waiting, key = { it.summary.conversationId.key }) { e ->
                    ConversationItemCard(conversation = e.summary, attention = e.attention, understanding = understanding[e.summary.conversationId.key], onClick = { onSelect(e.summary) })
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
    understanding: MessageUnderstanding? = null,
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

            understanding?.takeIf { it.hasSignal }?.let { signal ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BadgeLabel(signal.urgency, if (signal.urgency == "High") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)
                    BadgeLabel(signal.purpose, MaterialTheme.colorScheme.secondaryContainer)
                    signal.deadline?.let { BadgeLabel(it, MaterialTheme.colorScheme.errorContainer) }
                }
                Text(
                    text = signal.explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
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
private fun LinkedThreadView(
    summary: ConversationObservationSummary,
    timeline: List<ObservedNotification>,
    localDraftText: String,
    localDraftStatus: String?,
    isLocalDraftInProgress: Boolean,
    onBack: () -> Unit,
    onLocalDraftRequested: (String) -> Unit,
    onLocalDraftChanged: (String) -> Unit,
    onDraftPrepared: (String, String) -> Unit,
    replyIntent: String,
    onReplyIntentChanged: (String) -> Unit,
) {
    val messages = timeline.filterNot { it.isRemoved }.sortedBy { it.postTime.epochMillis }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Text(summary.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Linked WhatsApp on this phone. Pausing notifications does not stop this chat.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text("Waiting for messages from the linked session…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(messages, key = { it.id.key }) { obs ->
                val mine = obs.direction == app.mini.hub.connector.MessageDirection.Outbound
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.82f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (!mine) {
                                Text(obs.senderHint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Text(obs.textFragments.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = replyIntent,
            onValueChange = onReplyIntentChanged,
            label = { Text("Ask AI to draft (optional)") },
            placeholder = { Text("e.g. confirm tomorrow 3pm") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = localDraftText,
            onValueChange = onLocalDraftChanged,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )
        localDraftStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onLocalDraftRequested(replyIntent.ifBlank { "write a short reply" }) },
                enabled = !isLocalDraftInProgress,
                modifier = Modifier.weight(1f),
            ) { Text(if (isLocalDraftInProgress) "Drafting…" else "Draft with AI") }
            Button(
                onClick = { onDraftPrepared(localDraftText, summary.senderHint) },
                enabled = localDraftText.isNotBlank() && !isLocalDraftInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("Review and send") }
        }
    }
}

@Composable
fun ConversationDetailView(
    summary: ConversationObservationSummary,
    understanding: MessageUnderstanding? = null,
    styleProfile: app.mini.hub.style.CompactStyleProfile? = null,
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
    if (summary.packageId.startsWith(app.mini.hub.observation.CommunicationSource.API_PACKAGE_PREFIX)) {
        LinkedThreadView(
            summary = summary,
            timeline = timeline,
            localDraftText = localDraftText,
            localDraftStatus = localDraftStatus,
            isLocalDraftInProgress = isLocalDraftInProgress,
            onBack = onBack,
            onLocalDraftRequested = onLocalDraftRequested,
            onLocalDraftChanged = onLocalDraftChanged,
            onDraftPrepared = onDraftPrepared,
            replyIntent = replyIntent,
            onReplyIntentChanged = { replyIntent = it },
        )
        return
    }

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

        understanding?.let { signal ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Local understanding", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("${signal.purpose} · ${signal.urgency}", style = MaterialTheme.typography.bodySmall)
                    signal.deadline?.let { Text("Deadline signal: $it", style = MaterialTheme.typography.bodySmall) }
                    signal.requiredAction?.let { Text("Suggested action: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Why: ${signal.explanation}", style = MaterialTheme.typography.labelSmall)
                    Text("Evidence: ${signal.evidenceText}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }

        styleProfile?.takeIf { it.observationCount > 0 }?.let { profile ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Learned writing style", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${profile.category.name} · confidence ${"%.0f".format(profile.confidence * 100)}% from ${profile.observationCount} user-authored sample(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "formality ${"%.2f".format(profile.traits.formality)} · brevity ${"%.2f".format(profile.traits.brevity)} · ${profile.traits.introStyle}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
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
    context: ReviewContext? = null,
    isSending: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isApi = payload.route == app.mini.hub.action.ActionRoute.Api
    val provider = context?.providerName ?: "the provider"
    val handoffApp = context?.handoffPackage?.let { app.mini.hub.observation.SourceAppNames.forPackage(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check before it goes.") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (isApi) {
                        "MINI will send this through $provider's official API from the account below, as " +
                            (if (context?.isReply == true) "a threaded reply to the latest message." else "a new message in this conversation (no reply anchor).") +
                            " Nothing else is sent. Success only means $provider accepted it, not that it was delivered or read."
                    } else {
                        "MINI will open ${handoffApp ?: "an app you choose"} with this text. Nothing is sent until you send it there yourself."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                context?.note?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text("Route: ${if (isApi) "$provider API" else "Open ${handoffApp ?: "app"} (you send)"}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("Account: ${payload.accountId}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                payload.subject?.let {
                    Text("Subject: $it", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Text("To: ${payload.recipients.joinToString().ifBlank { "this conversation" }}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
            Button(onClick = onConfirm, enabled = !isSending) {
                Text(
                    when {
                        isSending -> "Sending…"
                        isApi -> "Approve & send via $provider"
                        else -> "Approve & open ${handoffApp ?: "app"}"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text("Cancel")
            }
        },
    )
}
