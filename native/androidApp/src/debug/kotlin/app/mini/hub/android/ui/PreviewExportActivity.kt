package app.mini.hub.android.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Debug-only fixture renderer. It never shows live account content and does not set FLAG_SECURE
 * so production synthetic previews can be exported as images. MainActivity stays FLAG_SECURE.
 */
class PreviewExportActivity : ComponentActivity() {

    private data class PreviewParams(
        val surface: String,
        val night: Boolean,
        val font: Float,
    )

    private val params = mutableStateOf(PreviewParams("inbox", false, 1f))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        params.value = readParams(intent)
        setContent {
            val p = params.value
            val base = LocalConfiguration.current
            val config = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (p.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale = p.font
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, p.font),
            ) {
                when (p.surface.lowercase()) {
                    "focus" -> MiniProductionPreview(MiniTab.Focus, darkTheme = p.night)
                    "console" -> MiniProductionPreview(MiniTab.Console, darkTheme = p.night)
                    "activity" -> MiniActivityScreenPreview(darkTheme = p.night)
                    "thread" -> MiniThreadScreenPreview(darkTheme = p.night)
                    "thread-generating", "generating" -> MiniThreadGeneratingPreview(darkTheme = p.night)
                    "thread-manual", "manual" -> MiniThreadManualPreview(darkTheme = p.night)
                    "evidence-unavailable", "unavailable-evidence", "unavailable" -> MiniWhyThisDraftUnavailablePreview(darkTheme = p.night)
                    "context", "evidence" -> MiniWhyThisDraftPreview(darkTheme = p.night)
                    else -> MiniProductionPreview(MiniTab.Inbox, darkTheme = p.night)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        params.value = readParams(intent)
    }

    private fun readParams(intent: Intent?): PreviewParams {
        val night = intent?.getBooleanExtra(EXTRA_NIGHT, false) ?: false
        val font = intent?.getFloatExtra(EXTRA_FONT, 1f)?.coerceIn(0.85f, 2f) ?: 1f
        val surface = intent?.getStringExtra(EXTRA_SURFACE).orEmpty()
        android.util.Log.i("PreviewExport", "readParams: surface=$surface night=$night font=$font extras=${intent?.extras}")
        return PreviewParams(surface, night, font)
    }

    companion object {
        const val EXTRA_SURFACE = "surface"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_FONT = "font"
    }
}
