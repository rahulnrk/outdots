package app.mini.hub.android.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.mini.hub.android.MiniHubRuntime
import app.mini.hub.observation.NotificationRawPayload

/**
 * Android NotificationListenerService for non-blocking capture of authorized notifications.
 *
 * Requirements enforced:
 * 1. Non-blocking callback: pushes payload into bounded queue and dispatches background persistence.
 * 2. Headless startup: initializes runtime and persists events even if UI Activity is closed.
 * 3. Explicit permission gating: checks whether user enabled notification access.
 * 4. Lifecycle state: tracks listener connection/disconnection and supports rebind.
 * 5. Filters out self-notifications to avoid loops (except synthetic test notifications).
 */
class MiniNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

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

        /**
         * Requests the system to rebind this service if permission is granted but currently disconnected.
         */
        fun rebindIfNeeded(context: Context) {
            if (isPermissionGranted(context) && !isConnected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        requestRebind(ComponentName(context, MiniNotificationListenerService::class.java))
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MiniHubRuntime.init(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MiniHubRuntime.init(applicationContext)
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Filter out self-notifications, allowing only synthetic test harness notifications
        if (pkg == packageName && sbn.tag != "synthetic_test") return

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

        val mappedPackage = if (pkg == packageName && sbn.tag == "synthetic_test") "com.whatsapp" else pkg

        val payload = NotificationRawPayload(
            key = sbn.key ?: "${pkg}_${sbn.id}",
            packageId = mappedPackage,
            notificationId = sbn.id,
            tag = sbn.tag,
            postTimeMillis = sbn.postTime,
            senderHint = title ?: subText ?: mappedPackage,
            title = title,
            text = text.ifBlank { bigText ?: "" },
            subText = subText,
            textFragments = textFragments,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || isGroup,
            isOngoing = sbn.isOngoing,
            category = notification.category,
            isRemoved = false,
        )

        MiniHubRuntime.onNotificationReceived(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName && sbn.tag != "synthetic_test") return

        val mappedPackage = if (pkg == packageName && sbn.tag == "synthetic_test") "com.whatsapp" else pkg

        val payload = NotificationRawPayload(
            key = sbn.key ?: "${pkg}_${sbn.id}",
            packageId = mappedPackage,
            notificationId = sbn.id,
            tag = sbn.tag,
            postTimeMillis = sbn.postTime,
            senderHint = mappedPackage,
            title = null,
            text = "",
            isRemoved = true,
        )

        MiniHubRuntime.onNotificationReceived(payload)
    }
}
