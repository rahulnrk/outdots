package app.mini.hub.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mini.hub.android.R

/**
 * Lineupp visual system, adapted from the "Paper & ink" design study (13 Oct 2026).
 * Placeholder branding: the name and mark are not final.
 */
object Brand {
    const val NAME = "Lineupp"
}

/** Semantic colours that Material's scheme has no slot for. Each state also carries words. */
@Immutable
data class LineuppColors(
    val attentionText: Color,
    val attentionFill: Color,
    val settledText: Color,
    val settledFill: Color,
    val errorText: Color,
    val errorFill: Color,
    val actionTint: Color,
    val divider: Color,
    val secondaryText: Color,
)

private val LightExtra = LineuppColors(
    attentionText = Color(0xFF8A4F08), attentionFill = Color(0xFFFFF1D6),
    settledText = Color(0xFF146B57), settledFill = Color(0xFFE8F3ED),
    errorText = Color(0xFFB32D3D), errorFill = Color(0xFFFCECEF),
    actionTint = Color(0xFFEDF1FF), divider = Color(0xFFE2E5E4), secondaryText = Color(0xFF626D73),
)

private val DarkExtra = LineuppColors(
    attentionText = Color(0xFFFFD494), attentionFill = Color(0xFF3D3023),
    settledText = Color(0xFFA0DFBE), settledFill = Color(0xFF213A32),
    errorText = Color(0xFFFFB7BE), errorFill = Color(0xFF412830),
    actionTint = Color(0xFF273651), divider = Color(0xFF34434B), secondaryText = Color(0xFFA8B5BC),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF284FE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDF1FF), onPrimaryContainer = Color(0xFF182449),
    secondary = Color(0xFF626D73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEDE8), onSecondaryContainer = Color(0xFF182126),
    tertiary = Color(0xFF146B57), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F3ED), onTertiaryContainer = Color(0xFF146B57),
    background = Color(0xFFF8F9F7), onBackground = Color(0xFF182126),
    surface = Color(0xFFF8F9F7), onSurface = Color(0xFF182126),
    surfaceVariant = Color(0xFFEEEDE8), onSurfaceVariant = Color(0xFF626D73),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF1F2EF),
    surfaceContainerHighest = Color(0xFFEEEDE8),
    outline = Color(0xFFCBD0CE), outlineVariant = Color(0xFFE2E5E4),
    error = Color(0xFFB32D3D), onError = Color.White,
    errorContainer = Color(0xFFFCECEF), onErrorContainer = Color(0xFFB32D3D),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA9BBFF), onPrimary = Color(0xFF182449),
    primaryContainer = Color(0xFF273651), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFA8B5BC), onSecondary = Color(0xFF121D23),
    secondaryContainer = Color(0xFF243138), onSecondaryContainer = Color(0xFFECF1EE),
    tertiary = Color(0xFFA0DFBE), onTertiary = Color(0xFF0E2A21),
    tertiaryContainer = Color(0xFF213A32), onTertiaryContainer = Color(0xFFA0DFBE),
    background = Color(0xFF121D23), onBackground = Color(0xFFECF1EE),
    surface = Color(0xFF121D23), onSurface = Color(0xFFECF1EE),
    surfaceVariant = Color(0xFF243138), onSurfaceVariant = Color(0xFFA8B5BC),
    surfaceContainerLowest = Color(0xFF1A252C), surfaceContainerLow = Color(0xFF1A252C),
    surfaceContainer = Color(0xFF1A252C), surfaceContainerHigh = Color(0xFF213039),
    surfaceContainerHighest = Color(0xFF243138),
    outline = Color(0xFF4A5A62), outlineVariant = Color(0xFF34434B),
    error = Color(0xFFFFB7BE), onError = Color(0xFF412830),
    errorContainer = Color(0xFF412830), onErrorContainer = Color(0xFFFFB7BE),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Manrope = FontFamily(
    variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600),
    variable(R.font.manrope, 700), variable(R.font.manrope, 800),
)

val Inter = FontFamily(
    variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700),
)

private fun heading(size: Int, line: Int, weight: Int, tracking: Double = -0.4) =
    TextStyle(fontFamily = Manrope, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private fun body(size: Int, line: Int, weight: Int = 400, tracking: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val LineuppTypography = Typography(
    displaySmall = heading(31, 35, 700, -0.8),
    headlineLarge = heading(31, 35, 700, -0.8),
    headlineMedium = heading(24, 30, 700, -0.5),
    headlineSmall = heading(20, 26, 700, -0.3),
    titleLarge = heading(20, 26, 700, -0.3),
    titleMedium = body(15, 22, 600),
    titleSmall = body(14, 20, 600),
    bodyLarge = body(15, 24),
    bodyMedium = body(14, 22),
    bodySmall = body(12, 18),
    labelLarge = body(14, 20, 600),
    labelMedium = body(12, 16, 500),
    labelSmall = body(11, 16, 500, 0.1),
)

private val LineuppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalLineuppColors = staticCompositionLocalOf { LightExtra }

/** Extra semantic colours for the current theme. */
val lineupp: LineuppColors
    @Composable get() = LocalLineuppColors.current

@Composable
fun LineuppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLineuppColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = LineuppTypography,
            shapes = LineuppShapes,
            content = content,
        )
    }
}

/**
 * Placeholder mark: three incoming lanes joined, with a warm cursor pausing in the middle lane.
 * The cursor is omitted below 20 dp, per the study.
 */
@Composable
fun LineuppMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 48f
        drawRoundRect(Color(0xFF182126), cornerRadius = CornerRadius(14 * s, 14 * s))
        val lane = Path().apply {
            moveTo(12 * s, 13 * s); lineTo(29 * s, 13 * s)
            cubicTo(38 * s, 13 * s, 38 * s, 24 * s, 29 * s, 24 * s); lineTo(24 * s, 24 * s)
            moveTo(12 * s, 24 * s); lineTo(17 * s, 24 * s)
            moveTo(24 * s, 24 * s); lineTo(29 * s, 24 * s)
            cubicTo(38 * s, 24 * s, 38 * s, 35 * s, 29 * s, 35 * s); lineTo(12 * s, 35 * s)
        }
        drawPath(lane, Color(0xFFBAC8FF), style = Stroke(width = 4 * s, cap = StrokeCap.Round))
        if (size >= 20.dp) {
            drawRoundRect(Color(0xFFF3B960), topLeft = Offset(19 * s, 22 * s), size = Size(4 * s, 4 * s), cornerRadius = CornerRadius(s, s))
        }
    }
}
