package app.mini.hub.connector

import app.mini.hub.domain.Provider

/** Where bridge plaintext is processed. A Matrix homeserver is never phone-local. */
enum class BridgeHosting { PhoneLocal, RemoteHomeserver, None }

/** What an identity string actually proved. */
enum class BridgeIdentityKind { ProviderAccount, MatrixUser, Unknown }

/** What an accepted send receipt actually proved. */
enum class BridgeReceiptKind { ProviderForwarding, HomeserverEvent, Unknown }

/**
 * Facts about one bridge session. Product-ready phone-local sessions must satisfy every field;
 * the current Matrix Client-Server adapter does not.
 */
data class BridgeSessionProof(
    val hosting: BridgeHosting,
    val identityKind: BridgeIdentityKind,
    val encryptedLocalSession: Boolean,
    val plaintextProcessedOnPhone: Boolean,
    val sendReceiptKind: BridgeReceiptKind,
    val reconnectsWithoutPastedHomeserverToken: Boolean,
)

/**
 * Pilot policy: WhatsApp, Messenger, Instagram and LinkedIn process plaintext on the phone.
 * A remote Matrix homeserver is not an allowed substitute.
 */
object PhoneLocalBridgePolicy {
    val PILOT_PROVIDERS: Set<Provider> = setOf(
        Provider.WHATSAPP,
        Provider.FACEBOOK,
        Provider.INSTAGRAM,
        Provider.LINKEDIN,
    )

    fun isPilotProvider(provider: Provider): Boolean =
        provider in PILOT_PROVIDERS || provider == Provider.WHATSAPP_BUSINESS

    fun allowsRemoteHomeserverPlaintext(): Boolean = false

    fun unmetRequirements(proof: BridgeSessionProof): List<String> = buildList {
        if (proof.hosting != BridgeHosting.PhoneLocal) add("hosting_not_phone_local")
        if (proof.identityKind != BridgeIdentityKind.ProviderAccount) add("identity_not_provider_account")
        if (!proof.encryptedLocalSession) add("session_not_encrypted_on_phone")
        if (!proof.plaintextProcessedOnPhone) add("plaintext_not_processed_on_phone")
        if (proof.sendReceiptKind != BridgeReceiptKind.ProviderForwarding) add("receipt_not_provider_forwarding")
        if (!proof.reconnectsWithoutPastedHomeserverToken) add("reconnect_requires_pasted_homeserver_token")
    }

    fun allowsSession(proof: BridgeSessionProof): Boolean = unmetRequirements(proof).isEmpty()

    /** Observed facts of [MatrixBridgeConnector]. This is not a phone-local provider session. */
    fun matrixHomeserverAdapterProof(): BridgeSessionProof = BridgeSessionProof(
        hosting = BridgeHosting.RemoteHomeserver,
        identityKind = BridgeIdentityKind.MatrixUser,
        encryptedLocalSession = false,
        plaintextProcessedOnPhone = false,
        sendReceiptKind = BridgeReceiptKind.HomeserverEvent,
        reconnectsWithoutPastedHomeserverToken = false,
    )

    fun classifyIdentity(providerAccountId: String): BridgeIdentityKind {
        val id = providerAccountId.trim()
        return if (id.startsWith("@") && ':' in id) BridgeIdentityKind.MatrixUser else BridgeIdentityKind.Unknown
    }

    fun classifyReceipt(receipt: String): BridgeReceiptKind =
        if (receipt.startsWith("matrix:")) BridgeReceiptKind.HomeserverEvent else BridgeReceiptKind.Unknown
}
