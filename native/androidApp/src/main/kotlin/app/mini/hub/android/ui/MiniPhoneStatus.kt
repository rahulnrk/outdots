package app.mini.hub.android.ui

/**
 * Header status. Notification permission, listener connection and account health are separate.
 * A temporarily disconnected listener must never read as "accounts need setup".
 */
internal enum class MiniPhoneStatus(val label: String, val healthy: Boolean) {
    OnThisPhone("On this phone", true),
    ListenerReconnecting("Reconnecting", false),
    NotificationAccessNeeded("Notification access off", false),
    AccountsNeedAttention("Account needs attention", false),
    NoAccounts("Needs setup", false),
}

internal fun miniPhoneStatus(
    permissionGranted: Boolean,
    listenerConnected: Boolean,
    accountCount: Int,
    accountsNeedingAttention: Int,
): MiniPhoneStatus = when {
    accountCount == 0 -> MiniPhoneStatus.NoAccounts
    accountsNeedingAttention > 0 -> MiniPhoneStatus.AccountsNeedAttention
    !permissionGranted -> MiniPhoneStatus.NotificationAccessNeeded
    !listenerConnected -> MiniPhoneStatus.ListenerReconnecting
    else -> MiniPhoneStatus.OnThisPhone
}

internal fun accountNeedsAttention(state: app.mini.hub.connector.ConnectionState): Boolean =
    state != app.mini.hub.connector.ConnectionState.Healthy &&
        state != app.mini.hub.connector.ConnectionState.Authorized &&
        state != app.mini.hub.connector.ConnectionState.Syncing

internal fun miniPhoneStatus(
    state: InboxViewState,
    accounts: List<app.mini.hub.connector.ConnectedAccount>,
): MiniPhoneStatus = miniPhoneStatus(
    permissionGranted = state.isPermissionGranted,
    listenerConnected = state.isListenerConnected,
    accountCount = accounts.size,
    accountsNeedingAttention = accounts.count { accountNeedsAttention(it.state) },
)
