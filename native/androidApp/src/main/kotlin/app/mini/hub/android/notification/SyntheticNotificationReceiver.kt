package app.mini.hub.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * BroadcastReceiver for controlled synthetic notification tests on device.
 * Posts actual Android notifications through NotificationManager.
 */
class SyntheticNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_POST_SYNTHETIC) return

        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Synthetic Client"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Synthetic message body for testing"
        val notifId = intent.getIntExtra(EXTRA_ID, 1001)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mini_synthetic_test_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Synthetic Test Notifications",
                NotificationManager.IMPORTANCE_HIGH,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(sender)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify("synthetic_test", notifId, notification)
    }

    companion object {
        const val ACTION_POST_SYNTHETIC = "app.mini.hub.POST_SYNTHETIC_NOTIFICATION"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
