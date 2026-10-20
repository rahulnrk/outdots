package app.mini.hub.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mini.hub.android.BuildConfig

/**
 * Read-only "Third-party notices" screen. Shows the components bundled in this build and
 * the exact license/NOTICE texts shipped in assets/licenses. It deliberately does not state
 * a licence for MINI's own code, offer source, or imply any upstream exception.
 */
class ThirdPartyNoticesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiniTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ThirdPartyNoticesScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
internal fun ThirdPartyNoticesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val index = remember { ThirdPartyNoticeIndex.load(context) }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val open = index?.components?.firstOrNull { it.name == openName }

    BackHandler(enabled = open != null) { openName = null }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { if (open != null) openName = null else onClose() }) {
                Text(if (open != null) "Back" else "Close")
            }
        }
        when {
            index == null -> NoticeMessage(
                "Third-party notices are not included in this build. Run native/compliance/build-notices.ps1 before building.",
            )
            open != null -> NoticeDetail(open)
            else -> NoticeList(index, query, onQuery = { query = it }, onOpen = { openName = it.name })
        }
    }
}

@Composable
private fun NoticeMessage(text: String) {
    Text(text, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NoticeList(
    index: ThirdPartyNoticeIndex,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (ThirdPartyNotice) -> Unit,
) {
    val q = query.trim().lowercase()
    val groups = index.grouped().map { (title, items) ->
        title to if (q.isEmpty()) items else items.filter {
            it.name.lowercase().contains(q) || it.license.lowercase().contains(q)
        }
    }.filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text(
                "Third-party notices",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                index.disclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Build ${BuildConfig.VERSION_NAME} · source ${index.gitCommit.take(12)} · ${index.components.size} components",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (index.unknownLicense.isNotEmpty() || index.missingText.isNotEmpty()) {
                Text(
                    "Incomplete: ${index.unknownLicense.size} unknown licence(s), ${index.missingText.size} missing text(s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Search by name or licence") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        groups.forEach { (title, items) ->
            item(key = "h_$title") {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            items(items, key = { "c_${it.name}" }) { n ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(n) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(n.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${n.license} · ${n.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun NoticeDetail(n: ThirdPartyNotice) {
    val context = LocalContext.current
    val texts = remember(n.name) {
        n.textPaths.map { p -> p to (ThirdPartyNoticeIndex.readText(context, p) ?: "[missing text: $p]") }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(n.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("Version: ${n.version}", style = MaterialTheme.typography.bodySmall)
        Text("Licence: ${n.license}", style = MaterialTheme.typography.bodySmall)
        Text("Included in: ${n.component}", style = MaterialTheme.typography.bodySmall)
        n.notes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        texts.forEach { (_, body) ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline)
            SelectionContainer {
                Text(body, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        Text("", modifier = Modifier.padding(bottom = 32.dp))
    }
}
