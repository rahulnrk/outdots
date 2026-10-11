package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ExactActionPayload
import app.mini.hub.action.GatewayResult
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.UtcInstant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversationReplyCapabilityTest {
    private val now = UtcInstant(1_700_000_000_000L)

    private fun account(provider: Provider, scopes: Set<String>) = ConnectedAccount(
        id = AccountId(provider, "test_user"),
        providerAccountId = "test_user",
        displayName = "Tester",
        accountType = AccountType.GoogleConsumer,
        route = ConnectorRoute.OfficialProviderApi,
        state = ConnectionState.Healthy,
        grantedScopes = scopes,
    )

    private fun dummyCommand() = OutboxCommand("c1", "a1", "digest", ActionRoute.Api, OutboxState.Dispatching, now)

    private fun payload(account: AccountId, convNativeId: String, replyTarget: String? = null) = ExactActionPayload(
        accountId = account.key,
        conversationId = convNativeId,
        route = ActionRoute.Api,
        recipients = listOf("recipient@example.com"),
        subject = null,
        body = "Hello there",
        attachments = emptyList(),
        sourceVersions = emptyList(),
        grantVersion = "grant-v1-api-send",
        policyVersion = "policy-v1-api-review",
        replyTarget = replyTarget,
    )

    @Test(timeout = 30_000)
    fun teamsConversationReplyWithoutMessageAnchorResolvesToReplyCapability() {
        // Teams chat has no message-level thread anchor (replyTarget is null or conversation-level)
        // Senders for Teams, Google Chat, X implement conversation replies.
        // The gateway must evaluate Capability.Reply when replying in an existing conversation.
        val acct = account(Provider.TEAMS, setOf("ChatMessage.Send"))
        val accountStore = InMemoryConnectedAccountStore().apply { upsert(acct) }
        val tokenStore = InMemoryAccessTokenStore().apply { put(acct.id, StoredAccessToken("tok", null)) }

        val sender = TeamsChatSender("https://graph.test")
        val gates = GateEvidenceSource {
            listOf(
                GateEvidence(ReleaseGate.SendQualification, "ev"),
                GateEvidence(ReleaseGate.MicrosoftAppRegistration, "ev"),
                GateEvidence(ReleaseGate.TenantAdminConsent, "ev"),
            )
        }
        val transport = ProviderHttpTransport.of { _ -> ProviderHttpResponse(200, "{\"id\":\"teams_msg_1\"}") }

        val gateway = ConnectorProviderGateway(
            accounts = accountStore,
            tokens = tokenStore,
            senders = listOf(sender),
            gates = gates,
            transport = transport,
            nowMillis = { now.epochMillis },
        )

        // Sending a reply to an existing Teams conversation (without individual message anchor)
        val result = gateway.execute(dummyCommand(), payload(acct.id, "19:meeting_chat@thread.v2", replyTarget = null))
        assertTrue(result is GatewayResult.Accepted, "Teams conversation reply failed with: $result")
    }

    @Test(timeout = 30_000)
    fun googleChatConversationReplyResolvesToReplyCapability() {
        val acct = account(Provider.GOOGLE_CHAT, setOf("https://www.googleapis.com/auth/chat.messages.create"))
        val accountStore = InMemoryConnectedAccountStore().apply { upsert(acct) }
        val tokenStore = InMemoryAccessTokenStore().apply { put(acct.id, StoredAccessToken("tok", null)) }

        val sender = GoogleChatSender("https://chat.test")
        val gates = GateEvidenceSource {
            listOf(
                GateEvidence(ReleaseGate.SendQualification, "ev"),
                GateEvidence(ReleaseGate.GoogleChatAppConfiguration, "ev"),
            )
        }
        val transport = ProviderHttpTransport.of { _ -> ProviderHttpResponse(200, "{\"name\":\"spaces/s1/messages/m1\"}") }

        val gateway = ConnectorProviderGateway(
            accounts = accountStore,
            tokens = tokenStore,
            senders = listOf(sender),
            gates = gates,
            transport = transport,
            nowMillis = { now.epochMillis },
        )

        val result = gateway.execute(dummyCommand(), payload(acct.id, "spaces/s1", replyTarget = null))
        assertTrue(result is GatewayResult.Accepted, "Google Chat reply to space failed with: $result")
    }
}
