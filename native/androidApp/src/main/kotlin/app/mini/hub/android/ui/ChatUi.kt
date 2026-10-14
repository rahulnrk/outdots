package app.mini.hub.android.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mini.hub.android.ConversationLabels
import app.mini.hub.android.MiniHubRuntime
import app.mini.hub.connector.MessageDirection
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.SourceAppNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MEDIA_MARKER = Regex("\u27e6([\\w-]+):([^\u27e7]+)\u27e7\\s?")
private val URL = Regex("(https?://[^\\s]+|www\\.[^\\s]+)")

internal fun isHiddenWhatsAppChat(chatId: String): Boolean {
    val c = chatId.lowercase()
    return c.startsWith("status@") || c.endsWith("@newsletter") || c.endsWith("@broadcast")
}

internal data class MediaRef(val rawKind: String, val id: String) {
    val isGmail get() = rawKind.startsWith("gm-")
    val isUrl get() = rawKind.startsWith("ig-")
    val kind get() = rawKind.removePrefix("gm-").removePrefix("ig-")
    val fileName get() = id.substringAfter('|', "")
}

internal fun splitMedia(text: String): Pair<MediaRef?, String> {
    val refs = allMedia(text)
    return refs.firstOrNull() to MEDIA_MARKER.replace(text, "").trim()
}

internal fun allMedia(text: String): List<MediaRef> =
    MEDIA_MARKER.findAll(text).map { MediaRef(it.groupValues[1], it.groupValues[2]) }.toList()

internal fun previewText(text: String): String {
    val (media, rest) = splitMedia(text)
    val icon = when (media?.kind) {
        "image" -> "📷 Photo"
        "video" -> "🎥 Video"
        "gif" -> "GIF"
        "sticker" -> "Sticker"
        "audio" -> "🎤 Voice"
        "document" -> "📄 Document"
        else -> null
    }
    return listOfNotNull(icon, rest.ifBlank { null }).joinToString(" ")
}

private fun timeLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - millis < 24 * 3600_000L) "HH:mm" else "dd MMM"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(millis))
}

/** Chat-app style list for every source, newest activity first, filterable by category. */
@Composable
fun ChatListView(
    conversations: List<ConversationObservationSummary>,
    labels: Map<String, String>,
    onSelect: (ConversationObservationSummary) -> Unit,
    onAttention: (app.mini.hub.domain.ScopedId, app.mini.hub.attention.AttentionAction) -> Unit = { _, _ -> },
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val linkedinActivities = conversations.filter(::isLinkedInActivitySummary)
    val chatConversations = conversations.filterNot(::isLinkedInActivitySummary)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            ConversationLabels.DEFAULTS.forEach { label ->
                FilterChip(selected = filter == label, onClick = { filter = if (filter == label) null else label }, label = { Text(label) })
            }
        }
        if (linkedinActivities.isNotEmpty() && filter == null) {
            Text("LinkedIn notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Activity is kept separate from messages. Choose which LinkedIn activity types to capture in Sources.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            linkedinActivities.sortedByDescending { it.latestEventAt.epochMillis }.forEach { activity ->
                LinkedInActivityRow(activity, onAttention)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        val shown = chatConversations
            .filter { filter == null || labels[it.conversationId.key] == filter }
            .sortedByDescending { it.latestEventAt.epochMillis }
        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == null) "No conversations yet." else "No chats marked $filter. Open a chat and tap its category.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn {
            items(shown, key = { it.conversationId.key }) { conv ->
                ChatRow(conv, labels[conv.conversationId.key]) { onSelect(conv) }
                HorizontalDivider()
            }
        }
    }
}

private fun isLinkedInActivitySummary(summary: ConversationObservationSummary): Boolean =
    summary.packageId == "com.linkedin.android" &&
        summary.category != app.mini.hub.observation.NotificationCategory.DirectMessage &&
        summary.category != app.mini.hub.observation.NotificationCategory.GroupMessage

@Composable
private fun LinkedInActivityRow(
    summary: ConversationObservationSummary,
    onAttention: (app.mini.hub.domain.ScopedId, app.mini.hub.attention.AttentionAction) -> Unit,
) {
    val context = LocalContext.current
    fun openLinkedIn() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/"))
                    .setPackage("com.linkedin.android")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { openLinkedIn() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Column {
                Text(summary.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary.latestSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("LinkedIn activity · ${summary.categoryLabel()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onAttention(summary.conversationId, app.mini.hub.attention.AttentionAction.MarkDone)
                }) { Text("Acknowledge") }
                OutlinedButton(onClick = ::openLinkedIn) { Text("Open in LinkedIn") }
            }
        }
    }
}

private fun ConversationObservationSummary.categoryLabel(): String = when (category) {
    app.mini.hub.observation.NotificationCategory.SocialUpdate -> "Social update"
    app.mini.hub.observation.NotificationCategory.GeneralNotification -> "General alert"
    else -> category.name
}

@Composable
private fun ChatRow(conv: ConversationObservationSummary, label: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(conv.title.trim().firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(timeLabel(conv.latestEventAt.epochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatChatText(previewText(conv.latestSnippet)).text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.unreadCount > 0) {
                    Box(
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp),
                    ) { Text("${conv.unreadCount}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(SourceAppNames.forPackage(conv.packageId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                label?.let { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
    }
}

/** One thread view for every connector: history with media/links, category marker, AI composer. */
@Composable
fun ThreadView(
    summary: ConversationObservationSummary,
    timeline: List<ObservedNotification>,
    label: String?,
    onLabel: (String?) -> Unit,
    draftText: String,
    draftStatus: String?,
    draftVersions: List<String>,
    isDrafting: Boolean,
    onBack: () -> Unit,
    onDraft: (instruction: String, revise: Boolean) -> Unit,
    onCancelDraft: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onApprove: (String) -> Unit,
    onAttention: (app.mini.hub.attention.AttentionAction) -> Unit = {},
) {
    val messages = remember(timeline) {
        // A swiped-away notification is still a received message; only provider deletions hide it.
        val live = timeline.filterNot { it.isRemoved && it.notificationKey.startsWith("provider:") }
        // Prefer synced provider rows; drop notification duplicates when the account is connected.
        val synced = live.filter { it.notificationKey.startsWith("provider:") }
        (if (synced.isNotEmpty()) synced else live)
            .distinctBy { o -> Triple(o.senderHint.lowercase(), o.postTime.epochMillis / 60_000, o.textFragments.joinToString(" ").take(200)) }
            .filter { o -> o.textFragments.any { it.isNotBlank() } }
            .sortedBy { it.postTime.epochMillis }
    }
    val isEmail = summary.accountId.provider == app.mini.hub.domain.Provider.GMAIL ||
        summary.accountId.provider == app.mini.hub.domain.Provider.OUTLOOK
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1) }
    var showLabels by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("←") }
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(SourceAppNames.forPackage(summary.packageId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            OutlinedButton(onClick = { showLabels = true }) { Text(label ?: "Category") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Mark done") }, onClick = { onAttention(app.mini.hub.attention.AttentionAction.MarkDone); menu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Waiting for their reply") }, onClick = { onAttention(app.mini.hub.attention.AttentionAction.MarkWaiting); menu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Snooze 4 hours") }, onClick = {
                        onAttention(app.mini.hub.attention.AttentionAction.Snooze(app.mini.hub.domain.UtcInstant(System.currentTimeMillis() + 4 * 3600_000L))); menu = false
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Needs my attention") }, onClick = { onAttention(app.mini.hub.attention.AttentionAction.MarkNeedsAttention); menu = false })
                }
            }
        }
        if (summary.coverage == app.mini.hub.observation.ConversationCoverage.NotificationOnly) {
            Text(
                summary.coverageExplanation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (showLabels) {
            AlertDialog(
                onDismissRequest = { showLabels = false },
                title = { Text("Mark this chat") },
                text = {
                    Column {
                        (ConversationLabels.DEFAULTS + listOf<String?>(null)).forEach { option ->
                            TextButton(onClick = { onLabel(option); showLabels = false }) {
                                Text(option ?: "No category", fontWeight = if (option == label) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) item { Text("No messages yet.", color = MaterialTheme.colorScheme.outline) }
            if (isEmail) {
                items(messages, key = { it.id.key }) { obs -> EmailCard(obs, expandedByDefault = obs == messages.last()) }
            } else {
                items(messages, key = { it.id.key }) { obs -> MessageBubble(obs, isGroupish = summary.title != obs.senderHint) }
            }
        }
        if (isEmail) {
            val subject = messages.lastOrNull { !it.title.isNullOrBlank() }?.title ?: summary.title
            val to = messages.lastOrNull { it.direction != MessageDirection.Outbound }?.senderHint ?: summary.senderHint
            Text(
                "Reply to ${displaySender(to)} · Re: $subject",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Composer(
            draftText = draftText,
            draftStatus = draftStatus,
            draftVersions = draftVersions,
            isDrafting = isDrafting,
            onDraft = onDraft,
            onCancelDraft = onCancelDraft,
            onDraftChanged = onDraftChanged,
            onApprove = onApprove,
        )
    }
}

@Composable
private fun EmailCard(obs: ObservedNotification, expandedByDefault: Boolean) {
    var expanded by rememberSaveable(obs.id.key) { mutableStateOf(expandedByDefault) }
    val mine = obs.direction == MessageDirection.Outbound
    val joined = obs.textFragments.joinToString("\n")
    val attachments = allMedia(joined)
    val body = splitMedia(joined).second
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text(displaySender(obs.senderHint).firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (mine) "You" else displaySender(obs.senderHint), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (!expanded) {
                        Text(previewText(joined), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Text(fullTime(obs.postTime.epochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                EmailField("From", obs.senderHint)
                obs.recipients?.takeIf { it.isNotEmpty() }?.let { EmailField("To", it.joinToString(", ")) }
                obs.title?.takeIf { it.isNotBlank() }?.let { EmailField("Subject", it) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (body.isNotBlank()) LinkText(body)
                if (attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("📎 ${attachments.size} attachment(s)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    attachments.forEach { MediaView(it, obs.accountId) }
                }
            }
        }
    }
}

@Composable
private fun EmailField(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun displaySender(raw: String): String =
    raw.substringBefore('<').trim().trim('"').ifBlank { raw.substringAfter('<').substringBefore('>') }

private fun fullTime(millis: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun MessageBubble(obs: ObservedNotification, isGroupish: Boolean) {
    val mine = obs.direction == MessageDirection.Outbound
    val joined = obs.textFragments.joinToString("\n")
    val medias = allMedia(joined)
    val text = splitMedia(joined).second
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!mine && isGroupish) {
                    Text(obs.senderHint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                medias.forEach { MediaView(it, obs.accountId) }
                if (text.isNotBlank()) LinkText(text)
                Text(
                    timeLabel(obs.postTime.epochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End),
                )
                if (obs.truncationState == app.mini.hub.observation.TruncationState.Truncated) {
                    Text(
                        "Message shortened by ${SourceAppNames.forPackage(obs.packageId)} notification",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkText(text: String) {
    val context = LocalContext.current
    val annotated = remember(text) { formatChatText(text) }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { a ->
                val url = if (a.item.startsWith("http")) a.item else "https://${a.item}"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
    )
}

private val WA_FORMAT = Regex("(?<![\\w*_~])([*_~])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*_~])")

/** WhatsApp *bold* _italic_ ~strike~ plus clickable links, without showing the markers. */
internal fun formatChatText(text: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var last = 0
    val tokens = (URL.findAll(text).map { it to "url" } + WA_FORMAT.findAll(text).map { it to "fmt" })
        .sortedBy { it.first.range.first }
    for ((m, type) in tokens) {
        if (m.range.first < last) continue
        append(text.substring(last, m.range.first))
        if (type == "url") {
            pushStringAnnotation("url", m.value)
            pushStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1565C0), textDecoration = TextDecoration.Underline))
            append(m.value)
            pop(); pop()
        } else {
            val style = when (m.groupValues[1]) {
                "*" -> SpanStyle(fontWeight = FontWeight.Bold)
                "_" -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                else -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            }
            pushStyle(style)
            append(m.groupValues[2])
            pop()
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun MediaView(ref: MediaRef, accountId: app.mini.hub.domain.AccountId) {
    if (ref.isUrl) {
        val ctx = LocalContext.current
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ref.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(if (ref.kind == "video") "▶ Open video" else "📷 Open photo") }
        return
    }
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf<ImageBitmap?>(null) }
    var opening by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, ref.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (ref.isGmail) {
                    if (ref.kind == "image" || ref.kind == "gif") {
                        MiniHubRuntime.connectors.gmailDownloadAttachment(accountId, ref.id)?.let { f ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                        }
                    } else null
                } else {
                    MiniHubRuntime.connectors.whatsappMediaThumbnail(ref.id)
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    LaunchedEffect(opening) {
        if (!opening) return@LaunchedEffect
        status = "Loading…"
        val file = withContext(Dispatchers.IO) {
            runCatching {
                if (ref.isGmail) MiniHubRuntime.connectors.gmailDownloadAttachment(accountId, ref.id)
                else MiniHubRuntime.connectors.whatsappDownloadMedia(ref.id)
            }.getOrNull()
        }
        if (file == null) {
            val why = if (ref.isGmail) null else runCatching { MiniHubRuntime.connectors.whatsappLastMediaError() }.getOrNull()
            status = when {
                why == null -> "Couldn't load this ${ref.kind}."
                why.contains("media_unavailable") -> "This ${ref.kind} isn't available on the linked device. Open it in WhatsApp."
                why.contains("404") || why.contains("410") || why.contains("gone", true) -> "WhatsApp no longer has this ${ref.kind} (too old)."
                else -> "Couldn't load this ${ref.kind}: $why"
            }
        } else {
            full = if (ref.kind == "image" || ref.kind == "sticker" || (ref.isGmail && ref.kind == "gif")) {
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
            } else null
            status = null
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", file)
            val mime = when (ref.kind) {
                "video" -> "video/*"; "gif" -> if (ref.isGmail) "image/gif" else "video/*"; "audio" -> "audio/*"
                "image" -> "image/*"; "sticker" -> "image/webp"
                else -> android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            }
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { status = "No app can open this ${ref.kind}." }
        }
        opening = false
    }
    val label = when (ref.kind) {
        "video" -> "▶ Video"; "gif" -> "▶ GIF"; "audio" -> "▶ Voice message"
        "document" -> "📄 " + ref.fileName.ifBlank { "Open document" }; "sticker" -> "Sticker"; else -> "Photo"
    }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 220.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface).clickable { opening = true },
        contentAlignment = Alignment.Center,
    ) {
        val img = full ?: thumb
        if (img != null) {
            Image(img, contentDescription = ref.kind, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
        }
        if (full == null && ref.kind != "image" && ref.kind != "sticker" || img == null) {
            Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(6.dp))
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    Spacer(modifier = Modifier.height(4.dp))
}

/** Type your own reply, or ask AI to draft, refine with an instruction, pick a version, edit, then approve. */
@Composable
private fun Composer(
    draftText: String,
    draftStatus: String?,
    draftVersions: List<String>,
    isDrafting: Boolean,
    onDraft: (String, Boolean) -> Unit,
    onCancelDraft: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onApprove: (String) -> Unit,
) {
    var instruction by rememberSaveable { mutableStateOf("") }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (aiOpen) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("AI assistant (on this phone)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        placeholder = { Text(if (draftText.isBlank()) "What should the reply say?" else "How should I change it? e.g. shorter, more formal, in Hindi") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("Shorter", "More polite", "More casual", "Say no politely", "Ask a question").forEach { quick ->
                            FilterChip(selected = false, onClick = { instruction = quick }, label = { Text(quick) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (isDrafting) {
                            OutlinedButton(onClick = onCancelDraft) { Text("Stop") }
                        } else {
                            Button(onClick = { onDraft(instruction.ifBlank { "write a short, natural reply" }, false) }) {
                                Text(if (draftText.isBlank()) "Write reply" else "New version")
                            }
                            if (draftText.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onDraft(instruction.ifBlank { "improve it" }, true) },
                                    enabled = instruction.isNotBlank(),
                                ) { Text("Change this draft") }
                            }
                        }
                    }
                    if (draftVersions.size > 1) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            draftVersions.forEachIndexed { i, v ->
                                FilterChip(selected = v == draftText, onClick = { onDraftChanged(v) }, label = { Text("Version ${i + 1}") })
                            }
                        }
                    }
                    draftStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TextButton(onClick = { aiOpen = !aiOpen }) { Text(if (aiOpen) "✕ AI" else "✨ AI") }
            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftChanged,
                placeholder = { Text("Type a reply") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(onClick = { onApprove(draftText) }, enabled = draftText.isNotBlank() && !isDrafting) { Text("Review reply") }
        }
        Text(
            "Review reply shows the exact message for your approval first.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
