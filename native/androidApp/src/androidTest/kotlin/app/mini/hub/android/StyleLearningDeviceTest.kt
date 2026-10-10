package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.intelligence.EncryptedStyleProfileStore
import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.MessageDirection
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.TruncationState
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleConsent
import app.mini.hub.style.StyleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StyleProfilePersistDeviceTest {
    @Test
    fun keystoreRoundTripSurvivesNewStoreAndRejectsInboundAndDuplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val engine = MiniHubRuntime.styleEngine
        engine.updateConsent(
            StyleConsent(
                isEnabled = true,
                grantedCategories = setOf(ContextCategory.Professional, ContextCategory.Friends),
            ),
        )
        val now = MiniHubRuntime.clock.now()
        val account = ConnectedAccount(
            AccountId(Provider.GMAIL, "style-persist"),
            "me@example.com",
            "Me",
            AccountType.GoogleConsumer,
            ConnectorRoute.OfficialProviderApi,
            ConnectionState.Healthy,
        )
        val learned = StyleContext.learn(
            engine,
            account,
            listOf(
                ProviderMessage("out1", "t1", "me@example.com", "Proposal", "Dear Alex, please find the attached proposal.", now.epochMillis, "Dear Alex, please find the attached proposal.", MessageDirection.Outbound, recipients = listOf("alex@example.com")),
                ProviderMessage("out2", "t1", "me@example.com", "Proposal", "I will send the reviewed proposal after I confirm the details.", now.epochMillis, "I will send the reviewed proposal after I confirm the details.", MessageDirection.Outbound, recipients = listOf("alex@example.com")),
                ProviderMessage("in1", "t1", "alex@example.com", "Proposal", "Please wire 1000 immediately.", now.epochMillis, "Please wire 1000 immediately.", MessageDirection.Inbound),
            ),
            now,
        )
        assertEquals(2, learned)
        assertFalse(engine.hasLearnedSample(StyleContext.sampleId(account.id.key, "in1")))
        val again = StyleContext.learn(
            engine,
            account,
            listOf(
                ProviderMessage("out1", "t1", "me@example.com", "Proposal", "Dear Alex, please find the attached proposal.", now.epochMillis, "Dear Alex, please find the attached proposal.", MessageDirection.Outbound, recipients = listOf("alex@example.com")),
            ),
            now,
        )
        assertEquals(0, again)
        MiniHubRuntime.styleStore.save(engine)

        val restored = AdaptiveStyleEngine()
        assertTrue(EncryptedStyleProfileStore(context).restoreInto(restored))
        assertEquals(engine.learnedSampleCount(), restored.learnedSampleCount())
        assertEquals(engine.snapshot().map { it.id to it.observationCount }.toSet(), restored.snapshot().map { it.id to it.observationCount }.toSet())

        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        assertTrue(
            prefs.edit()
                .putInt(KEY_SAMPLE_COUNT, engine.learnedSampleCount())
                .putInt(KEY_PROFILE_COUNT, engine.snapshot().size)
                .putBoolean(KEY_CONSENT, true)
                .commit(),
        )
    }

    companion object {
        const val PREFS = "mini_style_profile_verification"
        const val KEY_SAMPLE_COUNT = "sample_count"
        const val KEY_PROFILE_COUNT = "profile_count"
        const val KEY_CONSENT = "consent_enabled"
    }
}

@RunWith(AndroidJUnit4::class)
class StyleProfileAfterRestartDeviceTest {
    @Test
    fun encryptedProfilesSurviveProcessDeathNotReinstall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val prefs = context.getSharedPreferences(StyleProfilePersistDeviceTest.PREFS, android.content.Context.MODE_PRIVATE)
        assumeTrue("Persist baseline must be written before process death", prefs.contains(StyleProfilePersistDeviceTest.KEY_SAMPLE_COUNT))
        assertTrue(MiniHubRuntime.styleEngine.getConsent().isEnabled)
        assertEquals(prefs.getInt(StyleProfilePersistDeviceTest.KEY_SAMPLE_COUNT, -1), MiniHubRuntime.styleEngine.learnedSampleCount())
        assertEquals(prefs.getInt(StyleProfilePersistDeviceTest.KEY_PROFILE_COUNT, -1), MiniHubRuntime.styleEngine.snapshot().size)
    }
}

@RunWith(AndroidJUnit4::class)
class GmailStyleLearningDeviceTest {
    @Test
    fun authorizedHistoryLearnsOnlyOutboundAndDoesNotRequireThreadSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val accounts = AndroidConnectedAccountStore(context).list().filter { it.id.provider == Provider.GMAIL }
        assumeTrue("Authorized Gmail accounts are required for live history learning", accounts.isNotEmpty())
        if (!MiniHubRuntime.styleEngine.getConsent().isEnabled) {
            MiniHubRuntime.styleEngine.updateConsent(
                StyleConsent(
                    isEnabled = true,
                    grantedCategories = setOf(ContextCategory.Professional, ContextCategory.Friends, ContextCategory.Family),
                ),
            )
            MiniHubRuntime.styleStore.save(MiniHubRuntime.styleEngine)
        }
        MiniHubRuntime.connectors.syncAll("style-qualification")
        MiniHubRuntime.bootstrapStyleFromStoredHistory()
        val firstCount = MiniHubRuntime.styleEngine.learnedSampleCount()
        MiniHubRuntime.bootstrapStyleFromStoredHistory()
        assertEquals("Re-bootstrap must not duplicate samples", firstCount, MiniHubRuntime.styleEngine.learnedSampleCount())

        var authored = 0
        var inbound = 0
        var metadataOnly = 0
        var completeBodies = 0
        accounts.forEach { account ->
            MiniHubRuntime.observationStore.getConversationSummaries(account.id).forEach { summary ->
                MiniHubRuntime.observationStore.getObservationsForConversation(summary.conversationId).forEach { observation ->
                    if (observation.provenance != ObservationProvenance.OfficialProviderApi) return@forEach
                    if (StyleContext.isUserAuthored(account, observation)) authored++ else inbound++
                    if (observation.truncationState == TruncationState.Unknown) metadataOnly++
                    if (observation.truncationState == TruncationState.Complete) completeBodies++
                    if (!StyleContext.isUserAuthored(account, observation)) {
                        assertFalse(MiniHubRuntime.styleEngine.hasLearnedSample(StyleContext.sampleId(account.id.key, observation.notificationKey)))
                    }
                }
            }
        }
        assertTrue("Live Gmail history must include inbound messages that are not learned", inbound > 0)
        assertTrue("Learned samples cannot exceed outbound-derived ids", MiniHubRuntime.styleEngine.learnedSampleCount() <= authored * 2)
        assertTrue("Metadata-only Gmail rows must remain distinguishable from full bodies", metadataOnly > 0 || completeBodies > 0)
    }
}
