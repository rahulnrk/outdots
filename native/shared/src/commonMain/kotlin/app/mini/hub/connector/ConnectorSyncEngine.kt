package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.Provider
import app.mini.hub.observation.IngestCursor
import app.mini.hub.persistence.ObservationStore

/** Result of a provider token refresh, independent of the provider's wire format. */
sealed class RefreshOutcome {
    data class Refreshed(val token: StoredAccessToken) : RefreshOutcome()
    /** Refresh credential is dead (revoked, expired, rotated elsewhere). User must reconnect. */
    data class Revoked(val code: String) : RefreshOutcome()
    data object NoRefreshToken : RefreshOutcome()
    data class Transient(val reason: String) : RefreshOutcome()
}

fun interface TokenRefresher {
    fun refresh(token: StoredAccessToken, nowMillis: Long): RefreshOutcome
}

/** Privacy-safe outcome: counts and states only, never content or tokens. */
data class AccountSyncReport(
    val accountKey: String,
    val listed: Int,
    val inserted: Int,
    val updated: Int,
    val state: ConnectionState,
    val refreshed: Boolean,
    val gapRecorded: Boolean = false,
    val skippedReason: String? = null,
) {
    fun logLine(trigger: String): String =
        "trigger=$trigger account=$accountKey state=$state listed=$listed inserted=$inserted updated=$updated refreshed=$refreshed gap=$gapRecorded" +
            (skippedReason?.let { " skipped=$it" } ?: "")
}

/**
 * Provider-neutral sync host for every API connector except Gmail's specialized path. For each
 * account it: skips disconnected/revoked accounts without downgrading their status; refreshes an
 * expired token once; runs one bounded [AccountConnector.sync]; stores new observations, newer
 * revisions and tombstones together with the cursor in one transaction; and maps failures to a
 * per-account health state. One account's failure never touches another account.
 */
class ConnectorSyncEngine(
    private val accounts: ConnectedAccountStore,
    private val tokens: AccessTokenStore,
    private val store: ObservationStore,
    private val digest: DigestProvider,
    private val connectors: Map<Provider, AccountConnector>,
    private val refreshers: Map<Provider, TokenRefresher>,
    private val transport: ProviderHttpTransport,
    private val pageSize: Int = 50,
    private val expirySkewMillis: Long = 60_000L,
    private val onMessagesFetched: (ConnectedAccount, List<ProviderMessage>) -> Unit = { _, _ -> },
) {
    fun syncAll(nowMillis: Long): List<AccountSyncReport> =
        accounts.list().filter { it.id.provider in connectors }.map { account ->
            try {
                syncAccount(account, nowMillis)
            } catch (e: Exception) {
                AccountSyncReport(account.id.key, 0, 0, 0, accounts.get(account.id)?.state ?: account.state, false, skippedReason = e::class.simpleName)
            }
        }

    fun syncAccount(account: ConnectedAccount, nowMillis: Long): AccountSyncReport {
        val connector = connectors[account.id.provider] ?: error("No connector for ${account.id.provider}")
        val current = accounts.get(account.id) ?: error("Account is not registered")
        val key = current.id.key
        if (current.state == ConnectionState.Disconnected) return skip(current, "disconnected")
        var token = tokens.get(current.id) ?: run {
            if (current.state in TERMINAL_WITHOUT_CREDENTIAL) return skip(current, "no_credential")
            mark(current, ConnectionState.AuthorizationRequired, reconnect(current))
            return AccountSyncReport(key, 0, 0, 0, ConnectionState.AuthorizationRequired, false, skippedReason = "no_credential")
        }
        var refreshed = false
        if (token.isExpired(nowMillis + expirySkewMillis)) {
            token = refresh(current, token, nowMillis) ?: return skip(accounts.get(current.id) ?: current, "refresh_failed")
            refreshed = true
        }
        val source = cursorSource(current)
        val previous = store.getCursor(source)
        val checkpoint = SyncCheckpoint(previous?.lastProcessedKey, previous?.processedCount ?: 0, previous?.gapCount ?: 0)
        val result = try {
            connector.sync(current, token, checkpoint, transport, pageSize)
        } catch (e: ConnectorHttpException) {
            if (e.kind == ProviderErrorKind.Unauthorized && !refreshed) {
                val retry = refresh(current, token, nowMillis) ?: return skip(accounts.get(current.id) ?: current, "refresh_failed_after_401")
                refreshed = true
                try { connector.sync(current, retry, checkpoint, transport, pageSize) } catch (again: ConnectorHttpException) {
                    classify(current, again); throw again
                }
            } else {
                classify(current, e); throw e
            }
        }
        if (result.state == ConnectionState.AuthorizationRequired) {
            mark(current, ConnectionState.AuthorizationRequired, result.userActionRequired ?: reconnect(current))
            return AccountSyncReport(key, 0, 0, 0, ConnectionState.AuthorizationRequired, refreshed, skippedReason = "permission_missing")
        }
        val factory = ConnectorObservationFactory(digest)
        var inserted = 0
        var updated = 0
        val toWrite = result.messages.mapNotNull { message ->
            val observation = factory.fromMessage(current.id, message, nowMillis)
            val existing = store.getObservationByNotificationKeyIncludingRemoved(observation.notificationKey)
            when {
                existing == null -> if (message.isDeleted) null else observation.also { inserted++ }
                // Same revision and same removal state: an exact duplicate delivery. Skip.
                existing.revision >= observation.revision && existing.isRemoved == observation.isRemoved -> null
                // Newer edit, or a provider deletion of a stored message: keep the same ID, new revision.
                else -> observation.copy(
                    id = existing.id,
                    revision = maxOf(existing.revision + 1, observation.revision),
                    textFragments = if (message.isDeleted) existing.textFragments else observation.textFragments,
                ).also { updated++ }
            }
        }
        val gap = result.next.gapCount > checkpoint.gapCount
        val cursor = IngestCursor(
            source = source,
            lastProcessedTimeMillis = nowMillis,
            lastProcessedKey = result.next.cursor,
            processedCount = result.next.processedCount,
            gapCount = result.next.gapCount,
        )
        val persisted = store.persistBatchWithCursors(toWrite, listOf(cursor))
        check(persisted.isFullySuccessful) { "Sync not committed for $key: ${persisted.errors.size} errors" }
        // Learning is fed only authoritative provider messages after the transaction commits. The
        // callback is local and must not perform network I/O.
        onMessagesFetched(current, result.messages)
        accounts.upsert(
            (accounts.get(current.id) ?: current).copy(
                state = ConnectionState.Healthy,
                userActionRequired = if (gap) GAP_NOTICE else null,
                lastSuccessfulSyncMillis = nowMillis,
            ),
        )
        return AccountSyncReport(key, result.listedCount, inserted, updated, ConnectionState.Healthy, refreshed, gap)
    }

    /** Same refresh-and-mark path sync uses; for callers that need a usable token now (send, reads). */
    fun refreshForUse(account: ConnectedAccount, token: StoredAccessToken, nowMillis: Long): StoredAccessToken? =
        refresh(account, token, nowMillis)

    private fun refresh(account: ConnectedAccount, token: StoredAccessToken, nowMillis: Long): StoredAccessToken? {
        val refresher = refreshers[account.id.provider]
        if (refresher == null || token.refreshToken == null) {
            mark(account, ConnectionState.Expired, reconnect(account))
            return null
        }
        return when (val outcome = refresher.refresh(token, nowMillis)) {
            is RefreshOutcome.Refreshed -> {
                // Providers that rotate refresh tokens (Slack PKCE, X) return a new one; keep the old if absent.
                val merged = outcome.token.copy(refreshToken = outcome.token.refreshToken ?: token.refreshToken)
                tokens.put(account.id, merged); merged
            }
            is RefreshOutcome.Revoked -> {
                tokens.remove(account.id)
                mark(account, ConnectionState.Revoked, "Access was removed at the provider. Reconnect to resume. Local history is kept.")
                null
            }
            RefreshOutcome.NoRefreshToken -> { mark(account, ConnectionState.Expired, reconnect(account)); null }
            is RefreshOutcome.Transient -> { mark(account, ConnectionState.Degraded, RETRY_NOTICE); null }
        }
    }

    private fun classify(account: ConnectedAccount, e: ConnectorHttpException) {
        val (state, action) = when (e.kind) {
            ProviderErrorKind.Unauthorized -> ConnectionState.Revoked to "Access was removed or expired. Reconnect to resume. Local history is kept."
            ProviderErrorKind.PermissionMissing -> ConnectionState.AuthorizationRequired to "A required permission is missing (${e.reason ?: "unknown"}). Reconnect or ask your administrator."
            ProviderErrorKind.RateLimited -> ConnectionState.Degraded to "Rate limited by the provider" + (e.retryAfterSeconds?.let { "; retry after ${it}s" } ?: "") + "."
            else -> ConnectionState.Degraded to RETRY_NOTICE
        }
        mark(account, state, action)
    }

    private fun skip(account: ConnectedAccount, reason: String) =
        AccountSyncReport(account.id.key, 0, 0, 0, account.state, false, skippedReason = reason)

    private fun mark(account: ConnectedAccount, state: ConnectionState, action: String?) {
        accounts.upsert((accounts.get(account.id) ?: account).copy(state = state, userActionRequired = action))
    }

    private fun reconnect(account: ConnectedAccount) = "Reconnect ${account.id.provider.wire} to resume this account. Local history is kept."

    companion object {
        private val TERMINAL_WITHOUT_CREDENTIAL = setOf(ConnectionState.Revoked, ConnectionState.Expired)
        const val RETRY_NOTICE = "The provider is temporarily unavailable; MINI will retry."
        const val GAP_NOTICE = "Sync restarted after the provider cursor expired. Some changes from before this point may be missing."

        fun cursorSource(account: ConnectedAccount) = "${account.id.provider.wire}:${account.id.key}"
    }
}
