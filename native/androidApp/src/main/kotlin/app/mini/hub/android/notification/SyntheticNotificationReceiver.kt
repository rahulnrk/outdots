package app.mini.hub.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import app.mini.hub.android.MiniHubRuntime

/**
 * BroadcastReceiver for controlled synthetic notification tests on device.
 * Posts actual Android notifications through NotificationManager.
 */
class SyntheticNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MiniHubRuntime.init(context.applicationContext)

        if (intent?.action == ACTION_QUERY_ACCOUNTING) {
            val report = MiniHubRuntime.ledger.generateReport()
            Log.i(
                "MiniHubAccounting",
                "ACCOUNTING_SUMMARY: total=${report.totalTrackedEvents}, delivered=${report.fullyDeliveredCount}, dropped=${report.droppedCount}, p50=${report.p50LatencyMillis}ms, p95=${report.p95LatencyMillis}ms, p99=${report.p99LatencyMillis}ms",
            )
            return
        }

        if (intent?.action != ACTION_POST_SYNTHETIC) return

        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Synthetic Client"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Synthetic message body for testing"
        val notifId = intent.getIntExtra(EXTRA_ID, 1001)

        val key = "app.mini.hub_${notifId}_synthetic_test"
        MiniHubRuntime.ledger.record(key, app.mini.hub.observation.PipelineStage.SourcePublished, System.currentTimeMillis())

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
        const val ACTION_QUERY_ACCOUNTING = "app.mini.hub.QUERY_DELIVERY_ACCOUNTING"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
