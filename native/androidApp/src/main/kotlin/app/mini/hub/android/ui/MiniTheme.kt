package app.mini.hub.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mini.hub.android.R

@OptIn(ExperimentalTextApi::class)
private val MiniManrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(ExperimentalTextApi::class)
private val MiniInter = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** Paper & ink color and type tokens, branded MINI. */
object MiniInk {
    val lightCanvas = Color(0xFFF8F9F7)
    val lightSurface = Color(0xFFFFFFFF)
    val lightSoft = Color(0xFFEEEDE8)
    val lightText = Color(0xFF182126)
    val lightSecondary = Color(0xFF626D73)
    val lightDivider = Color(0xFFE2E5E4)
    val lightAction = Color(0xFF284FE0)
    val lightOnAction = Color(0xFFFFFFFF)
    val lightActionTint = Color(0xFFEDF1FF)
    val lightAttentionText = Color(0xFF8A4F08)
    val lightAttentionFill = Color(0xFFFFF1D6)
    val lightSettledText = Color(0xFF146B57)
    val lightSettledFill = Color(0xFFE8F3ED)
    val lightErrorText = Color(0xFFB32D3D)
    val lightErrorFill = Color(0xFFFCECEF)

    val darkCanvas = Color(0xFF121D23)
    val darkSurface = Color(0xFF1A252C)
    val darkSoft = Color(0xFF243138)
    val darkText = Color(0xFFECF1EE)
    val darkSecondary = Color(0xFFA8B5BC)
    val darkDivider = Color(0xFF34434B)
    val darkAction = Color(0xFFA9BBFF)
    val darkOnAction = Color(0xFF182449)
    val darkActionTint = Color(0xFF273651)
    val darkAttentionText = Color(0xFFFFD494)
    val darkAttentionFill = Color(0xFF3D3023)
    val darkSettledText = Color(0xFFA0DFBE)
    val darkSettledFill = Color(0xFF213A32)
    val darkErrorText = Color(0xFFFFB7BE)
    val darkErrorFill = Color(0xFF412830)

    val gutter = 20
    val rowGap = 12
    val cardRadius = 16
    val controlRadius = 12
    val primaryTarget = 48
}

private val MiniLightColors = lightColorScheme(
    primary = MiniInk.lightAction,
    onPrimary = MiniInk.lightOnAction,
    primaryContainer = MiniInk.lightActionTint,
    onPrimaryContainer = MiniInk.lightAction,
    background = MiniInk.lightCanvas,
    onBackground = MiniInk.lightText,
    surface = MiniInk.lightSurface,
    onSurface = MiniInk.lightText,
    surfaceVariant = MiniInk.lightSoft,
    onSurfaceVariant = MiniInk.lightSecondary,
    outline = MiniInk.lightDivider,
    error = MiniInk.lightErrorText,
    errorContainer = MiniInk.lightErrorFill,
    tertiary = MiniInk.lightAttentionText,
    tertiaryContainer = MiniInk.lightAttentionFill,
    secondary = MiniInk.lightSettledText,
    secondaryContainer = MiniInk.lightSettledFill,
)

private val MiniDarkColors = darkColorScheme(
    primary = MiniInk.darkAction,
    onPrimary = MiniInk.darkOnAction,
    primaryContainer = MiniInk.darkActionTint,
    onPrimaryContainer = MiniInk.darkAction,
    background = MiniInk.darkCanvas,
    onBackground = MiniInk.darkText,
    surface = MiniInk.darkSurface,
    onSurface = MiniInk.darkText,
    surfaceVariant = MiniInk.darkSoft,
    onSurfaceVariant = MiniInk.darkSecondary,
    outline = MiniInk.darkDivider,
    error = MiniInk.darkErrorText,
    errorContainer = MiniInk.darkErrorFill,
    tertiary = MiniInk.darkAttentionText,
    tertiaryContainer = MiniInk.darkAttentionFill,
    secondary = MiniInk.darkSettledText,
    secondaryContainer = MiniInk.darkSettledFill,
)

private val MiniTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(
        fontFamily = MiniManrope,
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 35.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = MiniManrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = MiniManrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = MiniManrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = MiniInter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = MiniInter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = MiniInter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = MiniInter,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun MiniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) MiniDarkColors else MiniLightColors,
        typography = MiniTypography,
        content = content,
    )
}
