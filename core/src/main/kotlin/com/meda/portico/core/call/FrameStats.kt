package com.meda.portico.core.call

/**
 * The counters of ONE call's audio carried as frames over the socket (design 15.17, DEV-811 P1): frames sent, frames not sent (the
 * socket's backlog was too long, so a stale frame was dropped rather than queued), frames received, frames CONCEALED (the speaker was
 * due a frame and had none: played as silence), UNDERRUN EVENTS (a run of misses after audio had started counts once; counting stops
 * at the server's `end` event, so the call's tail is not an underrun), the LARGEST and the 95th-percentile GAP between two downlink
 * frames (from a small fixed histogram), the playout queue's smallest and largest depth, and the call's length in seconds. They are
 * what the checkpoint reads to say whether the frames path is good.
 *
 * Counted by [FrameMedia], written once to the journal when the audio closes. Thread-safe: the capture thread, the socket's thread
 * and the answer thread all touch it. Numbers only: nothing here can hold an id or a name.
 */
class FrameStats(private val clockMs: () -> Long) {
    data class Summary(
        val sent: Long,
        val dropped: Long,
        val received: Long,
        val concealed: Long,
        val maxGapMs: Long,
        val underruns: Long = 0,
        val p95GapMs: Long = 0,
        val qMin: Int = 0,
        val qMax: Int = 0,
        val secs: Long = 0,
    )

    private var sent = 0L
    private var dropped = 0L
    private var received = 0L
    private var concealed = 0L
    private var maxGapMs = 0L
    private var lastReceivedAtMs = -1L
    private var underruns = 0L
    private var played = false
    private var inMiss = false
    private var ended = false
    private val hist = LongArray(GAP_BOUNDS.size + 1)
    private var qMin = Int.MAX_VALUE
    private var qMax = 0
    private var startedAtMs = -1L
    private var stoppedAtMs = -1L

    // The per-window view (taken every few seconds by FrameMedia): the cumulative counters at the last take and this window's own gap histogram / queue range.
    private var wSent = 0L
    private var wDropped = 0L
    private var wReceived = 0L
    private var wConcealed = 0L
    private var wUnderruns = 0L
    private var wHist = LongArray(GAP_BOUNDS.size + 1)
    private var wMaxGap = 0L
    private var wQMin = Int.MAX_VALUE
    private var wQMax = 0

    /** What happened since the previous [takeWindow] (or the start): counter deltas, this window's gap histogram, its largest and 95th-percentile gap, its queue range. */
    data class Window(
        val sent: Long, val dropped: Long, val received: Long, val concealed: Long, val underruns: Long,
        val gaps: LongArray, val maxGapMs: Long, val p95GapMs: Long, val qMin: Int, val qMax: Int,
    )

    @Synchronized
    fun takeWindow(): Window {
        val total = wHist.sum()
        var p95 = 0L
        if (total > 0) {
            val need = (total * 95 + 99) / 100
            var cum = 0L
            p95 = wMaxGap
            for (i in wHist.indices) {
                cum += wHist[i]
                if (cum >= need) { p95 = if (i < GAP_BOUNDS.size) GAP_BOUNDS[i] else wMaxGap; break }
            }
        }
        val w = Window(
            sent - wSent, dropped - wDropped, received - wReceived, concealed - wConcealed, underruns - wUnderruns,
            wHist, wMaxGap, p95, if (wQMin == Int.MAX_VALUE) 0 else wQMin, wQMax,
        )
        wSent = sent; wDropped = dropped; wReceived = received; wConcealed = concealed; wUnderruns = underruns
        wHist = LongArray(GAP_BOUNDS.size + 1); wMaxGap = 0; wQMin = Int.MAX_VALUE; wQMax = 0
        return w
    }

    /** The audio is running now: [Summary.secs] counts from here. */
    @Synchronized
    fun onStarted() {
        if (startedAtMs < 0) startedAtMs = clockMs()
    }

    @Synchronized
    fun onSent(ok: Boolean) {
        if (ok) sent++ else dropped++
    }

    @Synchronized
    fun onReceived() {
        val now = clockMs()
        if (lastReceivedAtMs >= 0) {
            val gap = now - lastReceivedAtMs
            maxGapMs = maxOf(maxGapMs, gap)
            hist[bucket(gap)]++
            wMaxGap = maxOf(wMaxGap, gap)
            wHist[bucket(gap)]++
        }
        lastReceivedAtMs = now
        received++
    }

    /** The speaker was handed a frame. */
    @Synchronized
    fun onPlayed() {
        played = true
        inMiss = false
    }

    /** The speaker asked for a frame and there was none. Only counted once audio has started (the first frame is not an underrun). */
    @Synchronized
    fun onConcealed() {
        if (received > 0) {
            concealed++
            if (played && !inMiss && !ended) underruns++
            if (played) inMiss = true
        }
    }

    /** The playout queue's depth at a speaker slot, once audio has started. */
    @Synchronized
    fun onQueue(depth: Int) {
        if (received == 0L) return
        qMin = minOf(qMin, depth)
        qMax = maxOf(qMax, depth)
        wQMin = minOf(wQMin, depth)
        wQMax = maxOf(wQMax, depth)
    }

    /** The server said the call is over (`end`): what follows is the tail, not a fault. */
    @Synchronized
    fun onEnd() {
        ended = true
    }

    /** The audio closed: fixes [Summary.secs]. */
    @Synchronized
    fun onStopped() {
        if (stoppedAtMs < 0) stoppedAtMs = clockMs()
    }

    @Synchronized
    fun summary(): Summary {
        val secs = if (startedAtMs < 0) 0L else ((if (stoppedAtMs >= 0) stoppedAtMs else clockMs()) - startedAtMs).coerceAtLeast(0) / 1000
        return Summary(sent, dropped, received, concealed, maxGapMs, underruns, p95(), if (qMin == Int.MAX_VALUE) 0 else qMin, qMax, secs)
    }

    /** The upper bound of the bucket holding the 95th percentile gap; the overflow bucket reports the largest gap seen. */
    private fun p95(): Long {
        val total = hist.sum()
        if (total == 0L) return 0
        val need = (total * 95 + 99) / 100
        var cum = 0L
        for (i in hist.indices) {
            cum += hist[i]
            if (cum >= need) return if (i < GAP_BOUNDS.size) GAP_BOUNDS[i] else maxGapMs
        }
        return maxGapMs
    }

    private fun bucket(gap: Long): Int {
        for (i in GAP_BOUNDS.indices) if (gap <= GAP_BOUNDS[i]) return i
        return GAP_BOUNDS.size
    }

    companion object {
        /** Upper bounds, in ms, of the gap histogram's buckets (a nominal gap is 40). */
        val GAP_BOUNDS = longArrayOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 120, 150, 200, 300, 400, 600, 1000, 2000)
    }
}

/**
 * The device-side numbers of one call (DEV-811 P1's `audio dev` line): peak levels and clipped samples each way, the longest
 * blocking read, and how the effects and the device went. Written by [FrameMedia] from the audio threads; thread-safe.
 */
class DeviceStats {
    private var upPeak = 0
    private var dnPeak = 0
    private var upClip = 0L
    private var dnClip = 0L

    // The window view: sum of squares and sample counts (for an RMS), peaks and clip counts since the last [takeWindow].
    private var wUpSq = 0.0
    private var wUpN = 0L
    private var wDnSq = 0.0
    private var wDnN = 0L
    private var wUpF = 0L
    private var wDnF = 0L
    private var wUpPeak = 0
    private var wDnPeak = 0
    private var wUpClip = 0L
    private var wDnClip = 0L

    @Synchronized
    fun onUp(frame: ShortArray) {
        for (s in frame) {
            val a = abs(s.toInt())
            if (a > upPeak) upPeak = a
            if (a >= 32767) upClip++
            if (a > wUpPeak) wUpPeak = a
            if (a >= 32767) wUpClip++
            wUpSq += a.toDouble() * a
        }
        wUpN += frame.size
        wUpF++
    }

    @Synchronized
    fun onDown(frame: ShortArray) {
        for (s in frame) {
            val a = abs(s.toInt())
            if (a > dnPeak) dnPeak = a
            if (a >= 32767) dnClip++
            if (a > wDnPeak) wDnPeak = a
            if (a >= 32767) wDnClip++
            wDnSq += a.toDouble() * a
        }
        wDnN += frame.size
        wDnF++
    }

    /** The levels since the previous take, then a fresh window. */
    @Synchronized
    fun takeWindow(): DeviceWindow {
        val w = DeviceWindow(wUpSq, wUpN, wDnSq, wDnN, wUpPeak, wDnPeak, wUpClip, wDnClip, wUpF, wDnF)
        wUpSq = 0.0; wUpN = 0; wDnSq = 0.0; wDnN = 0; wUpPeak = 0; wDnPeak = 0; wUpClip = 0; wDnClip = 0; wUpF = 0; wDnF = 0
        return w
    }

    @Synchronized
    fun upPeak(): Int = upPeak

    @Synchronized
    fun dnPeak(): Int = dnPeak

    @Synchronized
    fun upClip(): Long = upClip

    @Synchronized
    fun dnClip(): Long = dnClip

    private fun abs(v: Int) = if (v < 0) -v else v

    companion object {
        /** A peak in dBFS, whole decibels; silence is -99. */
        /** An RMS in dBFS, whole decibels, from a sum of squares of 16-bit samples over their count; null with no samples, silence is -99. */
        fun dbfsRms(sumSq: Double, n: Long): Int? {
            if (n <= 0) return null
            val rms = Math.sqrt(sumSq / n)
            return if (rms <= 0.0) -99 else Math.round(20.0 * Math.log10(rms / 32768.0)).toInt().coerceAtLeast(-99)
        }

        fun dbfs(peak: Int): Int {
            if (peak <= 0) return -99
            return Math.round(20.0 * Math.log10(peak / 32768.0)).toInt().coerceAtLeast(-99)
        }
    }
}

/** [DeviceStats]' levels over one window: sums of squares and sample counts (RMS is made from them), peaks, clipped samples. */
data class DeviceWindow(
    val upSumSq: Double, val upN: Long, val dnSumSq: Double, val dnN: Long,
    val upPeak: Int, val dnPeak: Int, val upClip: Long, val dnClip: Long,
    /** Frames captured / played (a played frame is one the speaker was actually handed, not silence). */
    val upFrames: Long = 0, val dnFrames: Long = 0,
)
