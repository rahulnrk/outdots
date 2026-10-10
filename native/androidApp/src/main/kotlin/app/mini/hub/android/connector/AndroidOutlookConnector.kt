package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.connector.AccountConnectionManager
import app.mini.hub.connector.AccountSyncReport
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorSyncEngine
import app.mini.hub.connector.OutlookGraphConnector
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.persistence.ObservationStore

/**
 * Android Outlook connector host. Handles bounded delta syncs for all registered Outlook accounts
 * using [ConnectorSyncEngine] and platform storage.
 */
class AndroidOutlookConnector(
    context: Context,
    private val observationStore: ObservationStore,
    private val digest: DigestProvider,
    private val transport: ProviderHttpTransport = AndroidProviderHttpTransport(),
) {
    private val accounts = AndroidConnectedAccountStore(context)
    private val tokens = AndroidKeystoreAccessTokenStore(context)
    private val connector = OutlookGraphConnector()
    private val refresher = MicrosoftTokenRefresher()

    private val engine = ConnectorSyncEngine(
        accounts = accounts,
        tokens = tokens,
        store = observationStore,
        digest = digest,
        connectors = mapOf(Provider.OUTLOOK to connector),
        refreshers = mapOf(Provider.OUTLOOK to refresher),
        transport = transport,
    )

    fun connectionManager() = AccountConnectionManager(accounts, tokens, IdGenerator { "unused-$it" })

    fun syncAllConnected(nowMillis: Long, trigger: String): List<AccountSyncReport> =
        engine.syncAll(nowMillis).onEach { Log.i(LOG_TAG, it.logLine(trigger)) }

    fun sync(account: ConnectedAccount, nowMillis: Long): AccountSyncReport =
        engine.syncAccount(account, nowMillis).also { Log.i(LOG_TAG, it.logLine("manual")) }

    fun fetchConversation(conversationId: String, account: ConnectedAccount): List<app.mini.hub.connector.ProviderMessage> {
        val token = tokens.get(account.id) ?: error("Credential missing for Outlook account")
        return connector.fetchConversation(conversationId, token, transport)
    }

    companion object {
        const val LOG_TAG = "MiniOutlookSync"
    }
}
