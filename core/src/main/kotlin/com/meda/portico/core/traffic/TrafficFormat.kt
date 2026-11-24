package com.meda.portico.core.traffic

/**
 * Byte counts for people: decimal units like Android's data-usage screen (1 MB = 1,000,000 bytes). One unit per value; 0.6.71: zero
 * decimals from 100 up, one decimal below 100 (so a cell stays short), and a value that rounds up to 1000 moves to the next unit.
 */
object TrafficFormat {
    private val UNITS = listOf("KB", "MB", "GB")

    fun bytes(n: Long): String {
        val b = n.coerceAtLeast(0)
        if (b < 1_000L) return "$b B"
        var unit = 0
        var v = b / 1_000.0
        while (unit < UNITS.size - 1 && Math.round(v * 10) / 10.0 >= 1_000.0) {
            v /= 1_000.0
            unit++
        }
        return num(v) + " " + UNITS[unit]
    }

    /** One decimal below 100 (judged after rounding, so 99.96 reads "100", not "100.0"), none from 100 up. */
    private fun num(v: Double): String {
        val oneDecimal = Math.round(v * 10) / 10.0
        return if (oneDecimal >= 100.0) String.format(java.util.Locale.ROOT, "%.0f", v)
        else String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
