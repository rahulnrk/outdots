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
}
