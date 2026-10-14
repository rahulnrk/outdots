package app.mini.hub.android.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Debug-only fixture renderer. It never shows live account content and does not set FLAG_SECURE
 * so production synthetic previews can be exported as images. MainActivity stays FLAG_SECURE.
 */
class PreviewExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        val font = intent.getFloatExtra(EXTRA_FONT, 1f).coerceIn(0.85f, 2f)
        val surface = intent.getStringExtra(EXTRA_SURFACE).orEmpty()
        setContent {
            val base = LocalConfiguration.current
            val config = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale = font
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, font),
            ) {
                when (surface.lowercase()) {
                    "focus" -> MiniProductionPreview(MiniTab.Focus)
                    "console" -> MiniProductionPreview(MiniTab.Console)
                    "activity" -> MiniActivityScreenPreview()
                    else -> MiniProductionPreview(MiniTab.Inbox)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SURFACE = "surface"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_FONT = "font"
    }
}
