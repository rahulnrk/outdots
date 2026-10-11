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
            !route.usesMatrixEntry && route.provider !in MATRIX_BRIDGE_PROVIDERS && when (route.provider) {
                in RELEASE_PROVIDERS -> true
                in DEBUG_ONLY_PROVIDERS -> isDebugBuild
                else -> false
            }
        }.map { route ->
            if (route.provider in DEBUG_ONLY_PROVIDERS) route.copy(title = route.title + NOT_LIVE_QUALIFIED) else route
        }
}
