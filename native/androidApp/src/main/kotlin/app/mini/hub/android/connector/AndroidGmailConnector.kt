package app.mini.hub.android.connector

import android.content.Context
import android.util.Log
import app.mini.hub.action.DigestProvider
import app.mini.hub.connector.AccountConnectionManager
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorObservationFactory
import app.mini.hub.connector.ConnectorSyncEngine
import app.mini.hub.connector.ConnectorHttpException
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.GmailApiConnector
import app.mini.hub.connector.ProviderHttpResponse
import app.mini.hub.connector.ProviderHttpTransport
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.connector.SyncCheckpoint
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.ObservationStore
import java.net.HttpURLConnection
import java.net.URL

class AndroidProviderHttpTransport : ProviderHttpTransport {
    override fun get(url: String, headers: Map<String, String>): ProviderHttpResponse =
        execute(app.mini.hub.connector.ProviderHttpRequest(app.mini.hub.connector.HttpMethod.GET, url, headers))

    override fun execute(request: app.mini.hub.connector.ProviderHttpRequest): ProviderHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method.name
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
        request.body?.let { bodyText ->
            connection.doOutput = true
            connection.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val headers = connection.headerFields.entries.mapNotNull { (k, v) ->
                k?.let { key -> v.firstOrNull()?.let { key to it } }
            }.toMap()
            ProviderHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), headers)
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
    /** History remained beyond this bounded pass; a prompt follow-up pass should run. */
    val moreAvailable: Boolean = false,
) {
    val processedCount: Long get() = checkpoint.processedCount
    fun logLine(trigger: String): String =
        "trigger=$trigger account=$accountLocalId state=$state fetched=$fetched inserted=$inserted refreshed=$refreshed more=$moreAvailable" +
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
    private val onMessagesFetched: (ConnectedAccount, List<ProviderMessage>) -> Unit = { _, _ -> },
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
            // A revoked or expired account has no credential by design. Keep its specific status and
            // instruction instead of downgrading it to a generic "needs permission" message.
            if (current.state == ConnectionState.Revoked || current.state == ConnectionState.Expired) {
                return GmailSyncReport(current.id.localId, checkpoint, 0, 0, current.state, false, "no_credential")
            }
            markState(current, ConnectionState.AuthorizationRequired, RECONNECT_ACTION)
            return GmailSyncReport(current.id.localId, checkpoint, 0, 0, ConnectionState.AuthorizationRequired, false, "no_credential")
        }
        var refreshed = false
        // Skip the per-message metadata request for messages this account already stored. Without
        // this, every sync re-fetched all 50 listed messages (about 40-70 s per account on device).
        val isKnown: (String) -> Boolean = { id ->
            observationStore.getObservationByNotificationKeyIncludingRemoved("provider:${current.id.key}:$id") != null
        }
        if (token.isExpired(nowMillis + EXPIRY_SKEW_MILLIS)) {
            token = refreshOrMark(current, token, nowMillis)
                ?: return GmailSyncReport(current.id.localId, checkpoint, 0, 0, accounts.get(current.id)?.state ?: current.state, false, "refresh_failed")
            refreshed = true
        }
        val previousCursor = observationStore.getCursor("gmail:${current.id.key}")
        val startHistoryId = checkpoint.cursor ?: previousCursor?.lastProcessedKey
        val incResult = try {
            connector.syncIncremental(current, token, startHistoryId, transport, PAGE_SIZE, isKnown)
        } catch (error: ConnectorHttpException) {
            if (error.statusCode == 401 && !refreshed) {
                // The access token may have been invalidated early (for example by revocation).
                val retryToken = refreshOrMark(current, token, nowMillis)
                    ?: return GmailSyncReport(current.id.localId, checkpoint, 0, 0, accounts.get(current.id)?.state ?: current.state, false, "refresh_failed_after_401")
                refreshed = true
                try {
                    connector.syncIncremental(current, retryToken, startHistoryId, transport, PAGE_SIZE, isKnown)
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
        val newObs = incResult.messages.map { factory.fromMessage(current.id, it, nowMillis) }
            .filter { observationStore.getObservationByNotificationKeyIncludingRemoved(it.notificationKey) == null }
        val tombstones = incResult.deleted.map { factory.fromMessage(current.id, it, nowMillis) }
        val removals = tombstones.map { it.notificationKey to app.mini.hub.domain.UtcInstant(nowMillis) }
        val nextGap = (previousCursor?.gapCount ?: 0L) + if (incResult.gap) 1L else 0L
        val cursor = IngestCursor(
            source = "gmail:${current.id.key}",
            lastProcessedTimeMillis = nowMillis,
            lastProcessedKey = incResult.nextHistoryId,
            processedCount = (previousCursor?.processedCount ?: 0L) + newObs.size,
            gapCount = nextGap,
        )
        val persisted = observationStore.persistAtomicBatch(removals, newObs, listOf(cursor))
        check(persisted.isFullySuccessful) { "Gmail sync was not committed: ${persisted.errors.joinToString()}" }
        onMessagesFetched(current, incResult.messages)
        val userNotice = if (incResult.gap) ConnectorSyncEngine.GAP_NOTICE else null
        accounts.upsert(
            (accounts.get(current.id) ?: current).copy(
                state = ConnectionState.Healthy,
                userActionRequired = userNotice,
                lastSuccessfulSyncMillis = nowMillis,
            ),
        )
        val nextCheckpoint = SyncCheckpoint(cursor = incResult.nextHistoryId, processedCount = cursor.processedCount, gapCount = nextGap)
        return GmailSyncReport(current.id.localId, nextCheckpoint, incResult.listedCount, newObs.size, ConnectionState.Healthy, refreshed, moreAvailable = incResult.moreAvailable)
    }

    fun fetchThread(threadId: String, account: ConnectedAccount): List<app.mini.hub.connector.ProviderMessage> {
        val token = tokens.get(account.id) ?: error("Credential missing for Gmail account")
        return connector.fetchThread(threadId, token, transport, account.displayName)
    }

    /** Same refresh-and-mark path sync uses; for send and conversation reads. */
    fun refreshForUse(account: ConnectedAccount, token: StoredAccessToken, nowMillis: Long): StoredAccessToken? =
        refreshOrMark(account, token, nowMillis)

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
        private const val PAGE_SIZE = 50
        private const val EXPIRY_SKEW_MILLIS = 60_000L
        private val SCOPE_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        const val RECONNECT_ACTION = "Reconnect Gmail to resume this account. Local history is preserved."
        const val REVOKED_ACTION = "Google access for this account was removed. Tap Connect Gmail and choose this account to restore syncing. Local history is preserved."
        const val SCOPE_ACTION = "Gmail read permission is missing. Reconnect and allow reading email. Local history is preserved."
        const val RETRY_ACTION = "Gmail is temporarily unavailable; MINI will retry."
    }
}
