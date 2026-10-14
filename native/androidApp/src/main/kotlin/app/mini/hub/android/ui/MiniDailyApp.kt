package app.mini.hub.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import app.mini.hub.attention.AttentionEntry
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.connector.ConnectionState
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.SourceAppNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MiniTab { Inbox, Focus, Console }

@Composable
fun MiniDailyApp(
    state: InboxViewState,
    viewModel: InboxViewModel,
    accountStore: ConnectedAccountStore,
    labels: Map<String, String>,
    onCompose: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLearning: () -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
    capabilities: (ConnectedAccount) -> app.mini.hub.connector.CapabilitySnapshot?,
    thread: @Composable () -> Unit,
    extraList: @Composable () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(MiniTab.Inbox.name) }
    val current = MiniTab.valueOf(tab)
    val inThread = state.selectedConversation != null
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!inThread) {
                MiniBottomBar(current) { tab = it.name }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding(),
        ) {
            if (inThread) {
                thread()
            } else {
                MiniBrandBar(onThisPhone = state.isListenerConnected && state.isPermissionGranted)
                when (current) {
                    MiniTab.Inbox -> MiniInboxPane(
                        state = state,
                        viewModel = viewModel,
                        accountStore = accountStore,
                        labels = labels,
                        onCompose = onCompose,
                        onRequestPermission = onRequestPermission,
                    )
                    MiniTab.Focus -> MiniFocusPane(
                        state = state,
                        viewModel = viewModel,
                        accountStore = accountStore,
                        labels = labels,
                    )
                    MiniTab.Console -> MiniConsolePane(
                        state = state,
                        accountStore = accountStore,
                        capabilities = capabilities,
                        onOpenSources = onOpenSources,
                        onOpenAccounts = onOpenAccounts,
                        onOpenLearning = onOpenLearning,
                        onRequestPermission = onRequestPermission,
                        onSyncNow = onSyncNow,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBrandBar(onThisPhone: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiniInk.gutter.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text("MINI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            if (onThisPhone) "On this phone" else "Needs setup",
            style = MaterialTheme.typography.labelSmall,
            color = if (onThisPhone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (onThisPhone) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MiniBottomBar(current: MiniTab, onSelect: (MiniTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniTab.entries.forEach { tab ->
            val selected = tab == current
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun UnusedOldInboxPane(
    state: InboxViewState,
    viewModel: InboxViewModel,
    accountStore: ConnectedAccountStore,
    labels: Map<String, String>,
    onCompose: () -> Unit,
    onRequestPermission: () -> Unit,
    extraList: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val seen = remember { LocalSeenStore(context) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var unseenOnly by rememberSaveable { mutableStateOf(false) }
    var accountFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val accounts = remember(state.conversations) { state.conversations.map { it.accountId }.distinct() }
    val unseenCount = state.conversations.count { seen.isUnseen(it.conversationId.key, it.latestEventAt.epochMillis) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MiniInk.gutter.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Inbox", style = MaterialTheme.typography.headlineLarge)
                Text("Everything that's arrived.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = onCompose,
                modifier = Modifier.height(MiniInk.primaryTarget.dp),
            ) { Text("Compose") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
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
            FilterChip(selected = category == null && !unseenOnly && accountFilter == null, onClick = {
                category = null; unseenOnly = false; accountFilter = null; viewModel.onSearchSourceFilterChanged(null)
            }, label = { Text("All") })
            FilterChip(selected = unseenOnly, onClick = { unseenOnly = !unseenOnly }, label = { Text("Unseen in MINI $unseenCount") })
            ConversationLabels.DEFAULTS.forEach { label ->
                FilterChip(selected = category == label, onClick = { category = if (category == label) null else label }, label = { Text(label) })
            }
            accounts.forEach { id ->
                val alias = accountStore.get(id)?.displayName ?: accountStore.get(id)?.providerAccountId ?: id.provider.wire
                FilterChip(
                    selected = accountFilter == id.key,
                    onClick = { accountFilter = if (accountFilter == id.key) null else id.key },
                    label = { Text(alias) },
                )
            }
        }
        if (!state.isPermissionGranted) {
            UnusedMiniBanner(
                title = "Notification access needed",
                body = "MINI can still show connected accounts. Grant access to capture notifications.",
                action = "Open settings",
                onAction = onRequestPermission,
            )
        } else if (!state.isListenerConnected) {
            UnusedMiniBanner(
                title = "Notification listener idle",
                body = "New notifications aren't being saved until the listener is connected.",
                action = "Fix",
                onAction = onRequestPermission,
            )
        }
        state.storageFailure?.let {
            UnusedMiniBanner(title = "Storage error", body = it, action = null, onAction = {})
        }
        extraList()
        val chats = state.conversations
            .filterNot { it.packageId == "com.linkedin.android" && it.category != app.mini.hub.observation.NotificationCategory.DirectMessage && it.category != app.mini.hub.observation.NotificationCategory.GroupMessage }
            .filter { category == null || labels[it.conversationId.key] == category }
            .filter { accountFilter == null || it.accountId.key == accountFilter }
            .filter { !unseenOnly || seen.isUnseen(it.conversationId.key, it.latestEventAt.epochMillis) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MiniInk.gutter.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(MiniInk.rowGap.dp),
        ) {
            if (state.searchQuery.isBlank()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Latest conversations", style = MaterialTheme.typography.titleMedium)
                        Text("${chats.size} conversations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(chats, key = { it.conversationId.key }) { conv ->
                    UnusedMiniPeopleRow(
                        conv = conv,
                        label = labels[conv.conversationId.key],
                        attention = state.attentionRecords[conv.conversationId.key],
                        accountAlias = accountAlias(accountStore.get(conv.accountId), conv),
                        unseen = seen.isUnseen(conv.conversationId.key, conv.latestEventAt.epochMillis),
                        onClick = {
                            seen.markSeen(conv.conversationId.key, conv.latestEventAt.epochMillis)
                            viewModel.selectConversation(conv)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (chats.isEmpty()) {
                    item {
                        Text(
                            when {
                                state.conversations.isEmpty() && accountStore.list().isEmpty() -> "No accounts yet. Open Console to connect one."
                                state.conversations.isEmpty() -> "No messages in MINI yet."
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
private fun UnusedMiniPeopleRow(
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
            Text(initials(conv.title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(timeLabel(conv.latestEventAt.epochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            conv.title.takeIf { it.isNotBlank() && it != conv.latestSnippet }?.let {
                Text(previewText(conv.latestSnippet), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text(previewText(conv.latestSnippet), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                attentionChip(attention)
                Text("$accountAlias${label?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unseen) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun attentionChip(attention: app.mini.hub.attention.ConversationAttention?) {
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
private fun MiniFocusPane(
    state: InboxViewState,
    viewModel: InboxViewModel,
    accountStore: ConnectedAccountStore,
    labels: Map<String, String>,
) {
    val context = LocalContext.current
    val seen = remember { LocalSeenStore(context) }
    var section by rememberSaveable { mutableStateOf("needs") }
    var laterFor by remember { mutableStateOf<AttentionEntry?>(null) }
    val view = state.attentionView
    val needs = view?.needsAttention.orEmpty()
    val waiting = view?.waiting.orEmpty()
    val later = view?.snoozed.orEmpty()
    val entries = when (section) {
        "waiting" -> waiting
        "later" -> later
        else -> needs
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = MiniInk.gutter.dp)) {
        Text(
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("A little focus.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text("${needs.size}", style = MaterialTheme.typography.headlineLarge)
        }
        Text("Here's what needs you today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.canUndoAttention) {
            TextButton(onClick = { viewModel.undoLastAttention() }) { Text("Undo last attention change") }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            FocusTab("Needs you ${needs.size}", section == "needs", Modifier.weight(1f)) { section = "needs" }
            FocusTab("Waiting ${waiting.size}", section == "waiting", Modifier.weight(1f)) { section = "waiting" }
            FocusTab("Later ${later.size}", section == "later", Modifier.weight(1f)) { section = "later" }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        if (!state.isListenerConnected) {
            Text("Notification listener is idle. Source health is unchanged by an empty Focus list.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        state.storageFailure?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when {
            state.conversations.isEmpty() && accountStore.list().isEmpty() ->
                Text("No accounts connected. Open Console to add one.", modifier = Modifier.padding(24.dp))
            state.conversations.isEmpty() ->
                Text("No messages in MINI yet.", modifier = Modifier.padding(24.dp))
            entries.isEmpty() ->
                Text(
                    when (section) {
                        "waiting" -> "Nothing is waiting."
                        "later" -> "Nothing snoozed for later."
                        else -> "Nothing needs you right now."
                    },
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(entries, key = { it.summary.conversationId.key }) { entry ->
                    MiniFocusCard(
                        entry = entry,
                        understanding = state.understanding[entry.summary.conversationId.key],
                        accountAlias = inboxAccountAlias(accountStore.get(entry.summary.accountId), entry.summary),
                        category = labels[entry.summary.conversationId.key],
                        onReply = {
                            seen.markSeen(entry.summary.conversationId.key, entry.summary.latestEventAt.epochMillis)
                            viewModel.selectConversation(entry.summary)
                        },
                        onLater = { laterFor = entry },
                        onDone = { viewModel.applyAttentionAction(entry.summary.conversationId, AttentionAction.MarkDone) },
                        onWaiting = { viewModel.applyAttentionAction(entry.summary.conversationId, AttentionAction.MarkWaiting) },
                    )
                }
            }
        }
    }
    laterFor?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { laterFor = null },
            title = { Text("Later") },
            text = {
                Column {
                    Text("Snooze stays in MINI. This does not schedule an OS notification.")
                    viewModel.laterPresets().forEach { (label, at) ->
                        TextButton(onClick = {
                            viewModel.applyAttentionAction(entry.summary.conversationId, AttentionAction.Snooze(at))
                            laterFor = null
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { laterFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FocusTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .heightIn(min = MiniInk.primaryTarget.dp)
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun MiniFocusCard(
    entry: AttentionEntry,
    understanding: app.mini.hub.understanding.MessageUnderstanding?,
    accountAlias: String,
    category: String?,
    onReply: () -> Unit,
    onLater: () -> Unit,
    onDone: () -> Unit,
    onWaiting: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiniInk.cardRadius.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(MiniInk.cardRadius.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(inboxInitials(entry.summary.title), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.summary.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$accountAlias${category?.let { " · $it" } ?: ""} · ${inboxTimeLabel(entry.summary.latestEventAt.epochMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniAttentionChip(entry.attention)
        }
        Spacer(Modifier.height(8.dp))
        Text(understanding?.explanation ?: entry.summary.latestSnippet, style = MaterialTheme.typography.bodyMedium)
        understanding?.evidenceText?.takeIf { it.isNotBlank() }?.let { evidence ->
            Text(
                "“$evidence”",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onReply,
                modifier = Modifier.weight(1f).height(MiniInk.primaryTarget.dp),
            ) { Text("Reply") }
            OutlinedButton(
                onClick = onLater,
                modifier = Modifier.weight(1f).height(MiniInk.primaryTarget.dp),
            ) { Text("Later") }
        }
        TextButton(onClick = onDone) { Text("Doesn't need a reply") }
        if (entry.attention.status == FollowUpStatus.NeedsAttention) {
            TextButton(onClick = onWaiting) { Text("Waiting on them") }
        }
    }
}

@Composable
private fun MiniConsolePane(
    state: InboxViewState,
    accountStore: ConnectedAccountStore,
    capabilities: (ConnectedAccount) -> app.mini.hub.connector.CapabilitySnapshot?,
    onOpenSources: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLearning: () -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val accounts = accountStore.list()
    val needs = accounts.count { it.state != ConnectionState.Healthy && it.state != ConnectionState.Authorized && it.state != ConnectionState.Syncing }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MiniInk.gutter.dp),
    ) {
        Text("Your console.", style = MaterialTheme.typography.headlineLarge)
        Text("A clear view of what's working.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MiniInk.cardRadius.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(16.dp),
        ) {
            Text("Private, by design.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            Text("Messages are stored encrypted here. Connected services still use the internet.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Connected accounts", style = MaterialTheme.typography.titleMedium)
            Text(if (needs > 0) "$needs need attention" else "Healthy", style = MaterialTheme.typography.labelSmall)
        }
        accounts.forEach { account ->
            val cap = capabilities(account)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${account.id.provider.wire} · ${account.displayName ?: account.providerAccountId}", style = MaterialTheme.typography.titleMedium)
                        account.userActionRequired?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(lastCheckLabel(account), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Reply ${if (cap?.enabled?.contains(app.mini.hub.connector.Capability.Reply) == true) "available" else "off"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        accountStateShort(account.state),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        if (accounts.isEmpty()) {
            Text("No authenticated accounts. Add Gmail or Outlook from Manage accounts.", modifier = Modifier.padding(vertical = 12.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth().height(MiniInk.primaryTarget.dp)) { Text("Manage accounts") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSources, modifier = Modifier.fillMaxWidth().height(MiniInk.primaryTarget.dp)) { Text("Notification sources") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSyncNow, modifier = Modifier.fillMaxWidth().height(MiniInk.primaryTarget.dp)) { Text("Sync now") }
        if (!state.isPermissionGranted) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth().height(MiniInk.primaryTarget.dp)) { Text("Grant notification access") }
        }
        Spacer(Modifier.height(20.dp))
        Text("YOUR PREFERENCES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenLearning) { Text("Writing style") }
        Text(
            "Listener: ${if (state.isListenerConnected) "connected" else "idle"} · Permission: ${if (state.isPermissionGranted) "granted" else "needed"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun UnusedMiniBanner(title: String, body: String, action: String?, onAction: () -> Unit) {
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

private fun initials(title: String): String =
    title.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "?" }

private fun timeLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - millis < 24 * 3600_000L) "HH:mm" else "dd MMM"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(millis))
}

private fun accountAlias(account: ConnectedAccount?, conv: ConversationObservationSummary): String {
    val source = SourceAppNames.forPackage(conv.packageId)
    val alias = account?.displayName ?: account?.providerAccountId
    return if (alias.isNullOrBlank() || alias.equals(source, ignoreCase = true)) source else "$source · $alias"
}

private fun lastCheckLabel(account: ConnectedAccount): String {
    val at = account.lastSuccessfulSyncMillis ?: return "Not checked yet"
    val delta = System.currentTimeMillis() - at
    return when {
        delta < 60_000L -> "Checked just now"
        delta < 60 * 60_000L -> "Checked ${delta / 60_000L} min ago"
        else -> "Last check ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(at))}"
    }
}

private fun accountStateShort(state: ConnectionState): String = when (state) {
    ConnectionState.Authorized, ConnectionState.Healthy -> "Connected"
    ConnectionState.Syncing -> "Syncing"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Revoked -> "Access removed"
    ConnectionState.Expired -> "Sign-in expired"
    ConnectionState.AuthorizationRequired -> "Needs permission"
    ConnectionState.Degraded -> "Unavailable"
}
