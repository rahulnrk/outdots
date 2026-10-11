package app.mini.hub.android.intelligence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mini.hub.android.MiniHubRuntime

/**
 * Debug-only controls for on-device verification of the local AI lifecycle. Registered only in
 * the debug manifest. Never reads, logs, or returns message content.
 *
 * - FAULT: arm N real engine failures (native generation starts, then times out after timeout_ms).
 * - SIMULATE_PRESSURE: call the same onMemoryPressure path as onTrimMemory. Logged as SIMULATED.
 * - STATUS: log runtime flags.
 */
class LocalAiDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MiniHubRuntime.init(context.applicationContext)
        if (!MiniHubRuntime.isStorageReady) return
        val experiment = MiniHubRuntime.localDraftExperiment
        when (intent?.action) {
            ACTION_FAULT -> {
                val count = intent.getIntExtra(EXTRA_COUNT, 3).coerceIn(1, 10)
                val timeout = intent.getLongExtra(EXTRA_TIMEOUT_MILLIS, 300L).coerceIn(1L, 60_000L)
                experiment.injectTimeoutFailures(count, timeout)
            }
            ACTION_SIMULATE_PRESSURE -> {
                LocalAiTrace.log("SIMULATED_MEMORY_PRESSURE source=debug-broadcast")
                val ticket = experiment.onMemoryPressure()
                LocalAiTrace.log("SIMULATED_MEMORY_PRESSURE queuedRelease=${ticket.id}")
            }
            ACTION_STATUS -> {
                val last = experiment.lastRelease
                LocalAiTrace.log(
                    "STATUS needsRecovery=${experiment.needsRecovery} " +
                        "lastRelease=${last?.id}:${last?.status?.label}:${last?.reason}",
                )
            }
        }
    }

    companion object {
        const val ACTION_FAULT = "app.mini.hub.debug.LOCAL_AI_FAULT"
        const val ACTION_SIMULATE_PRESSURE = "app.mini.hub.debug.LOCAL_AI_SIMULATE_PRESSURE"
        const val ACTION_STATUS = "app.mini.hub.debug.LOCAL_AI_STATUS"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TIMEOUT_MILLIS = "timeout_ms"
    }
}
