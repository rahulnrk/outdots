package app.mini.hub.android.connector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mini.hub.connector.ReleaseGate
import app.mini.hub.domain.Provider

/**
 * Debug-only. Lets the project owner record or clear qualification evidence after a real test:
 *   adb shell am broadcast -n app.mini.hub/.android.connector.GateEvidenceDebugReceiver \
 *     -a app.mini.hub.debug.RECORD_GATE --es provider gmail --es gate SendQualification \
 *     --es evidence "Reviewed send to owner test inbox verified 2026-10-12"
 */
class GateEvidenceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = intent.getStringExtra("provider")?.let(Provider::fromWire)
        val gate = intent.getStringExtra("gate")?.let { runCatching { ReleaseGate.valueOf(it) }.getOrNull() }
        if (provider == null || provider == Provider.APP || gate == null) {
            Log.w(GateEvidenceStore.LOG_TAG, "ignored: provider and gate are required")
            return
        }
        val store = GateEvidenceStore(context)
        when (intent.action) {
            ACTION_RECORD -> intent.getStringExtra("evidence")?.takeIf { it.isNotBlank() }
                ?.let { runCatching { store.record(provider, gate, it) }.onFailure { e -> Log.w(GateEvidenceStore.LOG_TAG, "rejected: ${e.message}") } }
                ?: Log.w(GateEvidenceStore.LOG_TAG, "ignored: evidence text is required")
            ACTION_CLEAR -> store.clear(provider, gate)
        }
    }

    companion object {
        const val ACTION_RECORD = "app.mini.hub.debug.RECORD_GATE"
        const val ACTION_CLEAR = "app.mini.hub.debug.CLEAR_GATE"
    }
}
