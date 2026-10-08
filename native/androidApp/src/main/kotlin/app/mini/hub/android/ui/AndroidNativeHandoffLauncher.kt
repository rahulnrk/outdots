package app.mini.hub.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.HandoffLauncher
import app.mini.hub.action.OutboxCommand

/**
 * Android implementation of [HandoffLauncher].
 * Safely opens native application intent (e.g. WhatsApp or default mail composer).
 *
 * Truthfulness guarantee: this records [OutboxState.HandoffOpened], NEVER claiming
 * that a message was sent or delivered by the external provider.
 */
class AndroidNativeHandoffLauncher(
    private val context: Context,
) : HandoffLauncher {
    override fun open(command: OutboxCommand, payload: ExactActionPayload): Boolean {
        return try {
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
            context.startActivity(Intent.createChooser(sendIntent, "Share draft via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Throwable) {
            false
        }
    }
}
