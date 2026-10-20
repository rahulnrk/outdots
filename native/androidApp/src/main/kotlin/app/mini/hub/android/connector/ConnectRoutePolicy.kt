package app.mini.hub.android.connector

import app.mini.hub.domain.Provider

/**
 * Which connect routes the Accounts screen may offer. Pure, so it is unit-tested for both build
 * variants. Connector code and tests for hidden routes stay in the repository; only the UI entry
 * points are withheld.
 */
object ConnectRoutePolicy {
    /** Qualified for release: official provider APIs that have been exercised end to end. */
    val RELEASE_PROVIDERS: List<Provider> = listOf(Provider.GMAIL, Provider.OUTLOOK)

    /** Fixture-tested chat routes, offered only in debug builds and labelled as such. */
    val DEBUG_ONLY_PROVIDERS: Set<Provider> = setOf(
        Provider.TEAMS, Provider.SLACK, Provider.GOOGLE_CHAT, Provider.TELEGRAM, Provider.TWITTER,
    )

    /** Debug phone-local sessions. Never a Matrix homeserver. */
    val DEBUG_PHONE_LOCAL_PROVIDERS: Set<Provider> = setOf(Provider.WHATSAPP, Provider.INSTAGRAM)

    /**
     * Matrix-homeserver bridge routes route plaintext through a third-party server, which is
     * against product policy. Never offered in any build variant.
     */
    val MATRIX_BRIDGE_PROVIDERS: Set<Provider> = setOf(
        Provider.WHATSAPP, Provider.WHATSAPP_BUSINESS, Provider.INSTAGRAM, Provider.LINKEDIN,
    )

    const val NOT_LIVE_QUALIFIED = " (not live-qualified)"

    fun visible(all: List<ConnectRouteInfo>, isDebugBuild: Boolean): List<ConnectRouteInfo> =
        all.filter { route ->
            when {
                route.usesMatrixEntry -> false
                route.usesNotificationFallback -> isDebugBuild
                route.usesPhoneLocalPairing || route.usesInstagramPhoneLocalSignIn ->
                    isDebugBuild && route.provider in DEBUG_PHONE_LOCAL_PROVIDERS
                route.provider in MATRIX_BRIDGE_PROVIDERS -> false
                route.provider in RELEASE_PROVIDERS -> true
                route.provider in DEBUG_ONLY_PROVIDERS -> isDebugBuild
                else -> false
            }
        }.map { route ->
            val needsLabel = route.provider in DEBUG_ONLY_PROVIDERS ||
                route.usesPhoneLocalPairing ||
                route.usesInstagramPhoneLocalSignIn ||
                route.usesNotificationFallback
            if (needsLabel) route.copy(title = route.title + NOT_LIVE_QUALIFIED) else route
        }
}
