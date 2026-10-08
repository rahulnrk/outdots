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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.ObservedNotification

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    handoffLauncher: HandoffLauncher,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MINI Hub — Private Local Inbox") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                actions = {
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
                ConversationDetailView(
                    summary = state.selectedConversation!!,
                    timeline = state.selectedTimeline,
                    onBack = { viewModel.clearSelection() },
                    onDraftPrepared = { body, recip -> viewModel.prepareDraft(body, recip) },
                )
            } else {
                // Inbox List View
                ConversationListView(
                    conversations = state.conversations,
                    onSelect = { viewModel.selectConversation(it) },
                )
            }
        }
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
fun ConversationListView(
    conversations: List<ConversationObservationSummary>,
    onSelect: (ConversationObservationSummary) -> Unit,
) {
    if (conversations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No notification observations yet.\nMessages received from WhatsApp/Gmail will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.conversationId.key }) { conv ->
                ConversationItemCard(conversation = conv, onClick = { onSelect(conv) })
            }
        }
    }
}

@Composable
fun ConversationItemCard(
    conversation: ConversationObservationSummary,
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
                    text = conversation.packageId.substringAfterLast('.').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = conversation.latestSnippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Truthful coverage badge
                Text(
                    text = "Notification Observation (Partial)",
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
fun ConversationDetailView(
    summary: ConversationObservationSummary,
    timeline: List<ObservedNotification>,
    onBack: () -> Unit,
    onDraftPrepared: (String, String) -> Unit,
) {
    var draftText by remember { mutableStateOf("") }

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

        // Quick Draft Composer
        OutlinedTextField(
            value = draftText,
            onValueChange = { draftText = it },
            label = { Text("Draft reply (requires your approval before opening)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = {
                if (draftText.isNotBlank()) {
                    onDraftPrepared(draftText, summary.senderHint)
                }
            },
            enabled = draftText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Review & Approve Draft")
        }
    }
}

@Composable
fun ExactActionReviewDialog(
    payload: app.mini.hub.action.ExactActionPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact Action Review (No Unintended Send)") },
        text = {
            Column {
                Text(
                    text = "Review the exact message and destination before proceeding. This action will open WhatsApp / email with this pre-filled draft.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Route: Native Handoff", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                Text("Approve & Open App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
