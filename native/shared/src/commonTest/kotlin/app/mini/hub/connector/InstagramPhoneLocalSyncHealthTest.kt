package app.mini.hub.connector

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.observation.CommunicationSourcePolicy
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstagramPhoneLocalSyncHealthTest {
    private val digest = DigestProvider { "dig-$it" }
    private val now = 1_700_000_000_000L

    private fun phoneLocalAccount(state: ConnectionState = ConnectionState.Healthy) = ConnectedAccount(
        id = AccountId(Provider.INSTAGRAM, "33037782034"),
        providerAccountId = "33037782034",
        displayName = "@owner",
        accountType = AccountType.Unknown,
        route = ConnectorRoute.PhoneLocalBridge,
        state = state,
        grantedScopes = setOf("instagram.phone-local-session"),
        userActionRequired = InstagramPhoneLocalHealth.PARTIAL_HISTORY,
    )

    @Test
    fun officialApiSyncSkipsPhoneLocalInstagramWithoutCallingConnector() {
        val accounts = InMemoryConnectedAccountStore()
        val tokens = InMemoryAccessTokenStore()
        val store = InMemoryObservationStore()
        val account = phoneLocalAccount()
        accounts.upsert(account)
        tokens.put(account.id, StoredAccessToken("phone-local-session", expiresAtMillis = null))
        var connectorCalls = 0
        val engine = ConnectorSyncEngine(
            accounts = accounts,
            tokens = tokens,
            store = store,
            digest = digest,
            connectors = mapOf(
                Provider.INSTAGRAM to object : AccountConnector {
                    override val provider = Provider.INSTAGRAM
                    override val route = ConnectorRoute.OfficialProviderApi
                    override fun discoverAccount(
                        accessToken: StoredAccessToken,
                        transport: ProviderHttpTransport,
                    ): ProviderAccountIdentity = error("discover must not be called")
                    override fun sync(
                        account: ConnectedAccount,
                        accessToken: StoredAccessToken,
                        checkpoint: SyncCheckpoint,
                        transport: ProviderHttpTransport,
                        maxMessages: Int,
                    ): SyncResult {
                        connectorCalls++
                        error("phone-local Instagram must not use the official API connector")
                    }
                },
            ),
            refreshers = emptyMap(),
            transport = ProviderHttpTransport.of { error("transport must not be called") },
        )

        val report = engine.syncAccount(account, now)

        assertEquals("phone_local_instagram", report.skippedReason)
        assertEquals(0, connectorCalls)
        assertEquals(ConnectionState.Healthy, accounts.get(account.id)?.state)
        assertEquals(InstagramPhoneLocalHealth.PARTIAL_HISTORY, accounts.get(account.id)?.userActionRequired)
    }

    @Test
    fun transientIngestFailureDoesNotExpireHealthySession() {
        val account = phoneLocalAccount(ConnectionState.Healthy)
        val updated = InstagramPhoneLocalHealth.applyIngestResult(
            account = account,
            success = false,
            partial = false,
            failureCode = "network_error",
            nowMillis = now,
        )
        assertEquals(ConnectionState.Healthy, updated.state)
        assertEquals(account.userActionRequired, updated.userActionRequired)
    }

    @Test
    fun sessionExpiredIngestRequiresReconnect() {
        val account = phoneLocalAccount(ConnectionState.Healthy)
        val updated = InstagramPhoneLocalHealth.applyIngestResult(
            account = account,
            success = false,
            partial = false,
            failureCode = InstagramPhoneLocalHealth.SESSION_EXPIRED,
            nowMillis = now,
        )
        assertEquals(ConnectionState.Expired, updated.state)
        assertEquals(InstagramPhoneLocalHealth.RECONNECT, updated.userActionRequired)
    }

    @Test
    fun successfulIngestRestoresHealthyFromExpired() {
        val account = phoneLocalAccount(ConnectionState.Expired).copy(
            userActionRequired = InstagramPhoneLocalHealth.RECONNECT,
        )
        val updated = InstagramPhoneLocalHealth.applyIngestResult(
            account = account,
            success = true,
            partial = true,
            failureCode = null,
            nowMillis = now,
        )
        assertEquals(ConnectionState.Healthy, updated.state)
        assertEquals(InstagramPhoneLocalHealth.PARTIAL_HISTORY, updated.userActionRequired)
        assertEquals(now, updated.lastSuccessfulSyncMillis)
    }

    @Test
    fun providerInstagramRowsRemainVisibleWithoutNotificationSourceToggle() {
        val policy = CommunicationSourcePolicy { emptySet() }
        assertTrue(policy.isVisibleInInbox(ConnectorObservationFactory.packageFor(Provider.INSTAGRAM)))
        assertFalse(policy.isVisibleInInbox("com.instagram.android"))
        assertEquals("provider.instagram", ConnectorObservationFactory.packageFor(Provider.INSTAGRAM))
    }
}
