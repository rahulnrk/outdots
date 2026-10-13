package app.mini.hub.android.ui

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.android.CommunicationSourcePreferences
import app.mini.hub.android.ConversationLabels
import app.mini.hub.attention.AttentionAction
import app.mini.hub.attention.AttentionView
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.SourceAppNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HubScreen {
    Welcome, Sources, Permission, Ready,
    Inbox, Filters, Search, Focus, Later,
    Thread, Context, Review, Accepted, Unknown, Unavailable, Handoff, Opened,
    Console, Health, Model, Style, Privacy, Accessibility,
}

private val ONBOARDING = "lineupp_onboarding"

@Composable
fun HubApp(
    viewModel: InboxViewModel,
    handoffLauncher: HandoffLauncher,
    onRequestPermission: () -> Unit,
    sourcePreferences: CommunicationSourcePreferences,
    accountStore: ConnectedAccountStore,
    connectionStatus: String?,
    onConnect: (Provider) -> Unit,
    onSourceSelectionChanged: () -> Unit,
    styleLearningEnabled: Boolean,
    onStyleLearningChanged: (Boolean) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSources: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val state by viewModel.state
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ONBOARDING, Context.MODE_PRIVATE) }
    val conversationLabels = remember { ConversationLabels(context) }
    var labels by remember { mutableStateOf(conversationLabels.all()) }
    var screen by rememberSaveable {
        mutableStateOf(
            if (prefs.getBoolean("done", false) || accountStore.list().isNotEmpty()) HubScreen.Inbox.name else HubScreen.Welcome.name,
        )
    }
    fun go(s: HubScreen) { screen = s.name }
    val current = HubScreen.entries.firstOrNull { it.name == screen } ?: HubScreen.Inbox

    var selectedSources by remember {
        mutableStateOf(
            sourcePreferences.selectedSources().map { it.name }.toSet().ifEmpty { setOf("Gmail", "WhatsApp") },
        )
    }
    var permissionSkipped by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var accountFilter by rememberSaveable { mutableStateOf("All") }
    var focusTab by rememberSaveable { mutableStateOf("Needs you") }
    var laterTarget by rememberSaveable { mutableStateOf("") }
    var laterChoice by rememberSaveable { mutableStateOf("This evening") }
    var healthSource by rememberSaveable { mutableStateOf("WhatsApp") }

    LaunchedEffect(state.selectedConversation?.conversationId?.key) {
        if (state.selectedConversation != null && current in setOf(HubScreen.Inbox, HubScreen.Focus, HubScreen.Search, HubScreen.Later)) {
            go(HubScreen.Thread)
        }
    }
    LaunchedEffect(state.activeReviewPayload) {
        val payload = state.activeReviewPayload ?: return@LaunchedEffect
        go(if (payload.route == app.mini.hub.action.ActionRoute.Api) HubScreen.Review else HubScreen.Handoff)
    }
    LaunchedEffect(state.isSending, state.outboxStatusMessage) {
        val msg = state.outboxStatusMessage ?: return@LaunchedEffect
        if (state.isSending) return@LaunchedEffect
        when {
            msg.contains("accepted", true) -> go(HubScreen.Accepted)
            msg.contains("opened", true) -> go(HubScreen.Opened)
            msg.contains("unknown", true) -> go(HubScreen.Unknown)
            msg.contains("Not sent", true) -> go(HubScreen.Unavailable)
        }
    }

    val daily = current in setOf(HubScreen.Inbox, HubScreen.Focus, HubScreen.Console)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (daily) {
            BrandBar(
                statusText = if (state.isListenerConnected) "On this phone" else "Listener idle",
                statusOk = state.isListenerConnected,
                onStatusClick = { go(HubScreen.Console) },
                onCompose = {
                    val gmail = accountStore.list().firstOrNull {
                        it.id.provider == Provider.GMAIL && it.state != app.mini.hub.connector.ConnectionState.Disconnected
                    }
                    if (gmail != null) viewModel.startComposeEmail(gmail.id) else onOpenAccounts()
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                HubScreen.Welcome -> WelcomeScreen(onStart = { go(HubScreen.Sources) })
                HubScreen.Sources -> SourcesScreen(
                    selected = selectedSources,
                    onToggle = { name ->
                        selectedSources = if (name in selectedSources) selectedSources - name else selectedSources + name
                    },
                    onBack = { go(HubScreen.Welcome) },
                    onContinue = {
                        if (selectedSources.isEmpty()) return@SourcesScreen
                        val mapped = selectedSources.mapNotNull { n -> CommunicationSource.entries.find { it.displayName == n || it.name == n } }.toSet()
                        if (mapped.isNotEmpty()) {
                            sourcePreferences.replaceSelection(mapped)
                            onSourceSelectionChanged()
                        }
                        val needsNotif = "WhatsApp" in selectedSources || "LinkedIn" in selectedSources
                        permissionSkipped = !needsNotif
                        go(if (needsNotif) HubScreen.Permission else HubScreen.Ready)
                    },
                )
                HubScreen.Permission -> PermissionScreen(
                    onOpenSettings = { onRequestPermission(); go(HubScreen.Ready) },
                    onSkip = { permissionSkipped = true; go(HubScreen.Ready) },
                    onBack = { go(HubScreen.Sources) },
                )
                HubScreen.Ready -> ReadyScreen(
                    selected = selectedSources,
                    accounts = accountStore.list(),
                    permissionSkipped = permissionSkipped,
                    onConnect = onConnect,
                    onOpenInbox = {
                        prefs.edit().putBoolean("done", true).apply()
                        go(HubScreen.Inbox)
                    },
                )
                HubScreen.Inbox -> InboxDestination(
                    state = state,
                    labels = labels,
                    sourceFilter = sourceFilter,
                    accountFilter = accountFilter,
                    onSearch = { go(HubScreen.Search) },
                    onFilters = { go(HubScreen.Filters) },
                    onAccountFilter = { accountFilter = it },
                    onSelect = { viewModel.selectConversation(it) },
                    onAttention = { id, a -> viewModel.applyAttentionAction(id, a) },
                    onFixListener = { healthSource = "WhatsApp"; go(HubScreen.Health) },
                    onOpenPermission = onRequestPermission,
                )
                HubScreen.Filters -> FiltersScreen(
                    sourceFilter = sourceFilter,
                    accountFilter = accountFilter,
                    onSource = { sourceFilter = if (sourceFilter == it) "" else it },
                    onAccount = { accountFilter = it },
                    onReset = { sourceFilter = ""; accountFilter = "All" },
                    onBack = { go(HubScreen.Inbox) },
                    onApply = {
                        viewModel.onSearchSourceFilterChanged(
                            when (sourceFilter) {
                                "Gmail" -> "com.google.android.gm"
                                "Outlook" -> "com.microsoft.office.outlook"
                                "WhatsApp" -> "com.whatsapp"
                                "LinkedIn" -> "com.linkedin.android"
                                else -> null
                            },
                        )
                        go(HubScreen.Inbox)
                    },
                )
                HubScreen.Search -> SearchDestination(
                    state = state,
                    onQuery = { viewModel.onSearchQueryChanged(it) },
                    onClear = { viewModel.clearSearch() },
                    onSelect = { viewModel.selectConversationFromSearch(it) },
                    onBack = { viewModel.clearSearch(); go(HubScreen.Inbox) },
                    onFilters = { go(HubScreen.Filters) },
                    sourceFilter = sourceFilter,
                    accountFilter = accountFilter,
                )
                HubScreen.Focus -> FocusDestination(
                    view = state.attentionView,
                    tab = focusTab,
                    onTab = { focusTab = it },
                    onSelect = { viewModel.selectConversation(it) },
                    onLater = { summary -> laterTarget = summary.conversationId.key; go(HubScreen.Later) },
                    onDone = { viewModel.applyAttentionAction(it.conversationId, AttentionAction.MarkDone) },
                )
                HubScreen.Later -> LaterScreen(
                    onBack = { go(HubScreen.Focus) },
                    choice = laterChoice,
                    onChoice = { laterChoice = it },
                    onSave = {
                        val millis = when (laterChoice) {
                            "Tomorrow morning" -> 18 * 3600_000L
                            "Next week" -> 7 * 24 * 3600_000L
                            else -> 6 * 3600_000L
                        }
                        state.conversations.find { it.conversationId.key == laterTarget }?.let {
                            viewModel.applyAttentionAction(it.conversationId, AttentionAction.Snooze(UtcInstant(System.currentTimeMillis() + millis)))
                        }
                        focusTab = "Later"
                        go(HubScreen.Focus)
                    },
                )
                HubScreen.Thread -> ThreadDestination(
                    viewModel = viewModel,
                    labels = labels,
                    onLabel = { key, label ->
                        conversationLabels.set(key, label)
                        labels = conversationLabels.all()
                    },
                    onBack = { viewModel.clearSelection(); go(HubScreen.Inbox) },
                    onReview = {
                        if (state.reviewContext?.handoffPackage != null && state.activeReviewPayload?.route != app.mini.hub.action.ActionRoute.Api) {
                            go(HubScreen.Handoff)
                        } else go(HubScreen.Review)
                    },
                    onContext = { go(HubScreen.Context) },
                )
                HubScreen.Context -> ContextScreen(
                    state = state,
                    onBack = { go(HubScreen.Thread) },
                )
                HubScreen.Review -> ReviewScreen(
                    state = state,
                    onBack = { viewModel.clearDraftReview(); go(HubScreen.Thread) },
                    onEdit = { viewModel.clearDraftReview(); go(HubScreen.Thread) },
                    onApprove = { viewModel.approveAndDispatch(handoffLauncher) },
                )
                HubScreen.Handoff -> HandoffScreen(
                    state = state,
                    onBack = { viewModel.clearDraftReview(); go(HubScreen.Thread) },
                    onOpen = { viewModel.approveAndDispatch(handoffLauncher) },
                )
                HubScreen.Accepted -> OutcomeScreen(
                    title = (state.reviewContext?.providerName ?: "Provider") + " accepted your reply",
                    subtitle = "Delivery and read status aren't available here.",
                    tone = PillTone.Settled,
                    icon = "check",
                    body = state.outboxStatusMessage,
                    primary = "Back to inbox" to { go(HubScreen.Inbox) },
                    onBack = { go(HubScreen.Inbox) },
                )
                HubScreen.Opened -> OutcomeScreen(
                    title = "Opened in " + (state.reviewContext?.handoffPackage?.let { SourceAppNames.forPackage(it) } ?: "the app") + ".",
                    subtitle = "Your text was handed to the app. Sending is not confirmed here.",
                    tone = PillTone.Settled,
                    icon = "external",
                    body = state.outboxStatusMessage,
                    primary = "Return to inbox" to { go(HubScreen.Inbox) },
                    onBack = { go(HubScreen.Inbox) },
                )
                HubScreen.Unknown -> OutcomeScreen(
                    title = "We couldn't confirm this went.",
                    subtitle = "The connection ended after sending started. Check the original service before sending again.",
                    tone = PillTone.Attention,
                    icon = "info",
                    body = state.outboxStatusMessage,
                    primary = "Back to inbox" to { go(HubScreen.Inbox) },
                    onBack = { go(HubScreen.Inbox) },
                )
                HubScreen.Unavailable -> OutcomeScreen(
                    title = "Send this reply from the original app.",
                    subtitle = "Direct sending from Lineupp isn't available for this account yet.",
                    tone = PillTone.Attention,
                    icon = "send",
                    body = state.outboxStatusMessage,
                    primary = "Open app with this text" to { viewModel.approveAndDispatch(handoffLauncher) },
                    secondary = "Back to reply" to { go(HubScreen.Thread) },
                    onBack = { go(HubScreen.Thread) },
                )
                HubScreen.Console -> ConsoleDestination(
                    accounts = remember(connectionStatus) { accountStore.list() },
                    state = state,
                    styleLearningEnabled = styleLearningEnabled,
                    connectionStatus = connectionStatus,
                    onAccounts = onOpenAccounts,
                    onSources = onOpenSources,
                    onStyle = { go(HubScreen.Style) },
                    onModel = { go(HubScreen.Model) },
                    onPrivacy = { go(HubScreen.Privacy) },
                    onAccess = { go(HubScreen.Accessibility) },
                    onHealth = { healthSource = it; go(HubScreen.Health) },
                    onPermission = onRequestPermission,
                    onSync = onSyncNow,
                )
                HubScreen.Health -> HealthScreen(
                    source = healthSource,
                    isPermissionGranted = state.isPermissionGranted,
                    isListenerConnected = state.isListenerConnected,
                    onBack = { go(HubScreen.Console) },
                    onPermission = onRequestPermission,
                    onSources = onOpenSources,
                )
                HubScreen.Model -> ModelScreen(onBack = { go(HubScreen.Console) })
                HubScreen.Style -> StyleScreen(
                    enabled = styleLearningEnabled,
                    onChanged = onStyleLearningChanged,
                    onBack = { go(HubScreen.Console) },
                )
                HubScreen.Privacy -> PrivacyScreen(
                    onBack = { go(HubScreen.Console) },
                    onSources = { go(HubScreen.Sources) },
                    onModel = { go(HubScreen.Model) },
                    onStyle = { go(HubScreen.Style) },
                )
                HubScreen.Accessibility -> AccessibilityScreen(onBack = { go(HubScreen.Console) })
            }
        }
        if (daily) {
            LineuppBottomBar(
                selected = when (current) {
                    HubScreen.Focus -> HubTab.Focus
                    HubScreen.Console -> HubTab.Console
                    else -> HubTab.Inbox
                },
                onSelect = {
                    when (it) {
                        HubTab.Inbox -> go(HubScreen.Inbox)
                        HubTab.Focus -> go(HubScreen.Focus)
                        HubTab.Console -> go(HubScreen.Console)
                    }
                },
            )
        }
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    ScreenScaffold(
        top = {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LineuppMark(30.dp)
                Spacer(Modifier.width(8.dp))
                Text(Brand.NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("Your third console", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText)
            }
        },
        footer = { FooterColumn(note = "No " + Brand.NAME + " account needed") { PrimaryButton("Get started", onStart) } },
    ) {
        StepProgress(1)
        PageTitle("All your messages.\nStill your call.", "A calmer place for personal and work conversations.")
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { LineuppMark(72.dp) }
        PromiseRow("lock", "Stored encrypted on this phone", "Your connected services still send and receive messages.")
        PromiseRow("spark", "AI that works here", "Optional drafts written on your device.")
        PromiseRow("check", "You review. You decide.", "Every outgoing reply needs your approval.")
    }
}

@Composable
private fun SourcesScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val rows = listOf(
        Triple("Gmail", "Email history", "Sign in to read mail. Reply options depend on permissions."),
        Triple("Outlook", "Inbox mail", "Connect your inbox. Other folders aren't included yet."),
        Triple("WhatsApp", "Notifications only", "New notifications. Replies open WhatsApp."),
        Triple("LinkedIn", "Notifications only", "New notifications. Replies open LinkedIn."),
    )
    ScreenScaffold(
        top = { SubTopBar("Your sources", onBack) },
        footer = {
            FooterColumn(note = "You choose what " + Brand.NAME + " can access") {
                PrimaryButton("Continue with " + selected.size + " sources", onContinue, enabled = selected.isNotEmpty())
            }
        },
    ) {
        StepProgress(2)
        PageTitle("Start with your everyday conversations.", "Choose a source. You can add more later.")
        rows.forEach { (title, pill, body) ->
            SourceChoiceRow(title, pill, body, title in selected) { onToggle(title) }
            HorizontalDivider(color = lineupp.divider)
        }
        InfoNote("Notification sources show only what this phone receives after access is enabled.")
    }
}

@Composable
private fun PermissionScreen(onOpenSettings: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(
        top = { SubTopBar("Notification access", onBack) },
        footer = {
            FooterColumn {
                PrimaryButton("Open Android settings", onOpenSettings, trailing = "external")
                TextAction("Skip for now · use email only", onSkip, Modifier.align(Alignment.CenterHorizontally))
            }
        },
    ) {
        StepProgress(3)
        LargeIconBox("info")
        PageTitle("Bring new messages into one place.", "Android needs your permission to let " + Brand.NAME + " read notifications.")
        PromiseRow("info", "Only sources you choose are saved", "Android grants broad notification access. " + Brand.NAME + " filters to your selected apps.")
        PromiseRow("lock", "Kept on this phone", "Notification previews may be incomplete. Your source apps remain available.")
        WhiteCard {
            Text("What happens next", fontWeight = FontWeight.SemiBold)
            Text("Open Android settings, find " + Brand.NAME + " and enable notification access. Then return here.", style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ReadyScreen(
    selected: Set<String>,
    accounts: List<ConnectedAccount>,
    permissionSkipped: Boolean,
    onConnect: (Provider) -> Unit,
    onOpenInbox: () -> Unit,
) {
    ScreenScaffold(
        footer = { FooterColumn { PrimaryButton("Open my inbox", onOpenInbox) } },
    ) {
        LargeIconBox("check", PillTone.Settled)
        PageTitle("A good place to begin.", "Your inbox brings available conversations together.")
        WhiteCard {
            selected.forEach { name ->
                val connected = accounts.any { it.id.provider.wire.equals(name, true) && it.state != app.mini.hub.connector.ConnectionState.Disconnected }
                SettingRow(
                    title = name,
                    subtitle = when {
                        name == "WhatsApp" && permissionSkipped -> "Skipped · turn on whenever you choose"
                        connected -> "Connected"
                        name == "Gmail" || name == "Outlook" -> "Tap to sign in"
                        else -> "New notifications enabled"
                    },
                    onClick = {
                        when (name) {
                            "Gmail" -> onConnect(Provider.GMAIL)
                            "Outlook" -> onConnect(Provider.OUTLOOK)
                        }
                    },
                    trailing = { StatePill(if (connected) "Connected" else "Ready", if (connected) PillTone.Settled else PillTone.Neutral) },
                )
            }
        }
        WhiteCard(Modifier.padding(top = 16.dp)) {
            Text("On-device AI is optional", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text("You can read, organise and write replies without a model. Set it up whenever you're ready.", style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, modifier = Modifier.padding(top = 8.dp))
        }
        InfoNote("Your inbox may take a moment to fill. Notification sources don't include older history.")
    }
}
