package app.mini.hub.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.mini.hub.platformName

/**
 * Minimal host Activity. The product UI lives in `app.mini.hub.android.ui` (owned by Agent D).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Suppress app-switcher previews of private content (architecture section 11).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Mini Hub shell (${platformName()}). Not a functional product build.")
                }
            }
        }
    }
}
