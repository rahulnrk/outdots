package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/**
 * Official Gmail REST adapter. Reads use gmail.readonly. Sending requires gmail.send (or
 * gmail.modify) and only runs through [GmailSender] behind exact approval. OAuth UI, client
 * identity and token storage stay platform specific. Never falls back to notifications or bridges.
 */
class GmailApiConnector(
    private val apiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
) : AccountConnector {
    override val provider: Provider = Provider.GMAIL
    override val route: ConnectorRoute = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
    ): ProviderAccountIdentity {
        val email = client(transport, accessToken).get("$apiBase/profile").str("emailAddress")
            ?: throw ConnectorHttpException(200, "Gmail response omitted emailAddress", kind = ProviderErrorKind.Rejected)
        return ProviderAccountIdentity(Provider.GMAIL, email.lowercase(), email, AccountType.GoogleConsumer)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult = sync(account, accessToken, checkpoint, transport, maxMessages) { false }

    /**
     * Newest-page snapshot (or the page at [SyncCheckpoint.cursor], which is a page token, not an
     * incremental cursor). Known IDs are counted as listed but skip the metadata request.
     */
    fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
        isKnown: (providerMessageId: String) -> Boolean,
    ): SyncResult {
        require(account.id.provider == Provider.GMAIL)
        require(maxMessages in 1..100)
        val api = client(transport, accessToken)
        val url = buildString {
            append("$apiBase/messages?maxResults=").append(maxMessages)
            if (!checkpoint.cursor.isNullOrBlank()) append("&pageToken=").append(urlEncode(checkpoint.cursor))
        }
        val list = api.get(url)
        list["messages"]?.let { refs ->
            if (refs !is JsonValue.Arr || refs.items.any { it !is JsonValue.Obj || it.str("id").isNullOrBlank() })
                throw ConnectorHttpException(200, "Invalid Gmail message list", kind = ProviderErrorKind.Protocol)
        }
        var listed = 0
        val messages = list["messages"]?.objects.orEmpty().take(maxMessages).mapNotNull { ref ->
            val id = ref.str("id") ?: return@mapNotNull null
            listed++
            if (isKnown(id)) null else metadata(api, id, ref.str("threadId"))
        }
        return SyncResult(
            messages = messages,
            next = checkpoint.copy(cursor = list.str("nextPageToken"), processedCount = checkpoint.processedCount + messages.size),
            state = ConnectionState.Healthy,
            listedCount = listed,
        )
    }

    /**
     * Incremental sync using Gmail history. With no [startHistoryId] (first run) or an expired
     * one (HTTP 404), it takes a bounded newest-page snapshot and reports [GmailIncrementalResult.gap]
     * so the UI can show that older changes between the two points were not replayed.
     */
    fun syncIncremental(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        startHistoryId: String?,
        transport: ProviderHttpTransport,
        maxMessages: Int = 50,
        isKnown: (String) -> Boolean = { false },
    ): GmailIncrementalResult {
        require(account.id.provider == Provider.GMAIL)
        require(maxMessages in 1..100)
        val api = client(transport, accessToken)
        if (startHistoryId.isNullOrBlank()) return snapshot(account, api, transport, accessToken, maxMessages, isKnown, gap = false)
        val added = linkedMapOf<String, String?>()
        val deleted = linkedSetOf<String>()
        var pageToken: String? = null
        var latest = startHistoryId
        var pages = 0
        // Id of the last history record whose changes are fully captured in added/deleted.
        var lastRecordId: String? = null
        var stoppedMidPage = false
        try {
            pageLoop@ do {
                val url = buildString {
                    append("$apiBase/history?startHistoryId=").append(urlEncode(startHistoryId))
                    append("&historyTypes=messageAdded&historyTypes=messageDeleted&maxResults=").append(maxMessages)
                    pageToken?.let { append("&pageToken=").append(urlEncode(it)) }
                }
                val page = api.get(url)
                if (page.str("historyId").isNullOrBlank() || page["history"]?.let { it !is JsonValue.Arr } == true)
                    throw ConnectorHttpException(200, "Invalid Gmail history response", kind = ProviderErrorKind.Protocol)
                page["history"]?.array?.forEach { record ->
                    if (record !is JsonValue.Obj || record.str("id").isNullOrBlank())
                        throw ConnectorHttpException(200, "Invalid Gmail history record", kind = ProviderErrorKind.Protocol)
                    for (field in listOf("messagesAdded", "messagesDeleted")) record[field]?.let { changes ->
                        if (changes !is JsonValue.Arr || changes.items.any { it["message"]?.str("id").isNullOrBlank() })
                            throw ConnectorHttpException(200, "Invalid Gmail history changes", kind = ProviderErrorKind.Protocol)
                    }
                }
                page.str("historyId")?.let { latest = it }
                for (record in page["history"]?.objects.orEmpty()) {
                    if (added.size >= maxMessages) { stoppedMidPage = true; break }
                    record["messagesAdded"]?.objects.orEmpty().mapNotNull { it["message"] }.forEach { m ->
                        val id = m.str("id") ?: return@forEach
                        if (m["labelIds"]?.array?.any { it.string == "DRAFT" } == true) return@forEach
                        added[id] = m.str("threadId"); deleted -= id
                    }
                    record["messagesDeleted"]?.objects.orEmpty().mapNotNull { it["message"]?.str("id") }.forEach { id ->
                        added.remove(id); deleted += id
                    }
                    record.str("id")?.let { lastRecordId = it }
                }
                if (stoppedMidPage) break@pageLoop
                pageToken = page.str("nextPageToken")
                pages++
            } while (pageToken != null && pages < MAX_HISTORY_PAGES && added.size < maxMessages)
        } catch (e: ConnectorHttpException) {
            // history.list returns 404 when startHistoryId is too old, or 400 (invalidArgument)
            // if it is totally invalid (e.g. an old pageToken string instead of a uint64 historyId).
            if (e.kind != ProviderErrorKind.NotFound && e.kind != ProviderErrorKind.CursorInvalid && e.reason != "invalidArgument") throw e
            return snapshot(account, api, transport, accessToken, maxMessages, isKnown, gap = true)
        }
        // If more history remains than this bounded pass may fetch, advance only to the last fully
        // processed history record, so the next pass resumes after it and nothing is skipped.
        val truncated = stoppedMidPage || pageToken != null
        val nextCursor = if (!truncated) latest else lastRecordId ?: startHistoryId
        val fetched = added.entries.filterNot { isKnown(it.key) }.mapNotNull { (id, thread) ->
            try { metadata(api, id, thread) } catch (err: ConnectorHttpException) {
                if (err.kind == ProviderErrorKind.NotFound) null else throw err  // deleted after being listed
            }
        }
        val tombstones = deleted.map { ProviderMessage(it, it, "", null, "", 0L, isDeleted = true) }
        return GmailIncrementalResult(
            messages = fetched,
            deleted = tombstones,
            nextHistoryId = nextCursor,
            gap = false,
            moreAvailable = truncated,
        )
    }

    private fun snapshot(
        account: ConnectedAccount,
        api: ProviderApiClient,
        transport: ProviderHttpTransport,
        token: StoredAccessToken,
        maxMessages: Int,
        isKnown: (String) -> Boolean,
        gap: Boolean,
    ): GmailIncrementalResult {
        // Read historyId BEFORE listing so nothing that arrives during the snapshot is skipped.
        val historyId = api.get("$apiBase/profile").str("historyId")?.takeIf { it.isNotBlank() }
            ?: throw ConnectorHttpException(200, "Gmail history checkpoint missing", kind = ProviderErrorKind.Protocol)
        val page = sync(account, token, SyncCheckpoint(), transport, maxMessages, isKnown)
        return GmailIncrementalResult(page.messages, emptyList(), historyId, gap, moreAvailable = false, listedCount = page.listedCount)
    }

    /** Full selected thread with plain-text bodies, for reading and local drafting. */
    fun fetchThread(
        threadId: String,
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
        selfAddress: String? = null,
        accountId: AccountId? = null,
    ): List<ProviderMessage> {
        require(threadId.isNotBlank())
        val thread = client(transport, accessToken).get("$apiBase/threads/${urlEncode(threadId)}?format=full")
        return thread["messages"]?.objects.orEmpty().map { parseFull(it, threadId, selfAddress, accountId) }
    }

    /** Raw bytes of one attachment. Network I/O. */
    fun fetchAttachment(messageId: String, attachmentId: String, accessToken: StoredAccessToken, transport: ProviderHttpTransport): ByteArray? {
        val res = client(transport, accessToken).get("$apiBase/messages/${urlEncode(messageId)}/attachments/${urlEncode(attachmentId)}")
        return res.str("data")?.let { Base64Url.decode(it) }
    }

    private fun metadata(api: ProviderApiClient, id: String, threadId: String?): ProviderMessage {
        val detail = api.get(
            "$apiBase/messages/${urlEncode(id)}?format=metadata" +
                "&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Message-ID",
        )
        if (detail.str("id") != id)
            throw ConnectorHttpException(200, "Invalid Gmail message identity", kind = ProviderErrorKind.Protocol)
        val headers = headers(detail)
        return ProviderMessage(
            providerMessageId = id,
            conversationId = threadId ?: detail.str("threadId") ?: id,
            sender = headers["from"] ?: "Unknown sender",
            subject = headers["subject"],
            snippet = detail.str("snippet").orEmpty(),
            occurredAtMillis = detail.str("internalDate")?.toLongOrNull() ?: 0L,
            direction = direction(detail),
            replyAnchor = headers["message-id"],
            recipients = headers["to"]?.let(::splitAddresses).orEmpty(),
        )
    }

    private fun parseFull(message: JsonValue, threadId: String, selfAddress: String?, accountId: AccountId?): ProviderMessage {
        val id = message.str("id") ?: error("Gmail message without id")
        val payload = message["payload"]
        val headers = headers(message)
        val from = headers["from"] ?: "Unknown sender"
        val body = payload?.let { p ->
            val text = MimeText.extractPlainText(p)
            val markers = MimeText.attachmentMarkers(id, p, accountId)
            if (markers.isEmpty()) text else (markers + listOfNotNull(text)).joinToString("\n")
        }
        val dir = direction(message).takeIf { it != MessageDirection.Unknown }
            ?: if (selfAddress != null && from.contains(selfAddress, ignoreCase = true)) MessageDirection.Outbound else MessageDirection.Inbound
        return ProviderMessage(
            providerMessageId = id,
            conversationId = message.str("threadId") ?: threadId,
            sender = from,
            subject = headers["subject"],
            snippet = message.str("snippet").orEmpty(),
            occurredAtMillis = message.str("internalDate")?.toLongOrNull() ?: 0L,
            bodyText = body,
            direction = dir,
            replyAnchor = headers["message-id"],
            recipients = (headers["to"]?.let(::splitAddresses).orEmpty() + headers["cc"]?.let(::splitAddresses).orEmpty()),
        )
    }

    private fun direction(message: JsonValue): MessageDirection {
        val labels = message["labelIds"]?.array?.mapNotNull { it.string } ?: return MessageDirection.Unknown
        return when {
            "SENT" in labels -> MessageDirection.Outbound
            "INBOX" in labels || labels.isNotEmpty() -> MessageDirection.Inbound
            else -> MessageDirection.Unknown
        }
    }

    internal fun client(transport: ProviderHttpTransport, token: StoredAccessToken) = ProviderApiClient(
        providerName = "Gmail",
        transport = transport,
        token = token,
        classify = { status, reason ->
            if (status == 403 && reason in RATE_REASONS) ProviderErrorKind.RateLimited
            else ConnectorHttpException.defaultKind(status)
        },
    )

    companion object {
        private const val MAX_HISTORY_PAGES = 5
        private val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded")

        /** Gmail scope strings (verified against the Gmail scopes page on 2026-10-10). */
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"

        /** Human-readable explanation for a Gmail 403, used for account status. */
        fun forbiddenHint(reason: String?): String = when (reason) {
            "accessNotConfigured", "SERVICE_DISABLED" -> "Gmail API is not enabled for this Google Cloud project"
            "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> "Gmail read permission was not granted on the consent screen"
            else -> "Gmail permission denied for this account"
        }

        internal fun headers(message: JsonValue): Map<String, String> =
            message.path("payload", "headers")?.objects.orEmpty()
                .mapNotNull { h -> h.str("name")?.lowercase()?.let { n -> h.str("value")?.let { n to it } } }
                .toMap()

        internal fun splitAddresses(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

data class GmailIncrementalResult(
    val messages: List<ProviderMessage>,
    val deleted: List<ProviderMessage>,
    /** Persist atomically with [messages]. Null only if Gmail omitted it; then the next run snapshots. */
    val nextHistoryId: String?,
    /** True when the previous cursor expired and changes between the two points were not replayed. */
    val gap: Boolean,
    /** History remained beyond this bounded pass; the cursor only advanced to the last fully processed record. */
    val moreAvailable: Boolean,
    val listedCount: Int = messages.size + deleted.size,
)

/** Plain-text extraction from Gmail's MIME tree. HTML is reduced to text only as a fallback. */
object MimeText {
    fun extractPlainText(part: JsonValue): String? {
        findPart(part, "text/plain")?.let { return decode(it) }
        return findPart(part, "text/html")?.let { decode(it)?.let(::htmlToText) }
    }

    /** Attachment markers rendered inline by the chat UI: ⟦gm-image:gmail|localId|msgId/attId|name⟧. */
    fun attachmentMarkers(messageId: String, part: JsonValue, accountId: AccountId? = null): List<String> {
        val out = mutableListOf<String>()
        fun walk(p: JsonValue) {
            val name = p.str("filename").orEmpty()
            val att = p.path("body", "attachmentId")?.string
            if (name.isNotEmpty() && att != null) {
                val mime = p.str("mimeType").orEmpty().lowercase()
                val kind = when {
                    mime == "image/gif" -> "gm-gif"
                    mime.startsWith("image/") -> "gm-image"
                    mime.startsWith("video/") -> "gm-video"
                    mime.startsWith("audio/") -> "gm-audio"
                    else -> "gm-document"
                }
                val safe = name.replace('|', '_').replace('\u27e7', '_')
                val scoped = accountId?.let { "${it.key}|$messageId/$att|$safe" } ?: "$messageId/$att|$safe"
                out += "\u27e6$kind:$scoped\u27e7"
            }
            p["parts"]?.objects.orEmpty().forEach(::walk)
        }
        walk(part)
        return out
    }

    private fun findPart(part: JsonValue, mime: String): JsonValue? {
        val type = part.str("mimeType")?.lowercase()
        val isAttachment = !part.str("filename").isNullOrEmpty()
        if (type == mime && !isAttachment && part.path("body", "data") != null) return part
        return part["parts"]?.objects.orEmpty().firstNotNullOfOrNull { findPart(it, mime) }
    }

    private fun decode(part: JsonValue): String? =
        part.path("body", "data")?.string?.let { Base64Url.decode(it).decodeToString() }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Parsed Gmail attachment marker. Legacy refs omit [accountId] and must be fetched with the observation account. */
data class GmailAttachmentRef(
    val accountId: AccountId?,
    val messageId: String,
    val attachmentId: String,
    val fileName: String,
) {
    companion object {
        fun parse(raw: String, fallbackAccount: AccountId? = null): GmailAttachmentRef? {
            val parts = raw.split('|')
            return when {
                parts.size >= 4 -> {
                    val account = runCatching { AccountId.parse("${parts[0]}|${parts[1]}") }.getOrNull() ?: return null
                    val msgAtt = parts[2]
                    val msg = msgAtt.substringBefore('/')
                    val att = msgAtt.substringAfter('/', "")
                    if (msg.isBlank() || att.isBlank()) return null
                    GmailAttachmentRef(account, msg, att, parts.drop(3).joinToString("|").ifBlank { "attachment" })
                }
                parts.size == 2 -> {
                    val msg = parts[0].substringBefore('/')
                    val att = parts[0].substringAfter('/', "")
                    if (msg.isBlank() || att.isBlank()) return null
                    GmailAttachmentRef(fallbackAccount, msg, att, parts[1].ifBlank { "attachment" })
                }
                else -> null
            }
        }

        fun resolveAccount(requested: AccountId, parsed: GmailAttachmentRef): AccountId? {
            val marked = parsed.accountId
            return when {
                marked != null && marked != requested -> null
                else -> requested
            }
        }

        fun cachePartition(accountId: AccountId): String =
            accountId.key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
