package app.mini.hub.android.ui

import android.content.Context
import android.content.Intent
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxCommand

/**
 * Android implementation of [HandoffLauncher].
 * Opens the provider's own app with the reviewed text when it is installed and accepts a shared
 * draft, otherwise the system share chooser.
 *
 * Truthfulness guarantee: this records [OutboxState.HandoffOpened], NEVER claiming
 * that a message was sent or delivered by the external provider.
 */
class AndroidNativeHandoffLauncher(
    private val context: Context,
    private val targetPackage: String? = null,
) : HandoffLauncher {

    /** Same launcher aimed at one app package (for example "com.Slack"). */
    fun forPackage(packageId: String?): AndroidNativeHandoffLauncher =
        AndroidNativeHandoffLauncher(context, packageId)

    /** Opens the installed provider app. Does not send or prefill a message. */
    fun openSourceApp(packageId: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageId)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }

    override fun open(command: OutboxCommand, payload: ExactActionPayload): Boolean {
        return try {
            if (isEmailPayload(payload)) {
                return openEmailHandoff(payload)
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload.body)
                if (payload.subject != null) {
                    putExtra(Intent.EXTRA_SUBJECT, payload.subject)
                }
                if (payload.recipients.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EMAIL, payload.recipients.toTypedArray())
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val targeted = targetPackage?.let { Intent(sendIntent).setPackage(it) }
            if (targeted != null && targeted.resolveActivity(context.packageManager) != null) {
                context.startActivity(targeted)
            } else {
                context.startActivity(Intent.createChooser(sendIntent, "Share draft via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Email handoff uses mailto with the requested provider package when that package is known.
     * It never forces Gmail for Outlook or unknown mail accounts. Opening the app does not prove
     * the sending identity or thread inside that app.
     */
    private fun openEmailHandoff(payload: ExactActionPayload): Boolean {
        val compose = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, payload.recipients.toTypedArray())
            if (payload.cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, payload.cc.toTypedArray())
            if (payload.bcc.isNotEmpty()) putExtra(Intent.EXTRA_BCC, payload.bcc.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, payload.subject)
            putExtra(Intent.EXTRA_TEXT, payload.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val preferred = targetPackage?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            val targeted = Intent(compose).setPackage(preferred)
            if (targeted.resolveActivity(context.packageManager) != null) {
                context.startActivity(targeted)
                return true
            }
        }
        if (compose.resolveActivity(context.packageManager) != null) {
            context.startActivity(compose)
            return true
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, payload.recipients.toTypedArray())
            if (payload.cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, payload.cc.toTypedArray())
            if (payload.bcc.isNotEmpty()) putExtra(Intent.EXTRA_BCC, payload.bcc.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, payload.subject)
            putExtra(Intent.EXTRA_TEXT, payload.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targetedSend = preferred?.let { Intent(send).setPackage(it) }
        if (targetedSend != null && targetedSend.resolveActivity(context.packageManager) != null) {
            context.startActivity(targetedSend)
            return true
        }
        context.startActivity(Intent.createChooser(send, "Open draft in mail app").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun isEmailPayload(payload: ExactActionPayload): Boolean =
        payload.subject != null && payload.recipients.isNotEmpty() && payload.recipients.all { it.contains('@') }
}
