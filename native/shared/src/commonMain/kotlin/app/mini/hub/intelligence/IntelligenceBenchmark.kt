package app.mini.hub.intelligence

import app.mini.hub.domain.currentEpochMillis

data class BenchmarkConfig(
    val scenarioName: String = "HostDeterministicBaseline",
    val warmupIterations: Int = 3,
    val measuredIterations: Int = 10,
    val budget: DeviceResourceBudget = DeviceResourceBudget.Tier2MidRange,
) {
    init {
        require(scenarioName.isNotBlank())
        require(warmupIterations >= 0)
        require(measuredIterations >= 1)
    }
}

data class BenchmarkResult(
    val scenarioName: String,
    val tier: String,
    val coldLoadDurationMillis: Long,
    val avgInferenceLatencyMillis: Double,
    val p95InferenceLatencyMillis: Double,
    val cancellationLatencyMillis: Long,
    val memoryPressureRecoveryMillis: Long,
    val tasksCompleted: Int,
    val failures: Int,
) {
    fun toLedgerMarkdown(): String {
        return """
            | Metric | Measured Value | Budget / Target |
            |---|---|---|
            | Hardware Tier | $tier | ${tier} |
            | Cold Model Load | ${coldLoadDurationMillis} ms | < 3000 ms |
            | Avg Inference Latency | ${avgInferenceLatencyMillis} ms | < 2500 ms |
            | P95 Inference Latency | ${p95InferenceLatencyMillis} ms | < 5000 ms |
            | Cancellation Latency | ${cancellationLatencyMillis} ms | < 50 ms |
            | Memory Pressure Recovery | ${memoryPressureRecoveryMillis} ms | < 500 ms |
            | Tasks Completed | $tasksCompleted | $tasksCompleted |
            | Failures / OOMs | $failures | 0 |
        """.trimIndent()
    }
}

class IntelligenceBenchmark(
    private val config: BenchmarkConfig = BenchmarkConfig(),
) {
    fun run(
        engineFactory: () -> InferenceEngine,
        modelId: String = "gemma-4-e2b-qat",
    ): BenchmarkResult {
        val scope = CompartmentId("bench:session")
        val evidence = EvidenceRef(SourceId("bench-doc-1"), 1L, scope)
        val sampleTask = { idx: Int ->
            InferenceTask(
                id = "bench-task-$idx",
                compartment = scope,
                context = ContextBundle(scope, listOf(evidence), 64),
                budget = InferenceBudget(512, 64),
                prompt = "Benchmark prompt evaluation for task $idx",
            )
        }

        // 1. Measure Cold Load
        val engine = engineFactory()
        val supervisor = ResourceSupervisor(engine, modelId, config.budget)
        val coldStart = currentEpochMillis()
        check(supervisor.ensureReady()) { "Engine failed to cold load" }
        val coldLoadDuration = currentEpochMillis() - coldStart

        // 2. Warmup
        repeat(config.warmupIterations) { idx ->
            val task = sampleTask(idx)
            supervisor.submit(task)
            supervisor.runNext()
        }

        // 3. Measured Inference Runs
        val latencies = mutableListOf<Long>()
        var completed = 0
        var failures = 0

        repeat(config.measuredIterations) { idx ->
            val task = sampleTask(1000 + idx)
            val start = currentEpochMillis()
            val submitRes = supervisor.submit(task)
            if (submitRes == SubmitResult.Accepted) {
                val proposal = supervisor.runNext()
                val elapsed = currentEpochMillis() - start
                if (proposal != null) {
                    latencies.add(elapsed)
                    completed++
                } else {
                    failures++
                }
            } else {
                failures++
            }
        }

        val sortedLatencies = latencies.sorted()
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val p95Latency = if (sortedLatencies.isNotEmpty()) {
            val p95Index = ((sortedLatencies.size - 1) * 0.95).toInt()
            sortedLatencies[p95Index].toDouble()
        } else 0.0

        // 4. Measure Cancellation Latency
        val cancelTask = sampleTask(9999)
        supervisor.submit(cancelTask)
        val cancelStart = currentEpochMillis()
        supervisor.cancel(cancelTask.id)
        supervisor.runNext() // drains queue or skips cancelled
        val cancellationLatency = currentEpochMillis() - cancelStart

        // 5. Measure Memory Pressure Recovery
        val pressureStart = currentEpochMillis()
        supervisor.onMemoryPressure()
        supervisor.resumeAfterPressure()
        val pressureRecovery = currentEpochMillis() - pressureStart

        supervisor.release()

        return BenchmarkResult(
            scenarioName = config.scenarioName,
            tier = config.budget.tierName,
            coldLoadDurationMillis = coldLoadDuration,
            avgInferenceLatencyMillis = avgLatency,
            p95InferenceLatencyMillis = p95Latency,
            cancellationLatencyMillis = cancellationLatency,
            memoryPressureRecoveryMillis = pressureRecovery,
            tasksCompleted = completed,
            failures = failures,
        )
    }
}
