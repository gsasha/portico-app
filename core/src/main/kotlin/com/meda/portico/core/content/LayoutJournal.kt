package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize

/**
 * A small in-memory journal of Home's layout (0.5.7-alpha): one line after every rebuild of the page body, saying what was
 * built, what it measured to and any exception a block's build threw. It exists because the "items after a shrunken camera
 * disappear" bug (0.5.4 to 0.5.6) could not be seen from the build host: the next report can carry these lines instead of a
 * guess. Home's menu has "Copy layout log". Bounded ([MAX_LINES]), thread-safe, lost with the process, and holds counts,
 * sizes and exception class names only: no label, id, address or token.
 */
object LayoutJournal {
    const val MAX_LINES = 40
    private const val MAX_MESSAGE = 80

    private val lines = ArrayDeque<String>()

    /** DEV-762 A5: where a bounded `layout fail ...` line goes (the Journal reporter's `offer`); null until the app wires it. The reporter's own switch decides. */
    @Volatile
    var observer: ((String) -> Unit)? = null

    /** Minimum gap between two reported failure lines, and the most per process. */
    const val REPORT_GAP_MS = 60_000L
    const val REPORT_MAX = 10
    private var reportedAt = Long.MIN_VALUE
    private var reportedCount = 0

    /**
     * DEV-762 A5: when a rebuild had a block that failed, hand [failureLine] to [observer], at most once per [REPORT_GAP_MS] and
     * [REPORT_MAX] times per process (a failing rebuild repeats on every tick). Total: never throws into the rebuild.
     */
    fun reportFailure(stats: List<BlockStat>, nowMs: Long = System.currentTimeMillis()) {
        try {
            val o = observer ?: return
            val line = failureLine(stats) ?: return
            synchronized(this) {
                if (reportedCount >= REPORT_MAX || (reportedCount > 0 && nowMs - reportedAt in 0 until REPORT_GAP_MS)) return
                reportedCount++
                reportedAt = nowMs
            }
            o(line)
        } catch (_: Throwable) {
        }
    }

    /** Test hook: forget the rate-limit state. */
    @Synchronized
    fun resetReporting() {
        reportedAt = Long.MIN_VALUE
        reportedCount = 0
    }

    /**
     * The reported line, or null when no block failed: `layout fail blocks=3 failed=1 b1 cams=2[SB] btns=1 gridRows=2 exc=IllegalStateException`.
     * Counts, sizes and exception CLASS names only: never a message, label, id or address.
     */
    fun failureLine(stats: List<BlockStat>): String? {
        val failed = stats.withIndex().filter { it.value.failure != null }
        if (failed.isEmpty()) return null
        return "layout fail blocks=${stats.size} failed=${failed.size}" + failed.take(3).joinToString("") { (i, s) ->
            " b$i cams=${s.cameraSizes.length}[${s.cameraSizes.filter { it == 'S' || it == 'B' }}] btns=${s.buttons} gridRows=${s.gridRows} exc=${className(s.failure)}"
        }
    }

    private fun className(failure: String?): String = (failure ?: "").substringBefore(':').filter { it.isLetterOrDigit() || it == '_' }.take(40).ifEmpty { "unknown" }

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()

    @Synchronized
    fun clear() = lines.clear()

    /** What one block's build produced: [failure] is [describe] of the exception that ended it, else null. */
    data class BlockStat(
        val cameraSizes: String,
        val buttons: Int,
        val gridRows: Int,
        val gridChildren: Int,
        val buttonRows: Int,
        val failure: String? = null,
    )

    /** `S` for a small tile, `B` for a big one, in order: "SB". */
    fun sizes(cameras: List<CameraTile>): String = cameras.joinToString("") { if (it.size == CameraSize.BIG) "B" else "S" }

    /** The one line written after a rebuild. */
    fun rebuildLine(stats: List<BlockStat>): String =
        "layout: rebuilt blocks=${stats.size}" + stats.withIndex().joinToString("") { (i, s) ->
            " | b$i cams=${s.cameraSizes.length}[${s.cameraSizes}] btns=${s.buttons} gridRows=${s.gridRows} gridKids=${s.gridChildren} btnRows=${s.buttonRows}" +
                (s.failure?.let { " FAILED $it" } ?: "")
        }

    /** The line written after the next layout pass: the heights the page body really got, in px (the screen, the body, then each block). */
    fun measuredLine(scrollHeight: Int, bodyHeight: Int, blockHeights: List<Int>): String =
        "layout: measured scroll=$scrollHeight body=$bodyHeight blocks=${blockHeights.joinToString(",")}"

    /** An exception as one short line: its class and the first [MAX_MESSAGE] characters of its message. */
    fun describe(e: Throwable): String {
        val m = e.message?.replace('\n', ' ')?.take(MAX_MESSAGE)
        return e.javaClass.simpleName + (if (m.isNullOrEmpty()) "" else ": $m")
    }
}
