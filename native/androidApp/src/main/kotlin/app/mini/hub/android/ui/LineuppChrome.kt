package app.mini.hub.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = "arrow",
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            StrokeIcon(trailing, Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        if (leading != null) {
            StrokeIcon(leading, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
fun SubTopBar(title: String, onBack: () -> Unit, onClose: (() -> Unit)? = onBack) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonCircle("back", "Back", onClick = onBack)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (onClose != null) IconButtonCircle("close", "Close", onClick = onClose)
    }
}

@Composable
fun StepProgress(step: Int, of: Int = 3) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text("STEP $step OF $of", style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(of) { i ->
                Box(
                    Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
fun PromiseRow(icon: String, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StrokeIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText)
        }
    }
}

@Composable
fun InfoNote(text: String, icon: String = "info", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StrokeIcon(icon, Modifier.size(16.dp), lineupp.secondaryText)
        Text(text, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText)
    }
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun DraftCard(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val border = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, border.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StrokeIcon("spark", Modifier.size(16.dp), border)
            Text(label, color = border, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun ReviewKv(label: String, title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, modifier = Modifier.width(56.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText)
        }
    }
}

@Composable
fun LargeIconBox(icon: String, tone: PillTone = PillTone.Action, modifier: Modifier = Modifier) {
    val (fill, ink) = pillColors(tone)
    Box(
        modifier = modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(fill),
        contentAlignment = Alignment.Center,
    ) { StrokeIcon(icon, Modifier.size(28.dp), ink) }
}

@Composable
fun FooterColumn(note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        if (!note.isNullOrBlank()) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = lineupp.secondaryText, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun ScreenScaffold(
    top: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), content = { top() })
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            content = content,
        )
        footer()
    }
}

@Composable
fun SourceChoiceRow(
    title: String,
    pill: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(title, title, 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                StatePill(pill, PillTone.Neutral)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = lineupp.secondaryText)
        }
        Box(
            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) StrokeIcon("check", Modifier.size(14.dp), MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun SearchBar(text: String, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StrokeIcon("search", Modifier.size(18.dp), lineupp.secondaryText)
        Text(if (text.isBlank()) placeholder else text, color = if (text.isBlank()) lineupp.secondaryText else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun DashedDraftBorder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                color = color,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }
        content()
    }
}
