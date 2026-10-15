package app.mini.hub.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.persistence.DraftProvenance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhyThisDraftView(
    provenance: DraftProvenance?,
    selected: ConversationObservationSummary,
    account: ConnectedAccount?,
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val validProvenance = provenance?.takeIf { it.accountId == selected.accountId.key }
    val accountLabel = account?.displayName?.ifBlank { null }
        ?: account?.providerAccountId?.ifBlank { null }
        ?: selected.accountId.localId
    val providerWire = selected.accountId.provider.wire

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = MiniInk.gutter.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("← Back to my draft", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            text = "Grounded in\nthis conversation.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Nothing from another account is used.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // Lock pill: Account isolation
        Card(
            shape = RoundedCornerShape(MiniInk.controlRadius.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("🔒", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "$providerWire · $accountLabel",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Only this account is used. No silently combined WhatsApp or Outlook context.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (validProvenance == null) {
            Card(
                shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Evidence unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Generation evidence is unavailable for this draft. Manual edits or drafts created before provenance tracking are preserved without simulated evidence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            // Section: Message used
            Text(
                text = "Message used",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            if (validProvenance.sourceMessageReferences.isEmpty()) {
                Card(
                    shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "No prior messages in this conversation were cited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                validProvenance.sourceMessageReferences.forEach { ref ->
                    Card(
                        shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "“${ref.snippet}”",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${ref.senderHint} · ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ref.timestampMillis))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { onOpenMessage(ref.messageId) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("Open original message ↗", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // Section: Your reply instruction
            Text(
                text = "Your reply instruction",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            Card(
                shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "“${validProvenance.userInstruction}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "User-supplied instruction controlling this draft's tone, content, or length.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    validProvenance.previousDraft?.let { prev ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Based on earlier draft: “$prev”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    validProvenance.understandingNote?.let { note ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Derived understanding: $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section: Applied style
            Text(
                text = "Applied style",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            Card(
                shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = validProvenance.appliedStyleSummary ?: "Standard neutral tone. No custom style profile applied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Section: Not included / Excluded attachments
            Text(
                text = "Not included",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            Card(
                shape = RoundedCornerShape(MiniInk.cardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (validProvenance.excludedAttachments.isNotEmpty()) {
                        validProvenance.excludedAttachments.forEach { att ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Text("📄", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(att, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text("Its contents haven’t been read for this draft.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No files or attachments were excluded. Only message text was consulted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Excluded attachments were not read by on-device AI. Verify any file details directly in the source document.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            validProvenance.coverageWarning?.let { warning ->
                Card(
                    shape = RoundedCornerShape(MiniInk.controlRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "Coverage limit: $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(MiniInk.primaryTarget.dp),
            shape = RoundedCornerShape(MiniInk.controlRadius.dp),
        ) {
            Text("Back to my draft")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
