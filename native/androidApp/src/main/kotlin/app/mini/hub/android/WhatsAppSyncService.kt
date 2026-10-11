package app.mini.hub.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone-local WhatsApp companion connected while MINI is closed and stores new
 * messages in MINI. Shows a quiet, persistent notification as Android requires.
 */
class WhatsAppSyncService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        MiniHubRuntime.init(applicationContext)
        if (!MiniHubRuntime.isStorageReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (task == null) {
            task = worker.scheduleWithFixedDelay({
                runCatching { MiniHubRuntime.connectors.ingestWhatsApp() }
                    .onSuccess { n -> if (n > 0) MiniHubRuntime.notifyInboxChanged() }
                    .onFailure { Log.w(TAG, "ingest failed: ${it.javaClass.simpleName}") }
            }, 0, 5, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        task?.cancel(false)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "WhatsApp link", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("MINI is connected to WhatsApp")
            .setContentText("Messages are saved on this phone.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "MiniWaService"
        private const val CHANNEL = "mini_whatsapp_link"
        private const val NOTIF_ID = 42_001
        const val ACTION_STOP = "app.mini.hub.WA_STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WhatsAppSyncService::class.java)) }
                .onFailure { Log.w(TAG, "start failed: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WhatsAppSyncService::class.java))
        }
    }
}
