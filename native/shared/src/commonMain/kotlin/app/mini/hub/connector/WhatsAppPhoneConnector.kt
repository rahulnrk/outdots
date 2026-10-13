package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.Provider

/** Phone-local WhatsApp personal inbox. Never a Matrix homeserver. */
class WhatsAppPhoneConnector(
    private val client: WhatsAppPhoneClient,
) : AccountConnector, ProviderSender {
    override val provider = Provider.WHATSAPP
    override val route = ConnectorRoute.PhoneLocalBridge

    override fun discoverAccount(
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
    ): ProviderAccountIdentity {
        val phone = client.linkedPhoneE164()
            ?: throw ConnectorHttpException(401, "WhatsApp companion is not linked", kind = ProviderErrorKind.Unauthorized)
        return ProviderAccountIdentity(provider, phone, phone, AccountType.Unknown)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        val messages = client.drainInbound()
        val cursor = messages.maxOfOrNull { it.occurredAtMillis }?.toString() ?: checkpoint.cursor
        val next = checkpoint.copy(cursor = cursor, processedCount = checkpoint.processedCount + messages.size)
        // Messages already drained must still be stored: report Healthy while any arrived, and
        // surface the logout on the next (empty) pass.
        if (messages.isNotEmpty()) return SyncResult(messages, next, ConnectionState.Healthy)
        return when (client.health()) {
            WhatsAppHealth.Connected, WhatsAppHealth.Reconnecting -> SyncResult(messages, next, ConnectionState.Healthy)
            WhatsAppHealth.LoggedOut -> SyncResult(messages, next, ConnectionState.AuthorizationRequired, LOGGED_OUT_NOTICE)
            WhatsAppHealth.NotLinked -> SyncResult(messages, next, ConnectionState.AuthorizationRequired, NOT_LINKED_NOTICE)
        }
    }

    companion object {
        const val LOGGED_OUT_NOTICE =
            "WhatsApp removed MINI as a linked device. This happens if it was unlinked on your phone or WhatsApp " +
                "was not opened on your phone for about 14 days. Link again in Accounts. Local history is kept."
        const val NOT_LINKED_NOTICE = "WhatsApp is not linked on this phone. Link it in Accounts."
    }

    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome = client.send(payload)

    fun conversation(chatJid: String): List<ProviderMessage> = client.conversation(chatJid)
}
