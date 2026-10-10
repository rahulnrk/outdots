package app.mini.hub.connector

import app.mini.hub.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorCatalogTest {
    @Test
    fun catalogCoversEveryRequestedProvider() {
        val providers = ConnectorCatalog.all.map { it.provider }.toSet()
        val expected = setOf(
            Provider.GMAIL, Provider.OUTLOOK, Provider.TEAMS, Provider.WHATSAPP,
            Provider.FACEBOOK, Provider.INSTAGRAM, Provider.LINKEDIN, Provider.TWITTER,
            Provider.SLACK, Provider.DISCORD, Provider.GOOGLE_CHAT, Provider.GOOGLE_MESSAGES,
            Provider.LINE, Provider.SIGNAL, Provider.TELEGRAM,
        )
        assertTrue(providers.containsAll(expected), "Missing provider: ${expected - providers}")
    }

    @Test
    fun capabilitiesDefaultToDisabledUntilScopesAndGatesAreCleared() {
        val desc = ConnectorCatalog.gmail
        val initial = CapabilityResolver.resolve(
            desc,
            grantedScopes = emptySet(),
            clearedGates = emptyList(),
            accountState = ConnectionState.Authorized,
        )
        assertTrue(initial.enabled.isEmpty())
        assertTrue(initial.disabled.containsKey(Capability.ListHistory))

        val readGranted = CapabilityResolver.resolve(
            desc,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY),
            clearedGates = emptyList(),
            accountState = ConnectionState.Healthy,
        )
        assertTrue(Capability.ListHistory in readGranted.enabled)
        assertTrue(Capability.ReadBody in readGranted.enabled)
        assertFalse(Capability.Reply in readGranted.enabled) // send scope missing + send gate uncleared

        val sendGranted = CapabilityResolver.resolve(
            desc,
            grantedScopes = setOf(GmailApiConnector.SCOPE_READONLY, GmailApiConnector.SCOPE_SEND),
            clearedGates = listOf(GateEvidence(ReleaseGate.SendQualification, "Verified live")),
            accountState = ConnectionState.Healthy,
        )
        assertTrue(Capability.Reply in sendGranted.enabled)
        assertTrue(Capability.SendNew in sendGranted.enabled)
    }

    @Test
    fun telegramAiDraftingIsBlockedByContentUseTermsGate() {
        val bot = ConnectorCatalog.telegramBot
        val resolved = CapabilityResolver.resolve(
            bot,
            grantedScopes = setOf(ConnectorCatalog.TELEGRAM_BOT_SCOPE),
            clearedGates = listOf(
                GateEvidence(ReleaseGate.TelegramBotToken, "BotFather token stored"),
                GateEvidence(ReleaseGate.SendQualification, "Verified"),
            ),
            accountState = ConnectionState.Healthy,
        )
        assertFalse(Capability.LocalAiDrafting in resolved.enabled)
        assertTrue(resolved.disabled[Capability.LocalAiDrafting]?.contains("TelegramContentUseTerms") == true)
    }
}
