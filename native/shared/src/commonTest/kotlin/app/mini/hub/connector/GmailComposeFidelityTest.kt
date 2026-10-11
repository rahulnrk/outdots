package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmailComposeFidelityTest {
    private val account = ConnectedAccount(
        AccountId(Provider.GMAIL, "me"), "me@example.com", "Me", AccountType.GoogleConsumer,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy, grantedScopes = setOf("https://www.googleapis.com/auth/gmail.send"),
    )

    @Test
    fun newEmailWithCcBccKeepsBodyExactly() {
        val body = "Hi *team* \u2014 price 2 * 3 = 6 \ud83d\ude00\n\u0928\u092e\u0938\u094d\u0924\u0947\n  indented line\nend"
        var raw = ""
        val transport = ProviderHttpTransport.of { req ->
            raw = Regex("\"raw\"\\s*:\\s*\"([^\"]+)\"").find(req.body.orEmpty())!!.groupValues[1]
            ProviderHttpResponse(200, """{"id":"sent1"}""")
        }
        val payload = ExactActionPayload(
            account.id.key, "", ActionRoute.Api, listOf("new.person@example.org"), "Hello \u2014 \u0928\u092e\u0938\u094d\u0924\u0947", body,
            emptyList(), emptyList(), "g", "p", cc = listOf("cc@example.org"), bcc = listOf("bcc@example.org"),
        )
        val out = GmailSender().send(account, StoredAccessToken("t", null), payload, "c1", transport)
        assertEquals(SendOutcome.Accepted("gmail:sent1"), out)
        val mime = Base64Url.decode(raw).decodeToString()
        assertTrue(mime.contains("To: new.person@example.org"))
        assertTrue(mime.contains("Cc: cc@example.org"))
        assertTrue(mime.contains("Bcc: bcc@example.org"))
        val encodedBody = mime.substringAfter("\r\n\r\n").replace("\r\n", "")
        val decoded = decodeStdBase64(encodedBody).decodeToString().replace("\r\n", "\n")
        assertEquals(body, decoded)
    }

    private fun decodeStdBase64(s: String): ByteArray =
        Base64Url.decode(s.replace('+', '-').replace('/', '_').trimEnd('='))
}
