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
import java.util.UUID

/**
 * BroadcastReceiver for controlled synthetic notification tests on device.
 * Posts actual Android notifications through NotificationManager.
 *
 * Correlation ID threading:
 *   Each broadcast generates a UUID stored in the `mini_correlation_id` notification extra.
 *   The NotificationListenerService reads that extra and passes it as
 *   NotificationRawPayload.correlationId so every pipeline stage is recorded under the same
 *   ID that SourcePublished used -- eliminating the OS-key vs synthetic-key mismatch.
 *
 * Operation types:
 *   ACTION_POST_SYNTHETIC   — post a new notification or update an existing one (distinct content)
 *   ACTION_CANCEL_SYNTHETIC — cancel (remove) a previously posted notification;
 *                             records SourcePublished under a removal correlationId so the
 *                             listener's onNotificationRemoved callback can be correlated
 *   ACTION_REPOST_SYNTHETIC — repost the same notification id with identical content;
 *                             the listener evaluates the actual Android post timestamp. A newer
 *                             timestamp is an Update; only an identical timestamp is a Drop.
 *   ACTION_QUERY_ACCOUNTING — emit ACCOUNTING_SUMMARY to logcat
 */
class SyntheticNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MiniHubRuntime.init(context.applicationContext)
        if (!MiniHubRuntime.isStorageReady) return

        when (intent?.action) {
            ACTION_QUERY_ACCOUNTING -> {
                val report = MiniHubRuntime.ledger.generateReport()
                val stageCounts = report.countsByStage
                    .filterValues { it > 0 }
                    .entries
                    .joinToString(",") { "${it.key}=${it.value}" }
                Log.i(
                    "MiniHubAccounting",
                    "ACCOUNTING_SUMMARY: total=${report.totalTrackedEvents}, delivered=${report.fullyDeliveredCount}, dropped=${report.droppedCount}, stages=$stageCounts, p50=${report.p50LatencyMillis}ms, p95=${report.p95LatencyMillis}ms, p99=${report.p99LatencyMillis}ms",
                )
            }

            ACTION_DUMP_SYNTHETIC_STATE -> {
                val notifId = readNotificationId(intent, -1)
                val observation = if (notifId >= 0) {
                    MiniHubRuntime.observationStore.getObservationByNotificationIdIncludingRemoved(
                        context.packageName,
                        notifId,
                    )
                } else {
                    null
                }
                if (observation == null) {
                    Log.i("MiniHubSynthetic", "STATE id=$notifId present=false")
                } else {
                    Log.i(
                        "MiniHubSynthetic",
                        "STATE id=$notifId present=true key=${observation.notificationKey} revision=${observation.revision} removed=${observation.isRemoved} postTime=${observation.postTime.epochMillis} text=${observation.textFragments.joinToString("|")}",
                    )
                }
            }

            ACTION_SET_ATTENTION -> {
                val actionType = intent.getStringExtra("action_type") ?: ""
                val convKey = intent.getStringExtra("conv_key") ?: return
                val parsedConvId = app.mini.hub.domain.ScopedId.parse(convKey)
                
                // Security boundary: Never modify real conversation records via adb broadcasts
                if (!parsedConvId.account.localId.contains("dev_test") && !parsedConvId.account.localId.contains("synthetic")) {
                    Log.w("MiniHubAttention", "Rejecting debug SET_ATTENTION for non-synthetic account: $convKey")
                    return
                }
                
                val action = when (actionType) {
                    "priority_important" -> app.mini.hub.attention.AttentionAction.SetPriority(app.mini.hub.attention.ConversationPriority.Important)
                    "priority_normal" -> app.mini.hub.attention.AttentionAction.SetPriority(app.mini.hub.attention.ConversationPriority.Normal)
                    "needs_attention" -> app.mini.hub.attention.AttentionAction.MarkNeedsAttention
                    "waiting" -> app.mini.hub.attention.AttentionAction.MarkWaiting
                    "done" -> app.mini.hub.attention.AttentionAction.MarkDone
                    "snooze" -> app.mini.hub.attention.AttentionAction.Snooze(app.mini.hub.domain.UtcInstant(System.currentTimeMillis() + 4 * 3600 * 1000L))
                    else -> return
                }
                
                // Get existing
                val loadRes = MiniHubRuntime.attentionStore.loadAll()
                if (loadRes is app.mini.hub.attention.AttentionStoreResult.Ok) {
                    val existing = loadRes.value[convKey] ?: app.mini.hub.attention.AttentionRules.default(parsedConvId, app.mini.hub.domain.UtcInstant(System.currentTimeMillis()))
                    val decision = app.mini.hub.attention.AttentionRules.apply(existing, action, null, app.mini.hub.domain.UtcInstant(System.currentTimeMillis()))
                    if (decision is app.mini.hub.attention.AttentionDecision.Applied) {
                        MiniHubRuntime.attentionStore.saveAll(listOf(decision.record))
                        Log.i("MiniHubAttention", "Applied action $actionType to $convKey. Resulting state: ${decision.record.status.name} / ${decision.record.priority.name}")
                    } else {
                        Log.i("MiniHubAttention", "Rejected action $actionType to $convKey: ${(decision as app.mini.hub.attention.AttentionDecision.Rejected).reason}")
                    }
                } else {
                    Log.i("MiniHubAttention", "Failed to load store: ${(loadRes as app.mini.hub.attention.AttentionStoreResult.Failed).message}")
                }
            }

            ACTION_DUMP_ATTENTION -> {
                val loadRes = MiniHubRuntime.attentionStore.loadAll()
                if (loadRes is app.mini.hub.attention.AttentionStoreResult.Ok) {
                    val syntheticRecords = loadRes.value.filterKeys { 
                        it.contains("dev_test") || it.contains("synthetic") 
                    }
                    Log.i("MiniHubAttention", "DUMP: Found ${syntheticRecords.size} synthetic records")
                    for ((k, v) in syntheticRecords) {
                        Log.i("MiniHubAttention", "RECORD: $k -> status=${v.status.name}, priority=${v.priority.name}, snoozedUntil=${v.snoozedUntil?.epochMillis}, resurfacedFrom=${v.resurfacedFrom?.name}")
                    }
                } else {
                    Log.i("MiniHubAttention", "DUMP Failed: ${(loadRes as app.mini.hub.attention.AttentionStoreResult.Failed).message}")
                }
            }

            ACTION_TRIGGER_UNBIND -> {
                val ok = MiniNotificationListenerService.requestUnbindForDebug()
                Log.i("MiniHubSynthetic", "TRIGGER_UNBIND requested result=$ok")
            }

            ACTION_TRIGGER_REBIND -> {
                val ok = MiniNotificationListenerService.rebindIfNeeded(context)
                Log.i("MiniHubSynthetic", "TRIGGER_REBIND requested result=$ok")
            }

            ACTION_CANCEL_SYNTHETIC -> {
                val notifId = readNotificationId(intent, 1001)
                // Generate a cancellation correlationId so the removal can be traced end-to-end.
                // The NotificationListenerService cannot recover this ID from onNotificationRemoved
                // because Android does not preserve extras there; however SourcePublished is still
                // recorded here so the operation appears in accounting as a discrete event.
                val correlationId = "corr_cancel_${notifId}_${UUID.randomUUID()}"
                MiniHubRuntime.ledger.record(
                    correlationId,
                    app.mini.hub.observation.PipelineStage.SourcePublished,
                    System.currentTimeMillis(),
                    // Details distinguish a cancel from an organic removal.
                )
                rememberCancellationCorrelationId(
                    packageName = context.packageName,
                    tag = SYNTHETIC_TAG,
                    notificationId = notifId,
                    correlationId = correlationId,
                )
                Log.d("MiniHubSynthetic", "CANCEL notifId=$notifId correlationId=$correlationId")

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(SYNTHETIC_TAG, notifId)
            }

            ACTION_POST_SYNTHETIC, ACTION_REPOST_SYNTHETIC -> {
                val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Synthetic Client"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "Synthetic message body for testing"
                val notifId = readNotificationId(intent, 1001)
                val isRepost = intent.action == ACTION_REPOST_SYNTHETIC

                // Unique correlation ID for this specific broadcast operation.
                // Repost uses a distinct UUID so accounting records a separate SourcePublished
                // event. The listener uses sbn.postTime, not this action name, to classify it.
                val correlationId = if (isRepost) {
                    "corr_repost_${notifId}_${UUID.randomUUID()}"
                } else {
                    "corr_${notifId}_${UUID.randomUUID()}"
                }

                // Record SourcePublished under the correlation ID.
                MiniHubRuntime.ledger.record(
                    correlationId,
                    app.mini.hub.observation.PipelineStage.SourcePublished,
                    System.currentTimeMillis(),
                )

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
                
                if (intent.getBooleanExtra("direct", false)) {
                    val payload = app.mini.hub.observation.NotificationRawPayload(
                        key = "${context.packageName}_$notifId",
                        packageId = context.packageName,
                        notificationId = notifId,
                        tag = SYNTHETIC_TAG,
                        // Optional fixed post time replays an exact duplicate (same id, text, time).
                        postTimeMillis = intent.getLongExtra("post_time", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
                        senderHint = sender,
                        text = text,
                        correlationId = correlationId,
                    )
                    MiniHubRuntime.onNotificationReceived(payload)
                    Log.d("MiniHubSynthetic", "DIRECT POST notifId=$notifId correlationId=$correlationId")
                } else {
                    val notification = NotificationCompat.Builder(context, channelId)
                        .setContentTitle(sender)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        // Embed correlation ID so the listener can carry it through the pipeline.
                        .addExtras(android.os.Bundle().apply {
                            putString("mini_correlation_id", correlationId)
                        })
                        .build()
    
                    notificationManager.notify(SYNTHETIC_TAG, notifId, notification)
                    Log.d(
                        "MiniHubSynthetic",
                        "POST notifId=$notifId repost=$isRepost correlationId=$correlationId requestedAt=${System.currentTimeMillis()}",
                    )
                }
            }
        }
    }

    companion object {
        private const val SYNTHETIC_TAG = "synthetic_test"
        private const val MAX_PENDING_CANCELLATIONS = 64
        private val pendingCancellationCorrelationIds = LinkedHashMap<String, String>()

        const val ACTION_POST_SYNTHETIC = "app.mini.hub.POST_SYNTHETIC_NOTIFICATION"
        /** Cancel (remove) a previously posted synthetic notification. */
        const val ACTION_CANCEL_SYNTHETIC = "app.mini.hub.CANCEL_SYNTHETIC_NOTIFICATION"
        /**
         * Repost the same notification id with identical content.
         * Pipeline classifies this from the actual notification timestamp. Same timestamp means
         * DeduplicatedDrop; a newer timestamp means Update, even when content is identical.
         * Use ACTION_POST_SYNTHETIC with different text to post a genuine update.
         */
        const val ACTION_REPOST_SYNTHETIC = "app.mini.hub.REPOST_SYNTHETIC_NOTIFICATION"
        const val ACTION_QUERY_ACCOUNTING = "app.mini.hub.QUERY_DELIVERY_ACCOUNTING"
        /** Debug-only readback of synthetic content, revision, and removal state. */
        const val ACTION_DUMP_SYNTHETIC_STATE = "app.mini.hub.DUMP_SYNTHETIC_STATE"
        const val ACTION_SET_ATTENTION = "app.mini.hub.SET_ATTENTION"
        const val ACTION_DUMP_ATTENTION = "app.mini.hub.DUMP_ATTENTION"
        const val ACTION_TRIGGER_UNBIND = "app.mini.hub.TRIGGER_UNBIND"
        const val ACTION_TRIGGER_REBIND = "app.mini.hub.TRIGGER_REBIND"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        private fun readNotificationId(intent: Intent, defaultValue: Int): Int {
            val directValue = intent.getIntExtra(EXTRA_ID, Int.MIN_VALUE)
            if (directValue != Int.MIN_VALUE) return directValue

            val alternateValue = intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
            if (alternateValue != Int.MIN_VALUE) return alternateValue

            return intent.getStringExtra(EXTRA_ID)?.toIntOrNull()
                ?: intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.toIntOrNull()
                ?: defaultValue
        }
        @Synchronized
        fun rememberCancellationCorrelationId(
            packageName: String,
            tag: String?,
            notificationId: Int,
            correlationId: String,
        ) {
            val key = removalKey(packageName, tag, notificationId)
            pendingCancellationCorrelationIds.remove(key)
            pendingCancellationCorrelationIds[key] = correlationId
            while (pendingCancellationCorrelationIds.size > MAX_PENDING_CANCELLATIONS) {
                pendingCancellationCorrelationIds.entries.firstOrNull()?.let {
                    pendingCancellationCorrelationIds.remove(it.key)
                }
            }
        }

        @Synchronized
        fun takeCancellationCorrelationId(
            packageName: String,
            tag: String?,
            notificationId: Int,
        ): String? = pendingCancellationCorrelationIds.remove(removalKey(packageName, tag, notificationId))

        private fun removalKey(packageName: String, tag: String?, notificationId: Int): String =
            "$packageName|${tag ?: "<null>"}|$notificationId"
    }
}
