package com.meda.portico.core.live

/**
 * DEV-764 (camera diagnostics): one reading of a live stream's WebRTC receiver statistics, already reduced to plain numbers and words
 * (the app's peer reads libwebrtc's `getStats` into it). Every field may be null (the platform did not report it). Counters are
 * running totals; [StatsPacer] turns them into per-interval deltas.
 */
data class RtcReading(
    val fps: Int? = null,
    val decoded: Long? = null,
    val dropped: Long? = null,
    val bytes: Long? = null,
    val packets: Long? = null,
    val lost: Long? = null,
    val jitterMs: Int? = null,
    val freezes: Long? = null,
    val freezeMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val decoder: String? = null,
    val rttMs: Int? = null,
    val localType: String? = null,
    val remoteType: String? = null,
    val net: String? = null,
    val proto: String? = null,
)

/**
 * Decides which of the readings (taken every few seconds) reach the journal, and turns one into the line's fields. The journal holds
 * about 1500 lines for everything, so: the first [first] readings of a stream are written (the picture's start), then one a minute,
 * plus a reading that looks wrong (fps <= 2, no frame decoded, packets lost, frames dropped, a freeze) at up to one per reading for
 * [anomalyBurst] readings in a row, after which it falls back to one a minute until a good reading resets it. Pure: no clock of its own.
 */
class StatsPacer(
    private val first: Int = 3,
    private val everyMs: Long = 60_000,
    private val anomalyBurst: Int = 12,
) {
    private var prev: RtcReading? = null
    private var prevAt = 0L
    private var seen = 0
    private var lastEmitAt = Long.MIN_VALUE
    private var badRun = 0

    /** The fields of the line for this reading, or null when the pacer holds it back. */
    fun line(nowMs: Long, r: RtcReading): List<Pair<String, Any?>>? {
        val p = prev
        val dt = nowMs - prevAt
        prev = r
        prevAt = nowMs
        seen++
        fun d(cur: Long?, old: Long?): Long? = if (cur != null && old != null && cur >= old && p != null) cur - old else null
        val dDec = d(r.decoded, p?.decoded)
        val dDrop = d(r.dropped, p?.dropped)
        val dLost = d(r.lost, p?.lost)
        val dFrz = d(r.freezes, p?.freezes)
        val dBytes = d(r.bytes, p?.bytes)
        val kbps = if (dBytes != null && dt > 0) dBytes * 8 / dt else null // bits per ms = kbit per s
        val bad = (r.fps != null && r.fps <= 2) || dDec == 0L || (dLost ?: 0L) > 0 || (dDrop ?: 0L) > 0 || (dFrz ?: 0L) > 0
        val emit = when {
            seen <= first -> true
            bad && badRun < anomalyBurst -> true
            nowMs - lastEmitAt >= everyMs -> true
            else -> false
        }
        badRun = if (bad) badRun + 1 else 0
        if (!emit) return null
        lastEmitAt = nowMs
        return listOf(
            "fps" to r.fps, "dec" to dDec, "drop" to dDrop, "kbps" to kbps, "jit" to r.jitterMs, "lost" to dLost,
            "frz" to dFrz, "frzms" to r.freezeMs, "w" to r.width, "h" to r.height, "codec" to word(r.decoder),
            "rtt" to r.rttMs, "path" to path(r.localType, r.remoteType), "net" to word(r.net), "proto" to word(r.proto),
            "flag" to if (bad) "bad" else null,
        )
    }

    companion object {
        /** A platform string as a journal word: lowercase, anything but `a-z 0-9 _ -` becomes `_`, at most 24 characters; null when empty. */
        fun word(s: String?): String? = s?.lowercase()?.map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }?.joinToString("")?.take(24)?.ifEmpty { null }

        /** `host-srflx`: the nominated pair's local and remote candidate types. */
        fun path(local: String?, remote: String?): String? = if (local == null && remote == null) null else "${word(local) ?: "?"}-${word(remote) ?: "?"}"
    }
}

/**
 * Notices a live stream whose frame total stops growing: [Event.Began] once no frame has come for [stallMs] (the label would read
 * `0 fps`), [Event.Ended] when frames return, with how long they were missing (from the last frame seen). Fed the running total
 * about once a second. A total or a clock that goes back starts afresh. Pure.
 */
class StallTracker(private val stallMs: Long = 2_000) {
    sealed class Event {
        data class Began(val silentMs: Long) : Event()
        data class Ended(val downMs: Long) : Event()
    }

    private var lastTotal = -1L
    private var lastChangeAt = 0L
    private var stalled = false

    fun reset() {
        lastTotal = -1L
        stalled = false
    }

    fun sample(nowMs: Long, total: Long): Event? {
        if (lastTotal < 0 || total < lastTotal || nowMs < lastChangeAt) {
            lastTotal = total
            lastChangeAt = nowMs
            stalled = false
            return null
        }
        if (total > lastTotal) {
            val was = stalled
            val down = nowMs - lastChangeAt
            lastTotal = total
            lastChangeAt = nowMs
            stalled = false
            return if (was) Event.Ended(down) else null
        }
        if (!stalled && nowMs - lastChangeAt >= stallMs) {
            stalled = true
            return Event.Began(nowMs - lastChangeAt)
        }
        return null
    }

    /** The stream ends while stalled: how long it had been missing frames, or null when it was not stalled. */
    fun end(nowMs: Long): Long? {
        if (!stalled) return null
        stalled = false
        return nowMs - lastChangeAt
    }
}
