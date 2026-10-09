package app.mini.hub.android.connector

import android.content.Context
import app.mini.hub.action.DigestProvider
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorObservationFactory
import app.mini.hub.connector.GmailApiConnector
import app.mini.hub.connector.ProviderHttpResponse
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.SyncCheckpoint
import app.mini.hub.domain.IdGenerator
import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.ObservationStore
import java.net.HttpURLConnection
import java.net.URL

class AndroidProviderHttpTransport : ProviderHttpTransport {
    override fun get(url: String, headers: Map<String, String>): ProviderHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ProviderHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/** One bounded, account-scoped Gmail read. WorkManager or a foreground lifecycle may call this. */
class AndroidGmailConnector(
    context: Context,
    private val observationStore: ObservationStore,
    private val ids: IdGenerator,
    private val digest: DigestProvider,
) {
    private val accounts = AndroidConnectedAccountStore(context)
    private val tokens = AndroidKeystoreAccessTokenStore(context)
    private val connector = GmailApiConnector()
    private val transport = AndroidProviderHttpTransport()

    fun sync(account: ConnectedAccount, checkpoint: SyncCheckpoint, nowMillis: Long): SyncCheckpoint {
        require(account.id.provider == app.mini.hub.domain.Provider.GMAIL)
        val token = tokens.get(account.id) ?: error("Gmail account requires OAuth access-token setup")
        check(!token.isExpired(nowMillis)) { "Gmail access token expired; refresh or reconnect this account" }
        check(accounts.get(account.id)?.providerAccountId == account.providerAccountId) { "Account metadata changed" }
        val result = connector.sync(account, token, checkpoint, transport)
        val factory = ConnectorObservationFactory(ids, digest)
        val observations = result.messages
            .map { factory.fromMessage(account.id, it, nowMillis) }
            .filter { observationStore.getObservationByNotificationKey(it.notificationKey) == null }
        val cursor = IngestCursor(
            source = "gmail:${account.id.key}",
            lastProcessedTimeMillis = nowMillis,
            lastProcessedKey = result.next.cursor,
            processedCount = result.next.processedCount,
            gapCount = result.next.gapCount,
        )
        val persisted = observationStore.persistBatchWithCursors(observations, listOf(cursor))
        check(persisted.isFullySuccessful) { "Gmail sync was not committed: ${persisted.errors.joinToString()}" }
        return result.next
    }
}
