package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.Provider

/**
 * Microsoft Graph mail adapter (delegated, personal Outlook.com or work/school). Reads the Inbox
 * with per-folder delta: the full `@odata.deltaLink` URL is the cursor and is stored opaquely.
 * Authentication is MSAL on Android (public client, no secret); this adapter only sees tokens.
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
     * One bounded delta pass over the Inbox. [SyncCheckpoint.cursor] is either null (start a new
     * round), an `@odata.nextLink` (round in progress) or an `@odata.deltaLink` (round finished).
     * A rejected/expired token (410 or SyncStateNotFound) restarts with a recorded gap.
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
        val start = checkpoint.cursor?.takeIf { it.startsWith(graphBase) } ?: initialDelta()
        var url: String? = start
        var gaps = checkpoint.gapCount
        val messages = mutableListOf<ProviderMessage>()
        var nextCursor: String? = checkpoint.cursor
        var pages = 0
        while (url != null && pages < MAX_PAGES && messages.size < maxMessages) {
            val page = try {
                api.call(ProviderHttpRequest(HttpMethod.GET, url, auth(accessToken) + PREFER))
            } catch (e: ConnectorHttpException) {
                if (e.kind == ProviderErrorKind.CursorInvalid && url != initialDelta()) {
                    // Sync state expired: restart the round. Changes in between are not replayed.
                    gaps++
                    url = initialDelta(); nextCursor = null; continue
                }
                throw e
            }
            page["value"]?.objects.orEmpty().forEach { item -> parse(item)?.let(messages::add) }
            val next = page.str("@odata.nextLink")
            val delta = page.str("@odata.deltaLink")
            nextCursor = next ?: delta ?: nextCursor
            url = next
            pages++
        }
        return SyncResult(
            messages = messages,
            next = SyncCheckpoint(nextCursor, checkpoint.processedCount + messages.size, gaps),
            state = ConnectionState.Healthy,
        )
    }

    /** Full selected conversation with bodies, newest last. */
    fun fetchConversation(conversationId: String, accessToken: StoredAccessToken, transport: ProviderHttpTransport): List<ProviderMessage> {
        val filter = urlEncode("conversationId eq '${conversationId.replace("'", "''")}'")
        val page = client(transport, accessToken).call(
            ProviderHttpRequest(
                HttpMethod.GET,
                "$graphBase/me/messages?\$filter=$filter&\$top=50&\$select=$SELECT",
                auth(accessToken) + PREFER,
            ),
        )
        return page["value"]?.objects.orEmpty().mapNotNull { parse(it) }.sortedBy { it.occurredAtMillis }
    }

    private fun initialDelta() = "$graphBase/me/mailFolders/inbox/messages/delta?\$select=$SELECT"

    private fun parse(item: JsonValue): ProviderMessage? {
        val id = item.str("id") ?: return null
        if (item["@removed"] != null) {
            return ProviderMessage(id, id, "", null, "", 0L, isDeleted = true)
        }
        val from = item.path("from", "emailAddress")
        val sender = listOfNotNull(from?.str("name"), from?.str("address")?.let { "<$it>" }).joinToString(" ").ifBlank { "Unknown sender" }
        val body = item["body"]
        val text = body?.str("content")?.let { if (body.str("contentType").equals("html", true)) MimeText.htmlToText(it) else it }
        return ProviderMessage(
            providerMessageId = id,
            conversationId = item.str("conversationId") ?: id,
            sender = sender,
            subject = item.str("subject"),
            snippet = item.str("bodyPreview").orEmpty(),
            occurredAtMillis = Rfc3339.parseMillis(item.str("receivedDateTime")) ?: 0L,
            bodyText = text,
            direction = if (item["isDraft"]?.bool == true) MessageDirection.Outbound else MessageDirection.Inbound,
            replyAnchor = item.str("internetMessageId"),
            recipients = item["toRecipients"]?.objects.orEmpty().mapNotNull { it.path("emailAddress", "address")?.string },
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
        private val PREFER = mapOf("Prefer" to "odata.maxpagesize=50, IdType=\"ImmutableId\", outlook.body-content-type=\"text\"")
        private const val SELECT = "id,conversationId,subject,from,toRecipients,receivedDateTime,bodyPreview,body,internetMessageId,isDraft"

        internal fun auth(token: StoredAccessToken) = mapOf("Authorization" to "Bearer ${token.value}")

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
                val message = mapOf(
                    "subject" to (payload.subject ?: ""),
                    "body" to mapOf("contentType" to "Text", "content" to payload.body),
                    "toRecipients" to payload.recipients.map { mapOf("emailAddress" to mapOf("address" to it)) },
                )
                api.postJson("$graphBase/me/sendMail", mapOf("message" to message, "saveToSentItems" to true), headers)
            }
        } catch (e: ConnectorHttpException) {
            if (e.kind == ProviderErrorKind.NotFound) return SendOutcome.Rejected("reply_target_missing")
            return ConnectorProviderGateway.classify(e)
        }
        return SendOutcome.Accepted("graph-accepted:$clientRequestId")
    }
}
