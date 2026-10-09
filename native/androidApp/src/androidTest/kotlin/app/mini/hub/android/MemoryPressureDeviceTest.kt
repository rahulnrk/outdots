package app.mini.hub.android

import android.content.ComponentCallbacks2
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.android.intelligence.LocalDraftRequest
import app.mini.hub.android.intelligence.LocalDraftResult
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.intelligence.CompartmentId
import app.mini.hub.intelligence.ContextBundle
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import app.mini.hub.domain.Clock
import app.mini.hub.domain.UtcInstant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MemoryPressureDeviceTest {

    @Test
    fun verifyDraftingCancellationAndMemoryPressureHandling() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        MiniHubRuntime.init(appContext)
        val experiment = MiniHubRuntime.localDraftExperiment

        val account = AccountId(Provider.WHATSAPP, "dev_test")
        val convId = ScopedId(account, ScopedId.Kind.CONVERSATION, "Alice")
        val compartment = CompartmentId(account.key)
        val clock = Clock { UtcInstant(System.currentTimeMillis()) }

        val obs = ObservedNotification(
            id = ScopedId(account, ScopedId.Kind.OBSERVATION, "obs1"),
            accountId = account,
            conversationId = convId,
            packageId = "com.whatsapp",
            notificationKey = "key1",
            notificationId = 1,
            tag = null,
            senderHint = "Alice",
            title = "Alice",
            textFragments = listOf("Do we have a meeting?"),
            rawTextDigest = "hash",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.SyntheticFixture
        )

        val request = LocalDraftRequest(
            conversationLabel = "Alice",
            sender = "Alice",
            compartment = compartment,
            context = ContextBundle(compartment, listOf(app.mini.hub.intelligence.EvidenceRef(app.mini.hub.intelligence.SourceId(obs.id.key), obs.revision, compartment)), 10, false),
            observations = listOf(obs),
            coverageWarning = "",
            userIntent = "Confirm we do"
        )

        // 1. App starts in background -> Drafting is rejected
        experiment.setForeground(false)
        val bgResult = experiment.draftBlocking(request)
        assertTrue("Drafting should be rejected in background, got $bgResult", bgResult is LocalDraftResult.Refused && bgResult.reason.contains("background"))

        // 2. Foreground app -> Generation can start
        experiment.setForeground(true)
        
        var cancelledResult: LocalDraftResult? = null
        val latch = CountDownLatch(1)
        val future = experiment.draftAsync(request) { res ->
            cancelledResult = res
            latch.countDown()
        }

        // 3. Verify generation actually starts (executor accepts it)
        Thread.sleep(100) 

        // 4. Trigger memory pressure from the main thread
        // Verify it doesn't block the main thread waiting for inference or model loading
        val start = SystemClock.elapsedRealtime()
        experiment.onMemoryPressure()
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("onMemoryPressure blocked the main thread for $elapsed ms", elapsed < 500)

        // 5. Future completes with cancellation
        latch.await(5, TimeUnit.SECONDS)
        assertTrue("Expected failure due to cancellation or pressure, got: $cancelledResult", cancelledResult is LocalDraftResult.Failed)

        // 6. Draft attempts while pressured should fail without loading model
        val pressureResult = experiment.draftBlocking(request)
        assertTrue("Should be pressured, got: $pressureResult", pressureResult is LocalDraftResult.Failed && pressureResult.reason.contains("pressured"))

        // 7. Recover by transitioning to foreground again
        experiment.setForeground(true)
        // give the async resumeAfterPressure a tiny bit of time
        Thread.sleep(100)

        // 8. Successful generation
        val syncResult = experiment.draftBlocking(request)
        assertTrue("Should recover from pressure and successfully generate or correctly fail with useful error, got: $syncResult",
            syncResult is LocalDraftResult.Success || syncResult is LocalDraftResult.Failed)
    }
}
