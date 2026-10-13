package app.mini.hub.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider

/** Opaque encrypted companion-session bytes. Never a Matrix homeserver token. */
data class WhatsAppSessionEnvelope(val ciphertext: ByteArray) {
    init { require(ciphertext.isNotEmpty()) }
    override fun equals(other: Any?): Boolean =
        other is WhatsAppSessionEnvelope && ciphertext.contentEquals(other.ciphertext)
    override fun hashCode(): Int = ciphertext.contentHashCode()
}

sealed class WhatsAppPairing {
    /** QR payload for WhatsApp Linked Devices. Not a homeserver URL or access token. */
    data class QrReady(val qrPayload: String, val expiresAtMillis: Long = 0) : WhatsAppPairing() {
        init {
            require(qrPayload.isNotBlank())
            require(!qrPayload.contains("://", ignoreCase = true)) { "QR payload must not be a URL" }
        }
    }
    data class Linked(val phoneE164: String, val envelope: WhatsAppSessionEnvelope) : WhatsAppPairing() {
        init {
            require(PhoneLocalBridgePolicy.classifyIdentity(phoneE164) == BridgeIdentityKind.ProviderAccount)
        }
    }
    data class PhoneCode(val code: String) : WhatsAppPairing() {
        init { require(code.isNotBlank()) }
    }
    data class Unavailable(val reason: String) : WhatsAppPairing()
    data object Cancelled : WhatsAppPairing()
}

sealed class WhatsAppReconnect {
    data class Restored(val phoneE164: String) : WhatsAppReconnect()
    data class Unavailable(val reason: String) : WhatsAppReconnect()
}

interface EncryptedBridgeSessionStore {
    fun save(accountId: AccountId, envelope: WhatsAppSessionEnvelope)
    fun load(accountId: AccountId): WhatsAppSessionEnvelope?
    fun clear(accountId: AccountId): Boolean
}

class InMemoryEncryptedBridgeSessionStore : EncryptedBridgeSessionStore {
    private val lock = Any()
    private val blobs = linkedMapOf<String, ByteArray>()
    override fun save(accountId: AccountId, envelope: WhatsAppSessionEnvelope) = synchronized(lock) {
        blobs[accountId.key] = envelope.ciphertext.copyOf()
    }
    override fun load(accountId: AccountId): WhatsAppSessionEnvelope? = synchronized(lock) {
        blobs[accountId.key]?.copyOf()?.let(::WhatsAppSessionEnvelope)
    }
    override fun clear(accountId: AccountId): Boolean = synchronized(lock) { blobs.remove(accountId.key) != null }
}

/** Companion-device client. Implementations must keep plaintext on the phone. */
interface WhatsAppPhoneClient {
    fun startPairing(): WhatsAppPairing
    fun startPhonePairing(phoneE164: String): WhatsAppPairing =
        WhatsAppPairing.Unavailable(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
    fun pollPairing(): WhatsAppPairing = startPairing()
    fun cancelPairing() {}
    fun restoreAndReconnect(envelope: WhatsAppSessionEnvelope): WhatsAppReconnect
    fun linkedPhoneE164(): String?
    fun send(payload: ExactActionPayload): SendOutcome
    fun drainInbound(): List<ProviderMessage> = emptyList()
    fun conversation(chatJid: String): List<ProviderMessage> = emptyList()
    fun persistSnapshot(): ByteArray? = null
    fun logout() {}
    fun unencryptedSessionFiles(): List<String> = emptyList()
    /** Live connection health of the linked companion session. */
    fun health(): WhatsAppHealth = if (linkedPhoneE164() != null) WhatsAppHealth.Connected else WhatsAppHealth.NotLinked
}

/** What the companion runtime can tell us about the link right now. */
enum class WhatsAppHealth {
    Connected,
    /** Linked but the socket is down; whatsmeow reconnects by itself. */
    Reconnecting,
    /** WhatsApp removed this linked device (user unlinked it, or the primary phone was offline too long). */
    LoggedOut,
    NotLinked,
}

/**
 * Default client until a licensed Android runtime is packaged. Pairing and send fail closed.
 * Hosting remains phone-local by design: this is not a Matrix homeserver adapter.
 */
class UnprovisionedWhatsAppClient : WhatsAppPhoneClient {
    override fun startPairing(): WhatsAppPairing =
        WhatsAppPairing.Unavailable(RUNTIME_NOT_PACKAGED)
    override fun restoreAndReconnect(envelope: WhatsAppSessionEnvelope): WhatsAppReconnect =
        WhatsAppReconnect.Unavailable(RUNTIME_NOT_PACKAGED)
    override fun linkedPhoneE164(): String? = null
    override fun send(payload: ExactActionPayload): SendOutcome =
        SendOutcome.Rejected(RUNTIME_NOT_PACKAGED)

    companion object {
        const val RUNTIME_NOT_PACKAGED = "whatsapp_runtime_not_packaged"
        const val PHONE_SESSION_SCOPE = "whatsapp.phone_session"
    }
}

class UnprovisionedWhatsAppSender : ProviderSender {
    override val provider = Provider.WHATSAPP
    override fun send(
        account: ConnectedAccount,
        token: StoredAccessToken,
        payload: ExactActionPayload,
        clientRequestId: String,
        transport: ProviderHttpTransport,
    ): SendOutcome = SendOutcome.Rejected(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
}

/**
 * Coordinates encrypted local session storage with a phone-local WhatsApp client.
 * Does not accept a Matrix homeserver URL or token.
 */
class WhatsAppPhoneSession(
    private val accountId: AccountId,
    private val store: EncryptedBridgeSessionStore,
    private val client: WhatsAppPhoneClient = UnprovisionedWhatsAppClient(),
) {
    init { require(accountId.provider == Provider.WHATSAPP) }

    fun pair(): WhatsAppPairing {
        val result = client.startPairing()
        persistIfLinked(result)
        return result
    }

    fun pairPhone(phoneE164: String): WhatsAppPairing {
        val result = client.startPhonePairing(phoneE164)
        persistIfLinked(result)
        return result
    }

    fun pollPairing(): WhatsAppPairing {
        val result = client.pollPairing()
        persistIfLinked(result)
        return result
    }

    fun cancelPairing() = client.cancelPairing()

    fun logout() {
        client.logout()
        store.clear(accountId)
    }

    fun drainInbound(): List<ProviderMessage> = client.drainInbound().also {
        client.persistSnapshot()?.let { bytes -> store.save(accountId, WhatsAppSessionEnvelope(bytes)) }
    }

    fun conversation(chatJid: String): List<ProviderMessage> = client.conversation(chatJid)

    private fun persistIfLinked(result: WhatsAppPairing) {
        if (result is WhatsAppPairing.Linked) store.save(accountId, result.envelope)
        else client.persistSnapshot()?.takeIf { it.isNotEmpty() }?.let { store.save(accountId, WhatsAppSessionEnvelope(it)) }
    }

    fun reconnect(): WhatsAppReconnect {
        val envelope = store.load(accountId)
            ?: return WhatsAppReconnect.Unavailable("whatsapp_session_missing")
        val result = client.restoreAndReconnect(envelope)
        client.persistSnapshot()?.takeIf { it.isNotEmpty() }?.let { store.save(accountId, WhatsAppSessionEnvelope(it)) }
        return result
    }

    fun proof(): BridgeSessionProof {
        val envelope = store.load(accountId)
        val phone = client.linkedPhoneE164()
        val identity = phone?.let(PhoneLocalBridgePolicy::classifyIdentity) ?: BridgeIdentityKind.Unknown
        val linkedLocal = envelope != null && identity == BridgeIdentityKind.ProviderAccount && client !is UnprovisionedWhatsAppClient
        return BridgeSessionProof(
            hosting = BridgeHosting.PhoneLocal,
            identityKind = identity,
            encryptedLocalSession = envelope != null,
            plaintextProcessedOnPhone = true,
            sendReceiptKind = if (linkedLocal) BridgeReceiptKind.ProviderForwarding else BridgeReceiptKind.Unknown,
            reconnectsWithoutPastedHomeserverToken = linkedLocal,
        )
    }
}
