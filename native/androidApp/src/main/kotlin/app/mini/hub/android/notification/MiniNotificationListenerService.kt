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
        // Logged once per connection, never per notification, so a broken store cannot spam logs.
        if (!MiniHubRuntime.isStorageReady) MiniHubRuntime.logState("work=notification_listener skipped storage=unavailable")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!MiniHubRuntime.isStorageReady) return
        val pkg = sbn.packageName ?: return

        // Filter out self-notifications, allowing only synthetic test harness notifications
        if (pkg == packageName && sbn.tag != "synthetic_test") return
        if (pkg != packageName && !MiniHubRuntime.isCommunicationSourcePackageSelected(pkg)) return

        // A Gmail notification hints that new mail arrived. Ask the connector to fetch it
        // authoritatively instead of relying only on notification text.
        if (pkg == "com.google.android.gm") {
            android.util.Log.i("MiniGmailSync", "gmail app notification observed; requesting sync")
            app.mini.hub.android.connector.GmailSyncJobService.requestSoon(applicationContext, "gmail_notification")
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() } ?: emptyList()
        val isGroupConversation = extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ?: false
        val messagingStyle = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val hasMessagingStyle = messagingStyle != null && messagingStyle.messages.isNotEmpty()
        // Carry caller-supplied correlation ID if present (set by SyntheticNotificationReceiver)
        val correlationId = extras?.getString("mini_correlation_id")

        val textFragments = buildList {
            if (bigText != null && bigText != text) add(bigText)
            addAll(lines)
            messagingStyle?.messages?.mapNotNull { it.text?.toString() }?.let { addAll(it) }
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
            text = text.ifBlank { bigText ?: messagingStyle?.messages?.lastOrNull()?.text?.toString() ?: "" },
            subText = subText,
            textFragments = textFragments.distinct(),
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isGroupConversation = isGroupConversation || messagingStyle?.isGroupConversation == true,
            hasMessagingStyle = hasMessagingStyle,
            isOngoing = sbn.isOngoing,
            category = notification.category,
            isRemoved = false,
            correlationId = correlationId,
        )

        val nowMs = System.currentTimeMillis()
        // Record under exactly one ledger key: correlationId when the caller embedded one
        // (synthetic test), or the OS notification key for organic notifications.
        // Never record the same operation under both keys -- that would inflate total counts.
        val listenerLedgerKey = correlationId ?: payload.key
        MiniHubRuntime.ledger.record(listenerLedgerKey, app.mini.hub.observation.PipelineStage.ListenerReceived, nowMs)
        MiniHubRuntime.onNotificationReceived(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!MiniHubRuntime.isStorageReady) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName && sbn.tag != "synthetic_test") return
        if (pkg != packageName && !MiniHubRuntime.isCommunicationSourcePackageSelected(pkg)) return

        // Android does not preserve notification extras in removal callbacks. Debug synthetic
        // cancellations bridge that limitation through a bounded in-process side-channel keyed
        // by package, tag, and notification ID. Organic removals simply have no correlation ID.
        val correlationId = SyntheticNotificationReceiver.takeCancellationCorrelationId(
            packageName = pkg,
            tag = sbn.tag,
            notificationId = sbn.id,
        )

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
            correlationId = correlationId,
        )

        MiniHubRuntime.ledger.record(
            correlationId ?: payload.key,
            app.mini.hub.observation.PipelineStage.ListenerReceived,
            System.currentTimeMillis(),
        )
        MiniHubRuntime.onNotificationReceived(payload)
    }
}
