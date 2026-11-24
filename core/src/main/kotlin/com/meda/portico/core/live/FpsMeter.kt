package com.meda.portico.core.live

/**
 * The frame-rate label of a live tile (the owner's decision 2, the card's wording): `--` until the meter has watched at least
 * [MIN_SPAN_MS] of video, then `N fps`, `0 fps` when no frame has come for [STALL_MS]. Shown only when the document's `show_fps`
 * is true (the screen decides that; this class only measures).
 *
 * Fed the running TOTAL of frames received, about once a second ([sample]). The rate is read over a sliding window of the last
 * [WINDOW_MS] (not the last second, which swung by several fps whenever the decoder delivered a burst or the network paused),
 * and the shown integer moves only when the measured rate has left it by a full frame per second ([HYSTERESIS]), so a steady
 * stream draws a steady number. A total that goes back (a new peer connection) or a clock that goes back starts a new window.
 * Pure: no clock of its own.
 */
class FpsMeter {
    private val times = ArrayList<Long>()
    private val totals = ArrayList<Long>()
    private var lastFrameAt = -1L
    private var shown: Int? = null

    /** `--` or `N fps`: the label as drawn. */
    val label: String get() = format(shown)

    fun reset() {
        times.clear()
        totals.clear()
        lastFrameAt = -1L
        shown = null
    }

    /** One reading: [totalFrames] frames received so far, at [nowMs] (any monotonic millisecond clock). */
    fun sample(nowMs: Long, totalFrames: Long) {
        if (times.isNotEmpty() && (nowMs < times.last() || totalFrames < totals.last())) reset() // a clock or a counter that went back
        if (times.isEmpty()) {
            times.add(nowMs)
            totals.add(totalFrames)
            return
        }
        if (nowMs == times.last()) return // the same instant twice: nothing to divide by
        if (totalFrames > totals.last()) lastFrameAt = nowMs
        times.add(nowMs)
        totals.add(totalFrames)
        // keep one reading at or before the window's edge, drop the older ones
        while (times.size > 2 && times[1] <= nowMs - WINDOW_MS) {
            times.removeAt(0)
            totals.removeAt(0)
        }
        if (lastFrameAt >= 0 && nowMs - lastFrameAt >= STALL_MS) {
            shown = 0
            // measure afresh once frames return, rather than averaging the silence in
            val t = times.last()
            val n = totals.last()
            times.clear(); totals.clear()
            times.add(t); totals.add(n)
            return
        }
        val span = nowMs - times.first()
        if (span < MIN_SPAN_MS) return
        val delta = totalFrames - totals.first()
        if (delta <= 0) return // no frame in the window yet: nothing to say
        val raw = delta * 1000.0 / span
        val cur = shown
        if (cur == null || Math.abs(raw - cur) >= HYSTERESIS) shown = Math.round(raw).toInt()
    }

    companion object {
        /** The rate is read over this much of the recent past. */
        const val WINDOW_MS = 4_000L

        /** `--` until the meter has watched this much video. */
        const val MIN_SPAN_MS = 2_000L

        /** No frame for this long: `0 fps`. */
        const val STALL_MS = 2_000L

        /** The shown number moves only when the measured rate is this far from it. */
        const val HYSTERESIS = 1.0

        fun format(rate: Int?): String = if (rate == null) "--" else "$rate fps"
    }
}
