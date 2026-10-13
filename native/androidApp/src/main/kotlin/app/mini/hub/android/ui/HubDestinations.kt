package app.mini.hub.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mini.hub.action.ActionRoute
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.AttentionView
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.domain.ScopedId
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.SourceAppNames
import app.mini.hub.search.SearchResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun InboxDestination(
    state: InboxViewState,
    labels: Map<String, String>,
    sourceFilter: String,
    accountFilter: String,
    onSearch: () -> Unit,
    onFilters: () -> Unit,
    onSelect: (ConversationObservationSummary) -> Unit,
    onAttention: (ScopedId, AttentionAction) -> Unit,
    onFixListener: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val visible = state.conversations.filter { conv ->
        val source = SourceAppNames.forPackage(conv.packageId)
        val label = labels[conv.conversationId.key]
        (sourceFilter.isBlank() || source.equals(sourceFilter, true)) &&
            (accountFilter == "All" || label == accountFilter || (accountFilter == "Work" && label == "Work") || (accountFilter == "Personal" && label != "Work"))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            PageTitle("Inbox", "Everything that's arrived.", Modifier.weight(1f))
            IconButtonCircle("console", "Filters", onClick = onFilters)
        }
        SearchBar(state.searchQuery, "Search people and messages", onClick = onSearch, modifier = Modifier.padding(bottom = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Unread", "Work", "Personal").forEach { chip ->
                val on = when (chip) {
                    "All" -> accountFilter == "All" && sourceFilter.isBlank()
                    "Work" -> accountFilter == "Work"
                    "Personal" -> accountFilter == "Personal"
                    else -> false
                }
                LineuppChip(chip, selected = on, onClick = onFilters)
            }
        }
        if (!state.isPermissionGranted) {
            NoticeBanner("Notification access needed", "Android needs this so " + Brand.NAME + " can save new messages from the sources you choose.", PillTone.Attention, Modifier.padding(bottom = 12.dp), "Open settings", onOpenPermission)
        } else if (!state.isListenerConnected) {
            NoticeBanner("WhatsApp is paused", "New notifications aren't being saved.", PillTone.Attention, Modifier.padding(bottom = 12.dp), "Fix", onFixListener)
        }
        SectionLabel(if (sourceFilter.isBlank()) "Latest conversations" else sourceFilter, visible.size.toString() + " conversations")
        ChatListView(
            conversations = visible,
            labels = labels,
            attention = state.attentionRecords,
            onSelect = onSelect,
            onAttention = onAttention,
        )
    }
}

@Composable
internal fun FiltersScreen(
    sourceFilter: String,
    accountFilter: String,
    onSource: (String) -> Unit,
    onAccount: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    ScreenScaffold(
        top = { SubTopBar("Inbox filters", onBack) },
        footer = {
            FooterColumn {
                PrimaryButton("Show conversations", onApply)
                TextAction("Reset filters", onReset, Modifier.align(Alignment.CenterHorizontally))
            }
        },
    ) {
        PageTitle("Make room for what matters.", "Filter this inbox by source and account.")
        Text("Sources", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Gmail", "Outlook", "WhatsApp", "LinkedIn").forEach { s ->
                LineuppChip(if (sourceFilter == s) "$s ✓" else s, selected = sourceFilter == s, onClick = { onSource(s) })
            }
        }
        Text("Account", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Work", "Personal").forEach { a ->
                LineuppChip(if (a == "All") "All accounts" else a, selected = accountFilter == a, onClick = { onAccount(a) })
            }
        }
        InfoNote("No source selected means all sources. Search respects these filters.")
        WhiteCard(Modifier.padding(top = 20.dp)) {
            Text("Your accounts stay distinct", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text("Work and personal mail never share a draft's context. Every conversation keeps its original account.", style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
internal fun SearchDestination(
    state: InboxViewState,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onSelect: (SearchResult) -> Unit,
    onBack: () -> Unit,
    onFilters: () -> Unit,
    sourceFilter: String,
    accountFilter: String,
) {
    ScreenScaffold(top = { SubTopBar("Search", onBack) }) {
        PageTitle("Find the conversation.")
        OutlinedTextField(state.searchQuery, onQuery, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, placeholder = { Text("Search people and messages") }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineuppChip("Current inbox filters", selected = true, onClick = {})
            LineuppChip("Change filters", selected = false, onClick = onFilters)
        }
        SectionLabel("Messages on this phone", if (state.isSearching) "Searching" else state.searchResults.size.toString() + " results")
        if (!state.isSearching && state.searchQuery.isNotBlank() && state.searchResults.isEmpty()) {
            Text("No stored messages match this search.", color = lineupp.secondaryText)
        }
        state.searchResults.forEach { res ->
            Column(Modifier.fillMaxWidth().clickable { onSelect(res) }.padding(vertical = 12.dp)) {
                Row { Text(res.conversationTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(SourceAppNames.forPackage(res.packageId), style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText) }
                Text(res.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = lineupp.secondaryText)
            }
            HorizontalDivider(color = lineupp.divider)
        }
        InfoNote("Search checks stored messages locally. Notification previews may not include the whole conversation.", "lock")
        if (state.searchQuery.isNotBlank()) TextAction("Clear search", onClear)
    }
}

@Composable
internal fun FocusDestination(
    view: AttentionView?,
    tab: String,
    onTab: (String) -> Unit,
    onSelect: (ConversationObservationSummary) -> Unit,
    onLater: (ConversationObservationSummary) -> Unit,
    onDone: (ConversationObservationSummary) -> Unit,
) {
    val needs = view?.needsAttention.orEmpty()
    val waiting = view?.waiting.orEmpty()
    val later = view?.snoozed.orEmpty()
    val shown = when (tab) {
        "Waiting" -> waiting
        "Later" -> later
        else -> needs
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            PageTitle("A little focus.", when (tab) {
                "Waiting" -> "Follow-ups, without the guesswork."
                "Later" -> "Ready when you are."
                else -> if (needs.isEmpty()) "Nothing needs you right now." else "Here's what needs you today."
            }, Modifier.weight(1f))
            Text(needs.size.toString(), style = MaterialTheme.typography.headlineLarge)
        }
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Needs you" to needs.size, "Waiting" to waiting.size, "Later" to later.size).forEach { (name, n) ->
                Column(Modifier.clickable { onTab(name) }.padding(bottom = 6.dp)) {
                    Text("$name $n", fontWeight = if (tab == name) FontWeight.SemiBold else FontWeight.Normal, color = if (tab == name) MaterialTheme.colorScheme.onSurface else lineupp.secondaryText)
                    Box(Modifier.padding(top = 6.dp).height(2.dp).fillMaxWidth().background(if (tab == name) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        if (shown.isEmpty()) {
            Text("You're caught up on this list.", color = lineupp.secondaryText, modifier = Modifier.padding(top = 24.dp))
        }
        shown.forEach { entry ->
            val source = SourceAppNames.forPackage(entry.summary.packageId)
            WhiteCard(Modifier.padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(entry.summary.title, source, 40.dp)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.summary.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("$source · " + (entry.summary.appName), style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText)
                    }
                    StatePill(if (tab == "Waiting") "Waiting" else if (tab == "Later") "Later" else "Needs reply", if (tab == "Needs you") PillTone.Attention else PillTone.Neutral)
                }
                Text(entry.summary.latestSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Buttonish("Reply") { onSelect(entry.summary) }
                    if (tab == "Needs you") Outlinedish("Later") { onLater(entry.summary) }
                }
                TextAction("Doesn't need a reply", onClick = { onDone(entry.summary) })
            }
        }
    }
}

@Composable
private fun Buttonish(text: String, onClick: () -> Unit) {
    Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp))
}

@Composable
private fun Outlinedish(text: String, onClick: () -> Unit) {
    Text(text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp))
}

@Composable
internal fun LaterScreen(onBack: () -> Unit, choice: String, onChoice: (String) -> Unit, onSave: () -> Unit) {
    val options = listOf(
        "This evening" to "Today · 18:00",
        "Tomorrow morning" to "Tomorrow · 9:00",
        "Next week" to "Next week · 9:00",
        "Pick a date" to "Choose the time that suits you",
    )
    ScreenScaffold(
        top = { SubTopBar("Later", onBack) },
        footer = { FooterColumn { PrimaryButton("Save for later", onSave, trailing = "check") } },
    ) {
        PageTitle("Pick it up later.", "This moves the item to Later. It returns to Needs you at the selected time.")
        options.forEach { (title, sub) ->
            SettingRow(title, sub, trailing = {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(if (choice == title) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }, onClick = { onChoice(title) })
        }
        InfoNote("This moves the item to Later. It returns to Needs you at the selected time.", "info")
    }
}

@Composable
internal fun ThreadDestination(
    viewModel: InboxViewModel,
    labels: Map<String, String>,
    onLabel: (String, String?) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onContext: () -> Unit,
) {
    val state = viewModel.state.value
    val summary = state.selectedConversation ?: return
    val selectedKey = summary.conversationId.key
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        ThreadView(
            summary = summary,
            timeline = state.selectedTimeline,
            label = labels[selectedKey],
            onLabel = { onLabel(selectedKey, it) },
            draftText = state.localDraftText,
            draftStatus = state.localDraftStatus,
            draftVersions = state.draftVersions,
            isDrafting = state.isLocalDraftInProgress,
            onBack = onBack,
            onDraft = { instruction, revise -> viewModel.requestLocalDraft(instruction, revise) },
            onCancelDraft = { viewModel.cancelLocalDraft() },
            onDraftChanged = { text -> viewModel.updateLocalDraftText(text) },
            onApprove = { body ->
                viewModel.prepareDraft(body, "", "")
                onReview()
            },
            onAttention = { a -> viewModel.applyAttentionAction(summary.conversationId, a) },
        )
        TextAction("Show the context", onContext, Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun ContextScreen(state: InboxViewState, onBack: () -> Unit) {
    val summary = state.selectedConversation
    ScreenScaffold(
        top = { SubTopBar("Draft context", onBack) },
        footer = { FooterColumn { SecondaryButton("Back to my draft", onBack, leading = "edit") } },
    ) {
        PageTitle("Grounded in this conversation.", "Nothing from another account is used.")
        NoticeBanner("Only this conversation", SourceAppNames.forPackage(summary?.packageId.orEmpty()) + " · " + (summary?.title ?: ""), PillTone.Settled)
        Text("Message used", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 18.dp))
        WhiteCard { Text("“" + (summary?.latestSnippet ?: "") + "”", style = MaterialTheme.typography.bodyMedium) }
        Text("Your reply instruction", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 18.dp))
        WhiteCard { Text(state.localDraftStatus ?: "A draft written on this phone from this conversation.", style = MaterialTheme.typography.bodySmall) }
        InfoNote("The draft does not approve prices or claim you've reviewed attachments.")
    }
}

@Composable
internal fun ReviewScreen(
    state: InboxViewState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onApprove: () -> Unit,
) {
    val payload = state.activeReviewPayload
    val ctx = state.reviewContext
    val isApi = payload?.route == ActionRoute.Api
    val provider = ctx?.providerName ?: "the provider"
    ScreenScaffold(
        top = { SubTopBar("Review reply", onBack) },
        footer = {
            FooterColumn(note = "You approve the account, recipient and words shown above") {
                PrimaryButton(
                    text = if (state.isSending) "Sending…" else if (isApi) "Approve & send via $provider" else "Approve & open",
                    onClick = onApprove,
                    enabled = !state.isSending,
                    trailing = "send",
                )
                SecondaryButton("Edit reply", onEdit, leading = "edit")
            }
        },
    ) {
        PageTitle("Check before it goes.", "Read the exact reply you're approving.")
        NoticeBanner("Only this exact reply can be sent", "Account, recipient and words stay together.", PillTone.Settled)
        ReviewKv("From", provider, payload?.accountId)
        ReviewKv("To", payload?.recipients?.joinToString().orEmpty().ifBlank { "this conversation" }, if (isApi) null else "Choose the chat in the app")
        ReviewKv("Route", if (isApi) "$provider API" else "Open app", if (isApi) "Send from the reviewed account" else "You press Send there")
        HorizontalDivider(color = lineupp.divider)
        WhiteCard(Modifier.padding(top = 12.dp)) {
            Text("YOUR MESSAGE", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText)
            Text(payload?.body.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        InfoNote(if (isApi) "Provider acceptance confirms the request was accepted. It doesn't mean the message was delivered or read." else "Opening the app does not send anything.")
        ctx?.note?.let { NoticeBanner("Check this", it, PillTone.Attention, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
internal fun HandoffScreen(state: InboxViewState, onBack: () -> Unit, onOpen: () -> Unit) {
    val payload = state.activeReviewPayload
    val app = state.reviewContext?.handoffPackage?.let { SourceAppNames.forPackage(it) } ?: "the app"
    ScreenScaffold(
        top = { SubTopBar("Review app handoff", onBack) },
        footer = {
            FooterColumn {
                PrimaryButton("Open $app with this text", onOpen, trailing = "external")
                SecondaryButton("Back to reply", onBack, leading = "edit")
            }
        },
    ) {
        PageTitle("Take this reply to $app.", "Check the words before leaving " + Brand.NAME + ".")
        ReviewKv("From", Brand.NAME)
        ReviewKv("To", state.selectedConversation?.title ?: "Choose the chat in $app", "Choose the chat in $app")
        ReviewKv("Route", "Open $app", "You press Send there")
        WhiteCard { Text("YOUR MESSAGE", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText); Text(payload?.body.orEmpty(), modifier = Modifier.padding(top = 8.dp)) }
        NoticeBanner("Choose the chat in $app", "Check the recipient and press Send there.", PillTone.Attention)
        InfoNote("Opening $app does not send anything. " + Brand.NAME + " can't confirm what happens after the handoff.")
    }
}

@Composable
internal fun OutcomeScreen(
    title: String,
    subtitle: String,
    tone: PillTone,
    icon: String,
    body: String?,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        top = { SubTopBar("Reply status", onBack) },
        footer = {
            FooterColumn {
                PrimaryButton(primary.first, primary.second, trailing = null)
                secondary?.let { SecondaryButton(it.first, it.second) }
            }
        },
    ) {
        LargeIconBox(icon, tone)
        PageTitle(title, subtitle)
        body?.let { WhiteCard(Modifier.padding(top = 16.dp)) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
internal fun ConsoleDestination(
    accounts: List<ConnectedAccount>,
    state: InboxViewState,
    styleLearningEnabled: Boolean,
    connectionStatus: String?,
    onAccounts: () -> Unit,
    onSources: () -> Unit,
    onStyle: () -> Unit,
    onModel: () -> Unit,
    onPrivacy: () -> Unit,
    onAccess: () -> Unit,
    onHealth: (String) -> Unit,
    onPermission: () -> Unit,
    onSync: () -> Unit,
) {
    val needs = accounts.count { it.state != ConnectionState.Healthy && it.state != ConnectionState.Authorized && it.state != ConnectionState.Syncing }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        PageTitle("Your console.", "A clear view of what's working.")
        NoticeBanner("Private, by design.", "Messages are stored encrypted here.\nYour connected services still use the internet.", PillTone.Settled)
        SectionLabel("Connected sources", if (accounts.isEmpty()) "None yet" else if (needs > 0) needs.toString() + " need attention" else "All active")
        WhiteCard {
            if (accounts.isEmpty()) SettingRow("No authenticated accounts", "Connect Gmail, Outlook or others.", onClick = onAccounts)
            accounts.forEachIndexed { i, account ->
                if (i > 0) HorizontalDivider(color = lineupp.divider)
                val ok = account.state == ConnectionState.Healthy || account.state == ConnectionState.Authorized || account.state == ConnectionState.Syncing
                SettingRow(
                    title = (account.displayName ?: account.providerAccountId) + " · " + account.id.provider.wire,
                    subtitle = account.userActionRequired ?: account.state.name,
                    trailing = { StatePill(if (ok) "Connected" else "Needs attention", if (ok) PillTone.Settled else PillTone.Attention) },
                    onClick = { if (!ok) onHealth(account.id.provider.wire) else onAccounts() },
                )
            }
        }
        SectionLabel("Your preferences")
        WhiteCard {
            SettingRow("On-device AI", "Optional · inbox works without it", onClick = onModel)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Writing style", if (styleLearningEnabled) "On for approved outgoing writing" else "Off · learns only with your permission", onClick = onStyle)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Privacy & storage", "Your data, your choices", onClick = onPrivacy)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Comfort & accessibility", "Theme, text size and approval options", onClick = onAccess)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Notification sources", "Choose which apps appear in the inbox", onClick = onSources)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Add or disconnect accounts", "Provider sign-in stays with that service", onClick = onAccounts)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Sync connected accounts now", "Pull new mail from signed-in accounts", onClick = onSync)
            HorizontalDivider(color = lineupp.divider)
            SettingRow("Notification access", if (state.isPermissionGranted) "Android access is enabled" else "Needed to save notifications", onClick = onPermission)
        }
        connectionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
internal fun HealthScreen(source: String, isPermissionGranted: Boolean, isListenerConnected: Boolean, onBack: () -> Unit, onPermission: () -> Unit, onSources: () -> Unit) {
    ScreenScaffold(
        top = { SubTopBar("Source health", onBack) },
        footer = { FooterColumn { PrimaryButton(if (isListenerConnected) "Return to console" else "Turn on $source notifications", { if (!isPermissionGranted) onPermission() else onSources() }, trailing = "check") } },
    ) {
        LargeIconBox("info", if (isListenerConnected) PillTone.Settled else PillTone.Attention)
        PageTitle(if (isListenerConnected) "$source is active." else "$source is paused.", if (isListenerConnected) "New selected-source notifications can be saved." else "New $source notifications aren't being saved.")
        WhiteCard {
            SettingRow("Source", "$source · Personal", trailing = { StatePill("Notifications only", PillTone.Neutral) })
            SettingRow("Notification access", if (isPermissionGranted) "Android access is enabled" else "Not granted", trailing = { StatePill(if (isPermissionGranted) "On" else "Off", if (isPermissionGranted) PillTone.Settled else PillTone.Attention) }, onClick = onPermission)
            SettingRow("Save this source", if (isListenerConnected) "Enabled" else "Paused in " + Brand.NAME, trailing = { StatePill(if (isListenerConnected) "On" else "Off", if (isListenerConnected) PillTone.Settled else PillTone.Attention) }, onClick = onSources)
        }
        InfoNote("Turning this on saves future notifications. It won't restore messages missed while paused.")
        TextAction("Open Android notification settings", onClick = { onPermission() })
    }
}

@Composable
internal fun ModelScreen(onBack: () -> Unit) {
    ScreenScaffold(
        top = { SubTopBar("On-device AI", onBack) },
        footer = { FooterColumn { SecondaryButton("Set up later", onBack) } },
    ) {
        LargeIconBox("spark")
        PageTitle("A little help. Right on this phone.", "Optional AI for drafting, without sending your messages to an AI service.")
        WhiteCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("On-device model", fontWeight = FontWeight.SemiBold)
                StatePill("Not installed", PillTone.Neutral)
            }
            Text("Add a compatible model file to get started. " + Brand.NAME + " checks the file before using it.", style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 12.dp))
        }
        PromiseRow("info", "Runs on your device", "No private message content goes to a cloud AI model.")
        PromiseRow("check", "Your inbox works without it", "Read, organise and write your own replies any time.")
        InfoNote("Drafts can be wrong. Read them before approval, especially dates, prices and commitments.")
    }
}

@Composable
internal fun StyleScreen(enabled: Boolean, onChanged: (Boolean) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(top = { SubTopBar("Writing style", onBack) }, footer = { FooterColumn { SecondaryButton("Done", onBack) } }) {
        PageTitle("More like you. Only if you choose.", "Style learning is off until you enable it.")
        WhiteCard {
            SettingRow("Learn my writing style", if (enabled) "On for approved outgoing writing" else "Off by default", onClick = { onChanged(!enabled) }, trailing = {
                StatePill(if (enabled) "On" else "Off", if (enabled) PillTone.Settled else PillTone.Neutral)
            })
        }
        PromiseRow("edit", "Learn from your own outgoing writing", "With permission, uses available sent history and replies you approved.")
        PromiseRow("lock", "Keep accounts separate", "Your work style isn't used in your personal conversations.")
        InfoNote("This is a local style profile, not training a cloud model on your conversations.")
    }
}

@Composable
internal fun PrivacyScreen(onBack: () -> Unit, onSources: () -> Unit, onModel: () -> Unit, onStyle: () -> Unit) {
    ScreenScaffold(top = { SubTopBar("Privacy & storage", onBack) }) {
        PageTitle("Your messages. Your boundaries.", "Clear choices, in one place.")
        WhiteCard {
            SettingRow("Stored encrypted on this phone", "Messages, drafts and preferences", trailing = { StrokeIcon("lock", Modifier.size(18.dp), lineupp.secondaryText) })
            SettingRow("Connected sources", "Choose what can enter your inbox", onClick = onSources)
            SettingRow("On-device AI", "Optional · not installed", onClick = onModel)
            SettingRow("Writing style", "Off unless you opt in", onClick = onStyle)
        }
        InfoNote("Connected services still receive account requests. " + Brand.NAME + " doesn't verify or change their encryption.")
    }
}

@Composable
internal fun AccessibilityScreen(onBack: () -> Unit) {
    ScreenScaffold(top = { SubTopBar("Comfort & accessibility", onBack) }, footer = { FooterColumn { SecondaryButton("Done", onBack) } }) {
        PageTitle("Make it comfortable.", "A few choices for how you use " + Brand.NAME + ".")
        Text("Appearance follows your system light or dark theme.", style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText)
        WhiteCard(Modifier.padding(top = 16.dp)) {
            SettingRow("Tap to approve", "A review screen, then one deliberate tap", trailing = { StatePill("Default", PillTone.Action) })
        }
        InfoNote("State labels use words as well as colour. Motion follows your system's reduced-motion setting.")
    }
}
