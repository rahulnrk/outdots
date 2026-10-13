package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.Provider

/**
 * Cursor for chat-style providers that sync many conversations. Encoded as a compact JSON object
 * in [SyncCheckpoint.cursor] so it stays opaque to storage. Each conversation keeps its own
 * high-water mark; a provider-wide cursor (Telegram update offset, X pagination) uses [global].
 */
data class ChatCursor(val perConversation: Map<String, String> = emptyMap(), val global: String? = null) {
    fun encode(): String = JsonWriter.write(mapOf("c" to perConversation, "g" to global))

    companion object {
        fun decode(value: String?): ChatCursor {
            val json = value?.let { JsonValue.parseOrNull(it) } as? JsonValue.Obj ?: return ChatCursor()
            val conv = (json["c"] as? JsonValue.Obj)?.fields.orEmpty().mapNotNull { (k, v) -> v.string?.let { k to it } }.toMap()
            return ChatCursor(conv, json.str("g"))
        }
    }
}

/** Monotonic per-message revision from provider edit metadata. Unedited messages stay at 1. */
internal object MessageRevision {
    fun slack(message: JsonValue): Long {
        val editedTs = message.path("edited", "ts")?.string ?: message["edited"]?.str("ts")
        if (editedTs.isNullOrBlank()) return 1L
        val seconds = editedTs.substringBefore('.').toLongOrNull() ?: return 2L
        val frac = editedTs.substringAfter('.', "0").padEnd(6, '0').take(6).toLongOrNull() ?: 0L
        return maxOf(seconds * 1_000_000L + frac, 2L)
    }

    fun rfc3339(value: String?): Long {
        val millis = value?.let(Rfc3339::parseMillis) ?: return 1L
        return if (millis > 1L) millis else 2L
    }
}

// ---------------------------------------------------------------------------------------------
// Slack (Web API, user token from the PKCE public-client flow)
// ---------------------------------------------------------------------------------------------

/**
 * Slack Web API with a user token. Slack returns HTTP 200 with `ok:false` for most errors, so
 * every call goes through [slackCall]. Workspace + user ID form the provider account identity so
 * two workspaces never merge.
 */
class SlackConnector(private val apiBase: String = "https://slack.com/api") : AccountConnector {
    override val provider = Provider.SLACK
    override val route = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val auth = slackCall(transport, accessToken, "auth.test", emptyMap())
        val team = auth.str("team_id") ?: error("Slack auth.test omitted team_id")
        val user = auth.str("user_id") ?: error("Slack auth.test omitted user_id")
        return ProviderAccountIdentity(Provider.SLACK, "$team:$user", listOfNotNull(auth.str("user"), auth.str("team")).joinToString(" @ "), AccountType.Workspace)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.SLACK)
        val cursor = ChatCursor.decode(checkpoint.cursor)
        val selfUser = account.providerAccountId.substringAfter(':')
        val types = conversationTypes(account.grantedScopes)
        if (types.isEmpty()) {
            return SyncResult(emptyList(), checkpoint, ConnectionState.AuthorizationRequired, "Slack history permission was not granted.")
        }
        val listed = mutableListOf<JsonValue>()
        var listCursor: String? = null
        var listPages = 0
        while (listPages < MAX_LIST_PAGES) {
            listPages++
            val page = slackCall(
                transport, accessToken, "conversations.list",
                buildMap {
                    put("types", types.joinToString(","))
                    put("exclude_archived", "true")
                    put("limit", "200")
                    listCursor?.let { put("cursor", it) }
                },
            )
            listed += page["channels"]?.objects.orEmpty()
            listCursor = page.path("response_metadata", "next_cursor")?.string?.takeIf { it.isNotBlank() }
            if (listCursor == null) break
        }
        val conversations = listed
            .filter { it["is_member"]?.bool != false }
            .sortedByDescending { it.str("updated")?.toLongOrNull() ?: 0L }
            .take(MAX_CONVERSATIONS)
        val marks = cursor.perConversation.toMutableMap()
        val out = mutableListOf<ProviderMessage>()
        val names = SlackNames(this, transport, accessToken, "users:read" in account.grantedScopes)
        var gaps = 0
        for (conv in conversations) {
            if (out.size >= maxMessages) break
            val id = conv.str("id") ?: continue
            val pageMessages = mutableListOf<JsonValue>()
            var historyCursor: String? = null
            var historyComplete = false
            var pages = 0
            while (pages < MAX_HISTORY_PAGES) {
                pages++
                val params = buildMap {
                    put("channel", id)
                    put("limit", "$PER_CONVERSATION")
                    marks[id]?.let { put("oldest", it) }
                    historyCursor?.let { put("cursor", it) }
                }
                val history = try {
                    slackCall(transport, accessToken, "conversations.history", params)
                } catch (e: ConnectorHttpException) {
                    if (e.reason in SKIPPABLE) {
                        historyComplete = true
                        break
                    } else throw e
                }
                pageMessages += history["messages"]?.objects.orEmpty()
                val hasMore = history["has_more"]?.bool == true
                historyCursor = history.path("response_metadata", "next_cursor")?.string?.takeIf { it.isNotBlank() }
                if (!hasMore || historyCursor == null) {
                    historyComplete = true
                    break
                }
            }
            pageMessages.forEach { m ->
                val ts = m.str("ts") ?: return@forEach
                if (m.str("subtype") in IGNORED_SUBTYPES) return@forEach
                val user = m.str("user") ?: m.str("bot_id") ?: "unknown"
                val text = m.str("text").orEmpty()
                out += ProviderMessage(
                    providerMessageId = "$id:$ts",
                    conversationId = id,
                    sender = names.nameOf(user),
                    subject = conv.str("name")?.let { "#$it" } ?: conv.str("user")?.let { names.nameOf(it) },
                    snippet = text.take(200),
                    occurredAtMillis = (ts.substringBefore('.').toLongOrNull() ?: 0L) * 1000L,
                    bodyText = text,
                    direction = if (user == selfUser) MessageDirection.Outbound else MessageDirection.Inbound,
                    isDeleted = m.str("subtype") == "message_deleted",
                    revision = MessageRevision.slack(m),
                    replyAnchor = m.str("thread_ts") ?: ts,
                )
            }
            if (historyComplete) {
                pageMessages.mapNotNull { it.str("ts") }.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }?.let { marks[id] = it }
            } else {
                gaps++
            }
        }
        return SyncResult(out, SyncCheckpoint(ChatCursor(marks).encode(), checkpoint.processedCount + out.size, checkpoint.gapCount + gaps), ConnectionState.Healthy)
    }

    internal fun slackCall(transport: ProviderHttpTransport, token: StoredAccessToken, method: String, form: Map<String, String>): JsonValue {
        val json = ProviderApiClient("Slack", transport, token).postForm("$apiBase/$method", form)
        if (json["ok"]?.bool == true) return json
        val error = json.str("error") ?: "unknown_error"
        val kind = when (error) {
            "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive" -> ProviderErrorKind.Unauthorized
            "missing_scope", "not_allowed_token_type", "no_permission", "ekm_access_denied" -> ProviderErrorKind.PermissionMissing
            "ratelimited" -> ProviderErrorKind.RateLimited
            "channel_not_found", "message_not_found", "thread_not_found" -> ProviderErrorKind.NotFound
            "internal_error", "service_unavailable", "fatal_error", "request_timeout" -> ProviderErrorKind.Transient
            else -> ProviderErrorKind.Rejected
        }
        throw ConnectorHttpException(200, "Slack $method failed ($error)", error, kind)
    }

    companion object {
        private const val MAX_CONVERSATIONS = 20
        private const val PER_CONVERSATION = 20
        private const val MAX_HISTORY_PAGES = 5
        private const val MAX_LIST_PAGES = 5
        private val SKIPPABLE = setOf("channel_not_found", "not_in_channel", "missing_scope")
        private val IGNORED_SUBTYPES = setOf("channel_join", "channel_leave", "channel_topic", "channel_purpose")

        fun conversationTypes(scopes: Set<String>): List<String> = buildList {
            if ("im:history" in scopes) add("im")
            if ("mpim:history" in scopes) add("mpim")
            if ("channels:history" in scopes) add("public_channel")
            if ("groups:history" in scopes) add("private_channel")
        }
    }
}

/**
 * Resolves Slack user IDs to display names with `users.info` (needs users:read), cached per sync
 * and bounded so a busy workspace cannot turn one sync into hundreds of calls. Falls back to the ID.
 */
internal class SlackNames(
    private val connector: SlackConnector,
    private val transport: ProviderHttpTransport,
    private val token: StoredAccessToken,
    private val enabled: Boolean,
) {
    private val cache = mutableMapOf<String, String>()
    private var lookups = 0

    fun nameOf(userId: String): String {
        cache[userId]?.let { return it }
        if (!enabled || !userId.startsWith("U") && !userId.startsWith("W") || lookups >= MAX_LOOKUPS) return userId
        lookups++
        val name = runCatching {
            val user = connector.slackCall(transport, token, "users.info", mapOf("user" to userId))["user"]
            user?.path("profile", "display_name")?.string?.takeIf { it.isNotBlank() }
                ?: user?.path("profile", "real_name")?.string?.takeIf { it.isNotBlank() }
                ?: user?.str("real_name")?.takeIf { it.isNotBlank() }
                ?: user?.str("name")
        }.getOrNull() ?: userId
        cache[userId] = name
        return name
    }

    private companion object { const val MAX_LOOKUPS = 40 }
}

/** Slack `chat.postMessage` as the signed-in user, threaded with `thread_ts` when replying. */
class SlackSender(private val apiBase: String = "https://slack.com/api") : ProviderSender {
    override val provider = Provider.SLACK
    override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
        val form = buildMap {
            put("channel", payload.conversationId)
            put("text", payload.body)
            payload.replyTarget?.let { put("thread_ts", it) }
        }
        val result = try {
            SlackConnector(apiBase).slackCall(transport, token, "chat.postMessage", form)
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        val ts = result.str("ts") ?: return SendOutcome.Unknown("slack_response_without_ts")
        return SendOutcome.Accepted("slack:${result.str("channel") ?: payload.conversationId}:$ts")
    }
}

// ---------------------------------------------------------------------------------------------
// Google Chat (user authentication)
// ---------------------------------------------------------------------------------------------

class GoogleChatConnector(private val apiBase: String = "https://chat.googleapis.com/v1") : AccountConnector {
    override val provider = Provider.GOOGLE_CHAT
    override val route = ConnectorRoute.OfficialProviderApi

    /** Chat has no "me" endpoint; identity comes from the Google OpenID userinfo (sub + email). */
    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val info = ProviderApiClient("Google", transport, accessToken).get("https://openidconnect.googleapis.com/v1/userinfo")
        val sub = info.str("sub") ?: error("Google userinfo omitted sub")
        return ProviderAccountIdentity(Provider.GOOGLE_CHAT, sub, info.str("email"), AccountType.Workspace)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.GOOGLE_CHAT)
        val api = ProviderApiClient("Google Chat", transport, accessToken)
        val cursor = ChatCursor.decode(checkpoint.cursor)
        val marks = cursor.perConversation.toMutableMap()
        val listedSpaces = mutableListOf<JsonValue>()
        var spaceToken: String? = null
        var spacePages = 0
        while (spacePages < MAX_PAGES) {
            spacePages++
            val tokenQ = spaceToken?.let { "&pageToken=" + urlEncode(it) } ?: ""
            val page = api.get("$apiBase/spaces?pageSize=$MAX_SPACES$tokenQ")
            listedSpaces += page["spaces"]?.objects.orEmpty()
            spaceToken = page.str("nextPageToken")?.takeIf { it.isNotBlank() }
            if (spaceToken == null) break
        }
        val spaces = listedSpaces
            .sortedByDescending { Rfc3339.parseMillis(it.str("lastActiveTime")) ?: 0L }
        val out = mutableListOf<ProviderMessage>()
        var gaps = 0
        for (space in spaces) {
            if (out.size >= maxMessages) break
            val name = space.str("name") ?: continue
            val since = marks[name]
            val filter = since?.let { "&filter=" + urlEncode("createTime > \"$it\"") } ?: ""
            val pageMessages = mutableListOf<JsonValue>()
            var pageToken: String? = null
            var pages = 0
            var complete = false
            while (pages < MAX_PAGES) {
                pages++
                val tokenQ = pageToken?.let { "&pageToken=" + urlEncode(it) } ?: ""
                val page = try {
                    api.get("$apiBase/$name/messages?pageSize=$PER_SPACE&orderBy=${urlEncode("createTime desc")}$filter$tokenQ")
                } catch (e: ConnectorHttpException) {
                    if (e.kind == ProviderErrorKind.NotFound || e.kind == ProviderErrorKind.PermissionMissing) {
                        complete = true
                        break
                    } else throw e
                }
                pageMessages += page["messages"]?.objects.orEmpty()
                pageToken = page.str("nextPageToken")?.takeIf { it.isNotBlank() }
                if (pageToken == null) {
                    complete = true
                    break
                }
            }
            pageMessages.forEach { m ->
                val id = m.str("name") ?: return@forEach
                val text = m.str("text").orEmpty()
                val senderName = m.path("sender", "name")?.string
                out += ProviderMessage(
                    providerMessageId = id,
                    conversationId = name,
                    sender = m.path("sender", "displayName")?.string ?: senderName ?: "unknown",
                    subject = space.str("displayName"),
                    snippet = text.take(200),
                    occurredAtMillis = Rfc3339.parseMillis(m.str("createTime")) ?: 0L,
                    bodyText = text,
                    // Chat user resource names are "users/{id}" with the same ID as the OpenID sub.
                    direction = when (senderName) {
                        null -> MessageDirection.Unknown
                        "users/${account.providerAccountId}" -> MessageDirection.Outbound
                        else -> MessageDirection.Inbound
                    },
                    isDeleted = m["deletionMetadata"] != null,
                    revision = MessageRevision.rfc3339(m.str("lastUpdateTime")),
                    replyAnchor = m.path("thread", "name")?.string,
                )
            }
            if (complete) {
                pageMessages.mapNotNull { it.str("createTime") }.maxByOrNull { Rfc3339.parseMillis(it) ?: 0L }?.let { marks[name] = it }
            } else {
                gaps++
            }
        }
        return SyncResult(out, SyncCheckpoint(ChatCursor(marks).encode(), checkpoint.processedCount + out.size, checkpoint.gapCount + gaps), ConnectionState.Healthy)
    }

    companion object {
        private const val MAX_SPACES = 30
        private const val PER_SPACE = 20
        private const val MAX_PAGES = 5
    }
}

/**
 * `spaces.messages.create` with user auth. A reply targets the approved thread name and uses
 * REPLY_MESSAGE_OR_FAIL so a missing thread fails instead of silently starting a new one.
 * `requestId` makes a retried create idempotent on Google's side.
 */
class GoogleChatSender(private val apiBase: String = "https://chat.googleapis.com/v1") : ProviderSender {
    override val provider = Provider.GOOGLE_CHAT
    override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
        require(payload.conversationId.startsWith("spaces/")) { "Google Chat conversation must be a space name" }
        val query = buildString {
            append("?requestId=").append(urlEncode(clientRequestId.take(64)))
            if (payload.replyTarget != null) append("&messageReplyOption=REPLY_MESSAGE_OR_FAIL")
        }
        val body = buildMap<String, Any> {
            put("text", payload.body)
            payload.replyTarget?.let { put("thread", mapOf("name" to it)) }
        }
        val result = try {
            ProviderApiClient("Google Chat", transport, token).postJson("$apiBase/${payload.conversationId}/messages$query", body)
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        return result.str("name")?.let { SendOutcome.Accepted("gchat:$it") } ?: SendOutcome.Unknown("chat_response_without_name")
    }
}

// ---------------------------------------------------------------------------------------------
// Microsoft Teams chats (delegated Graph)
// ---------------------------------------------------------------------------------------------

class TeamsChatConnector(private val graphBase: String = "https://graph.microsoft.com/v1.0") : AccountConnector {
    override val provider = Provider.TEAMS
    override val route = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val me = OutlookGraphConnector(graphBase).client(transport, accessToken).get("$graphBase/me?\$select=id,displayName,mail,userPrincipalName")
        val id = me.str("id") ?: error("Graph /me omitted id")
        return ProviderAccountIdentity(Provider.TEAMS, id, me.str("mail") ?: me.str("userPrincipalName"), AccountType.MicrosoftWorkSchool)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.TEAMS)
        val api = OutlookGraphConnector(graphBase).client(transport, accessToken)
        val marks = ChatCursor.decode(checkpoint.cursor).perConversation.toMutableMap()
        val chats = mutableListOf<JsonValue>()
        var chatListUrl: String? = "$graphBase/me/chats?\$top=$MAX_CHATS&\$orderby=${urlEncode("lastMessagePreview/createdDateTime desc")}&\$expand=lastMessagePreview"
        var chatListPages = 0
        while (chatListUrl != null && chatListPages < MAX_PAGES) {
            val page = api.get(chatListUrl)
            chatListPages++
            chats += page["value"]?.objects.orEmpty()
            chatListUrl = page.str("@odata.nextLink")
        }
        val out = mutableListOf<ProviderMessage>()
        var gaps = 0
        for (chat in chats) {
            if (out.size >= maxMessages) break
            val chatId = chat.str("id") ?: continue
            val lastPreview = chat.path("lastMessagePreview", "createdDateTime")?.string
            val mark = marks[chatId]
            if (mark != null && lastPreview != null && (Rfc3339.parseMillis(lastPreview) ?: 0L) <= (Rfc3339.parseMillis(mark) ?: 0L)) continue
            val pageMessages = mutableListOf<JsonValue>()
            var nextUrl: String? = "$graphBase/chats/${urlEncode(chatId)}/messages?\$top=$PER_CHAT"
            var pages = 0
            while (nextUrl != null && pages < MAX_PAGES) {
                val page = try {
                    api.get(nextUrl)
                } catch (e: ConnectorHttpException) {
                    if (e.kind == ProviderErrorKind.NotFound || e.kind == ProviderErrorKind.PermissionMissing) {
                        nextUrl = null
                        break
                    } else throw e
                }
                pages++
                pageMessages += page["value"]?.objects.orEmpty().filter { it.str("messageType") == "message" }
                nextUrl = page.str("@odata.nextLink")
            }
            val complete = nextUrl == null
            val markMillis = mark?.let(Rfc3339::parseMillis) ?: Long.MIN_VALUE
            pageMessages.forEach { m ->
                val id = m.str("id") ?: return@forEach
                val created = Rfc3339.parseMillis(m.str("createdDateTime")) ?: 0L
                val modified = Rfc3339.parseMillis(m.str("lastModifiedDateTime")) ?: created
                if (maxOf(created, modified) <= markMillis) return@forEach
                val body = m["body"]
                val raw = body?.str("content").orEmpty()
                val text = if (body?.str("contentType").equals("html", true)) MimeText.htmlToText(raw) else raw
                val fromUser = m.path("from", "user")
                out += ProviderMessage(
                    providerMessageId = "$chatId:$id",
                    conversationId = chatId,
                    sender = fromUser?.str("displayName") ?: m.path("from", "application", "displayName")?.string ?: "unknown",
                    subject = chat.str("topic"),
                    snippet = text.take(200),
                    occurredAtMillis = created,
                    bodyText = text,
                    direction = if (fromUser?.str("id") == account.providerAccountId) MessageDirection.Outbound else MessageDirection.Inbound,
                    isDeleted = m.str("deletedDateTime") != null,
                    revision = if (modified > created) modified else 1L,
                    replyAnchor = id,
                )
            }
            if (complete) {
                pageMessages.mapNotNull { m -> listOfNotNull(m.str("lastModifiedDateTime"), m.str("createdDateTime")).maxByOrNull { Rfc3339.parseMillis(it) ?: 0L } }
                    .maxByOrNull { Rfc3339.parseMillis(it) ?: 0L }?.let { marks[chatId] = it }
            } else {
                gaps++
            }
        }
        return SyncResult(out, SyncCheckpoint(ChatCursor(marks).encode(), checkpoint.processedCount + out.size, checkpoint.gapCount + gaps), ConnectionState.Healthy)
    }

    companion object {
        private const val MAX_CHATS = 20
        private const val PER_CHAT = 20
        private const val MAX_PAGES = 5
    }
}

/** `POST /chats/{id}/messages` as the signed-in user. Teams chats are flat; replies post to the chat. */
class TeamsChatSender(private val graphBase: String = "https://graph.microsoft.com/v1.0") : ProviderSender {
    override val provider = Provider.TEAMS
    override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
        val result = try {
            OutlookGraphConnector(graphBase).client(transport, token).postJson(
                "$graphBase/chats/${urlEncode(payload.conversationId)}/messages",
                mapOf("body" to mapOf("contentType" to "text", "content" to payload.body)),
                mapOf("client-request-id" to clientRequestId),
            )
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        return result.str("id")?.let { SendOutcome.Accepted("teams:${payload.conversationId}:$it") } ?: SendOutcome.Unknown("teams_response_without_id")
    }
}

// ---------------------------------------------------------------------------------------------
// Telegram Bot API (a bot the user owns; not their personal account)
// ---------------------------------------------------------------------------------------------

/**
 * Telegram Bot API via `getUpdates` long-polling offset. The bot token is the credential and is
 * stored per account like an OAuth token; it is part of the URL, so request diagnostics are
 * redacted by [ProviderHttpRequest.redacted]. Incompatible with a configured webhook (409).
 */
class TelegramBotConnector(private val apiBase: String = "https://api.telegram.org") : AccountConnector {
    override val provider = Provider.TELEGRAM
    override val route = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val me = call(transport, accessToken, "getMe", null)["result"] ?: error("Telegram getMe omitted result")
        val id = me.str("id") ?: error("Telegram getMe omitted id")
        return ProviderAccountIdentity(Provider.TELEGRAM, "bot:$id", me.str("username")?.let { "@$it (bot)" }, AccountType.Unknown)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.TELEGRAM)
        val offset = ChatCursor.decode(checkpoint.cursor).global?.toLongOrNull()
        val body = buildMap<String, Any> {
            put("limit", maxMessages.coerceIn(1, 100))
            put("timeout", 0)
            put("allowed_updates", listOf("message", "edited_message"))
            offset?.let { put("offset", it) }
        }
        val updates = call(transport, accessToken, "getUpdates", body)["result"]?.objects.orEmpty()
        val botId = account.providerAccountId.removePrefix("bot:")
        val out = updates.mapNotNull { u ->
            val edited = u["edited_message"]
            val m = edited ?: u["message"] ?: return@mapNotNull null
            val chat = m["chat"] ?: return@mapNotNull null
            val chatId = chat.str("id") ?: return@mapNotNull null
            val messageId = m.str("message_id") ?: return@mapNotNull null
            val text = m.str("text") ?: m.str("caption") ?: ""
            val from = m["from"]
            ProviderMessage(
                providerMessageId = "$chatId:$messageId",
                conversationId = chatId,
                sender = from?.str("username")?.let { "@$it" } ?: listOfNotNull(from?.str("first_name"), from?.str("last_name")).joinToString(" ").ifBlank { "unknown" },
                subject = chat.str("title") ?: chat.str("username"),
                snippet = text.take(200),
                occurredAtMillis = ((m.str("edit_date") ?: m.str("date"))?.toLongOrNull() ?: 0L) * 1000L,
                bodyText = text,
                direction = if (from?.str("id") == botId) MessageDirection.Outbound else MessageDirection.Inbound,
                revision = if (edited != null) (m.str("edit_date")?.toLongOrNull() ?: 2L) else 1L,
                replyAnchor = messageId,
            )
        }
        // Confirm processed updates only after the caller persists them: offset = last update_id + 1.
        val nextOffset = updates.mapNotNull { it.str("update_id")?.toLongOrNull() }.maxOrNull()?.plus(1) ?: offset
        return SyncResult(out, SyncCheckpoint(ChatCursor(global = nextOffset?.toString()).encode(), checkpoint.processedCount + out.size, checkpoint.gapCount), ConnectionState.Healthy, listedCount = updates.size)
    }

    internal fun call(transport: ProviderHttpTransport, token: StoredAccessToken, method: String, body: Any?): JsonValue {
        require(BOT_TOKEN.matches(token.value)) { "Not a Telegram bot token" }
        val request = ProviderHttpRequest(
            HttpMethod.POST, "$apiBase/bot${token.value}/$method", mapOf("Content-Type" to ProviderApiClient.JSON),
            JsonWriter.write(body ?: emptyMap<String, Any>()), ProviderApiClient.JSON,
        )
        val response = try { transport.execute(request) } catch (e: Exception) {
            throw ConnectorHttpException(0, "Telegram unreachable (${e::class.simpleName})", null, ProviderErrorKind.Transient)
        }
        val json = JsonValue.parseOrNull(response.body)
        if (response.statusCode in 200..299 && json?.get("ok")?.bool == true) return json
        val code = json?.str("error_code")?.toIntOrNull() ?: response.statusCode
        val retry = json?.path("parameters", "retry_after")?.long
        val kind = when (code) {
            401, 404 -> ProviderErrorKind.Unauthorized  // Bot API answers 404 for an invalid token
            403 -> ProviderErrorKind.PermissionMissing  // bot blocked or removed from chat
            409 -> ProviderErrorKind.Rejected           // webhook set, getUpdates disallowed
            429 -> ProviderErrorKind.RateLimited
            else -> ConnectorHttpException.defaultKind(code)
        }
        throw ConnectorHttpException(code, "Telegram ${request.redacted()} failed: $code", "telegram_$code", kind, retry)
    }

    companion object {
        val BOT_TOKEN = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")
    }
}

class TelegramBotSender(private val apiBase: String = "https://api.telegram.org") : ProviderSender {
    override val provider = Provider.TELEGRAM
    override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
        val body = buildMap<String, Any> {
            put("chat_id", payload.conversationId)
            put("text", payload.body)
            payload.replyTarget?.let { put("reply_parameters", mapOf("message_id" to (it.substringAfterLast(':').toLongOrNull() ?: return SendOutcome.Rejected("reply_target_invalid")), "allow_sending_without_reply" to false)) }
        }
        val result = try {
            TelegramBotConnector(apiBase).call(transport, token, "sendMessage", body)
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        return result.path("result", "message_id")?.string?.let { SendOutcome.Accepted("telegram:${payload.conversationId}:$it") }
            ?: SendOutcome.Unknown("telegram_response_without_message_id")
    }
}

// ---------------------------------------------------------------------------------------------
// X API v2 Direct Messages
// ---------------------------------------------------------------------------------------------

/** X DM events (last 30 days only). The newest event ID seen is the cursor for "new since". */
class XDirectMessageConnector(private val apiBase: String = "https://api.x.com/2") : AccountConnector {
    override val provider = Provider.TWITTER
    override val route = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val me = ProviderApiClient("X", transport, accessToken, reasonOf = ::xReason).get("$apiBase/users/me")["data"] ?: error("X users/me omitted data")
        val id = me.str("id") ?: error("X users/me omitted id")
        return ProviderAccountIdentity(Provider.TWITTER, id, me.str("username")?.let { "@$it" }, AccountType.SocialProfessional)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.TWITTER)
        val api = ProviderApiClient("X", transport, accessToken, reasonOf = ::xReason)
        val newestSeen = ChatCursor.decode(checkpoint.cursor).global
        val users = mutableMapOf<String, String>()
        val events = mutableListOf<JsonValue>()
        var paginationToken: String? = null
        var reachedSeen = false
        var pages = 0
        // Events arrive newest first. Page back until the newest event from the last sync shows
        // up, so a burst larger than one page is not silently skipped (bounded per sync).
        while (pages < MAX_PAGES) {
            pages++
            val page = api.get(
                "$apiBase/dm_events?max_results=${maxMessages.coerceIn(1, 100)}&event_types=MessageCreate" +
                    "&dm_event.fields=${urlEncode("id,text,created_at,sender_id,dm_conversation_id")}" +
                    "&expansions=sender_id&user.fields=${urlEncode("username,name")}" +
                    (paginationToken?.let { "&pagination_token=" + urlEncode(it) } ?: ""),
            )
            page.path("includes", "users")?.objects.orEmpty().forEach { u ->
                val uid = u.str("id") ?: return@forEach
                users[uid] = u.str("name")?.let { n -> u.str("username")?.let { "$n (@$it)" } ?: n } ?: u.str("username")?.let { "@$it" } ?: uid
            }
            val data = page["data"]?.objects.orEmpty()
            val fresh = data.takeWhile { it.str("id") != newestSeen }
            events += fresh
            if (fresh.size < data.size) { reachedSeen = true; break }
            // First sync: one page is the starting window, not a gap.
            if (newestSeen == null) { reachedSeen = true; break }
            paginationToken = page.path("meta", "next_token")?.string?.takeIf { it.isNotBlank() } ?: run { reachedSeen = true; null }
            if (paginationToken == null) break
        }
        val out = events.mapNotNull { e ->
            val id = e.str("id") ?: return@mapNotNull null
            val conv = e.str("dm_conversation_id") ?: return@mapNotNull null
            val text = e.str("text").orEmpty()
            ProviderMessage(
                providerMessageId = id,
                conversationId = conv,
                sender = e.str("sender_id")?.let { users[it] ?: it } ?: "unknown",
                subject = null,
                snippet = text.take(200),
                occurredAtMillis = Rfc3339.parseMillis(e.str("created_at")) ?: 0L,
                bodyText = text,
                direction = if (e.str("sender_id") == account.providerAccountId) MessageDirection.Outbound else MessageDirection.Inbound,
            )
        }
        // The previous newest event was never reached within the page budget: events may be missing.
        val gap = newestSeen != null && !reachedSeen
        val next = events.firstOrNull()?.str("id") ?: newestSeen
        return SyncResult(
            out,
            SyncCheckpoint(ChatCursor(global = next).encode(), checkpoint.processedCount + out.size, checkpoint.gapCount + if (gap) 1 else 0),
            ConnectionState.Healthy,
        )
    }

    companion object {
        private const val MAX_PAGES = 5
        /** X problem details: {"title":"...","type":"https://api.twitter.com/2/problems/client-forbidden"}. */
        fun xReason(json: JsonValue?): String? = json?.str("type")?.substringAfterLast('/') ?: json?.str("title") ?: ProviderApiClient.defaultReason(json)
    }
}

/** `POST /2/dm_conversations/{id}/messages`. Returns dm_event_id on acceptance. */
class XDirectMessageSender(private val apiBase: String = "https://api.x.com/2") : ProviderSender {
    override val provider = Provider.TWITTER
    override fun send(account: ConnectedAccount, token: StoredAccessToken, payload: ExactActionPayload, clientRequestId: String, transport: ProviderHttpTransport): SendOutcome {
        val result = try {
            ProviderApiClient("X", transport, token, reasonOf = XDirectMessageConnector::xReason)
                .postJson("$apiBase/dm_conversations/${urlEncode(payload.conversationId)}/messages", mapOf("text" to payload.body))
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        return result.path("data", "dm_event_id")?.string?.let { SendOutcome.Accepted("x:$it") } ?: SendOutcome.Unknown("x_response_without_event_id")
    }
}
