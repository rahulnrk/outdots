package app.mini.hub.intelligence

/**
 * Explicit device resource budget per architecture section 20 and Engineering Knowledge Pack 05 & 08.
 *
 * Mobile AI must not run unbounded. Hardware tiers strictly constrain resident memory,
 * queue bounds, cache size, token windows, and timeout deadlines.
 */
data class DeviceResourceBudget(
    val tierName: String,
    val maxResidentMemoryMb: Int,
    val maxQueueSize: Int,
    val maxProposalCacheEntries: Int,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val taskTimeoutMillis: Long,
) {
    init {
        require(tierName.isNotBlank()) { "tierName must not be blank" }
        require(maxResidentMemoryMb in 128..8192) { "maxResidentMemoryMb out of bounds (128..8192)" }
        require(maxQueueSize in 1..1024) { "maxQueueSize out of bounds (1..1024)" }
        require(maxProposalCacheEntries in 1..256) { "maxProposalCacheEntries out of bounds (1..256)" }
        require(maxInputTokens in 1..8192) { "maxInputTokens out of bounds (1..8192)" }
        require(maxOutputTokens in 1..512) { "maxOutputTokens out of bounds (1..512)" }
        require(taskTimeoutMillis in 100L..60_000L) { "taskTimeoutMillis out of bounds (100..60000)" }
    }

    companion object {
        /** Tier 1: Low-end devices (e.g. 2GB RAM phones, CPU only). Strict bounds to avoid OOM. */
        val Tier1LowEnd = DeviceResourceBudget(
            tierName = "Tier1LowEnd",
            maxResidentMemoryMb = 512,
            maxQueueSize = 64,
            maxProposalCacheEntries = 8,
            maxInputTokens = 1024,
            maxOutputTokens = 128,
            taskTimeoutMillis = 10_000L,
        )

        /** Tier 2: Mid-range devices (e.g. 4-6GB RAM phones, moderate accelerator/CPU). Default baseline. */
        val Tier2MidRange = DeviceResourceBudget(
            tierName = "Tier2MidRange",
            maxResidentMemoryMb = 1024,
            maxQueueSize = 128,
            maxProposalCacheEntries = 16,
            maxInputTokens = 2048,
            maxOutputTokens = 256,
            taskTimeoutMillis = 5_000L,
        )

        /** Tier 3: High-end devices (e.g. 8GB+ RAM phones, dedicated NPU/GPU). Highest local capacity. */
        val Tier3HighEnd = DeviceResourceBudget(
            tierName = "Tier3HighEnd",
            maxResidentMemoryMb = 2048,
            maxQueueSize = 256,
            maxProposalCacheEntries = 32,
            maxInputTokens = 4096,
            maxOutputTokens = 256,
            taskTimeoutMillis = 3_000L,
        )
    }
}
