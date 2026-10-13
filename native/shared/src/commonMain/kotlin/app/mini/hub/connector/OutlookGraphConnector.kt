package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.Provider

/**
 * Microsoft Graph mail adapter (delegated, personal Outlook.com or work/school). Reads the Inbox
 * and Sent Items with per-folder delta; each folder's full `@odata.deltaLink` URL is stored
 * opaquely. Authentication is MSAL on Android (public client, no secret); this adapter only sees
 * tokens.
 */
class OutlookGraphConnector(
    private val graphBase: String = "https://graph.microsoft.com/v1.0",
) : AccountConnector {
    override val provider = Provider.OUTLOOK
    override val route = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(accessToken: StoredAccessToken, transport: ProviderHttpTransport): ProviderAccountIdentity {
        val me = client(transport, accessToken).get("$graphBase/me?\$select=id,displayName,mail,userPrincipalName")
        val id = me.str("id") ?: throw ConnectorHttpException(200, "Graph /me omitted id", kind = ProviderErrorKind.Rejected)
        val address = me.str("mail") ?: me.str("userPrincipalName")
        // Graph does not label account type in /me; MSAL's tenant ID does. The caller refines it.
        return ProviderAccountIdentity(Provider.OUTLOOK, id, address ?: me.str("displayName"), AccountType.Unknown)
    }

    /**
     * One bounded delta pass over Inbox and Sent Items. The cursor is a small JSON object holding
     * one opaque Graph link per folder: absent (start a new round), an `@odata.nextLink` (round in
     * progress) or an `@odata.deltaLink` (round finished). A legacy cursor that is a bare Graph URL
     * is the Inbox link from before Sent Items were synced. An expired link (410 or
     * SyncStateNotFound) restarts that folder with a recorded gap.
     */
    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.OUTLOOK)
        require(maxMessages in 1..100)
        val api = client(transport, accessToken)
        val self = EmailAddress.extract(account.displayName)
        val links = decodeCursor(checkpoint.cursor).toMutableMap()
        var gaps = checkpoint.gapCount
        val messages = mutableListOf<ProviderMessage>()
        for (folder in FOLDERS) {
            if (messages.size >= maxMessages) break
            var url: String? = links[folder]?.takeIf { it.startsWith(graphBase) } ?: initialDelta(folder)
            var pages = 0
            while (url != null && pages < MAX_PAGES && messages.size < maxMessages) {
                val page = try {
                    api.call(ProviderHttpRequest(HttpMethod.GET, url, auth(accessToken) + PREFER))
                } catch (e: ConnectorHttpException) {
                    if (e.kind == ProviderErrorKind.CursorInvalid && url != initialDelta(folder)) {
                        // Sync state expired: restart this folder. Changes in between are not replayed.
                        gaps++
                        url = initialDelta(folder); links.remove(folder); continue
                    }
                    // Sent Items is additive context; its absence must not block the Inbox.
                    if (folder != INBOX && (e.kind == ProviderErrorKind.NotFound || e.kind == ProviderErrorKind.PermissionMissing)) break
                    throw e
                }
                page["value"]?.objects.orEmpty().forEach { item -> parse(item, self)?.let(messages::add) }
                val next = page.str("@odata.nextLink")
                val delta = page.str("@odata.deltaLink")
                (next ?: delta)?.let { links[folder] = it }
                url = next
                pages++
            }
        }
        return SyncResult(
            messages = messages,
            next = SyncCheckpoint(encodeCursor(links), checkpoint.processedCount + messages.size, gaps),
            state = ConnectionState.Healthy,
        )
    }

    /** Full selected conversation with bodies across all folders, newest last. */
    fun fetchConversation(
        conversationId: String,
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
        selfAddress: String? = null,
    ): List<ProviderMessage> {
        val filter = urlEncode("conversationId eq '${conversationId.replace("'", "''")}'")
        var nextUrl: String? = "$graphBase/me/messages?\$filter=$filter&\$top=50&\$select=$SELECT"
        val out = mutableListOf<ProviderMessage>()
        var pages = 0
        val apiClient = client(transport, accessToken)

        while (nextUrl != null && pages < MAX_PAGES) {
            val page = apiClient.call(
                ProviderHttpRequest(
                    HttpMethod.GET,
                    nextUrl,
                    auth(accessToken) + PREFER,
                ),
            )
            out.addAll(page["value"]?.objects.orEmpty().mapNotNull { parse(it, selfAddress) })
            nextUrl = page.str("@odata.nextLink")
            pages++
        }
        return out.sortedBy { it.occurredAtMillis }
    }

    private fun initialDelta(folder: String) = "$graphBase/me/mailFolders/$folder/messages/delta?\$select=$SELECT"

    private fun parse(item: JsonValue, selfAddress: String?): ProviderMessage? {
        val id = item.str("id") ?: return null
        if (item["@removed"] != null) {
            return ProviderMessage(id, id, "", null, "", 0L, isDeleted = true)
        }
        // Unsent drafts are not conversation messages.
        if (item["isDraft"]?.bool == true) return null
        val from = item.path("from", "emailAddress")
        val fromAddress = from?.str("address")
        val sender = listOfNotNull(from?.str("name"), fromAddress?.let { "<$it>" }).joinToString(" ").ifBlank { "Unknown sender" }
        val body = item["body"]
        val text = body?.str("content")?.let { if (body.str("contentType").equals("html", true)) MimeText.htmlToText(it) else it }
        val direction = when {
            selfAddress == null || fromAddress == null -> MessageDirection.Unknown
            fromAddress.equals(selfAddress, ignoreCase = true) -> MessageDirection.Outbound
            else -> MessageDirection.Inbound
        }
        val time = if (direction == MessageDirection.Outbound) item.str("sentDateTime") ?: item.str("receivedDateTime") else item.str("receivedDateTime")
        return ProviderMessage(
            providerMessageId = id,
            conversationId = item.str("conversationId") ?: id,
            sender = sender,
            subject = item.str("subject"),
            snippet = item.str("bodyPreview").orEmpty(),
            occurredAtMillis = Rfc3339.parseMillis(time) ?: 0L,
            bodyText = text,
            direction = direction,
            replyAnchor = item.str("internetMessageId"),
            recipients = (item["toRecipients"]?.objects.orEmpty() + item["ccRecipients"]?.objects.orEmpty())
                .mapNotNull { it.path("emailAddress", "address")?.string },
        )
    }

    internal fun client(transport: ProviderHttpTransport, token: StoredAccessToken) = ProviderApiClient(
        providerName = "Microsoft Graph",
        transport = transport,
        token = token,
        classify = ::classifyGraph,
    )

    companion object {
        private const val MAX_PAGES = 5
        private const val INBOX = "inbox"
        private const val SENT = "sentitems"
        private val FOLDERS = listOf(INBOX, SENT)
        private val PREFER = mapOf("Prefer" to "odata.maxpagesize=50, IdType=\"ImmutableId\", outlook.body-content-type=\"text\"")
        private const val SELECT = "id,conversationId,subject,from,toRecipients,ccRecipients,receivedDateTime,sentDateTime,bodyPreview,body,internetMessageId,isDraft"

        internal fun auth(token: StoredAccessToken) = mapOf("Authorization" to "Bearer ${token.value}")

        /** Legacy cursors are a bare Graph URL (Inbox only). */
        internal fun decodeCursor(value: String?): Map<String, String> {
            if (value.isNullOrBlank()) return emptyMap()
            if (value.startsWith("https://")) return mapOf(INBOX to value)
            val json = JsonValue.parseOrNull(value) as? JsonValue.Obj ?: return emptyMap()
            return json.fields.mapNotNull { (k, v) -> v.string?.let { k to it } }.toMap()
        }

        internal fun encodeCursor(links: Map<String, String>): String? =
            if (links.isEmpty()) null else JsonWriter.write(links)

        /** Graph error codes per https://learn.microsoft.com/en-us/graph/errors and delta docs. */
        fun classifyGraph(status: Int, reason: String?): ProviderErrorKind = when {
            status == 410 || reason == "SyncStateNotFound" || reason == "SyncStateInvalid" || reason == "resyncRequired" -> ProviderErrorKind.CursorInvalid
            status == 403 && reason in setOf("ErrorAccessDenied", "Forbidden", "Authorization_RequestDenied") -> ProviderErrorKind.PermissionMissing
            status == 503 || status == 504 -> ProviderErrorKind.Transient
            else -> ConnectorHttpException.defaultKind(status)
        }
    }
}

/**
 * Graph send. Replies use `POST /me/messages/{id}/reply` (threaded by Exchange, recipients from the
 * original). New mail uses `POST /me/sendMail`. Both return 202 Accepted with no body, which means
 * accepted for processing, not delivered. client-request-id carries MINI's outbox ID for tracing.
 */
class OutlookSender(private val graphBase: String = "https://graph.microsoft.com/v1.0") : ProviderSender {
    override val provider = Provider.OUTLOOK

    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome {
        val api = OutlookGraphConnector(graphBase).client(transport, token)
        val headers = mapOf("client-request-id" to clientRequestId)
        try {
            val target = payload.replyTarget
            if (target != null) {
                // Recheck: the target must still exist, be in the approved conversation, and the
                // recipients Exchange will use must equal the approved ones.
                val original = api.get("$graphBase/me/messages/${urlEncode(target)}?\$select=conversationId,from,replyTo")
                if (original.str("conversationId") != payload.conversationId) return SendOutcome.Rejected("reply_target_thread_mismatch")
                val replyTo = original["replyTo"]?.objects.orEmpty().mapNotNull { it.path("emailAddress", "address")?.string }
                val expected = replyTo.ifEmpty { listOfNotNull(original.path("from", "emailAddress", "address")?.string) }
                if (expected.map { it.lowercase() }.toSet() != payload.recipients.map { it.lowercase() }.toSet()) {
                    return SendOutcome.Rejected("reply_recipients_differ_from_approved")
                }
                api.postJson("$graphBase/me/messages/${urlEncode(target)}/reply", mapOf("comment" to payload.body), headers)
            } else {
                val address = { a: String -> mapOf("emailAddress" to mapOf("address" to a)) }
                val message = buildMap<String, Any> {
                    put("subject", payload.subject ?: "")
                    put("body", mapOf("contentType" to "Text", "content" to payload.body))
                    put("toRecipients", payload.recipients.map(address))
                    if (payload.cc.isNotEmpty()) put("ccRecipients", payload.cc.map(address))
                    if (payload.bcc.isNotEmpty()) put("bccRecipients", payload.bcc.map(address))
                }
                api.postJson("$graphBase/me/sendMail", mapOf("message" to message, "saveToSentItems" to true), headers)
            }
        } catch (e: ConnectorHttpException) {
            if (e.kind == ProviderErrorKind.NotFound) return SendOutcome.Rejected("reply_target_missing")
            return ConnectorProviderGateway.classify(e)
        }
        return SendOutcome.Accepted("graph-accepted:$clientRequestId")
    }
}
