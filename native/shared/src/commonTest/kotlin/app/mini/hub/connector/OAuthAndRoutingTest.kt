package app.mini.hub.connector

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.CommunicationSourcePolicy
import app.mini.hub.observation.SourceAppNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture tests for the OAuth/routing layer. None of these is live-provider evidence. */
class OAuthAndRoutingTest {
    private val redirect = "app.mini.hub://oauth2redirect/"

    @Test
    fun slackAuthorizeUsesUserScopeCommaSeparatedAndPkce() {
        val url = PkceAuthorization.authorizeUrl(OAuthProviderSpecs.slack(redirect + "slack"), "cid", "st", "ch")
        assertTrue(url.startsWith("https://slack.com/oauth/v2/authorize?"))
        assertTrue("user_scope=channels%3Aread%2Cchannels%3Ahistory" in url, url)
        assertFalse("&scope=" in url, "Slack user tokens must not request bot scopes")
        assertTrue("code_challenge_method=S256" in url)
        assertFalse("client_secret" in url)
    }

    @Test
    fun microsoftTeamsSendsScopeOnTokenRequestsAndUsesOrganizations() {
        val spec = OAuthProviderSpecs.teams(redirect + "teams")
        assertTrue(spec.authorizationEndpoint.contains("/organizations/"))
        val exchange = PkceAuthorization.codeExchangeForm(spec, "cid", "code", "ver")
        assertEquals("authorization_code", exchange["grant_type"])
        assertTrue(exchange["scope"]!!.contains("ChatMessage.Send"))
        assertFalse(exchange.containsKey("client_secret"))
        val refresh = PkceAuthorization.refreshForm(spec, "cid", "rt")
        assertEquals("refresh_token", refresh["grant_type"])
        assertTrue(refresh.containsKey("scope"))
    }

    @Test
    fun xRequestsOfflineAccessForRefresh() {
        val spec = OAuthProviderSpecs.xDirectMessages(redirect + "x")
        assertTrue("offline.access" in spec.scopes)
        assertEquals("https://api.x.com/2/oauth2/token", spec.tokenEndpoint)
        assertFalse(PkceAuthorization.codeExchangeForm(spec, "cid", "c", "v").containsKey("scope"))
    }

    @Test
    fun redirectMatchIsExact() {
        val spec = OAuthProviderSpecs.googleChat("com.googleusercontent.apps.123:/oauth2redirect/chat")
        assertTrue(spec.isRedirect("com.googleusercontent.apps.123:/oauth2redirect/chat"))
        assertFalse(spec.isRedirect("com.googleusercontent.apps.123:/oauth2redirect/google"))
        assertFalse(spec.isRedirect("com.googleusercontent.apps.999:/oauth2redirect/chat"))
    }

    @Test
    fun standardTokenResponseParsesAndNormalizesGraphScopes() {
        val result = TokenResponses.parse(
            TokenResponseStyle.Standard, 200,
            """{"access_token":"a","refresh_token":"r","expires_in":3600,"scope":"https://graph.microsoft.com/Chat.Read openid","id_token":"x.y.z"}""",
            nowMillis = 1_000L,
        )
        val grant = assertIs<TokenEndpointResult.Granted>(result).grant
        assertEquals(setOf("Chat.Read", "openid"), grant.grantedScopes)
        assertEquals(1_000L + 3_600_000L, grant.token.expiresAtMillis)
        assertEquals("r", grant.token.refreshToken)
        assertEquals("x.y.z", grant.idToken)
    }

    @Test
    fun slackUserTokenIsReadFromAuthedUser() {
        val result = TokenResponses.parse(
            TokenResponseStyle.SlackUserToken, 200,
            """{"ok":true,"access_token":"xoxb-bot","authed_user":{"id":"U1","access_token":"xoxe.xoxp-user","refresh_token":"xoxe-1","expires_in":43200,"scope":"im:history,chat:write"}}""",
            nowMillis = 0L,
        )
        val grant = assertIs<TokenEndpointResult.Granted>(result).grant
        assertEquals("xoxe.xoxp-user", grant.token.value, "Must use the user token, never the bot token")
        assertEquals(setOf("im:history", "chat:write"), grant.grantedScopes)
    }

    @Test
    fun slackOkFalseIsRejectedEvenWithHttp200() {
        val result = TokenResponses.parse(TokenResponseStyle.SlackUserToken, 200, """{"ok":false,"error":"invalid_auth"}""", 0L)
        val rejected = assertIs<TokenEndpointResult.Rejected>(result)
        assertTrue(rejected.revoked)
    }

    @Test
    fun refreshKeepsPreviousRefreshTokenWhenNotRotated() {
        val result = TokenResponses.parse(TokenResponseStyle.Standard, 200, """{"access_token":"a2","expires_in":60}""", 0L, previousRefreshToken = "old")
        assertEquals("old", assertIs<TokenEndpointResult.Granted>(result).grant.token.refreshToken)
    }

    @Test
    fun invalidGrantIsRevokedAndServerErrorIsTransient() {
        val revoked = TokenResponses.toRefreshOutcome(TokenResponses.parse(TokenResponseStyle.Standard, 400, """{"error":"invalid_grant"}""", 0L))
        assertIs<RefreshOutcome.Revoked>(revoked)
        val transient = TokenResponses.toRefreshOutcome(TokenResponses.parse(TokenResponseStyle.Standard, 503, "", 0L))
        assertIs<RefreshOutcome.Transient>(transient)
    }

    @Test
    fun microsoftAccountKindFromTenantClaim() {
        fun idToken(tid: String) = "h." + Base64Url.encode("""{"tid":"$tid"}""".encodeToByteArray()) + ".s"
        assertEquals(AccountType.MicrosoftPersonal, MicrosoftAccountKind.fromIdToken(idToken(MicrosoftAccountKind.CONSUMER_TENANT_ID)))
        assertEquals(AccountType.MicrosoftWorkSchool, MicrosoftAccountKind.fromIdToken(idToken("72f988bf-0000-0000-0000-000000000000")))
        assertEquals(AccountType.Unknown, MicrosoftAccountKind.fromIdToken(null))
        assertEquals(AccountType.Unknown, MicrosoftAccountKind.fromIdToken("garbage"))
    }

    @Test
    fun replyTargetsPerProvider() {
        assertEquals("m1", ReplyTargets.forProvider(Provider.GMAIL, "m1"))
        assertEquals("1700000000.000100", ReplyTargets.forProvider(Provider.SLACK, "C1:1700000000.000100"))
        assertNull(ReplyTargets.forProvider(Provider.SLACK, "noColon"))
        assertEquals("-100:42", ReplyTargets.forProvider(Provider.TELEGRAM, "-100:42"))
        assertNull(ReplyTargets.forProvider(Provider.TEAMS, "anything"))
        assertNull(ReplyTargets.forProvider(Provider.TWITTER, "e1"))
        assertNull(ReplyTargets.forProvider(Provider.GMAIL, " "))
        assertTrue(ReplyTargets.usesSubject(Provider.OUTLOOK))
        assertFalse(ReplyTargets.usesSubject(Provider.SLACK))
    }

    @Test
    fun emailAddressExtraction() {
        assertEquals("a@b.com", EmailAddress.extract("Alice <a@b.com>"))
        assertEquals("a@b.com", EmailAddress.extract(" a@b.com "))
        assertNull(EmailAddress.extract("Alice"))
        assertNull(EmailAddress.extract(null))
    }

    private fun account(provider: Provider, scopes: Set<String> = emptySet()) = ConnectedAccount(
        AccountId(provider, "a1"), "u1", "User", AccountType.Unknown,
        ConnectorRoute.OfficialProviderApi, ConnectionState.Healthy, scopes,
    )

    @Test
    fun gatePolicyClearsConfigurationGatesOnlyFromRealConfiguration() {
        val none = GatePolicy(emptySet()) { emptyList() }
        assertTrue(none.cleared(account(Provider.SLACK)).isEmpty())

        val configured = GatePolicy(setOf(Provider.SLACK, Provider.TEAMS)) { emptyList() }
        assertEquals(setOf(ReleaseGate.SlackAppInstallation), configured.cleared(account(Provider.SLACK)).map { it.gate }.toSet())
        val teamsGates = configured.cleared(account(Provider.TEAMS)).map { it.gate }.toSet()
        assertEquals(setOf(ReleaseGate.MicrosoftAppRegistration), teamsGates, "Tenant consent is never implied by configuration")
    }

    @Test
    fun gatePolicyNeverClearsQualificationGatesWithoutRecordedEvidence() {
        val policy = GatePolicy(Provider.entries.toSet()) { emptyList() }
        Provider.entries.filter { it != Provider.APP }.forEach { p ->
            val cleared = policy.cleared(account(p, setOf(ConnectorCatalog.TELEGRAM_BOT_SCOPE))).map { it.gate }.toSet()
            assertTrue((cleared intersect GatePolicy.QUALIFICATION_GATES).isEmpty(), "$p cleared a qualification gate by default")
        }
        val withEvidence = GatePolicy(emptySet()) { p ->
            if (p == Provider.GMAIL) listOf(GateEvidence(ReleaseGate.SendQualification, "owner recorded live send 2026-10-10")) else emptyList()
        }
        assertEquals(setOf(ReleaseGate.SendQualification), withEvidence.cleared(account(Provider.GMAIL)).map { it.gate }.toSet())
    }

    @Test
    fun gmailReplyStaysOffUntilSendQualificationIsRecorded() {
        val acct = account(Provider.GMAIL, setOf(GmailApiConnector.SCOPE_READONLY, GmailApiConnector.SCOPE_SEND))
        val off = CapabilityResolver.resolve(ConnectorCatalog.gmail, acct.grantedScopes, GatePolicy(emptySet()) { emptyList() }.cleared(acct), acct.state)
        assertFalse(Capability.Reply in off.enabled)
        assertTrue(off.disabled[Capability.Reply]!!.contains("SendQualification"))
        assertTrue(Capability.ReadBody in off.enabled)
    }

    @Test
    fun telegramBotTokenClearsRouteGateButAiStaysOff() {
        val acct = account(Provider.TELEGRAM, setOf(ConnectorCatalog.TELEGRAM_BOT_SCOPE))
        val snap = CapabilityResolver.resolve(ConnectorCatalog.telegramBot, acct.grantedScopes, GatePolicy(emptySet()) { emptyList() }.cleared(acct), acct.state)
        assertTrue(Capability.ReadBody in snap.enabled)
        assertFalse(Capability.LocalAiDrafting in snap.enabled)
        assertFalse(Capability.Reply in snap.enabled)
    }

    @Test
    fun gatewayStripsScopedConversationKeyAndRefusesOtherAccounts() {
        val mine = AccountId(Provider.GMAIL, "a1")
        val other = AccountId(Provider.GMAIL, "a2")
        val key = ScopedId(mine, ScopedId.Kind.CONVERSATION, "thread-1").key
        assertEquals("thread-1", ConnectorProviderGateway.nativeConversationId(key, mine))
        assertNull(ConnectorProviderGateway.nativeConversationId(key, other))
        assertEquals("spaces/AAA", ConnectorProviderGateway.nativeConversationId("spaces/AAA", mine))
        val pipeInside = ScopedId(mine, ScopedId.Kind.CONVERSATION, "C1|x").key
        assertEquals("C1|x", ConnectorProviderGateway.nativeConversationId(pipeInside, mine))
    }

    @Test
    fun apiRowsAreAlwaysVisibleAndNamed() {
        val policy = CommunicationSourcePolicy { emptySet() }
        assertTrue(policy.isVisibleInInbox("provider.slack"))
        assertFalse(policy.isVisibleInInbox("com.Slack"))
        assertEquals("Slack", SourceAppNames.forPackage("provider.slack"))
        assertEquals("X DMs", SourceAppNames.forPackage("provider.twitter"))
        assertEquals("Messenger", SourceAppNames.forPackage("com.facebook.orca"))
        assertEquals("com.Slack", CommunicationSource.primaryPackageFor(Provider.SLACK))
        assertEquals("org.thoughtcrime.securesms", CommunicationSource.primaryPackageFor(Provider.SIGNAL))
    }
}
