package app.mini.hub.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mini.hub.attention.AttentionRules
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.NotificationCategory

private val previewNow = UtcInstant(1_700_000_000_000L)
private val previewAccount = AccountId(Provider.WHATSAPP, "local_user")

private fun previewConv(
    name: String,
    snippet: String,
    pkg: String = "com.whatsapp",
    category: NotificationCategory = NotificationCategory.DirectMessage,
    coverage: ConversationCoverage = ConversationCoverage.NotificationOnly,
): ConversationObservationSummary {
    val account = AccountId(
        when {
            pkg.contains("gm") -> Provider.GMAIL
            pkg.contains("linkedin") -> Provider.LINKEDIN
            else -> Provider.WHATSAPP
        },
        "local_user",
    )
    return ConversationObservationSummary(
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
}

@Preview(showBackground = true, name = "Inbox conversation list")
@Composable
fun MiniInboxConversationPreview() {
    MiniTheme {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
            MiniPeopleRow(
                conv = previewConv("Alice Chen", "Can you send the deck before 4?"),
                label = "Work",
                attention = AttentionRules.default(ScopedId(previewAccount, ScopedId.Kind.CONVERSATION, "Alice Chen"), previewNow),
                accountAlias = "WhatsApp",
                unseen = true,
                onClick = {},
            )
            HorizontalDivider()
            MiniPeopleRow(
                conv = previewConv("Pat Rivera", "Re: Q3 invoice", "com.google.android.gm", coverage = ConversationCoverage.PartialApiSync),
                label = null,
                attention = AttentionRules.default(ScopedId(previewAccount, ScopedId.Kind.CONVERSATION, "Pat"), previewNow)
                    .copy(status = FollowUpStatus.Waiting, acknowledgedActivityAt = previewNow, isUserSet = true),
                accountAlias = "Gmail · work",
                unseen = false,
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "LinkedIn activity")
@Composable
fun MiniActivityPreview() {
    MiniTheme {
        MiniActivityPane(
            items = listOf(
                previewConv(
                    name = "Job alert",
                    snippet = "12 new roles for you",
                    pkg = "com.linkedin.android",
                    category = NotificationCategory.SocialUpdate,
                ),
            ),
            onBack = {},
            onAcknowledge = {},
            onOpen = {},
        )
    }
}
