package app.mini.hub.android.connector

import android.content.Context
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.UnprovisionedWhatsAppClient
import app.mini.hub.connector.WhatsAppPairing
import app.mini.hub.connector.WhatsAppPhoneClient
import app.mini.hub.connector.WhatsAppPhoneConnector
import app.mini.hub.connector.WhatsAppPhoneSession
import app.mini.hub.connector.WhatsAppReconnect
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import java.io.File

class WhatsAppAndroidHost(
    context: Context,
    private val accounts: AndroidConnectedAccountStore,
    private val tokens: AndroidKeystoreAccessTokenStore,
) {
    private val appContext = context.applicationContext
    val watchDir: File = File(appContext.filesDir, "whatsapp-runtime").apply { mkdirs() }
    val sessionStore = SqlCipherWhatsAppSessionStore(appContext)
    val nativeClient: WhatsAppPhoneClient =
        GomobileWhatsAppClient.load(watchDir.absolutePath) ?: UnprovisionedWhatsAppClient()
    val packaged: Boolean = nativeClient is GomobileWhatsAppClient
    val connector = WhatsAppPhoneConnector(nativeClient)
    private val placeholderId = AccountId(Provider.WHATSAPP, "pending")
    @Volatile private var session = WhatsAppPhoneSession(placeholderId, sessionStore, nativeClient)

    fun runtimeAvailable(): Boolean = packaged

    fun startPairing(): WhatsAppPairing = session.pair()
    fun startPhonePairing(phoneE164: String): WhatsAppPairing = session.pairPhone(phoneE164)
    fun pollPairing(): WhatsAppPairing = session.pollPairing()
    fun cancelPairing() = session.cancelPairing()
    fun conversation(chatJid: String) = session.conversation(chatJid)
    fun mediaThumbnail(id: String): ByteArray? = (nativeClient as? GomobileWhatsAppClient)?.mediaThumbnail(id)
    fun downloadMedia(id: String): java.io.File? {
        val dir = File(appContext.cacheDir, "wa-media").apply { mkdirs() }
        return (nativeClient as? GomobileWhatsAppClient)?.downloadMedia(id, dir.absolutePath)?.let(::File)
    }

    fun completeLinked(linked: WhatsAppPairing.Linked): ConnectedAccount {
        val localId = linked.phoneE164.trimStart('+')
        val accountId = AccountId(Provider.WHATSAPP, localId)
        sessionStore.save(accountId, linked.envelope)
        session = WhatsAppPhoneSession(accountId, sessionStore, nativeClient)
        val account = ConnectedAccount(
            id = accountId,
            providerAccountId = linked.phoneE164,
            displayName = linked.phoneE164,
            accountType = AccountType.Unknown,
            route = ConnectorRoute.PhoneLocalBridge,
            state = ConnectionState.Healthy,
            grantedScopes = setOf(UnprovisionedWhatsAppClient.PHONE_SESSION_SCOPE),
        )
        accounts.upsert(account)
        tokens.put(accountId, StoredAccessToken("phone-local-session", null))
        return account
    }

    fun restoreOnStart(): WhatsAppReconnect? {
        val saved = sessionStore.loadFirstWhatsApp() ?: return null
        session = WhatsAppPhoneSession(saved.first, sessionStore, nativeClient)
        val result = session.reconnect()
        if (result is WhatsAppReconnect.Restored) {
            val existing = accounts.get(saved.first)
            if (existing != null) {
                accounts.upsert(existing.copy(state = ConnectionState.Healthy, providerAccountId = result.phoneE164))
            }
        }
        return result
    }

    fun logout(accountId: AccountId) {
        session.logout()
        sessionStore.clear(accountId)
        tokens.remove(accountId)
    }

    fun persistIfDirty() {
        nativeClient.persistSnapshot()?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val id = accounts.list().firstOrNull { it.id.provider == Provider.WHATSAPP }?.id ?: return
            sessionStore.save(id, app.mini.hub.connector.WhatsAppSessionEnvelope(bytes))
        }
    }
}
