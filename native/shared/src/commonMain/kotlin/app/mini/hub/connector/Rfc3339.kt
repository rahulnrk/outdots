package app.mini.hub.connector

/** Dependency-free RFC 3339 parser for provider timestamps (common code has no java.time). */
object Rfc3339 {
    private val PATTERN = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|z|[+-]\\d{2}:?\\d{2})?$",
    )

    /** Epoch millis, or null when the value is not RFC 3339. A missing offset is treated as UTC. */
    fun parseMillis(value: String?): Long? {
        val m = PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
        val hour = g[4].toInt(); val minute = g[5].toInt(); val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        val offsetMinutes = when (val z = g[8]) {
            "", "Z", "z" -> 0
            else -> {
                val sign = if (z[0] == '-') -1 else 1
                val digits = z.substring(1).replace(":", "")
                sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt())
            }
        }
        val days = daysFromCivil(year, month, day)
        return ((days * 86_400L + hour * 3_600L + minute * 60L + second) - offsetMinutes * 60L) * 1000L + millis
    }

    /** Howard Hinnant's days-from-civil algorithm (proleptic Gregorian). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
