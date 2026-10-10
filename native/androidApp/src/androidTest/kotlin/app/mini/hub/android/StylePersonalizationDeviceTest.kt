package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.android.intelligence.LocalDraftRequest
import app.mini.hub.android.intelligence.LocalDraftResult
import app.mini.hub.android.intelligence.MediaPipeInferenceEngine
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.intelligence.ContextBundle
import app.mini.hub.intelligence.EvidenceRef
import app.mini.hub.intelligence.SourceId
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.style.CompactStyleProfile
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleTraits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StylePersonalizationDeviceTest {
    @Test
    fun mediapipeBaselineAndPersonalizedDraftsAreRecordedWithoutComposerRewrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(context)
        val model = MediaPipeInferenceEngine.defaultModelFile(context)
        assumeTrue("On-device MediaPipe model is required", model.isFile && model.length() > 0L)
        val experiment = MiniHubRuntime.localDraftExperiment
        experiment.setForeground(true)
        val pssBefore = android.os.Debug.getPss()
        val started = android.os.SystemClock.elapsedRealtime()
        val baseline = experiment.draftBlocking(request(null))
        val professional = experiment.draftBlocking(request(professionalProfile()))
        val casual = experiment.draftBlocking(request(casualProfile()))
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        val pssAfter = android.os.Debug.getPss()
        experiment.release()

        val baselineText = (baseline as? LocalDraftResult.Success)?.proposal?.text.orEmpty()
        val professionalText = (professional as? LocalDraftResult.Success)?.proposal?.text.orEmpty()
        val casualText = (casual as? LocalDraftResult.Success)?.proposal?.text.orEmpty()
        val artifact = JSONObject()
            .put("syntheticOnly", true)
            .put("draftRealization", "mediapipe-prompt-only")
            .put("deterministicComposerPostProcessed", false)
            .put("modelId", MediaPipeInferenceEngine.MODEL_ID)
            .put("modelBytes", model.length())
            .put("pssBeforeKb", pssBefore)
            .put("pssAfterKb", pssAfter)
            .put("elapsedMs", elapsed)
            .put("mainThread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            .put(
                "cases",
                JSONArray()
                    .put(caseJson("style-a-baseline", baseline, false))
                    .put(caseJson("style-a-professional", professional, true))
                    .put(caseJson("style-b-casual", casual, true)),
            )
            .put("professionalDiffersFromBaseline", professionalText.isNotBlank() && professionalText != baselineText)
            .put("casualDiffersFromProfessional", casualText.isNotBlank() && casualText != professionalText)
            .put("casualDiffersFromBaseline", casualText.isNotBlank() && casualText != baselineText)
        val file = File(context.getExternalFilesDir("review") ?: File(context.filesDir, "review"), "style-personalization.json")
        file.parentFile?.mkdirs()
        file.writeText(artifact.toString(2))

        assertTrue("Baseline draft must use MediaPipe, got $baseline", baseline is LocalDraftResult.Success || baseline is LocalDraftResult.Failed)
        if (professional is LocalDraftResult.Success) {
            assertTrue(professional.styleProfileApplied)
            assertFalse("Personalized draft must not invent a rupee amount", professionalText.contains("₹"))
        }
        if (casual is LocalDraftResult.Success) {
            assertTrue(casual.styleProfileApplied)
        }
        assertFalse("Qualification must not run MediaPipe on the main thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
    }

    private fun caseJson(id: String, result: LocalDraftResult, expectStyle: Boolean): JSONObject {
        val success = result as? LocalDraftResult.Success
        return JSONObject()
            .put("id", id)
            .put("outcome", result::class.simpleName)
            .put("styleProfileApplied", success?.styleProfileApplied == true)
            .put("expectedStyleInPrompt", expectStyle)
            .put("latencyMs", success?.latencyMs ?: 0L)
            .put("cold", success?.wasCold == true)
            .put("generatedDraft", success?.proposal?.text.orEmpty())
            .put("reason", (result as? LocalDraftResult.Failed)?.reason ?: (result as? LocalDraftResult.Refused)?.reason.orEmpty())
    }

    private fun professionalProfile() = CompactStyleProfile(
        id = "device-professional",
        category = ContextCategory.Professional,
        recipientId = "synthetic|alex",
        traits = StyleTraits(0.85, 0.4, 0.4, 0.0, "Formal greeting", "Formal signoff"),
        exampleSnippets = listOf("Dear Alex, please find the attached proposal."),
        observationCount = 4,
        confidence = 0.62,
        updatedAt = UtcInstant(1_760_000_000_000L),
    )

    private fun casualProfile() = professionalProfile().copy(
        id = "device-casual",
        category = ContextCategory.Friends,
        traits = StyleTraits(0.2, 0.8, 0.8, 0.5, "Casual greeting", "Warm signoff"),
        exampleSnippets = listOf("Hey, thanks for the ping."),
    )

    private fun request(profile: CompactStyleProfile?): LocalDraftRequest {
        val account = AccountId(Provider.GMAIL, "style-device")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, "alex")
        val id = ScopedId(account, ScopedId.Kind.OBSERVATION, "1")
        val observation = ObservedNotification(
            id, account, conversation, "synthetic.local", "synthetic-key", 1, null,
            "Synthetic Sender", "Proposal", listOf("Please send the signed proposal by Friday. No price was discussed."),
            "digest", UtcInstant(1_760_000_000_000L), UtcInstant(1_760_000_000_000L), 1L, false, false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.SyntheticFixture,
        )
        val compartment = CompartmentId(account.key)
        return LocalDraftRequest(
            "Proposal",
            "Synthetic Sender",
            compartment,
            ContextBundle(compartment, listOf(EvidenceRef(SourceId(id.key), 1L, compartment)), 40, false),
            listOf(observation),
            "",
            "Acknowledge the request and say I will review. Do not invent a date, price, or commitment.",
            profile,
        )
    }
}
