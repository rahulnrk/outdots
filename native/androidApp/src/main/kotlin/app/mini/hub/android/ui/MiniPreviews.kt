package app.mini.hub.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ReviewedActionSnapshot
import app.mini.hub.attention.AttentionEntry
import app.mini.hub.attention.AttentionRules
import app.mini.hub.attention.AttentionView
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.Capability
import app.mini.hub.connector.CapabilitySnapshot
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.InMemoryConnectedAccountStore
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.persistence.ConversationDraft
import app.mini.hub.search.SearchResult

private val previewNow = UtcInstant(1_700_000_000_000L)
private val previewWhatsApp = AccountId(Provider.WHATSAPP, "local_user")
private val previewGmail = AccountId(Provider.GMAIL, "work")

private fun previewConv(
    account: AccountId,
    name: String,
    snippet: String,
    pkg: String,
    category: NotificationCategory = NotificationCategory.DirectMessage,
    coverage: ConversationCoverage = ConversationCoverage.NotificationOnly,
): ConversationObservationSummary = ConversationObservationSummary(
    conversationId = ScopedId(account, ScopedId.Kind.CONVERSATION, name),
    accountId = account,
    title = name,
    appName = "preview",
    senderHint = name,
    packageId = pkg,
    latestSnippet = snippet,
    category = category,
    coverage = coverage,
    coverageExplanation = "synthetic preview",
    latestEventAt = previewNow,
    observationCount = 2,
    unreadCount = 1,
    isRemoved = false,
)

private fun previewAccounts(): InMemoryConnectedAccountStore {
    val store = InMemoryConnectedAccountStore()
    store.upsert(
        ConnectedAccount(
            id = previewGmail,
            providerAccountId = "work@example.com",
            displayName = "work",
            accountType = AccountType.GoogleConsumer,
            route = ConnectorRoute.OfficialProviderApi,
            state = ConnectionState.Healthy,
            lastSuccessfulSyncMillis = previewNow.epochMillis,
        ),
    )
    store.upsert(
        ConnectedAccount(
            id = previewWhatsApp,
            providerAccountId = "local_user",
            displayName = "WhatsApp",
            accountType = AccountType.Unknown,
            route = ConnectorRoute.NotificationOnly,
            state = ConnectionState.Healthy,
        ),
    )
    return store
}

private fun previewInboxState(): InboxViewState {
    val alice = previewConv(previewWhatsApp, "Alice Chen", "Can you send the deck before 4?", "com.whatsapp")
    val pat = previewConv(
        previewGmail,
        "Pat Rivera",
        "Re: Q3 invoice",
        "com.google.android.gm",
        coverage = ConversationCoverage.PartialApiSync,
    )
    val done = previewConv(previewWhatsApp, "Sam Lee", "Thanks, all set.", "com.whatsapp")
    val aliceAttention = AttentionRules.default(alice.conversationId, previewNow)
    val patAttention = AttentionRules.default(pat.conversationId, previewNow)
        .copy(status = FollowUpStatus.Waiting, acknowledgedActivityAt = previewNow, isUserSet = true)
    val doneAttention = AttentionRules.default(done.conversationId, previewNow)
        .copy(status = FollowUpStatus.Done, acknowledgedActivityAt = previewNow, isUserSet = true)
    return InboxViewState(
        isPermissionGranted = true,
        isListenerConnected = true,
        conversations = listOf(alice, pat, done),
        linkedinActivity = listOf(
            previewConv(
                AccountId(Provider.LINKEDIN, "local_user"),
                "Job alert",
                "12 new roles for you",
                "com.linkedin.android",
                category = NotificationCategory.SocialUpdate,
            ),
        ),
        attentionView = AttentionView(
            needsAttention = listOf(AttentionEntry(alice, aliceAttention)),
            waiting = listOf(AttentionEntry(pat, patAttention)),
            snoozed = emptyList(),
            done = listOf(AttentionEntry(done, doneAttention)),
        ),
        attentionRecords = mapOf(
            alice.conversationId.key to aliceAttention,
            pat.conversationId.key to patAttention,
            done.conversationId.key to doneAttention,
        ),
        searchQuery = "",
        searchResults = listOf(
            SearchResult(
                conversationId = alice.conversationId,
                accountId = alice.accountId,
                packageId = alice.packageId,
                conversationTitle = alice.title,
                senderHint = alice.senderHint,
                snippet = alice.latestSnippet,
                eventAt = alice.latestEventAt,
                messageCount = 2,
            ),
        ),
        savedDrafts = listOf(
            ConversationDraft(
                conversationKey = "${previewGmail.key}:conversation:compose:${previewNow.epochMillis}",
                accountId = previewGmail.key,
                body = "Following up on the invoice.",
                to = "pat@example.com",
                subject = "Q3 invoice",
                isNewEmail = true,
                updatedAtMillis = previewNow.epochMillis,
            ),
        ),
        reviewedSnapshots = listOf(
            ReviewedActionSnapshot(
                commandId = "cmd-preview-unknown",
                accountId = previewWhatsApp.key,
                conversationId = alice.conversationId.key,
                route = ActionRoute.NativeHandoff,
                recipients = listOf("Alice Chen"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = null,
                body = "Sending the deck now.",
                attachmentNames = emptyList(),
                replyTarget = null,
                state = OutboxState.OutcomeUnknown,
                createdAt = previewNow,
            ),
            ReviewedActionSnapshot(
                commandId = "cmd-preview",
                accountId = previewGmail.key,
                conversationId = pat.conversationId.key,
                route = ActionRoute.NativeHandoff,
                recipients = listOf("pat@example.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Q3 invoice",
                body = "Opening in Gmail. Not sent by MINI.",
                attachmentNames = emptyList(),
                replyTarget = null,
                state = OutboxState.HandoffOpened,
                createdAt = previewNow,
            ),
        ),
        canUndoAttention = true,
    )
}

@Composable
internal fun MiniProductionPreview(tab: MiniTab) {
    val state = previewInboxState()
    val accounts = previewAccounts()
    val labels = mapOf(
        state.conversations.first().conversationId.key to "Work",
    )
    MiniTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = { MiniBottomBar(tab) {} },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                MiniBrandBar(status = MiniPhoneStatus.OnThisPhone)
                when (tab) {
                    MiniTab.Inbox -> MiniInboxPane(
                        state = state,
                        actions = InboxUiActions(),
                        accountStore = accounts,
                        labels = labels,
                        onCompose = {},
                        onRequestPermission = {},
                    )
                    MiniTab.Focus -> MiniFocusPane(
                        state = state,
                        actions = InboxUiActions(),
                        accountStore = accounts,
                        labels = labels,
                    )
                    MiniTab.Console -> MiniConsolePane(
                        state = state,
                        accountStore = accounts,
                        capabilities = {
                            CapabilitySnapshot(enabled = setOf(Capability.Reply), disabled = emptyMap())
                        },
                        onOpenSources = {},
                        onOpenAccounts = {},
                        onOpenLearning = {},
                        onRequestPermission = {},
                        onSyncNow = {},
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Inbox light")
@Preview(showBackground = true, name = "Inbox dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Inbox large text", fontScale = 1.3f)
@Composable
fun MiniInboxScreenPreview() {
    MiniProductionPreview(MiniTab.Inbox)
}

@Preview(showBackground = true, name = "Focus light")
@Preview(showBackground = true, name = "Focus dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Focus large text", fontScale = 1.3f)
@Composable
fun MiniFocusScreenPreview() {
    MiniProductionPreview(MiniTab.Focus)
}

@Preview(showBackground = true, name = "Console light")
@Preview(showBackground = true, name = "Console dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Console large text", fontScale = 1.3f)
@Composable
fun MiniConsoleScreenPreview() {
    MiniProductionPreview(MiniTab.Console)
}

@Preview(showBackground = true, name = "Activity light")
@Preview(showBackground = true, name = "Activity dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Activity large text", fontScale = 1.3f)
@Composable
fun MiniActivityScreenPreview() {
    // Same host as production: MiniTheme > Scaffold (content color) > background Column.
    MiniTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                MiniBrandBar(status = MiniPhoneStatus.OnThisPhone)
                MiniActivityPane(
                    items = listOf(
                        previewConv(
                            AccountId(Provider.LINKEDIN, "local_user"),
                            "Job alert",
                            "12 new roles for you",
                            "com.linkedin.android",
                            NotificationCategory.SocialUpdate,
                        ),
                    ),
                    notice = "Couldn't open LinkedIn. The item is still here.",
                    onBack = {},
                    onAcknowledge = {},
                    onOpen = {},
                )
            }
        }
    }
}

private fun previewThreadSummary(): ConversationObservationSummary = previewConv(
    account = previewGmail,
    name = "Kiran Shah",
    snippet = "The revised total is ₹42,000. Could you review it and let me know what you think?",
    pkg = "com.google.android.gm",
    coverage = ConversationCoverage.FullProviderSync,
)

private fun previewObs(
    account: AccountId,
    convId: ScopedId,
    key: String,
    sender: String,
    title: String,
    text: String,
    direction: app.mini.hub.connector.MessageDirection = app.mini.hub.connector.MessageDirection.Inbound,
    time: UtcInstant = previewNow,
): app.mini.hub.observation.ObservedNotification = app.mini.hub.observation.ObservedNotification(
    id = ScopedId(account, ScopedId.Kind.OBSERVATION, key),
    accountId = account,
    conversationId = convId,
    packageId = "com.google.android.gm",
    notificationKey = key,
    notificationId = 1,
    tag = null,
    senderHint = sender,
    title = title,
    textFragments = listOf(text),
    rawTextDigest = "digest_$key",
    observedAt = time,
    postTime = time,
    revision = 1L,
    isRemoved = false,
    isGroupSummary = false,
    truncationState = app.mini.hub.observation.TruncationState.Complete,
    correlationConfidence = app.mini.hub.observation.CorrelationConfidence.High,
    provenance = app.mini.hub.observation.ObservationProvenance.OfficialProviderApi,
    direction = direction,
    recipients = listOf("rahul@studio.example"),
)

private fun previewThreadMessages(summary: ConversationObservationSummary): List<app.mini.hub.observation.ObservedNotification> = listOf(
    previewObs(
        account = summary.accountId,
        convId = summary.conversationId,
        key = "provider:msg_1",
        sender = "Kiran Shah <kiran@example.com>",
        title = "Revised quote for project",
        text = "Hi Rahul,\n\nFollowing up on our call yesterday, please find the revised scope and quote attached.\n\n📎 [document: quote_v2.pdf: 142000: https://example.com/quote.pdf]",
        direction = app.mini.hub.connector.MessageDirection.Inbound,
        time = previewNow,
    ),
    previewObs(
        account = summary.accountId,
        convId = summary.conversationId,
        key = "provider:msg_2",
        sender = "Kiran Shah <kiran@example.com>",
        title = "Revised quote for project",
        text = "The revised total is ₹42,000. Could you review it and let me know what you think?",
        direction = app.mini.hub.connector.MessageDirection.Inbound,
        time = UtcInstant(previewNow.epochMillis + 60_000L),
    ),
)

private fun previewDraftProvenance(): app.mini.hub.persistence.DraftProvenance = app.mini.hub.persistence.DraftProvenance(
    accountId = "gmail:work",
    accountLabel = "Gmail · Work · rahul@studio.example",
    userInstruction = "agree to 42k and confirm Friday delivery",
    sourceMessageReferences = listOf(
        app.mini.hub.persistence.DraftSourceReference(
            messageId = "provider:msg_2",
            senderHint = "Kiran Shah",
            timestampMillis = previewNow.epochMillis + 60_000L,
            snippet = "The revised total is ₹42,000. Could you review it and let me know what you think?",
            isIncludedAsText = true,
        ),
    ),
    appliedStyleSummary = "Professional, direct",
    coverageWarning = null,
    excludedAttachments = listOf("quote_v2.pdf (attachment content not read by AI)"),
    generatedAtMillis = previewNow.epochMillis + 120_000L,
)

@Preview(showBackground = true, name = "Thread light")
@Preview(showBackground = true, name = "Thread dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Thread large text", fontScale = 1.3f)
@Composable
fun MiniThreadScreenPreview() {
    val summary = previewThreadSummary()
    val messages = previewThreadMessages(summary)
    MiniTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                ThreadView(
                    summary = summary,
                    timeline = messages,
                    label = "Work",
                    onLabel = {},
                    accountLabel = "Gmail · Work",
                    loadStatus = "Full conversation loaded (2 messages).",
                    draftText = "Thanks Kiran. The ₹42,000 works for us. Please confirm delivery by Friday.",
                    draftStatus = "Local draft ready (210 ms). Review it before use.",
                    draftVersions = listOf("Thanks Kiran. The ₹42,000 works for us. Please confirm delivery by Friday."),
                    selectedVersionIndex = 0,
                    isDrafting = false,
                    hasProvenance = true,
                    onBack = {},
                    onDraft = { _, _ -> },
                    onCancelDraft = {},
                    onDraftChanged = {},
                    onApprove = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Why this draft light")
@Preview(showBackground = true, name = "Why this draft dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Why this draft large text", fontScale = 1.3f)
@Composable
fun MiniWhyThisDraftPreview() {
    val summary = previewThreadSummary()
    val provenance = previewDraftProvenance()
    val store = previewAccounts()
    val account = store.get(summary.accountId)
    MiniTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                WhyThisDraftView(
                    provenance = provenance,
                    selected = summary,
                    account = account,
                    onBack = {},
                    onOpenMessage = {},
                )
            }
        }
    }
}
