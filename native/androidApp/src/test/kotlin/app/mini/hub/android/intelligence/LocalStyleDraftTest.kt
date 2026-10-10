package app.mini.hub.android.intelligence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.intelligence.ContextBundle
import app.mini.hub.intelligence.EvidenceRef
import app.mini.hub.intelligence.SourceId
import app.mini.hub.intelligence.StyleAwareDeterministicEngine
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.style.CompactStyleProfile
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleTraits
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalStyleDraftTest {
    @Test
    fun professionalAndCasualProfilesChangeTheGeneratedDraft() {
        val executor = Executors.newSingleThreadExecutor()
        val owner = LocalDraftExperiment(StyleAwareDeterministicEngine(), "synthetic-style", executor, System::currentTimeMillis)
        owner.setForeground(true)
        try {
            val professional = owner.draftBlocking(request(profile(0.85, 0.4, 0.4, 0.0, ContextCategory.Professional)))
            val casual = owner.draftBlocking(request(profile(0.2, 0.8, 0.8, 0.6, ContextCategory.Friends)))
            val professionalText = assertIs<LocalDraftResult.Success>(professional).proposal.text
            val casualText = assertIs<LocalDraftResult.Success>(casual).proposal.text
            assertNotEquals(professionalText, casualText)
            assertTrue(professionalText.contains("Thank you") || professionalText.contains("confirmed details"))
            assertTrue(casualText.startsWith("Hey") || casualText.contains("thanks for the ping"))
            assertTrue(!professionalText.contains("tomorrow"))
            assertTrue(!casualText.contains("₹"))
        } finally {
            owner.release()
            executor.shutdownNow()
        }
    }

    private fun profile(
        formality: Double,
        brevity: Double,
        warmth: Double,
        emoji: Double,
        category: ContextCategory,
    ) = CompactStyleProfile(
        id = "test:$category",
        category = category,
        recipientId = "acct|alex@example.com",
        traits = StyleTraits(formality, brevity, warmth, emoji),
        observationCount = 4,
        confidence = 0.6,
        updatedAt = UtcInstant(1_760_000_000_000L),
    )

    private fun request(profile: CompactStyleProfile): LocalDraftRequest {
        val account = AccountId(Provider.GMAIL, "style-draft")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "alex")
        val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "1")
        val observation = ObservedNotification(
            id, account, conversation, "com.google.android.gm", "provider:${account.key}:1", 1, null,
            "Alex", "Proposal", listOf("Please send the signed proposal by Friday"), "digest",
            UtcInstant(1_760_000_000_000L), UtcInstant(1_760_000_000_000L), 1L, false, false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.OfficialProviderApi,
        )
        return LocalDraftRequest(
            "Proposal",
            "Alex",
            CompartmentId(account.key),
            app.mini.hub.intelligence.ContextBundle(CompartmentId(account.key), listOf(EvidenceRef(SourceId(id.key), 1L, CompartmentId(account.key))), 30, false),
            listOf(observation),
            "",
            "acknowledge and say I will review",
            profile,
            "deadline mentioned: by Friday",
        )
    }
}
