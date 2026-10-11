package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.ProviderGateway
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/**
 * What a provider said about one send attempt. "Accepted" is provider acceptance only; no adapter
 * here claims delivery or read. Graph returns 202 with no message ID, so its receipt is the
 * client request ID MINI sent, which support can trace but which is not a delivery proof.
 */
sealed class SendOutcome {
    data class Accepted(val receipt: String) : SendOutcome()
    data class Rejected(val reason: String) : SendOutcome()
    data class Unknown(val reason: String) : SendOutcome()
}

/** One provider's send/reply implementation. Called only by [ConnectorProviderGateway]. */
interface ProviderSender {
    val provider: Provider
    fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome
}

/** Recorded evidence that clears gates. Implemented by app settings; defaults to nothing cleared. */
fun interface GateEvidenceSource {
    fun cleared(account: ConnectedAccount): Collection<GateEvidence>
}

/**
 * Bridges the approval outbox to provider senders. At dispatch time it rechecks, in order:
 * the account still exists and is healthy, a credential exists, the account granted the needed
 * scope, every release gate is cleared, and the payload targets that account. Any failure before
 * the network call is a definite rejection. Network/5xx failures after the call started are
 * reported as Unknown and never retried here.
 */
class ConnectorProviderGateway(
    private val accounts: ConnectedAccountStore,
    private val tokens: AccessTokenStore,
    private val senders: List<ProviderSender>,
    private val gates: GateEvidenceSource,
    private val transport: ProviderHttpTransport,
    private val nowMillis: () -> Long,
) : ProviderGateway {
    override fun execute(command: OutboxCommand, payload: ExactActionPayload): GatewayResult {
        val accountId = runCatching { AccountId.parse(payload.accountId) }.getOrNull()
            ?: return GatewayResult.Rejected("account_id_invalid")
        val account = accounts.get(accountId) ?: return GatewayResult.Rejected("account_not_connected")
        if (account.route == ConnectorRoute.ThirdPartyBridge && !PhoneLocalBridgePolicy.allowsRemoteHomeserverPlaintext()) {
            return GatewayResult.Rejected("remote_bridge_plaintext_forbidden")
        }
        val descriptor = ConnectorCatalog.apiRouteFor(accountId.provider)
            ?: return GatewayResult.Rejected("no_api_route_for_provider")
        val sender = senders.firstOrNull { it.provider == accountId.provider }
            ?: return GatewayResult.Rejected("sender_not_implemented")
        if (payload.attachments.isNotEmpty()) return GatewayResult.Rejected("attachments_not_supported_yet")
        // Replying in an existing conversation (either with a specific message anchor or to a conversation thread) requires Reply capability.
        // Providers like Teams, Google Chat, and X DM reply to the conversation directly without a separate message anchor.
        val capability = if (payload.replyTarget != null || payload.conversationId.isNotBlank()) Capability.Reply else Capability.SendNew
        val snapshot = CapabilityResolver.resolve(descriptor, account.grantedScopes, gates.cleared(account), account.state)
        if (capability !in snapshot.enabled) {
            return GatewayResult.Rejected("capability_disabled:${capability.name}:${snapshot.disabled[capability] ?: "unknown"}")
        }
        val token = tokens.get(accountId) ?: return GatewayResult.Rejected("credential_missing")
        if (token.isExpired(nowMillis())) return GatewayResult.Rejected("credential_expired_refresh_first")
        val native = nativeConversationId(payload.conversationId, accountId)
            ?: return GatewayResult.Rejected("conversation_belongs_to_other_account")
        val providerPayload = payload.copy(conversationId = native)
        return when (val outcome = try {
            sender.send(account, token, providerPayload, command.id, transport)
        } catch (e: ConnectorHttpException) {
            classify(e)
        } catch (_: UnsupportedOperationException) {
            SendOutcome.Rejected("transport_read_only")
        }) {
            is SendOutcome.Accepted -> GatewayResult.Accepted(outcome.receipt)
            is SendOutcome.Rejected -> GatewayResult.Rejected(outcome.reason)
            is SendOutcome.Unknown -> GatewayResult.Unknown(outcome.reason)
        }
    }

    companion object {
        /**
         * The approved payload may carry MINI's scoped conversation key ("gmail|id|conversation|t1").
         * Senders need the provider-native ID. A scoped key from a different account is refused, so
         * an approval can never be redirected across accounts. Plain IDs pass through unchanged.
         */
        fun nativeConversationId(value: String, account: AccountId): String? {
            if (value.count { it == '|' } < 3) return value
            val scoped = runCatching { app.mini.hub.domain.ScopedId.parse(value) }.getOrNull() ?: return value
            if (scoped.kind != app.mini.hub.domain.ScopedId.Kind.CONVERSATION) return value
            return scoped.value.takeIf { scoped.account == account }
        }

        /** A failure response is a definite "not sent" except where the provider may have acted. */
        fun classify(e: ConnectorHttpException): SendOutcome = when (e.kind) {
            ProviderErrorKind.Transient -> SendOutcome.Unknown("provider_outcome_unknown:${e.statusCode}")
            ProviderErrorKind.RateLimited -> SendOutcome.Rejected("rate_limited" + (e.retryAfterSeconds?.let { ":retry_after=$it" } ?: ""))
            else -> SendOutcome.Rejected("${e.kind.name.lowercase()}:${e.statusCode}:${e.reason ?: "none"}")
        }
    }
}

internal fun requireNoHeaderInjection(vararg values: String?) {
    values.filterNotNull().forEach { require('\r' !in it && '\n' !in it) { "Header values must be single-line" } }
}

/** RFC 5322 message for Gmail `messages.send`. Plain text only; attachments are rejected upstream. */
object MimeMessageBuilder {
    fun build(
        to: List<String>,
        cc: List<String> = emptyList(),
        subject: String?,
        body: String,
        inReplyTo: String? = null,
        references: String? = null,
    ): String {
        require(to.isNotEmpty()) { "At least one recipient is required" }
        requireNoHeaderInjection(subject, inReplyTo, references, *to.toTypedArray(), *cc.toTypedArray())
        val lines = mutableListOf<String>()
        lines += "To: " + to.joinToString(", ")
        if (cc.isNotEmpty()) lines += "Cc: " + cc.joinToString(", ")
        subject?.let { lines += "Subject: " + encodeHeader(it) }
        inReplyTo?.let { lines += "In-Reply-To: $it" }
        references?.let { lines += "References: $it" }
        lines += "MIME-Version: 1.0"
        lines += "Content-Type: text/plain; charset=UTF-8"
        lines += "Content-Transfer-Encoding: base64"
        val encoded = standardBase64(body.replace("\r\n", "\n").replace("\n", "\r\n").encodeToByteArray()).chunked(76)
        return lines.joinToString("\r\n") + "\r\n\r\n" + encoded.joinToString("\r\n") + "\r\n"
    }

    /** RFC 2047 encoded-word for non-ASCII subjects. */
    fun encodeHeader(value: String): String =
        if (value.all { it.code in 32..126 }) value else "=?UTF-8?B?${standardBase64(value.encodeToByteArray())}?="

    fun standardBase64(bytes: ByteArray): String {
        val url = Base64Url.encode(bytes).replace('-', '+').replace('_', '/')
        return url + "=".repeat((4 - url.length % 4) % 4)
    }
}

/** Gmail `users.messages.send`. Replies stay in the thread and carry In-Reply-To/References. */
class GmailSender(
    private val apiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
) : ProviderSender {
    override val provider = Provider.GMAIL

    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome {
        val api = GmailApiConnector(apiBase).client(transport, token)
        var inReplyTo: String? = null
        var references: String? = null
        val subject = payload.subject
        payload.replyTarget?.let { targetId ->
            // Recheck the reply target at dispatch: it must still exist and belong to the approved thread.
            val target = try {
                api.get("$apiBase/messages/${urlEncode(targetId)}?format=metadata&metadataHeaders=Message-ID&metadataHeaders=References&metadataHeaders=Subject")
            } catch (e: ConnectorHttpException) {
                return if (e.kind == ProviderErrorKind.NotFound) SendOutcome.Rejected("reply_target_missing") else ConnectorProviderGateway.classify(e)
            }
            if (target.str("threadId") != payload.conversationId) return SendOutcome.Rejected("reply_target_thread_mismatch")
            val headers = GmailApiConnector.headers(target)
            inReplyTo = headers["message-id"] ?: return SendOutcome.Rejected("reply_target_has_no_message_id")
            references = listOfNotNull(headers["references"], inReplyTo).joinToString(" ")
            if (subject == null) return SendOutcome.Rejected("subject_required_in_reviewed_payload")
        }
        val mime = MimeMessageBuilder.build(payload.recipients, subject = subject, body = payload.body, inReplyTo = inReplyTo, references = references)
        val body = buildMap<String, Any> {
            put("raw", Base64Url.encode(mime.encodeToByteArray()))
            if (payload.replyTarget != null) put("threadId", payload.conversationId)
        }
        val sent = try {
            api.postJson("$apiBase/messages/send", body)
        } catch (e: ConnectorHttpException) {
            return ConnectorProviderGateway.classify(e)
        }
        val id = sent.str("id") ?: return SendOutcome.Unknown("gmail_response_without_id")
        return SendOutcome.Accepted("gmail:$id")
    }
}
