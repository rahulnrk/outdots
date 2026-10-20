package app.mini.hub.android.connector

import android.content.Context
import app.mini.hub.connector.MessageDirection
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.persistence.SqlCipherObservationStore
import app.mini.hub.persistence.PersistResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val INSTAGRAM_SOURCE = "instagram.read"
private const val INSTAGRAM_PACKAGE = "com.instagram.android"

class InstagramReadIngestor(
    context: Context,
    store: ObservationStore = SqlCipherObservationStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val store: ObservationStore = store
    private val syncLock = Any()

    sealed interface Result {
        data class Success(
            val view: InstagramReadView,
            val persistedCount: Int,
            val partial: Boolean,
        ) : Result
        data class Failed(val code: String) : Result
    }

    fun sync(accountId: AccountId, maxThreads: Int = 20, maxMessages: Int = 20): Result {
        return synchronized(syncLock) {
            if (accountId.provider != Provider.INSTAGRAM) return@synchronized Result.Failed("account_provider_mismatch")
            when (val read = InstagramSessionRestore(appContext).readRecent(accountId, maxThreads, maxMessages)) {
                is InstagramSessionRestore.Result.ReadJson -> ingest(accountId, read.json)
                is InstagramSessionRestore.Result.NoSession -> Result.Failed("session_expired")
                is InstagramSessionRestore.Result.Failed -> Result.Failed(read.reason)
                is InstagramSessionRestore.Result.Verified -> Result.Failed("read_unavailable")
            }
        }
    }

    fun loadPersisted(accountId: AccountId): InstagramReadView {
        val conversations = store.getConversationSummaries(accountId).map { summary ->
            val messages = store.getObservationsForConversation(summary.conversationId, 100)
                .sortedBy { it.postTime.epochMillis }
                .map { observation ->
                    InstagramReadMessageView(
                        id = observation.id.value,
                        conversationId = summary.conversationId.value,
                        timestampMillis = observation.postTime.epochMillis,
                        text = observation.textFragments.joinToString(" "),
                        direction = observation.direction?.name?.lowercase() ?: "unknown",
                    )
                }
            InstagramReadConversationView(
                id = summary.conversationId.value,
                title = summary.title,
                messages = messages,
                partial = summary.coverage.name == "PartialApiSync",
            )
        }
        return InstagramReadView(conversations, partial = conversations.any { it.partial })
    }

    internal fun parseReadJsonForTest(json: String): InstagramReadView {
        val root = JSONObject(json)
        val conversations = parseConversations(root.optJSONArray("conversations"))
        return InstagramReadView(conversations, root.optBoolean("has_more_threads") || conversations.any { it.partial })
    }

    internal fun mapForTest(accountId: AccountId, conversation: InstagramReadConversationView, message: InstagramReadMessageView): ObservedNotification =
        toObservation(accountId, conversation, message)
    private fun ingest(accountId: AccountId, json: String): Result {
        return try {
            val root = JSONObject(json)
            if (root.optString("verified_user_id") != accountId.localId) {
                return Result.Failed("verified_identity_mismatch")
            }
            val conversations = parseConversations(root.optJSONArray("conversations"))
            val observations = conversations.flatMap { conversation ->
                conversation.messages.map { message -> toObservation(accountId, conversation, message) }
            }
            val last = observations.maxWithOrNull(compareBy<ObservedNotification> { it.postTime.epochMillis }.thenBy { it.notificationKey })
            val cursor = IngestCursor(
                source = "$INSTAGRAM_SOURCE|${accountId.key}",
                lastProcessedTimeMillis = last?.postTime?.epochMillis ?: 0L,
                lastProcessedKey = last?.notificationKey,
                processedCount = observations.size.toLong(),
                gapCount = 0L,
            )
            val persisted = store.persistBatchWithCursors(observations, listOf(cursor))
            if (!persisted.isFullySuccessful) return Result.Failed("observation_persist_failed")
            Result.Success(
                view = InstagramReadView(conversations, root.optBoolean("has_more_threads") || conversations.any { it.partial }),
                persistedCount = persisted.successCount,
                partial = root.optBoolean("has_more_threads") || conversations.any { it.partial },
            )
        } catch (_: Throwable) {
            Result.Failed("read_mapping_failed")
        }
    }

    private fun toObservation(
        accountId: AccountId,
        conversation: InstagramReadConversationView,
        message: InstagramReadMessageView,
    ): ObservedNotification {
        val timestamp = UtcInstant(message.timestampMillis.takeIf { it > 0 } ?: 0L)
        val messageId = ScopedId(accountId, ScopedId.Kind.MESSAGE, message.id)
        return ObservedNotification(
            id = ScopedId(accountId, ScopedId.Kind.OBSERVATION, message.id),
            accountId = accountId,
            conversationId = ScopedId(accountId, ScopedId.Kind.CONVERSATION, conversation.id),
            packageId = INSTAGRAM_PACKAGE,
            notificationKey = "$INSTAGRAM_SOURCE|${accountId.key}|${conversation.id}|${message.id}",
            notificationId = messageId.value.hashCode() and Int.MAX_VALUE,
            tag = conversation.id,
            senderHint = message.senderId.ifBlank { message.direction },
            title = conversation.title,
            textFragments = listOf(message.text),
            rawTextDigest = digest(message.text),
            observedAt = UtcInstant(System.currentTimeMillis()),
            postTime = timestamp,
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = if (conversation.partial) TruncationState.Truncated else TruncationState.Unknown,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.ThirdPartyBridge,
            direction = when (message.direction) {
                "inbound" -> MessageDirection.Inbound
                "outbound" -> MessageDirection.Outbound
                else -> MessageDirection.Unknown
            },
            notificationCategory = NotificationCategory.DirectMessage,
            messagingEvidence = true,
            sourceMessageTimeMillis = message.timestampMillis,
        )
    }

    private fun parseConversations(array: JSONArray?): List<InstagramReadConversationView> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val messages = buildList {
                    val messageArray = item.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until messageArray.length()) {
                        val message = messageArray.optJSONObject(j) ?: continue
                        val messageId = message.optString("id")
                        if (messageId.isBlank()) continue
                        add(InstagramReadMessageView(
                            id = messageId,
                            conversationId = id,
                            timestampMillis = message.optLong("timestamp_ms"),
                            text = message.optString("text"),
                            senderId = message.optString("sender_id"),
                            direction = message.optString("direction", "unknown"),
                        ))
                    }
                }
                add(InstagramReadConversationView(
                    id = id,
                    title = item.optString("title", "Instagram conversation"),
                    messages = messages,
                    partial = item.optBoolean("has_more_messages"),
                ))
            }
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

data class InstagramReadView(val conversations: List<InstagramReadConversationView>, val partial: Boolean)
data class InstagramReadConversationView(val id: String, val title: String, val messages: List<InstagramReadMessageView>, val partial: Boolean)
data class InstagramReadMessageView(
    val id: String,
    val conversationId: String,
    val timestampMillis: Long,
    val text: String,
    val senderId: String = "",
    val direction: String,
)
