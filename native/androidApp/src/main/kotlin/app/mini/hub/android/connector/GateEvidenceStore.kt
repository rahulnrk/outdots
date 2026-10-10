package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.connector.GateEvidence
import app.mini.hub.connector.GatePolicy
import app.mini.hub.connector.ReleaseGate
import app.mini.hub.domain.Provider

/**
 * Owner-recorded qualification evidence (for example "send verified live with test account on
 * 2026-10-12"). Nothing in the app writes here automatically: entries come only from the debug
 * receiver run by the project owner after a real test, so fixtures can never unlock sending.
 */
class GateEvidenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mini_gate_evidence", Context.MODE_PRIVATE)

    fun evidenceFor(provider: Provider): List<GateEvidence> =
        prefs.getStringSet(provider.wire, emptySet()).orEmpty().mapNotNull { raw ->
            val gate = runCatching { ReleaseGate.valueOf(raw.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
            val evidence = raw.substringAfter('|', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GateEvidence(gate, evidence)
        }

    fun record(provider: Provider, gate: ReleaseGate, evidence: String) {
        require(gate in GatePolicy.QUALIFICATION_GATES) { "$gate is cleared by configuration, not by recorded evidence" }
        require(evidence.isNotBlank() && '|' !in gate.name)
        val next = prefs.getStringSet(provider.wire, emptySet()).orEmpty().filterNot { it.startsWith("${gate.name}|") }.toSet() +
            "${gate.name}|${evidence.trim()}"
        prefs.edit().putStringSet(provider.wire, next).commit()
        Log.i(LOG_TAG, "gate recorded provider=${provider.wire} gate=$gate")
    }

    fun clear(provider: Provider, gate: ReleaseGate) {
        val next = prefs.getStringSet(provider.wire, emptySet()).orEmpty().filterNot { it.startsWith("${gate.name}|") }.toSet()
        prefs.edit().putStringSet(provider.wire, next).commit()
        Log.i(LOG_TAG, "gate cleared provider=${provider.wire} gate=$gate")
    }

    companion object { const val LOG_TAG = "MiniGates" }
}
