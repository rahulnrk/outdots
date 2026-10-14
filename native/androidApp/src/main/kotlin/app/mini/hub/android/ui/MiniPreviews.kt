package app.mini.hub.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mini.hub.attention.AttentionEntry
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

@Preview(showBackground = true, name = "Inbox light")
@Preview(showBackground = true, name = "Inbox dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Inbox large text", fontScale = 1.3f)
@Composable
fun MiniInboxScreenPreview() {
    MiniTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(bottom = 12.dp)) {
            Text("Inbox", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text("Conversations, not notifications.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
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

@Preview(showBackground = true, name = "Focus light")
@Preview(showBackground = true, name = "Focus dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Focus large text", fontScale = 1.3f)
@Composable
fun MiniFocusScreenPreview() {
    val summary = previewConv("Alice Chen", "Can you send the deck before 4?")
    val entry = AttentionEntry(summary, AttentionRules.default(summary.conversationId, previewNow))
    MiniTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
            Text("A LITTLE FOCUS.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("A little focus.", style = MaterialTheme.typography.headlineMedium)
            Text("Needs you 1   Waiting 0   Later 0", style = MaterialTheme.typography.labelLarge)
            MiniPeopleRow(
                conv = entry.summary,
                label = "Work",
                attention = entry.attention,
                accountAlias = "WhatsApp",
                unseen = true,
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Console light")
@Preview(showBackground = true, name = "Console dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Console large text", fontScale = 1.3f)
@Composable
fun MiniConsoleScreenPreview() {
    MiniTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
            Text("Your console.", style = MaterialTheme.typography.headlineLarge)
            Text("A clear view of what's working.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Gmail · work", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Checked just now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Reply available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true, name = "Activity light")
@Preview(showBackground = true, name = "Activity dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Activity large text", fontScale = 1.3f)
@Composable
fun MiniActivityScreenPreview() {
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
            notice = "Couldn't open LinkedIn. The item is still here.",
            onBack = {},
            onAcknowledge = {},
            onOpen = {},
        )
    }
}
