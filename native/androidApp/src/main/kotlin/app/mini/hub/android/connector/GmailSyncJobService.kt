package app.mini.hub.android.connector

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.android.MiniHubRuntime
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Background Gmail read sync. Runs without the UI and with the screen off, subject to Android's
 * scheduling (Doze, App Standby buckets, battery restrictions). Logs carry only counts and states.
 *
 * This only stores new messages in MINI. It does not post OS notifications.
 */
class GmailSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        val trigger = params.extras.getString(EXTRA_TRIGGER) ?: if (params.jobId == PERIODIC_JOB_ID) "periodic" else "oneshot"
        executor.execute {
            try {
                MiniHubRuntime.init(applicationContext)
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                Log.i(LOG_TAG, "job start trigger=$trigger screenInteractive=${power.isInteractive} idle=${power.isDeviceIdleMode}")
                runSync(applicationContext, trigger)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "job failed trigger=$trigger error=${e.javaClass.simpleName}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val LOG_TAG = "MiniGmailSync"
        const val PERIODIC_JOB_ID = 41_001
        const val ONESHOT_JOB_ID = 41_002
        const val EXTRA_TRIGGER = "trigger"

        fun runSync(context: Context, trigger: String): List<GmailSyncReport> {
            MiniHubRuntime.init(context)
            val connector = AndroidGmailConnector(context, MiniHubRuntime.observationStore, sha256())
            return connector.syncAllConnected(System.currentTimeMillis(), trigger)
        }

        /** Periodic background sync. Android enforces a 15 minute minimum interval. */
        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) != null) return
            val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, GmailSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(false)
                .build()
            val result = runCatching { scheduler.schedule(job) }
                .onFailure { Log.w(LOG_TAG, "periodic job schedule failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            Log.i(LOG_TAG, "periodic job scheduled result=$result")
        }

        /** Requests a prompt sync, for example when a Gmail notification is observed. */
        fun requestSoon(context: Context, trigger: String) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val extras = android.os.PersistableBundle().apply { putString(EXTRA_TRIGGER, trigger) }
            val job = JobInfo.Builder(ONESHOT_JOB_ID, ComponentName(context, GmailSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(10_000L)
                .setExtras(extras)
                .build()
            // Never let a scheduling failure crash the notification listener.
            runCatching { scheduler.schedule(job) }
                .onFailure { Log.w(LOG_TAG, "oneshot job schedule failed: ${it.javaClass.simpleName}") }
        }

        private fun sha256() = DigestProvider { payload ->
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
