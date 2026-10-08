package app.mini.hub.observation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeliveryAccountingLedgerTest {

    @Test
    fun testStageRecordingAndLatencyCalculation() {
        val ledger = DeliveryAccountingLedger()

        // Event 1: Delivered in 50ms
        ledger.record("evt_1", PipelineStage.SourcePublished, 1000L)
        ledger.record("evt_1", PipelineStage.ListenerReceived, 1010L)
        ledger.record("evt_1", PipelineStage.QueueAdmitted, 1015L)
        ledger.record("evt_1", PipelineStage.Normalized, 1020L)
        ledger.record("evt_1", PipelineStage.DeduplicatedInsert, 1030L)
        ledger.record("evt_1", PipelineStage.DbCommitted, 1050L)
        ledger.record("evt_1", PipelineStage.UiNotified, 1055L)

        // Event 2: Delivered in 100ms
        ledger.record("evt_2", PipelineStage.SourcePublished, 2000L)
        ledger.record("evt_2", PipelineStage.ListenerReceived, 2020L)
        ledger.record("evt_2", PipelineStage.QueueAdmitted, 2030L)
        ledger.record("evt_2", PipelineStage.Normalized, 2040L)
        ledger.record("evt_2", PipelineStage.DeduplicatedInsert, 2060L)
        ledger.record("evt_2", PipelineStage.DbCommitted, 2100L)

        // Event 3: Dropped at queue
        ledger.record("evt_3", PipelineStage.SourcePublished, 3000L)
        ledger.record("evt_3", PipelineStage.ListenerReceived, 3010L)
        ledger.record("evt_3", PipelineStage.QueueDropped, 3015L, "Queue full")

        // Event 4: Deduplicated drop (50ms)
        ledger.record("evt_4", PipelineStage.SourcePublished, 4000L)
        ledger.record("evt_4", PipelineStage.ListenerReceived, 4010L)
        ledger.record("evt_4", PipelineStage.QueueAdmitted, 4020L)
        ledger.record("evt_4", PipelineStage.Normalized, 4030L)
        ledger.record("evt_4", PipelineStage.DeduplicatedDrop, 4050L)

        val report = ledger.generateReport()
        assertEquals(4, report.totalTrackedEvents)
        assertEquals(3, report.fullyDeliveredCount) // evt_1, evt_2, evt_4
        assertEquals(1, report.droppedCount)

        assertTrue(report.p50LatencyMillis in 50.0..100.0)
        assertTrue(report.p95LatencyMillis >= 50.0)
        assertEquals(1, report.countsByStage[PipelineStage.QueueDropped])
        assertEquals(2, report.countsByStage[PipelineStage.DeduplicatedInsert])
        assertEquals(1, report.countsByStage[PipelineStage.DeduplicatedDrop])

        val summary1 = ledger.summarizeEvent("evt_1")
        assertNotNull(summary1)
        assertTrue(summary1.isFullyDelivered)
        assertEquals(55L, summary1.totalLatencyMillis)
    }
}
