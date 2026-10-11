package app.mini.hub.android.connector

import app.mini.hub.domain.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T6: only qualified routes are offered; the Matrix bridge is unreachable from any UI. */
class ConnectRoutePolicyTest {
    private fun route(p: Provider, matrix: Boolean = false, token: Boolean = false, phoneLocal: Boolean = false) =
        ConnectRouteInfo(
            p, p.name, "kinds", true, "status",
            usesTokenEntry = token, usesMatrixEntry = matrix, usesPhoneLocalPairing = phoneLocal,
        )

    private val all = listOf(
        route(Provider.GMAIL), route(Provider.OUTLOOK), route(Provider.TEAMS), route(Provider.SLACK),
        route(Provider.GOOGLE_CHAT), route(Provider.TWITTER), route(Provider.TELEGRAM, token = true),
        route(Provider.WHATSAPP, phoneLocal = true),
        route(Provider.WHATSAPP, matrix = true), route(Provider.WHATSAPP_BUSINESS, matrix = true),
        route(Provider.INSTAGRAM, matrix = true), route(Provider.LINKEDIN, matrix = true),
        route(Provider.FACEBOOK),
    )

    @Test(timeout = 30_000)
    fun releaseOffersOnlyGmailAndOutlook() {
        val offered = ConnectRoutePolicy.visible(all, isDebugBuild = false)
        assertEquals(listOf(Provider.GMAIL, Provider.OUTLOOK), offered.map { it.provider })
    }

    @Test(timeout = 30_000)
    fun debugAddsChatRoutesLabelledNotLiveQualified() {
        val offered = ConnectRoutePolicy.visible(all, isDebugBuild = true)
        assertEquals(
            listOf(Provider.GMAIL, Provider.OUTLOOK, Provider.TEAMS, Provider.SLACK, Provider.GOOGLE_CHAT, Provider.TWITTER, Provider.TELEGRAM, Provider.WHATSAPP),
            offered.map { it.provider },
        )
        offered.filter { it.provider !in setOf(Provider.GMAIL, Provider.OUTLOOK) }.forEach {
            assertTrue(it.title.contains("not live-qualified"), "${it.provider} must be labelled")
        }
        offered.filter { it.provider in setOf(Provider.GMAIL, Provider.OUTLOOK) }.forEach {
            assertFalse(it.title.contains("not live-qualified"))
        }
        assertTrue(offered.any { it.provider == Provider.WHATSAPP && it.usesPhoneLocalPairing })
        assertTrue(offered.none { it.usesMatrixEntry })
    }

    @Test(timeout = 30_000)
    fun matrixBridgeIsUnreachableInEveryVariant() {
        for (debug in listOf(false, true)) {
            val offered = ConnectRoutePolicy.visible(all, isDebugBuild = debug)
            assertTrue(offered.none { it.usesMatrixEntry }, "debug=$debug")
            assertTrue(offered.none { it.provider == Provider.FACEBOOK }, "debug=$debug")
            if (!debug) {
                assertTrue(offered.none { it.provider == Provider.WHATSAPP }, "debug=$debug")
            }
        }
    }
}
