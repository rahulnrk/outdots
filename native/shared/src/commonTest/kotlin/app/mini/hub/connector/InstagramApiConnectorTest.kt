package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstagramApiConnectorTest {
    private val account = ConnectedAccount(
        AccountId(Provider.INSTAGRAM, "ig-17841"), "17841", "@me", AccountType.SocialProfessional,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy, grantedScopes = setOf(InstagramApiConnector.SCOPE),
    )
    private val token = StoredAccessToken("IGAA" + "x".repeat(60), null)

    private fun payload() = ExactActionPayload(
        account.id.key, "999", ActionRoute.Api, listOf("@friend"), null, "hi", emptyList(), emptyList(), "g", "p",
    )

    @Test
    fun parsesMetaTimestamps() {
        assertEquals(1_760_206_800_000L, InstagramApiConnector.parseTime("2025-10-11T18:20:00+0000"))
        assertEquals(1_700_000_000_000L, InstagramApiConnector.parseTime("1700000000"))
    }

    @Test
    fun windowIsTwentyFourHours() {
        val now = 10_000_000_000L
        assertTrue(InstagramApiConnector.windowOpen(now - 23 * 3600_000L, now))
        assertFalse(InstagramApiConnector.windowOpen(now - 25 * 3600_000L, now))
        assertFalse(InstagramApiConnector.windowOpen(null, now))
    }

    @Test
    fun outsideWindowErrorIsRecognised() {
        val transport = ProviderHttpTransport.of {
            ProviderHttpResponse(400, """{"error":{"message":"outside window","code":10,"error_subcode":2534022}}""")
        }
        val out = InstagramApiSender().send(account, token, payload(), "c1", transport)
        assertEquals(SendOutcome.Rejected(InstagramApiSender.OUTSIDE_WINDOW), out)
    }

    @Test
    fun sendReturnsMessageId() {
        var sentBody = ""
        val transport = ProviderHttpTransport.of { req ->
            sentBody = req.body.orEmpty()
            ProviderHttpResponse(200, """{"recipient_id":"999","message_id":"mid.1"}""")
        }
        val out = InstagramApiSender().send(account, token, payload(), "c1", transport)
        assertEquals(SendOutcome.Accepted("instagram:999:mid.1"), out)
        assertTrue(sentBody.contains("\"999\"") && sentBody.contains("\"hi\""))
    }

    @Test
    fun syncReadsConversationMessages() {
        val transport = ProviderHttpTransport.of { req ->
            val u = req.url
            val body = when {
                "/me/conversations" in u -> """{"data":[{"id":"c1","updated_time":"2025-10-11T18:20:00+0000"}]}"""
                "/c1?fields=messages" in u -> """{"messages":{"data":[{"id":"m1"},{"id":"m2"}]}}"""
                "/m1?" in u -> """{"id":"m1","created_time":"2025-10-11T18:19:00+0000","from":{"id":"999","username":"friend"},"to":{"data":[{"id":"17841"}]},"message":"hello"}"""
                "/m2?" in u -> """{"id":"m2","created_time":"2025-10-11T18:20:00+0000","from":{"id":"17841","username":"me"},"to":{"data":[{"id":"999","username":"friend"}]},"message":"hey"}"""
                else -> "{}"
            }
            ProviderHttpResponse(200, body)
        }
        val res = InstagramApiConnector().sync(account, token, SyncCheckpoint(), transport, 50)
        assertEquals(2, res.messages.size)
        assertTrue(res.messages.all { it.conversationId == "999" })
        assertEquals(MessageDirection.Inbound, res.messages.first { it.providerMessageId == "m1" }.direction)
        assertEquals(MessageDirection.Outbound, res.messages.first { it.providerMessageId == "m2" }.direction)
        assertEquals("@friend", res.messages.first().subject)
    }
}
