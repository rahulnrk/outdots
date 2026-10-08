package app.mini.hub.android.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.mini.hub.observation.BoundedIngestQueue
import app.mini.hub.observation.NotificationRawPayload

/**
 * Android NotificationListenerService for non-blocking capture of authorized notifications.
 *
 * Requirements enforced:
 * 1. Non-blocking callback: pushes payload into bounded queue and returns immediately.
 * 2. Explicit permission gating: checks whether user enabled notification access.
 * 3. Lifecycle state: tracks listener connection/disconnection.
 * 4. Filters out self-notifications to avoid loops.
 */
class MiniNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var sharedQueue: BoundedIngestQueue<NotificationRawPayload>? = null

        /**
         * Checks if the user has explicitly granted notification listener access to this app.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            if (enabledListeners.contains(context.packageName)) return true

            // Fallback check on secure settings
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
            return flat?.contains(context.packageName) == true
        }

        /**
         * Returns an Intent to open Android Notification Access settings.
         */
        fun createSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // Ignore self-notifications

        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() } ?: emptyList()
        val isGroup = extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ?: false

        val textFragments = buildList {
            if (bigText != null && bigText != text) add(bigText)
            addAll(lines)
            if (isEmpty() && text.isNotBlank()) add(text)
        }

        val payload = NotificationRawPayload(
            key = sbn.key ?: "${pkg}_${sbn.id}",
            packageId = pkg,
            notificationId = sbn.id,
            tag = sbn.tag,
            postTimeMillis = sbn.postTime,
            senderHint = title ?: subText ?: pkg,
            title = title,
            text = text.ifBlank { bigText ?: "" },
            subText = subText,
            textFragments = textFragments,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || isGroup,
            isOngoing = sbn.isOngoing,
            category = notification.category,
            isRemoved = false,
        )

        sharedQueue?.offer(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val payload = NotificationRawPayload(
            key = sbn.key ?: "${pkg}_${sbn.id}",
            packageId = pkg,
            notificationId = sbn.id,
            tag = sbn.tag,
            postTimeMillis = sbn.postTime,
            senderHint = pkg,
            title = null,
            text = "",
            isRemoved = true,
        )

        sharedQueue?.offer(payload)
    }
}
