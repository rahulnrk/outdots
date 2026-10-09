package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.connector.AccountConnectionManager
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorObservationFactory
import app.mini.hub.connector.ConnectorHttpException
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.GmailApiConnector
import app.mini.hub.connector.ProviderHttpResponse
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.SyncCheckpoint
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
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

/** Privacy-safe sync outcome: counts and states only, never message content or tokens. */
data class GmailSyncReport(
    val accountLocalId: String,
    val checkpoint: SyncCheckpoint,
    val fetched: Int,
    val inserted: Int,
    val state: ConnectionState,
    val refreshed: Boolean,
    val skippedReason: String? = null,
) {
    val processedCount: Long get() = checkpoint.processedCount
    fun logLine(trigger: String): String =
        "trigger=$trigger account=$accountLocalId state=$state fetched=$fetched inserted=$inserted refreshed=$refreshed" +
            (skippedReason?.let { " skipped=$it" } ?: "")
}

/**
 * One bounded, account-scoped Gmail read. Each account is handled independently: a failure in one
 * account only changes that account's state. Health transitions:
 * - access token expired -> refresh with the stored refresh token (no user action)
 * - refresh rejected with invalid_grant -> Revoked, user must reconnect
 * - 403 insufficient scope -> AuthorizationRequired, user must reconnect and grant read access
 * - 429/5xx/network -> Degraded, retried later
 */
class AndroidGmailConnector(
    context: Context,
    private val observationStore: ObservationStore,
    private val digest: DigestProvider,
    private val refresher: GoogleTokenRefresher = GoogleTokenRefresher(),
    private val transport: ProviderHttpTransport = AndroidProviderHttpTransport(),
) {
    private val accounts = AndroidConnectedAccountStore(context)
    private val tokens = AndroidKeystoreAccessTokenStore(context)
    private val connector = GmailApiConnector()

    fun connectionManager() = AccountConnectionManager(accounts, tokens, IdGenerator { "unused-$it" })

    /** Syncs the newest page for every Gmail account that is still connected. */
    fun syncAllConnected(nowMillis: Long, trigger: String): List<GmailSyncReport> =
        accounts.list()
            .filter { it.id.provider == Provider.GMAIL }
            .map { account ->
                val report = try {
                    sync(account, SyncCheckpoint(), nowMillis)
                } catch (e: Exception) {
                    val latest = accounts.get(account.id)
                    GmailSyncReport(account.id.localId, SyncCheckpoint(), 0, 0, latest?.state ?: account.state, false, e.javaClass.simpleName)
                }
                Log.i(LOG_TAG, report.logLine(trigger))
                report
            }

    fun sync(account: ConnectedAccount, checkpoint: SyncCheckpoint, nowMillis: Long): GmailSyncReport {
        require(account.id.provider == Provider.GMAIL)
        val current = accounts.get(account.id) ?: error("Gmail account is not registered")
        check(current.providerAccountId == account.providerAccountId) { "Account metadata changed" }
        if (current.state == ConnectionState.Disconnected) {
            return GmailSyncReport(current.id.localId, checkpoint, 0, 0, current.state, false, "disconnected")
        }
        var token = tokens.get(current.id) ?: run {
            markState(current, ConnectionState.AuthorizationRequired, RECONNECT_ACTION)
            return GmailSyncReport(current.id.localId, checkpoint, 0, 0, ConnectionState.AuthorizationRequired, false, "no_credential")
        }
        var refreshed = false
        if (token.isExpired(nowMillis + EXPIRY_SKEW_MILLIS)) {
            token = refreshOrMark(current, token, nowMillis)
                ?: return GmailSyncReport(current.id.localId, checkpoint, 0, 0, accounts.get(current.id)?.state ?: current.state, false, "refresh_failed")
            refreshed = true
        }
        val result = try {
            connector.sync(current, token, checkpoint, transport)
        } catch (error: ConnectorHttpException) {
            if (error.statusCode == 401 && !refreshed) {
                // The access token may have been invalidated early (for example by revocation).
                val retryToken = refreshOrMark(current, token, nowMillis)
                    ?: return GmailSyncReport(current.id.localId, checkpoint, 0, 0, accounts.get(current.id)?.state ?: current.state, false, "refresh_failed_after_401")
                refreshed = true
                try {
                    connector.sync(current, retryToken, checkpoint, transport)
                } catch (retryError: ConnectorHttpException) {
                    classifyAndMark(current, retryError)
                    throw retryError
                }
            } else {
                classifyAndMark(current, error)
                throw error
            }
        }
        val factory = ConnectorObservationFactory(digest)
        val observations = result.messages
            .map { factory.fromMessage(current.id, it, nowMillis) }
            .filter { observationStore.getObservationByNotificationKeyIncludingRemoved(it.notificationKey) == null }
        val cursor = IngestCursor(
            source = "gmail:${current.id.key}",
            lastProcessedTimeMillis = nowMillis,
            lastProcessedKey = result.next.cursor,
            processedCount = result.next.processedCount,
            gapCount = result.next.gapCount,
        )
        val persisted = observationStore.persistBatchWithCursors(observations, listOf(cursor))
        check(persisted.isFullySuccessful) { "Gmail sync was not committed: ${persisted.errors.joinToString()}" }
        accounts.upsert(
            (accounts.get(current.id) ?: current).copy(
                state = ConnectionState.Healthy,
                userActionRequired = null,
                lastSuccessfulSyncMillis = nowMillis,
            ),
        )
        return GmailSyncReport(current.id.localId, result.next, result.messages.size, observations.size, ConnectionState.Healthy, refreshed)
    }

    private fun refreshOrMark(account: ConnectedAccount, token: StoredAccessToken, nowMillis: Long): StoredAccessToken? =
        when (val outcome = refresher.refresh(token, nowMillis)) {
            is TokenRefreshOutcome.Refreshed -> {
                tokens.put(account.id, outcome.token)
                Log.i(LOG_TAG, "account=${account.id.localId} token refreshed")
                outcome.token
            }
            is TokenRefreshOutcome.Revoked -> {
                // The refresh token is dead. Drop it so nothing keeps retrying with it.
                tokens.remove(account.id)
                markState(account, ConnectionState.Revoked, REVOKED_ACTION)
                Log.w(LOG_TAG, "account=${account.id.localId} refresh rejected (${outcome.errorCode}); marked Revoked")
                null
            }
            is TokenRefreshOutcome.NoRefreshToken -> {
                markState(account, ConnectionState.Expired, RECONNECT_ACTION)
                null
            }
            is TokenRefreshOutcome.Transient -> {
                markState(account, ConnectionState.Degraded, RETRY_ACTION)
                Log.w(LOG_TAG, "account=${account.id.localId} refresh transient failure (${outcome.reason})")
                null
            }
        }

    private fun classifyAndMark(account: ConnectedAccount, error: ConnectorHttpException) {
        val (state, action) = when {
            error.statusCode == 401 -> ConnectionState.Revoked to REVOKED_ACTION
            error.statusCode == 403 && error.reason in SCOPE_REASONS -> ConnectionState.AuthorizationRequired to SCOPE_ACTION
            error.statusCode == 403 -> ConnectionState.Degraded to "Gmail refused access (${error.reason ?: "unknown"}). Check the Google Cloud project, then retry."
            else -> ConnectionState.Degraded to RETRY_ACTION
        }
        markState(account, state, action)
        Log.w(LOG_TAG, "account=${account.id.localId} HTTP ${error.statusCode} (${error.reason ?: "none"}); marked $state")
    }

    private fun markState(account: ConnectedAccount, state: ConnectionState, action: String?) {
        accounts.upsert((accounts.get(account.id) ?: account).copy(state = state, userActionRequired = action))
    }

    companion object {
        const val LOG_TAG = "MiniGmailSync"
        private const val EXPIRY_SKEW_MILLIS = 60_000L
        private val SCOPE_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        const val RECONNECT_ACTION = "Reconnect Gmail to resume this account. Local history is preserved."
        const val REVOKED_ACTION = "Google access for this account was removed. Tap Connect Gmail and choose this account to restore syncing. Local history is preserved."
        const val SCOPE_ACTION = "Gmail read permission is missing. Reconnect and allow reading email. Local history is preserved."
        const val RETRY_ACTION = "Gmail is temporarily unavailable; MINI will retry."
    }
}
