package app.mini.hub.connector

import app.mini.hub.domain.Provider

/**
 * Account-health transitions for phone-local Instagram ingest.
 * Official Graph API sync must not decide this route's expiry.
 */
object InstagramPhoneLocalHealth {
    const val SESSION_EXPIRED = "session_expired"
    const val PARTIAL_HISTORY =
        "Partial history: only a bounded recent window is fetched while MINI is open."
    const val RECONNECT =
        "Reconnect Instagram to resume this account. Local history is kept."

    fun applyIngestResult(
        account: ConnectedAccount,
        success: Boolean,
        partial: Boolean,
        failureCode: String?,
        nowMillis: Long,
    ): ConnectedAccount {
        require(account.id.provider == Provider.INSTAGRAM) { "not an Instagram account" }
        require(account.route == ConnectorRoute.PhoneLocalBridge) { "not a phone-local Instagram account" }
        return when {
            success -> account.copy(
                state = ConnectionState.Healthy,
                userActionRequired = if (partial) PARTIAL_HISTORY else null,
                lastSuccessfulSyncMillis = nowMillis,
            )
            failureCode == SESSION_EXPIRED -> account.copy(
                state = ConnectionState.Expired,
                userActionRequired = RECONNECT,
            )
            // Transient/network/mapping failures keep the current state so a valid
            // session is not false-expired by temporary read problems.
            else -> account
        }
    }
}
