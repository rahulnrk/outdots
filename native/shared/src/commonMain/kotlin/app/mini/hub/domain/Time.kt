package app.mini.hub.domain

import kotlin.jvm.JvmInline

/**
 * A UTC instant stored as epoch milliseconds. Timestamps are stored in UTC and only formatted
 * for display at the edge (architecture section 7).
 */
@JvmInline
value class UtcInstant(val epochMillis: Long) : Comparable<UtcInstant> {
    override fun compareTo(other: UtcInstant): Int = epochMillis.compareTo(other.epochMillis)
    operator fun plus(millis: Long): UtcInstant = UtcInstant(epochMillis + millis)
    operator fun minus(other: UtcInstant): Long = epochMillis - other.epochMillis

    /** ISO-8601 with millisecond precision and a `Z` suffix, e.g. `2026-10-08T12:00:00.000Z`. */
    fun toIso(): String {
        val days = epochMillis.floorDiv(MILLIS_PER_DAY)
        val msOfDay = epochMillis.mod(MILLIS_PER_DAY)
        val (y, m, d) = civilFromDays(days)
        val h = msOfDay / 3_600_000
        val min = (msOfDay / 60_000) % 60
        val s = (msOfDay / 1000) % 60
        val ms = msOfDay % 1000
        return "${pad(y, 4)}-${pad(m, 2)}-${pad(d, 2)}T${pad(h, 2)}:${pad(min, 2)}:${pad(s, 2)}.${pad(ms, 3)}Z"
    }

    override fun toString(): String = toIso()

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$""")

        /** Parses a strict UTC ISO-8601 timestamp. Offsets other than `Z` are rejected rather than guessed. */
        fun parse(iso: String): UtcInstant {
            val m = ISO.matchEntire(iso) ?: throw IllegalArgumentException("Not a UTC ISO-8601 instant: $iso")
            val g = m.groupValues
            val days = daysFromCivil(g[1].toLong(), g[2].toLong(), g[3].toLong())
            val frac = g[7].ifEmpty { "0" }.padEnd(3, '0').toLong()
            val millis = days * MILLIS_PER_DAY + g[4].toLong() * 3_600_000 + g[5].toLong() * 60_000 + g[6].toLong() * 1000 + frac
            return UtcInstant(millis)
        }

        private fun pad(v: Long, width: Int) = v.toString().padStart(width, '0')

        // Howard Hinnant's civil-date algorithms (public domain).
        private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
            val y = if (month <= 2) year - 1 else year
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        private fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
            val z = z0 + 719468
            val era = z.floorDiv(146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return Triple(y, m, d)
        }
    }
}

/** Injectable wall clock. Production code never uses fixed timestamps; tests inject a fixed test clock. */
fun interface Clock {
    fun now(): UtcInstant
}

/** Reads the platform wall clock. */
object SystemClock : Clock {
    override fun now(): UtcInstant = UtcInstant(currentEpochMillis())
}

/**
 * Returns monotonically increasing milliseconds since an arbitrary epoch (process start or
 * platform monotonic counter). Suitable for elapsed-time latency measurements that must not
 * be affected by wall-clock adjustments or NTP corrections. Do NOT use for absolute timestamps.
 */
expect fun monotonicMillis(): Long

internal expect fun currentEpochMillis(): Long
