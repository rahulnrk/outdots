package app.mini.hub.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HubTab(val label: String) { Inbox("Inbox"), Focus("Focus"), Console("Console") }

enum class PillTone { Neutral, Attention, Settled, Action, Error }

@Composable
fun pillColors(tone: PillTone): Pair<Color, Color> {
    val extra = lineupp
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        PillTone.Neutral -> scheme.surfaceVariant to extra.secondaryText
        PillTone.Attention -> extra.attentionFill to extra.attentionText
        PillTone.Settled -> extra.settledFill to extra.settledText
        PillTone.Action -> extra.actionTint to scheme.primary
        PillTone.Error -> extra.errorFill to extra.errorText
    }
}

/** Status words plus colour, never colour alone. */
@Composable
fun StatusPill(text: String, ok: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    StatePill(
        text = text,
        tone = if (ok) PillTone.Settled else PillTone.Attention,
        modifier = modifier,
        leading = { StrokeIcon(if (ok) "check" else "pause", Modifier.size(12.dp), pillColors(if (ok) PillTone.Settled else PillTone.Attention).second) },
        onClick = onClick,
    )
}

@Composable
fun StatePill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val (fill, ink) = pillColors(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        Text(text, color = ink, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp))
    }
}

@Composable
fun LineuppChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        color = if (selected) scheme.background else scheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(if (selected) scheme.onBackground else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(30.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
fun PageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                color = lineupp.secondaryText,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun NoticeBanner(
    title: String,
    body: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (fill, ink) = pillColors(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, color = ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        if (action != null && onAction != null) {
            Text(action, color = ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onAction).padding(8.dp))
        }
    }
}

@Composable
fun BrandBar(
    statusText: String,
    statusOk: Boolean,
    onStatusClick: () -> Unit,
    onCompose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineuppMark(30.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            Brand.NAME,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
            modifier = Modifier.weight(1f),
        )
        IconButtonCircle(icon = "edit", label = "Compose", onClick = onCompose)
        Spacer(Modifier.width(6.dp))
        StatusPill(statusText, statusOk, onClick = onStatusClick)
    }
}

@Composable
fun IconButtonCircle(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StrokeIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun LineuppBottomBar(selected: HubTab, onSelect: (HubTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) lineupp.actionTint else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(
                        name = tab.name.lowercase(),
                        modifier = Modifier.size(20.dp),
                        tint = if (on) scheme.primary else lineupp.secondaryText,
                    )
                }
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) scheme.primary else lineupp.secondaryText,
                )
            }
        }
    }
}

@Composable
fun PersonAvatar(name: String, source: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val palettes = listOf(
        Color(0xFFDDE4FF) to Color(0xFF284FE0),
        Color(0xFFF3E4E1) to Color(0xFF8A4A3C),
        Color(0xFFE6DFF0) to Color(0xFF5B4A8A),
        Color(0xFFF3E8D0) to Color(0xFF8A6A20),
    )
    val pair = palettes[kotlin.math.abs(name.hashCode()) % palettes.size]
    Box(modifier.size(size)) {
        Box(
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.36f)).background(pair.first),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(name), color = pair.second, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.32f).sp, fontFamily = Manrope)
        }
        Box(
            modifier = Modifier
                .size(size * 0.42f)
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(source.take(1).uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold))
        }
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
        parts.isNotEmpty() -> parts.first().take(2).uppercase()
        else -> "?"
    }
}

@Composable
fun SectionLabel(title: String, meta: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
        if (meta != null) Text(meta, style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText)
    }
}

@Composable
fun SettingRow(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else StrokeIcon("chevron", Modifier.size(18.dp), lineupp.secondaryText)
    }
}

/** Stroke icons matching the Paper & ink study, 24-unit grid. */
@Composable
fun StrokeIcon(name: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.65f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()
        when (name) {
            "search" -> {
                path.addOval(Rect(3f * s, 3f * s, 17f * s, 17f * s))
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(17f * s, 17f * s), Offset(21f * s, 21f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "inbox" -> {
                path.moveTo(4f * s, 4f * s); path.lineTo(20f * s, 4f * s); path.lineTo(20f * s, 16f * s)
                path.lineTo(16f * s, 20f * s); path.lineTo(8f * s, 20f * s); path.lineTo(4f * s, 16f * s); path.close()
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(4f * s, 14f * s), Offset(9f * s, 14f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "focus" -> {
                drawLine(tint, Offset(12f * s, 3f * s), Offset(12f * s, 6f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(12f * s, 18f * s), Offset(12f * s, 21f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(3f * s, 12f * s), Offset(6f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(18f * s, 12f * s), Offset(21f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                path.addOval(Rect(8f * s, 8f * s, 16f * s, 16f * s))
                drawPath(path, tint, style = stroke)
            }
            "console" -> {
                drawLine(tint, Offset(4f * s, 6f * s), Offset(20f * s, 6f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(4f * s, 18f * s), Offset(20f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(8f * s, 3f * s), Offset(8f * s, 9f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(16f * s, 9f * s), Offset(16f * s, 15f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "spark" -> {
                path.moveTo(12f * s, 3f * s); path.lineTo(14.5f * s, 9.5f * s); path.lineTo(21f * s, 12f * s)
                path.lineTo(14.5f * s, 14.5f * s); path.lineTo(12f * s, 21f * s); path.lineTo(9.5f * s, 14.5f * s)
                path.lineTo(3f * s, 12f * s); path.lineTo(9.5f * s, 9.5f * s); path.close()
                drawPath(path, tint, style = stroke)
            }
            "edit" -> {
                path.moveTo(15f * s, 4f * s); path.lineTo(20f * s, 9f * s); path.lineTo(9f * s, 20f * s)
                path.lineTo(4f * s, 20f * s); path.lineTo(4f * s, 15f * s); path.close()
                drawPath(path, tint, style = stroke)
            }
            "check" -> {
                path.moveTo(5f * s, 12f * s); path.lineTo(9f * s, 16f * s); path.lineTo(19f * s, 6f * s)
                drawPath(path, tint, style = stroke)
            }
            "pause" -> {
                drawLine(tint, Offset(8f * s, 5f * s), Offset(8f * s, 19f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(16f * s, 5f * s), Offset(16f * s, 19f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "chevron" -> {
                path.moveTo(9f * s, 6f * s); path.lineTo(15f * s, 12f * s); path.lineTo(9f * s, 18f * s)
                drawPath(path, tint, style = stroke)
            }
            "back" -> {
                path.moveTo(14f * s, 5f * s); path.lineTo(7f * s, 12f * s); path.lineTo(14f * s, 19f * s)
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(7f * s, 12f * s), Offset(21f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "more" -> {
                drawCircle(tint, 1.2f * s, Offset(5f * s, 12f * s))
                drawCircle(tint, 1.2f * s, Offset(12f * s, 12f * s))
                drawCircle(tint, 1.2f * s, Offset(19f * s, 12f * s))
            }
            "lock" -> {
                drawRoundRect(tint, topLeft = Offset(6f * s, 10f * s), size = androidx.compose.ui.geometry.Size(12f * s, 10f * s), cornerRadius = CornerRadius(s, s), style = stroke)
                path.moveTo(9f * s, 10f * s); path.lineTo(9f * s, 7f * s)
                path.cubicTo(9f * s, 4.5f * s, 15f * s, 4.5f * s, 15f * s, 7f * s); path.lineTo(15f * s, 10f * s)
                drawPath(path, tint, style = stroke)
            }
            "shield" -> {
                path.moveTo(12f * s, 3f * s); path.lineTo(20f * s, 6f * s); path.lineTo(20f * s, 12f * s)
                path.cubicTo(20f * s, 17f * s, 12f * s, 21f * s, 12f * s, 21f * s)
                path.cubicTo(12f * s, 21f * s, 4f * s, 17f * s, 4f * s, 12f * s); path.lineTo(4f * s, 6f * s); path.close()
                drawPath(path, tint, style = stroke)
            }
            "info" -> {
                path.addOval(Rect(3f * s, 3f * s, 21f * s, 21f * s))
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(12f * s, 11f * s), Offset(12f * s, 17f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(tint, 1.1f * s, Offset(12f * s, 8f * s))
            }
            "send" -> {
                path.moveTo(3f * s, 3f * s); path.lineTo(22f * s, 12f * s); path.lineTo(3f * s, 21f * s)
                path.lineTo(7f * s, 12f * s); path.close()
                drawPath(path, tint, style = stroke)
            }
            "close" -> {
                drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(6f * s, 18f * s), Offset(18f * s, 6f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            else -> {
                path.addOval(Rect(4f * s, 4f * s, 20f * s, 20f * s))
                drawPath(path, tint, style = stroke)
            }
        }
    }
}

fun Modifier.lineuppGutter(): Modifier = this.padding(horizontal = 20.dp)
