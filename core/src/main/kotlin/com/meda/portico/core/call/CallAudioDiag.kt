package com.meda.portico.core.call

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * D-20261121-ee09 (logs report every relevant setting and datum), the owner 2026-11-21 (kitchen tablet: WebRTC door audio "progressively
 * garbled", Frames audio "volume very low"): the per-window audio numbers of a call as fixed-word Journal fields. Pure, so a JVM test
 * pins every name and unit; the app reads libwebrtc / AudioManager and hands the raw numbers here. Every builder returns
 * `event to fields` for `PushJournal.diag`; values are small integers or words (a legal Journal value), so no id, address or raw `?` gets in.
 * Observation only: nothing here changes what the audio does.
 */
object RtcWindow {
    /** One cumulative read: counters (monotonic since the call began) and gauges (the value now), by their libwebrtc names. */
    class Snap(val counters: Map<String, Double>, val gauges: Map<String, Double>)

    private val INBOUND = listOf(
        "packetsReceived", "packetsLost", "jitterBufferDelay", "jitterBufferEmittedCount", "jitterBufferTargetDelay", "concealedSamples",
        "concealmentEvents", "silentConcealedSamples", "insertedSamplesForDeceleration", "removedSamplesForAcceleration",
        "totalSamplesReceived", "totalAudioEnergy", "totalSamplesDuration",
    )
    private val PLAYOUT = listOf("synthesizedSamplesDuration", "synthesizedSamplesEvents", "totalPlayoutDelay", "totalSamplesCount")
    private val SOURCE = listOf("totalAudioEnergy", "totalSamplesDuration")

    private fun isAudio(s: RawStat): Boolean {
        val kind = (s.members["kind"] ?: s.members["mediaType"]) as? String
        return kind == null || kind == "audio"
    }

    private fun num(s: RawStat?, key: String): Double? =
        (s?.members?.get(key) as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun snap(stats: List<RawStat>): Snap {
        val inn = stats.firstOrNull { it.type == "inbound-rtp" && isAudio(it) }
        val out = stats.firstOrNull { it.type == "outbound-rtp" && isAudio(it) }
        val play = stats.firstOrNull { it.type == "media-playout" && isAudio(it) }
        val src = stats.firstOrNull { it.type == "media-source" && isAudio(it) }
        val c = HashMap<String, Double>()
        val g = HashMap<String, Double>()
        for (k in INBOUND) num(inn, k)?.let { c["in.$k"] = it }
        for (k in PLAYOUT) num(play, k)?.let { c["pl.$k"] = it }
        for (k in SOURCE) num(src, k)?.let { c["src.$k"] = it }
        num(out, "packetsSent")?.let { c["out.packetsSent"] = it }
        num(inn, "jitter")?.let { g["jitter"] = it }
        for (k in listOf("audioLevel", "echoReturnLoss", "echoReturnLossEnhancement")) num(src, k)?.let { g[k] = it }
        return Snap(c, g)
    }

    /** The change in counter [key] across the window (the whole value for the first window); null if either end lacks it or it went backwards. */
    private fun d(prev: Snap?, cur: Snap, key: String): Double? {
        val b = cur.counters[key] ?: return null
        val a = if (prev == null) 0.0 else prev.counters[key] ?: return null
        return (b - a).takeIf { it >= 0.0 }
    }

    private fun long(v: Double?): Long? = v?.let { Math.round(it) }

    /** Seconds-valued [num] per [den] events, in whole ms (an average over the window); null when there was nothing to average. */
    private fun avgMs(num: Double?, den: Double?): Long? = if (num == null || den == null || den <= 0.0) null else Math.round(num / den * 1000.0)

    /** Energy over duration of the window, as whole dBFS (RMS); silence is -99. */
    fun rmsDb(energy: Double?, seconds: Double?): Int? {
        if (energy == null || seconds == null || seconds <= 0.0 || energy < 0.0) return null
        val rms = sqrt(energy / seconds)
        return if (rms <= 0.0) -99 else Math.round(20.0 * log10(rms)).toInt().coerceAtLeast(-99)
    }

    /**
     * `rtc-win`: the downlink and playout side of one window. `w` window number, `dt` its real length in ms, `rcv`/`lost` packets,
     * `jit` jitter now (ms), `jbd` average jitter-buffer delay in use (ms), `jbt` its target (ms), `conc`/`cev`/`sil` concealed samples,
     * concealment events and silent-concealed samples, `ins`/`rem` samples inserted for deceleration / removed for acceleration (NetEq
     * stretching), `tot` samples received, `dnrms` the received signal's level (dBFS), `synth`/`synthev` playout-synthesised audio (ms) and
     * events (the speaker was starved), `pdly` average playout delay (ms). A member the stack did not report is left out, never 0.
     */
    fun inbound(w: Int, dtMs: Long, prev: Snap?, cur: Snap): Pair<String, List<Pair<String, Any?>>> {
        fun i(k: String) = d(prev, cur, "in.$k")
        fun p(k: String) = d(prev, cur, "pl.$k")
        val jitter = cur.gauges["jitter"]?.let { Math.round(it * 1000.0) }
        return "rtc-win" to listOf(
            "w" to w, "dt" to dtMs, "rcv" to long(i("packetsReceived")), "lost" to long(i("packetsLost")), "jit" to jitter,
            "jbd" to avgMs(i("jitterBufferDelay"), i("jitterBufferEmittedCount")),
            "jbt" to avgMs(i("jitterBufferTargetDelay"), i("jitterBufferEmittedCount")),
            "conc" to long(i("concealedSamples")), "cev" to long(i("concealmentEvents")), "sil" to long(i("silentConcealedSamples")),
            "ins" to long(i("insertedSamplesForDeceleration")), "rem" to long(i("removedSamplesForAcceleration")),
            "tot" to long(i("totalSamplesReceived")), "dnrms" to rmsDb(i("totalAudioEnergy"), i("totalSamplesDuration")),
            "synth" to long(p("synthesizedSamplesDuration")?.times(1000.0)), "synthev" to long(p("synthesizedSamplesEvents")),
            "pdly" to avgMs(p("totalPlayoutDelay"), p("totalSamplesCount")),
        )
    }

    /** `rtc-win-up`: the uplink side: `sent` packets, `uprms` the microphone's level in the window (dBFS), `lvl` its level now (permille of full scale), `erl`/`erle` echo return loss (dB). */
    fun outbound(w: Int, prev: Snap?, cur: Snap): Pair<String, List<Pair<String, Any?>>> {
        fun s(k: String) = d(prev, cur, "src.$k")
        return "rtc-win-up" to listOf(
            "w" to w, "sent" to long(d(prev, cur, "out.packetsSent")), "uprms" to rmsDb(s("totalAudioEnergy"), s("totalSamplesDuration")),
            "lvl" to cur.gauges["audioLevel"]?.let { Math.round(it * 1000.0) },
            "erl" to cur.gauges["echoReturnLoss"]?.let { Math.round(it) }, "erle" to cur.gauges["echoReturnLossEnhancement"]?.let { Math.round(it) },
        )
    }
}

/**
 * Reads libwebrtc's stats every [periodMs] while a call's audio is up and hands each window's two lines to [emit]. The next read is
 * scheduled BEFORE the current one runs, so a read that never answers costs its window, not the sampler; at most one read is in flight.
 * [stop] ends it; a late answer after [stop] is dropped. Capped at [maxWindows] (20 minutes at 5 s), so a forgotten call cannot flood the Journal.
 */
class RtcWindowSampler(
    private val read: ((List<RawStat>?) -> Unit) -> Unit,
    private val emit: (String, List<Pair<String, Any?>>) -> Unit,
    private val scheduler: Scheduler,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val periodMs: Long = PERIOD_MS,
    private val maxWindows: Int = MAX_WINDOWS,
) {
    private val lock = Any()
    private var started = false
    private var stopped = false
    private var inFlight = false
    private var n = 0
    private var prev: RtcWindow.Snap? = null
    private var prevAt = -1L
    private var timer: Cancellable? = null

    /** Idempotent: a reconnect flap that fires `connected` again does not start a second sampler. */
    fun start() {
        synchronized(lock) {
            if (started || stopped) return
            started = true
            prevAt = clockMs()
            arm()
        }
    }

    fun stop() {
        val t = synchronized(lock) { stopped = true; timer.also { timer = null } }
        try { t?.cancel() } catch (_: Throwable) {}
    }

    private fun arm() {
        timer = try { scheduler.schedule(periodMs) { tick() } } catch (_: Throwable) { null }
    }

    private fun tick() {
        synchronized(lock) {
            if (stopped || n >= maxWindows) return
            arm()
            if (inFlight) return
            inFlight = true
        }
        try {
            read { stats -> onStats(stats) }
        } catch (_: Throwable) {
            synchronized(lock) { inFlight = false }
        }
    }

    private fun onStats(stats: List<RawStat>?) {
        val lines: List<Pair<String, List<Pair<String, Any?>>>>
        synchronized(lock) {
            inFlight = false
            if (stopped || stats == null) return
            val now = clockMs()
            val cur = try { RtcWindow.snap(stats) } catch (_: Throwable) { return }
            n++
            lines = listOf(RtcWindow.inbound(n, (now - prevAt).coerceAtLeast(0), prev, cur), RtcWindow.outbound(n, prev, cur))
            prev = cur
            prevAt = now
        }
        for ((e, f) in lines) try { emit(e, f) } catch (_: Throwable) {}
    }

    companion object {
        const val PERIOD_MS = 5_000L
        const val MAX_WINDOWS = 240
    }
}

/** The frames path's window lines: flow (`frames-win`) and level (`frames-lvl`), from [FrameStats.Window] and [DeviceWindow]. */
object FrameWindowWords {
    /** Coarse gap buckets over [FrameStats.GAP_BOUNDS] (a nominal gap is 40 ms): `g50` is gaps up to 50 ms ... `gbig` over 400 ms. */
    val GAP_FIELDS: List<Pair<String, IntRange>> = listOf(
        "g50" to 0..3, "g60" to 4..4, "g80" to 5..6, "g100" to 7..8, "g200" to 9..11, "g400" to 12..13, "gbig" to 14..FrameStats.GAP_BOUNDS.size,
    )

    fun flow(w: Int, dtMs: Long, f: FrameStats.Window): Pair<String, List<Pair<String, Any?>>> =
        "frames-win" to listOf<Pair<String, Any?>>(
            "w" to w, "dt" to dtMs, "sent" to f.sent, "drop" to f.dropped, "recv" to f.received, "conc" to f.concealed, "under" to f.underruns,
            "qmin" to f.qMin, "qmax" to f.qMax,
        ) + GAP_FIELDS.map { (name, r) -> name to r.sumOf { f.gaps[it] } } + listOf("gmax" to f.maxGapMs, "gp95" to f.p95GapMs)

    /** Levels in dBFS (RMS and peak, whole dB; silence -99), clipped sample counts, and how many frames were heard / sent. `rxfirst`/`txfirst`: ms from the start to the first frame each way. */
    fun level(w: Int, d: DeviceWindow, rxFirstMs: Long, txFirstMs: Long): Pair<String, List<Pair<String, Any?>>> =
        "frames-lvl" to listOf(
            "w" to w, "uprms" to DeviceStats.dbfsRms(d.upSumSq, d.upN), "dnrms" to DeviceStats.dbfsRms(d.dnSumSq, d.dnN),
            "uppeak" to DeviceStats.dbfs(d.upPeak), "dnpeak" to DeviceStats.dbfs(d.dnPeak), "upclip" to d.upClip, "dnclip" to d.dnClip,
            "upn" to d.upFrames, "dnn" to d.dnFrames,
            "rxfirst" to rxFirstMs.takeIf { it >= 0 }, "txfirst" to txFirstMs.takeIf { it >= 0 },
        )
}

/** The Journal words for the Android audio environment (`audio-env`); the Android half reads the ints, these only name them. */
object AudioEnvWords {
    /** `AudioManager.getMode()`. */
    fun mode(m: Int?): String = when (m) { 0 -> "normal"; 1 -> "ringtone"; 2 -> "incall"; 3 -> "incomm"; 4 -> "screening"; null -> "unknown"; else -> "other" }

    /** `AudioDeviceInfo.getType()` of the communication device (the route the call audio uses). */
    fun device(type: Int?): String = when (type) {
        null -> "none"; 1 -> "earpiece"; 2 -> "speaker"; 3 -> "headset"; 4 -> "headphones"; 7 -> "btsco"; 8 -> "bta2dp"; 11 -> "usbdev"; 22 -> "usbhead"
        26 -> "bthearing"; 27 -> "blebroad"; 30 -> "bleheadset"; 31 -> "blespeaker"; else -> "other"
    }

    /** `AudioAttributes.getUsage()`, for the playing streams. */
    fun usage(u: Int): String = when (u) {
        1 -> "media"; 2 -> "voicecomm"; 3 -> "signalling"; 4 -> "alarm"; 5 -> "notif"; 6 -> "ring"; 7 -> "notifcomm"; 11 -> "assist"; 12 -> "assistnav"
        13 -> "sonification"; 14 -> "game"; 16 -> "assistant"; 0 -> "unknown"; else -> "other"
    }

    /** The distinct usages of the streams playing now, as one word (`voicecomm`, `voicecomm-media`); `none` when nothing plays. */
    fun usages(list: List<Int>): String = list.distinct().sorted().joinToString("-") { usage(it) }.ifEmpty { "none" }.take(32)

    /** `AudioTrack.getStreamType()`. */
    fun stream(t: Int?): String = when (t) { 0 -> "vcall"; 1 -> "system"; 2 -> "ring"; 3 -> "music"; 4 -> "alarm"; 5 -> "notif"; null -> "unknown"; else -> "other" }

    /** `AudioTrack.getPerformanceMode()`. */
    fun perf(p: Int?): String = when (p) { 0 -> "none"; 1 -> "lowlat"; 2 -> "powersave"; null -> "unknown"; else -> "other" }
}
