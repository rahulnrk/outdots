package app.mini.hub.connector

import app.mini.hub.domain.Provider
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.IdGenerator
import kotlin.random.Random

/**
 * Connects to a standard Matrix Client-Server API homeserver.
 * Used for mautrix/Beeper user-session bridges (WhatsApp, Instagram, LinkedIn, Facebook).
 * The user provides their homeserver URL and access token. 
 *
 * Bridge sessions (like WhatsApp Web devices) run entirely on the homeserver. Message
 * content is processed by the bridge and stored unencrypted in the homeserver's database.
 * MINI acts as a standard Matrix client.
 */
class MatrixBridgeConnector(
    override val provider: Provider,
) : AccountConnector, ProviderSender {
    override val route: ConnectorRoute = ConnectorRoute.ThirdPartyBridge

    private fun extractHomeserver(accessToken: StoredAccessToken): String {
        val tokenParts = accessToken.value.split('|', limit = 2)
        require(tokenParts.size == 2) { "Matrix bridge token must be 'homeserver|token'" }
        return tokenParts[0]
    }
    
    private fun extractToken(accessToken: StoredAccessToken): StoredAccessToken {
        val tokenParts = accessToken.value.split('|', limit = 2)
        require(tokenParts.size == 2) { "Matrix bridge token must be 'homeserver|token'" }
        return accessToken.copy(value = tokenParts[1])
    }

    override fun discoverAccount(
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
    ): ProviderAccountIdentity {
        val hsUrl = extractHomeserver(accessToken)
        val matrixToken = extractToken(accessToken)
        val api = ProviderApiClient("Matrix", transport, matrixToken)
        val whoami = api.get("$hsUrl/_matrix/client/v3/account/whoami")
        val userId = whoami.str("user_id") ?: throw ConnectorHttpException(200, "Matrix response omitted user_id", kind = ProviderErrorKind.Rejected)
        return ProviderAccountIdentity(provider, userId, userId, AccountType.Unknown)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        val hsUrl = extractHomeserver(accessToken)
        val matrixToken = extractToken(accessToken)
        val api = ProviderApiClient("Matrix", transport, matrixToken)
        
        // Use a filter to only get m.room.message events and limit the timeline
        val filter = """{"room":{"timeline":{"types":["m.room.message"],"limit":$maxMessages}}}"""
        val url = buildString {
            append("$hsUrl/_matrix/client/v3/sync?timeout=0")
            append("&filter=").append(urlEncode(filter))
            if (!checkpoint.cursor.isNullOrBlank()) {
                append("&since=").append(urlEncode(checkpoint.cursor))
            }
        }
        
        val syncRes = api.get(url)
        val nextBatch = syncRes.str("next_batch") ?: checkpoint.cursor
        val messages = mutableListOf<ProviderMessage>()
        
        val rooms = (syncRes.path("rooms", "join") as? app.mini.hub.connector.JsonValue.Obj)?.fields ?: emptyMap()
        for ((roomId, roomObj) in rooms) {
            val events = roomObj.path("timeline", "events")?.array ?: emptyList()
            for (event in events) {
                if (event.str("type") != "m.room.message") continue
                val eventId = event.str("event_id") ?: continue
                val sender = event.str("sender") ?: "unknown"
                val content = event["content"] ?: continue
                
                val body = content.str("body") ?: ""
                val originServerTs = event.str("origin_server_ts")?.toLongOrNull() ?: 0L
                val msgType = content.str("msgtype")
                if (msgType != "m.text" && msgType != "m.image" && msgType != "m.video" && msgType != "m.audio") continue

                // Check for replies in m.relates_to
                val relatesTo = content["m.relates_to"]
                val replyTo = relatesTo?.path("m.in_reply_to", "event_id")?.string

                val isDeleted = (content as? app.mini.hub.connector.JsonValue.Obj)?.fields?.isEmpty() == true // m.room.redaction replaces content with empty

                messages.add(
                    ProviderMessage(
                        providerMessageId = eventId,
                        conversationId = roomId,
                        sender = sender,
                        subject = null,
                        snippet = body.take(100),
                        bodyText = body,
                        occurredAtMillis = originServerTs,
                        direction = if (sender == account.providerAccountId) MessageDirection.Outbound else MessageDirection.Inbound,
                        replyAnchor = replyTo,
                        recipients = emptyList(),
                        isDeleted = isDeleted,
                    )
                )
            }
        }

        return SyncResult(
            messages = messages.sortedBy { it.occurredAtMillis },
            next = checkpoint.copy(cursor = nextBatch, processedCount = checkpoint.processedCount + messages.size),
            state = ConnectionState.Healthy,
            listedCount = messages.size,
        )
    }

    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport
    ): SendOutcome {
        val hsUrl = extractHomeserver(token)
        val matrixToken = extractToken(token)
        val api = ProviderApiClient("Matrix", transport, matrixToken)
        val roomId = payload.conversationId
        
        // Generate a random transaction ID based on the client request ID
        val txnId = urlEncode(clientRequestId + Random.nextInt().toString())
        val endpoint = "$hsUrl/_matrix/client/v3/rooms/${urlEncode(roomId)}/send/m.room.message/$txnId"
        
        val content = mutableMapOf<String, Any>(
            "msgtype" to "m.text",
            "body" to payload.body
        )
        
        if (payload.replyTarget != null) {
            content["m.relates_to"] = mapOf(
                "m.in_reply_to" to mapOf(
                    "event_id" to payload.replyTarget
                )
            )
        }

        val res = api.putJson(endpoint, content)
        val eventId = res.str("event_id") 
            ?: return SendOutcome.Unknown("matrix_response_without_event_id")

        return SendOutcome.Accepted("matrix:$roomId:$eventId")
    }
}
