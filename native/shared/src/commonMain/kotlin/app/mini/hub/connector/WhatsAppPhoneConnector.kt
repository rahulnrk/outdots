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
        val messages = client.drainInbound().take(maxMessages)
        val cursor = messages.maxOfOrNull { it.occurredAtMillis }?.toString() ?: checkpoint.cursor
        return SyncResult(
            messages = messages,
            next = checkpoint.copy(cursor = cursor, processedCount = checkpoint.processedCount + messages.size),
            state = if (client.linkedPhoneE164() != null) ConnectionState.Healthy else ConnectionState.AuthorizationRequired,
        )
    }

    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome = client.send(payload)
}
