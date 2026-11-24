package com.meda.portico.core.traffic

/** The rows of the data-use table, top to bottom. The app maps each to a string resource. */
enum class TrafficCategory { VIDEO, CALLS, OTHER, TOTAL }

/** The periods the table shows, each as its own block (the block's header row names the period and the two columns). */
enum class TrafficPeriod { TODAY, MONTH, SINCE_RESET }

/** One table row: the two cells are already formatted ("-" when that column has no data). */
data class TrafficTableRow(val category: TrafficCategory, val mobile: String, val wifi: String)

/** One block: a period and its four rows. */
data class TrafficTableBlock(val period: TrafficPeriod, val rows: List<TrafficTableRow>)

/**
 * 0.6.70: the "Data used by this app" table as pure data. Two value columns, Mobile and Wi-Fi; one row per category.
 * A cell with nothing recorded (a column with no data, or one category that is zero) shows "-", never "0 B".
 */
object TrafficTable {
    const val NONE = "-"

    fun hasData(u: MobileUse): Boolean = u.os > 0 || u.video > 0 || u.audio > 0

    fun rows(mobile: MobileUse, wifi: MobileUse): List<TrafficTableRow> = TrafficCategory.entries.map { c ->
        TrafficTableRow(c, cell(mobile, c), cell(wifi, c))
    }

    private fun cell(u: MobileUse, c: TrafficCategory): String {
        if (!hasData(u)) return NONE
        val n = when (c) {
            TrafficCategory.VIDEO -> u.video
            TrafficCategory.CALLS -> u.audio
            TrafficCategory.OTHER -> u.other
            TrafficCategory.TOTAL -> u.total
        }
        return if (n <= 0) NONE else TrafficFormat.bytes(n) // 0.6.71: a zero cell is a dash, not "0 B"
    }

    /** The three blocks for [nowMs], read from [meter]. */
    fun blocks(meter: TrafficMeter, nowMs: Long): List<TrafficTableBlock> = TrafficPeriod.entries.map { p ->
        fun read(m: Medium) = when (p) {
            TrafficPeriod.TODAY -> meter.today(nowMs, m)
            TrafficPeriod.MONTH -> meter.month(nowMs, m)
            TrafficPeriod.SINCE_RESET -> meter.sinceReset(nowMs, m)
        }
        TrafficTableBlock(p, rows(read(Medium.MOBILE), read(Medium.WIFI)))
    }
}
