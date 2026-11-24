package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.push.PushJournalFormat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** 0.6.95: the per-window audio lines (WebRTC and frames), the Android audio words, and the sampler's once/stop/cap guards. */
class CallAudioDiagTest {
    private fun line(p: Pair<String, List<Pair<String, Any?>>>, extra: List<Pair<String, Any?>> = listOf("id" to 3, "ms" to 123456L)) =
        PushJournalFormat.diagLine(0L, TimeZone.getTimeZone("UTC"), "ui", p.first, extra + p.second)

    private fun fields(l: String) = l.split(' ').drop(3).associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun inbound(over: Map<String, Any?> = emptyMap()) = RawStat(
        "inbound-rtp", "RTCInboundRTPAudioStream_1",
        mapOf<String, Any?>(
            "kind" to "audio", "packetsReceived" to 250L, "packetsLost" to 2L, "jitter" to 0.012, "jitterBufferDelay" to 5000.0,
            "jitterBufferEmittedCount" to 250_000L, "jitterBufferTargetDelay" to 6000.0, "concealedSamples" to 4800L, "concealmentEvents" to 6L,
            "silentConcealedSamples" to 960L, "insertedSamplesForDeceleration" to 1200L, "removedSamplesForAcceleration" to 300L,
            "totalSamplesReceived" to 240_000L, "totalAudioEnergy" to 0.01, "totalSamplesDuration" to 5.0,
        ) + over,
    )

    private val playout = RawStat("media-playout", "p", mapOf("kind" to "audio", "synthesizedSamplesDuration" to 0.2, "synthesizedSamplesEvents" to 4L, "totalPlayoutDelay" to 1500.0, "totalSamplesCount" to 100_000L))
    private val outbound = RawStat("outbound-rtp", "o", mapOf("kind" to "audio", "packetsSent" to 248L))
    private val source = RawStat("media-source", "s", mapOf("kind" to "audio", "audioLevel" to 0.05, "totalAudioEnergy" to 0.0125, "totalSamplesDuration" to 5.0, "echoReturnLoss" to -30.0, "echoReturnLossEnhancement" to 12.0))

    @Test fun `the first window reads the whole value, the next one the change`() {
        val a = RtcWindow.snap(listOf(inbound(), playout, outbound, source))
        val first = fields(line(RtcWindow.inbound(1, 5000, null, a)))
        assertEquals("250", first["rcv"]); assertEquals("2", first["lost"]); assertEquals("12", first["jit"])
        assertEquals("20", first["jbd"], "5000 sample-seconds over 250000 emitted samples = 20 ms average"); assertEquals("24", first["jbt"])
        assertEquals("4800", first["conc"]); assertEquals("6", first["cev"]); assertEquals("960", first["sil"])
        assertEquals("1200", first["ins"]); assertEquals("300", first["rem"]); assertEquals("240000", first["tot"])
        assertEquals("200", first["synth"]); assertEquals("4", first["synthev"]); assertEquals("15", first["pdly"])
        assertEquals("-27", first["dnrms"], "sqrt(0.01/5) = 0.0447 = -27 dBFS")
        val b = RtcWindow.snap(listOf(inbound(mapOf("packetsReceived" to 500L, "packetsLost" to 2L, "concealedSamples" to 5000L, "jitterBufferDelay" to 6000.0, "jitterBufferEmittedCount" to 500_000L)), playout, outbound, source))
        val second = fields(line(RtcWindow.inbound(2, 5010, a, b)))
        assertEquals("250", second["rcv"]); assertEquals("0", second["lost"]); assertEquals("200", second["conc"])
        assertEquals("4", second["jbd"], "1000 sample-seconds over 250000 more samples")
    }

    @Test fun `dnrms is dBFS of the window's received energy`() {
        assertEquals(-27, RtcWindow.rmsDb(0.01, 5.0)) // sqrt(0.002) = 0.0447 -> -27 dBFS
        assertEquals(-99, RtcWindow.rmsDb(0.0, 5.0))
        assertNull(RtcWindow.rmsDb(null, 5.0)); assertNull(RtcWindow.rmsDb(1.0, 0.0))
    }

    @Test fun `the uplink line carries sent, mic level and echo return loss`() {
        val s = RtcWindow.snap(listOf(inbound(), outbound, source))
        val f = fields(line(RtcWindow.outbound(4, null, s)))
        assertEquals("4", f["w"]); assertEquals("248", f["sent"]); assertEquals("50", f["lvl"]); assertEquals("-30", f["erl"]); assertEquals("12", f["erle"])
        assertEquals("-26", f["uprms"], "sqrt(0.0125/5) = 0.05 = -26 dBFS")
    }

    @Test fun `a member the stack did not report is left out, never 0 or a question mark`() {
        val s = RtcWindow.snap(listOf(RawStat("inbound-rtp", "i", mapOf("kind" to "audio", "packetsReceived" to 10L))))
        val l = line(RtcWindow.inbound(1, 5000, null, s))
        val f = fields(l)
        assertEquals("10", f["rcv"])
        for (k in listOf("lost", "jit", "jbd", "jbt", "conc", "dnrms", "synth", "pdly")) assertFalse(f.containsKey(k), "$k in $l")
        assertFalse(l.contains("=?"), l)
    }

    @Test fun `a counter that went backwards (a restarted stream) drops that field, and a video stat is not audio`() {
        val a = RtcWindow.snap(listOf(inbound()))
        val b = RtcWindow.snap(listOf(inbound(mapOf("packetsReceived" to 3L))))
        assertFalse(fields(line(RtcWindow.inbound(2, 5000, a, b))).containsKey("rcv"))
        val v = RtcWindow.snap(listOf(RawStat("inbound-rtp", "v", mapOf("kind" to "video", "packetsReceived" to 99L))))
        assertTrue(v.counters.isEmpty())
    }

    @Test fun `every window line is under 240 characters with the id head, and uses only legal keys and values`() {
        val a = RtcWindow.snap(listOf(inbound(), playout, outbound, source))
        for (p in listOf(RtcWindow.inbound(240, 5000, null, a), RtcWindow.outbound(240, null, a))) {
            val l = line(p)
            assertTrue(l.length < 240, "${l.length}: $l")
            assertFalse(l.contains("=?"), l)
            assertTrue(Regex("[0-9:.]+ ui rtc-win(-up)? id=3 ms=123456( [a-z0-9]+=-?[0-9]+)+").matches(l), l)
        }
    }

    // ---- the sampler ---------------------------------------------------------------------------------------------------------

    private class Rig {
        var now = 0L
        val tasks = ArrayDeque<() -> Unit>()
        val delays = mutableListOf<Long>()
        var cancelled = 0
        val pendingReads = ArrayDeque<(List<RawStat>?) -> Unit>()
        val out = mutableListOf<Pair<String, List<Pair<String, Any?>>>>()
        var autoAnswer: List<RawStat>? = null
        fun sampler(max: Int = 240) = RtcWindowSampler(
            read = { cb -> if (autoAnswer != null) cb(autoAnswer) else pendingReads.addLast(cb) },
            emit = { e, f -> out += e to f },
            scheduler = { d, t -> delays += d; tasks.addLast(t); Cancellable { cancelled++ } },
            clockMs = { now },
            maxWindows = max,
        )
        fun fire() { now += 5000; tasks.removeFirst()() }
    }

    @Test fun `a connected call is read every 5 s and each read writes two lines with the real window length`() {
        val r = Rig(); r.autoAnswer = listOf(inbound(), outbound)
        val s = r.sampler(); s.start()
        assertEquals(listOf(5000L), r.delays)
        r.fire(); r.fire(); r.fire()
        assertEquals(listOf("rtc-win", "rtc-win-up", "rtc-win", "rtc-win-up", "rtc-win", "rtc-win-up"), r.out.map { it.first })
        assertEquals(listOf(1, 2, 3), r.out.filter { it.first == "rtc-win" }.map { it.second.first { p -> p.first == "w" }.second })
        assertEquals(5000L, r.out[0].second.first { it.first == "dt" }.second)
        assertEquals(listOf(5000L, 5000L, 5000L, 5000L), r.delays)
    }

    @Test fun `a flapping connection starts one sampler, and stop cancels it and drops a late answer`() {
        val r = Rig(); val s = r.sampler()
        repeat(5) { s.start() }
        assertEquals(1, r.delays.size)
        r.fire()
        assertEquals(1, r.pendingReads.size)
        s.stop()
        assertEquals(1, r.cancelled)
        r.pendingReads.removeFirst()(listOf(inbound()))
        assertTrue(r.out.isEmpty(), "an answer after stop writes nothing")
        s.start()
        assertEquals(2, r.delays.size, "stopped means stopped")
    }

    @Test fun `a read that never answers costs its window, not the sampler, and never piles up reads`() {
        val r = Rig(); val s = r.sampler(); s.start()
        r.fire(); r.fire(); r.fire()
        assertEquals(1, r.pendingReads.size, "one read in flight")
        r.pendingReads.removeFirst()(listOf(inbound(), outbound))
        r.fire()
        assertEquals(1, r.pendingReads.size)
        assertEquals(2, r.out.size)
    }

    @Test fun `a null read or a throwing read writes nothing and the next window still comes`() {
        val r = Rig(); var boom = true
        val s = RtcWindowSampler({ cb -> if (boom) { boom = false; throw IllegalStateException("x") } else cb(null) }, { e, f -> r.out += e to f }, { d, t -> r.tasks.addLast(t); Cancellable {} }, { r.now })
        s.start(); r.fire(); r.fire(); r.fire()
        assertTrue(r.out.isEmpty())
    }

    @Test fun `the number of windows is capped`() {
        val r = Rig(); r.autoAnswer = listOf(inbound()); val s = r.sampler(max = 3); s.start()
        r.fire(); r.fire(); r.fire()
        assertEquals(6, r.out.size)
        assertTrue(r.tasks.size <= 1)
        if (r.tasks.isNotEmpty()) { r.fire() }
        assertEquals(6, r.out.size, "no fourth window")
    }

    // ---- frames --------------------------------------------------------------------------------------------------------------

    @Test fun `FrameStats window holds the gap histogram, counters and queue range of its own window only`() {
        var t = 0L
        val f = FrameStats { t }
        for (gap in listOf(40L, 40L, 45L, 55L, 450L)) { t += gap; f.onReceived() }
        f.onQueue(2); f.onQueue(5); f.onSent(true); f.onSent(false)
        val w1 = f.takeWindow()
        assertEquals(5, w1.received); assertEquals(1, w1.sent); assertEquals(1, w1.dropped); assertEquals(2, w1.qMin); assertEquals(5, w1.qMax)
        assertEquals(450L, w1.maxGapMs)
        val lvl = fields(line(FrameWindowWords.flow(1, 5000, w1), listOf("call" to "x")))
        assertEquals("2", lvl["g50"]); assertEquals("1", lvl["g60"]); assertEquals("1", lvl["gbig"]); assertEquals("450", lvl["gmax"])
        t += 40; f.onReceived(); f.onQueue(3)
        val w2 = f.takeWindow()
        assertEquals(1, w2.received); assertEquals(40L, w2.maxGapMs); assertEquals(3, w2.qMin); assertEquals(0, w2.sent)
        assertEquals(1L, w2.gaps[2])
        assertEquals(6L, f.summary().received, "the cumulative summary is untouched by windows")
        assertEquals(450L, f.summary().maxGapMs)
    }

    @Test fun `DeviceStats window gives RMS and peak per window and resets`() {
        val d = DeviceStats()
        d.onUp(ShortArray(100) { 3277 }) // -20 dBFS
        d.onDown(ShortArray(100) { if (it % 2 == 0) 16384 else -16384 }) // -6 dBFS
        d.onDown(ShortArray(100) { 32767 })
        val w = d.takeWindow()
        assertEquals(-20, DeviceStats.dbfsRms(w.upSumSq, w.upN))
        assertEquals(-2, DeviceStats.dbfsRms(w.dnSumSq, w.dnN), "half at -6 dBFS, half at full scale: -2 dBFS")
        assertEquals(100L, w.dnClip); assertEquals(2L, w.dnFrames); assertEquals(1L, w.upFrames)
        val l = fields(line(FrameWindowWords.level(2, w, 120, -1), listOf("call" to "x")))
        assertEquals("-20", l["uprms"]); assertEquals("-2", l["dnrms"]); assertEquals("0", l["upclip"]); assertEquals("100", l["dnclip"])
        assertEquals("120", l["rxfirst"]); assertFalse(l.containsKey("txfirst"))
        val again = d.takeWindow()
        assertNull(DeviceStats.dbfsRms(again.upSumSq, again.upN), "no samples is not a level")
        assertEquals(32767, DeviceStats().also { it.onUp(ShortArray(1) { 32767 }) }.upPeak(), "the cumulative peak still works")
    }

    // ---- FrameMedia wiring -----------------------------------------------------------------------------------------------------

    private class Pcm : PcmDevice {
        var nextPlayout: (() -> ShortArray?)? = null
        var onCapture: ((ShortArray) -> Unit)? = null
        override fun prepare(profile: AudioProfile) {}
        override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) { this.onCapture = onCapture; this.nextPlayout = nextPlayout }
        override fun setMuted(muted: Boolean) {}
        override fun stop() {}
        override fun facts(): List<Pair<String, Any?>> = listOf("outsr" to 16000, "outbuf" to 1280, "under" to 2)
    }

    private class Channel : FrameChannel {
        var onOpened: ((CommandReply) -> Unit)? = null
        var listener: FrameListener? = null
        override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) { this.listener = listener; this.onOpened = onOpened }
        override fun send(callId: String, payloadBase64: String) = true
        override fun close(callId: String) {}
    }

    @Test fun `a frames call journals start settings, environment, windows, and an end flush`() {
        val ch = Channel(); val pcm = Pcm(); var now = 1_000L
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        val diag = mutableListOf<Pair<String, List<Pair<String, Any?>>>>(); val env = mutableListOf<String>()
        val m = FrameMedia(
            ch, pcm, object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} }, { it() },
            scheduler = { d, t -> tasks.addLast(d to t); Cancellable {} }, clockMs = { now },
            onDiag = { e, f -> diag += e to f }, onEnv = { env += it },
        )
        m.start("hso-1760000000000-12-a1b2c3d4e5f6", "gate") {}
        ch.onOpened!!(CommandReply.Reply(HaInbound.Result(1, true, buildJsonObject { put("codec", "l16") }, null)))
        assertEquals(listOf("start"), env)
        assertEquals(listOf("frames-trk"), diag.map { it.first })
        val trk = diag[0].second.toMap()
        assertEquals("start", trk["at"]); assertEquals("l16", trk["codec"]); assertEquals(16000, trk["rate"]); assertEquals(40, trk["framems"]); assertEquals(2, trk["under"])
        pcm.onCapture!!(ShortArray(640) { 1000 })
        ch.listener!!.onFrame(java.util.Base64.getEncoder().encodeToString(ByteArray(AudioProfile.L16.wireBytes)))
        pcm.nextPlayout!!()
        now += 5000
        tasks.first { it.first == FrameMedia.WINDOW_MS }.second()
        assertEquals(listOf("frames-trk", "frames-win", "frames-lvl"), diag.map { it.first })
        assertEquals(1L, diag[1].second.toMap()["recv"])
        m.close()
        assertEquals(listOf("start", "end"), env)
        assertEquals(listOf("frames-trk", "frames-win", "frames-lvl", "frames-win", "frames-lvl", "frames-trk"), diag.map { it.first })
        assertEquals("end", diag.last().second.toMap()["at"])
        for ((e, f) in diag) {
            val l = PushJournalFormat.diagLine(0L, TimeZone.getTimeZone("UTC"), "ui", e, f)
            assertTrue(l.length < 240, "${l.length}: $l"); assertFalse(l.contains("=?"), l); assertFalse(l.contains("hso-1760000000000"), l)
        }
        // without the sinks the call writes nothing and schedules no window timer
        val tasks2 = ArrayDeque<() -> Unit>()
        val quiet = FrameMedia(Channel().also { c -> c.onOpened = null }, Pcm(), object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} }, { it() }, scheduler = { _, t -> tasks2.addLast(t); Cancellable {} })
        quiet.close()
        assertTrue(tasks2.isEmpty())
    }

    // ---- the words -------------------------------------------------------------------------------------------------------------

    @Test fun `the environment words name the Android constants, with unknown and other kept apart`() {
        assertEquals("incomm", AudioEnvWords.mode(3)); assertEquals("normal", AudioEnvWords.mode(0)); assertEquals("unknown", AudioEnvWords.mode(null)); assertEquals("other", AudioEnvWords.mode(9))
        assertEquals("speaker", AudioEnvWords.device(2)); assertEquals("btsco", AudioEnvWords.device(7)); assertEquals("none", AudioEnvWords.device(null))
        assertEquals("media-voicecomm", AudioEnvWords.usages(listOf(2, 1, 2))); assertEquals("none", AudioEnvWords.usages(emptyList()))
        assertEquals("vcall", AudioEnvWords.stream(0)); assertEquals("lowlat", AudioEnvWords.perf(1)); assertEquals("none", AudioEnvWords.perf(0))
    }

    @Test fun `an audio-env line is legal Journal text`() {
        val l = line(
            "audio-env" to listOf<Pair<String, Any?>>(
                "at" to "start", "mode" to AudioEnvWords.mode(3), "vcall" to 15, "vcallmax" to 15, "music" to 7, "musicmax" to 15, "spk" to true, "comm" to AudioEnvWords.device(2),
                "mmute" to false, "playing" to 1, "pbusage" to AudioEnvWords.usages(listOf(2)), "outsr" to 48000, "outfpb" to 192, "lowlat" to true, "pro" to false,
            ),
        )
        assertTrue(l.length < 240, l); assertFalse(l.contains("=?"), l)
        assertEquals("15", fields(l)["vcall"]); assertEquals("yes", fields(l)["spk"])
    }
}
