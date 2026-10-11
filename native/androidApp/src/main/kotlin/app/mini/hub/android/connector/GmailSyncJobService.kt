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
import app.mini.hub.android.RuntimeStorage
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Background read sync for every connected account (Gmail incremental history plus Outlook,
 * Teams, Slack, Google Chat, Telegram bot and X). Runs without the UI and with the screen off,
 * subject to Android's scheduling (Doze, App Standby buckets, battery restrictions). Logs carry
 * only counts and states.
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
                if (!RuntimeStorage.runIfReady(MiniHubRuntime.storage, "sync_job", { Log.w(LOG_TAG, it) }) {
                    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                    Log.i(LOG_TAG, "job start trigger=$trigger screenInteractive=${power.isInteractive} idle=${power.isDeviceIdleMode}")
                    val summary = runAll(applicationContext, trigger)
                    val followUps = params.extras.getInt(EXTRA_FOLLOW_UP_DEPTH, 0)
                    if (shouldRequestFollowUp(summary.moreAvailable, followUps)) {
                        Log.i(LOG_TAG, "more history available; requesting follow-up depth=${followUps + 1}")
                        requestSoon(applicationContext, TRIGGER_FOLLOW_UP, followUps + 1)
                    }
                }) return@execute
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
        const val EXTRA_FOLLOW_UP_DEPTH = "follow_up_depth"
        const val TRIGGER_FOLLOW_UP = "more_available"
        /** Bounds a chain of prompt follow-ups; after this the periodic job continues the backlog. */
        const val MAX_FOLLOW_UPS = 20

        /** Pure decision: request a prompt pass when history remains, up to [MAX_FOLLOW_UPS] in a row. */
        fun shouldRequestFollowUp(moreAvailable: Boolean, followUpsSoFar: Int): Boolean =
            moreAvailable && followUpsSoFar < MAX_FOLLOW_UPS

        fun runSync(context: Context, trigger: String): List<GmailSyncReport> {
            MiniHubRuntime.init(context)
            val connector = AndroidGmailConnector(context, MiniHubRuntime.observationStore, sha256())
            return connector.syncAllConnected(System.currentTimeMillis(), trigger)
        }

        /** Every connected provider account. One account's failure never affects another. */
        fun runAll(context: Context, trigger: String): AndroidConnectorRegistry.SyncSummary {
            MiniHubRuntime.init(context)
            return MiniHubRuntime.connectors.syncAll(trigger)
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
        fun requestSoon(context: Context, trigger: String, followUpDepth: Int = 0) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val extras = android.os.PersistableBundle().apply {
                putString(EXTRA_TRIGGER, trigger)
                putInt(EXTRA_FOLLOW_UP_DEPTH, followUpDepth)
            }
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
