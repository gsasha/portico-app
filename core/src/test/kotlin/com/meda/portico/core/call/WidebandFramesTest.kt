package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * DEV-811 P1 + P2: the profile table, the l16 and pcmu round trips through [FrameMedia], the device lifecycle (prepare before the open,
 * the 8 kHz fallback, release on every exit, the start check and the mark), the codec rule on the reply, and the counters.
 * Device behaviour on a real phone is NOT covered here.
 */
class WidebandFramesTest {

    // ---- fakes ------------------------------------------------------------------------------------------------------

    private class Channel : FrameChannel {
        var openCodec: String? = null
        var opens = 0
        var listener: FrameListener? = null
        var onOpened: ((CommandReply) -> Unit)? = null
        val sent = mutableListOf<String>()
        var closes = 0
        override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) {
            opens++; openCodec = codec; this.listener = listener; this.onOpened = onOpened
        }
        override fun send(callId: String, payloadBase64: String): Boolean { sent += payloadBase64; return true }
        override fun close(callId: String) { closes++ }
    }

    /** Counts prepares and releases; [failAt] lists the rates whose prepare throws; [startFails] makes start throw. */
    private class Pcm : PcmDevice {
        val prepared = mutableListOf<Int>()
        var live = 0 // prepared and not yet released
        var releases = 0
        var starts = 0
        var failAt = emptySet<Int>()
        var startFails = false
        var capture: ((ShortArray) -> Unit)? = null
        var playout: (() -> ShortArray?)? = null
        var onError: ((String) -> Unit)? = null
        override fun prepare(profile: AudioProfile) {
            if (profile.rate in failAt) throw IllegalStateException("cannot ${profile.rate}")
            prepared += profile.rate; live++
        }
        override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) {
            if (startFails) throw IllegalStateException("recorder not recording")
            starts++; capture = onCapture; playout = nextPlayout; this.onError = onError
        }
        override fun setMuted(muted: Boolean) {}
        override fun stop() { if (live > 0) { live--; releases++ } }
        override fun report() = DeviceReport("on", "unavailable", 12)
    }

    private class Events : MediaListener {
        val log = mutableListOf<String>()
        override fun onConnected() { log += "connected" }
        override fun onDisconnected() { log += "disconnected" }
        override fun onFailed() { log += "failed" }
    }

    private class Timer : Scheduler {
        var task: (() -> Unit)? = null
        var delay = 0L
        var cancelled = false
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable { this.task = task; delay = delayMs; return Cancellable { cancelled = true } }
    }

    private val ch = Channel()
    private val pcm = Pcm()
    private val ev = Events()
    private val timer = Timer()
    private var clock = 1_000L
    private val reports = mutableListOf<FrameReport>()
    private var marked = 0
    private val posted = ArrayDeque<() -> Unit>()

    private fun media(choice: AudioChoice = AudioChoice.WIDEBAND, markWord: String = "none") = FrameMedia(
        ch, pcm, ev, { posted.addLast(it) }, choice = choice, scheduler = timer, clockMs = { clock },
        onWidebandFailed = { marked++ }, markWord = { markWord }, onReport = { reports += it },
    )

    private fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }

    private fun reply(codec: String, frameBytes: Int? = null) = CommandReply.Reply(
        HaInbound.Result(1, true, buildJsonObject { put("codec", codec); if (frameBytes != null) put("frame_bytes", frameBytes) }, null),
    )

    private fun run(m: FrameMedia, r: CommandReply): List<AnswerFailure?> {
        val results = mutableListOf<AnswerFailure?>()
        m.start("c1", "gate") { results += it }
        if (ch.onOpened != null) ch.onOpened!!(r)
        drain()
        return results
    }

    // ---- the profile table ------------------------------------------------------------------------------------------

    @Test
    fun `the profile table is the design's`() {
        val p = AudioProfile.PCMU
        assertEquals(listOf("pcmu", "8000", "320", "320", "2500"), listOf(p.codec, "${p.rate}", "${p.frameSamples}", "${p.wireBytes}", "${p.backlogBytes}"))
        val l = AudioProfile.L16
        assertEquals(listOf("l16", "16000", "640", "1280", "8990"), listOf(l.codec, "${l.rate}", "${l.frameSamples}", "${l.wireBytes}", "${l.backlogBytes}"))
        assertEquals(1280, l.frameBytes)
        assertSame(AudioProfile.L16, AudioProfile.forCodec("l16"))
        assertNull(AudioProfile.forCodec("opus"))
    }

    private fun assertSame(a: Any?, b: Any?) = assertTrue(a === b)

    @Test
    fun `l16 is little-endian PCM16 and round trips exactly`() {
        val pcm = ShortArray(640) { ((it * 97) - 30000).toShort() }
        val bytes = L16Codec.encode(pcm)
        assertEquals(1280, bytes.size)
        assertEquals((pcm[1].toInt() and 0xFF).toByte(), bytes[2])
        assertEquals((pcm[1].toInt() shr 8).toByte(), bytes[3])
        assertTrue(pcm.contentEquals(L16Codec.decode(bytes)))
        assertEquals(0x34.toByte(), L16Codec.encode(shortArrayOf(0x1234))[0])
    }

    // ---- the reply's codec must equal the requested one --------------------------------------------------------------

    @Test
    fun `parseOpen for l16, pcmu, another codec and a frame size mismatch`() {
        fun parse(codec: String?, fb: Int?, want: AudioProfile): MediaCommands.Opened = MediaCommands.parseOpen(
            HaInbound.Result(1, true, buildJsonObject { if (codec != null) put("codec", codec); if (fb != null) put("frame_bytes", fb) }, null), want,
        )
        assertEquals(MediaCommands.Opened.Ok("l16"), parse("l16", 1280, AudioProfile.L16))
        assertEquals(MediaCommands.Opened.Ok("l16"), parse("l16", null, AudioProfile.L16), "no frame_bytes in the reply is fine")
        assertEquals(MediaCommands.Opened.Ok("pcmu"), parse("pcmu", 320, AudioProfile.PCMU))
        assertTrue(parse("pcmu", 320, AudioProfile.L16) is MediaCommands.Opened.Refused, "asked l16, answered pcmu")
        assertTrue(parse("l16", 1280, AudioProfile.PCMU) is MediaCommands.Opened.Refused, "asked pcmu, answered l16")
        assertTrue(parse("opus", null, AudioProfile.L16) is MediaCommands.Opened.Refused)
        assertTrue(parse("l16", 320, AudioProfile.L16) is MediaCommands.Opened.Refused, "wrong frame size")
        assertTrue(parse(null, null, AudioProfile.L16) is MediaCommands.Opened.Refused)
    }

    @Test
    fun `a reply that names another codec than the one asked fails the start and releases the device`() {
        val r = run(media(), reply("pcmu"))
        assertEquals("malformed_result", r.single()!!.code)
        assertEquals(listOf(16000), pcm.prepared)
        assertEquals(1, pcm.releases)
        assertEquals(0, pcm.starts)
    }

    // ---- both rates through FrameMedia ---------------------------------------------------------------------------------

    @Test
    fun `wideband asks l16, prepares at 16 kHz before the open and a PCM frame round trips at 16 kHz`() {
        val m = media()
        m.start("c1", "gate") { }
        assertEquals("l16", ch.openCodec)
        assertEquals(listOf(16000), pcm.prepared)
        assertEquals(0, pcm.starts, "prepared, not started, until the reply")
        ch.onOpened!!(reply("l16", 1280)); drain()
        assertEquals(1, pcm.starts)
        val tone = ShortArray(640) { (it * 40 - 12000).toShort() }
        pcm.capture!!(tone)
        val wire = Base64.getDecoder().decode(ch.sent.single())
        assertEquals(1280, wire.size)
        assertTrue(tone.contentEquals(L16Codec.decode(wire)), "what the microphone read is what went on the wire")
        // the same bytes arriving as a downlink frame play back unchanged
        ch.listener!!.onFrame(ch.sent.single()); ch.listener!!.onFrame(ch.sent.single())
        assertTrue(tone.contentEquals(pcm.playout!!()!!))
    }

    @Test
    fun `Compatibility asks pcmu at 8 kHz with 320 byte frames and says why`() {
        val m = media(AudioChoice(false, "compat"))
        run(m, reply("pcmu", 320))
        assertEquals("pcmu", ch.openCodec)
        assertEquals(listOf(8000), pcm.prepared)
        pcm.capture!!(ShortArray(320) { 1000 })
        assertEquals(320, Base64.getDecoder().decode(ch.sent.single()).size)
        m.close()
        assertEquals("compat", reports.single().why)
        assertEquals("pcmu", reports.single().profile.codec)
    }

    @Test
    fun `the silence frame matches the profile`() {
        val wide = media(); run(wide, reply("l16"))
        wide.setMuted(true)
        pcm.capture!!(ShortArray(640) { 9000 })
        assertEquals(1280, Base64.getDecoder().decode(ch.sent.last()).size)
        assertTrue(Base64.getDecoder().decode(ch.sent.last()).all { it == 0.toByte() })
    }

    // ---- prepare before the open; the 8 kHz fallback -------------------------------------------------------------------

    @Test
    fun `a device that cannot prepare at 16 kHz is prepared at 8 kHz, the open asks pcmu and the journal says prep16`() {
        pcm.failAt = setOf(16000)
        val m = media()
        val r = run(m, reply("pcmu", 320))
        assertNull(r.single())
        assertEquals("pcmu", ch.openCodec)
        assertEquals(listOf(8000), pcm.prepared)
        assertEquals(0, marked, "a prepare failure is not a start failure: no mark")
        m.close()
        assertEquals("prep16", reports.single().why)
    }

    @Test
    fun `a device that cannot prepare at all refuses the call before any open, with one onResult`() {
        pcm.failAt = setOf(16000, 8000)
        val results = mutableListOf<AnswerFailure?>()
        val m = media()
        m.start("c1", "gate") { results += it }
        drain()
        assertEquals(0, ch.opens, "nothing was opened, so the station was not answered")
        assertEquals("media", results.single()!!.code)
        assertEquals(0, pcm.live)
    }

    @Test
    fun `Compatibility with a device that cannot prepare at 8 kHz also refuses before the open`() {
        pcm.failAt = setOf(8000)
        val results = mutableListOf<AnswerFailure?>()
        media(AudioChoice(false, "compat")).start("c1", "gate") { results += it }
        drain()
        assertEquals(0, ch.opens)
        assertEquals(1, results.size)
    }

    // ---- release on every exit -----------------------------------------------------------------------------------------

    @Test
    fun `every exit before the start releases the prepared device`() {
        // open refused
        run(media(), CommandReply.Reply(HaInbound.Result(1, false, null, com.meda.portico.core.ha.HaError("away_media_disabled", ""))))
        assertEquals(0, pcm.live, "open refused"); assertEquals(1, pcm.releases)
        // link lost / not sent
        ch.onOpened = null
        run(media(), CommandReply.Lost); assertEquals(0, pcm.live, "lost"); assertEquals(2, pcm.releases)
        run(media(), CommandReply.NotSent); assertEquals(0, pcm.live, "not sent"); assertEquals(3, pcm.releases)
        // reply refused (wrong codec)
        run(media(), reply("pcmu")); assertEquals(0, pcm.live, "reply refused"); assertEquals(4, pcm.releases)
        // closed before the reply
        val m = media()
        m.start("c2", "gate") { }
        assertEquals(1, pcm.live)
        m.close()
        assertEquals(0, pcm.live, "closed before the reply"); assertEquals(5, pcm.releases)
        ch.onOpened!!(reply("l16")); drain()
        assertEquals(0, pcm.starts, "a late reply starts nothing")
        // open never sent: the session closes the path right after start (link down before the open)
        val m2 = media(); m2.start("c3", "gate") { }; m2.close()
        assertEquals(0, pcm.live)
        assertEquals(0, reports.size, "audio never opened, so nothing to report")
    }

    @Test
    fun `close releases a running device once`() {
        val m = media(); run(m, reply("l16"))
        m.close(); m.close()
        assertEquals(1, pcm.releases)
        assertEquals(1, reports.size)
    }

    // ---- the start check and the mark ---------------------------------------------------------------------------------

    @Test
    fun `a start that throws at 16 kHz fails the call with one onResult and marks the device`() {
        pcm.startFails = true
        val results = run(media(), reply("l16"))
        assertEquals("media", results.single()!!.code)
        assertEquals(1, marked)
        assertEquals(0, pcm.live)
        assertTrue(ev.log.isEmpty())
    }

    @Test
    fun `a start that throws at 8 kHz does not mark the device`() {
        pcm.startFails = true
        run(media(AudioChoice(false, "compat")), reply("pcmu"))
        assertEquals(0, marked)
    }

    @Test
    fun `connected is reported at once, then no first frame within 2 seconds ends the call and marks the device`() {
        val results = mutableListOf<AnswerFailure?>()
        val m = media()
        m.start("c1", "gate") { results += it }
        ch.onOpened!!(reply("l16")); drain()
        assertEquals(listOf<AnswerFailure?>(null), results)
        assertEquals(listOf("connected"), ev.log)
        assertEquals(FrameMedia.FIRST_FRAME_LIMIT_MS, timer.delay)
        timer.task!!(); drain()
        assertEquals(listOf("connected", "failed"), ev.log)
        assertEquals(1, marked)
        assertEquals(1, results.size, "still exactly one onResult")
        m.close()
        assertEquals("noframe", reports.single().fail)
    }

    @Test
    fun `a first frame in time cancels nothing, passes the check and the time to it is journalled`() {
        val m = media(); run(m, reply("l16"))
        clock += 120
        pcm.capture!!(ShortArray(640))
        timer.task!!(); drain()
        assertEquals(listOf("connected"), ev.log)
        assertEquals(0, marked)
        m.close()
        assertEquals(120, reports.single().firstMs)
        assertTrue(timer.cancelled)
    }

    @Test
    fun `a read error after the start ends the call and marks the device, once`() {
        val m = media(); run(m, reply("l16"))
        pcm.onError!!("read"); pcm.onError!!("read"); drain()
        assertEquals(listOf("connected", "failed"), ev.log)
        assertEquals(1, marked)
    }

    // ---- the counters ------------------------------------------------------------------------------------------------

    @Test
    fun `the report carries the flow, the peaks, the clipping and the device facts`() {
        val m = media(markWord = "temp"); run(m, reply("l16"))
        clock += 40
        pcm.capture!!(ShortArray(640) { if (it == 0) 32767 else 16384 })
        val loud = Base64.getEncoder().encodeToString(L16Codec.encode(ShortArray(640) { -32768 }))
        ch.listener!!.onFrame(loud); ch.listener!!.onFrame(loud)
        pcm.playout!!()
        clock += 10_000
        m.close()
        val r = reports.single()
        assertEquals("c1", r.callId)
        assertEquals(32767, r.upPeak)
        assertEquals(1, r.upClip)
        assertEquals(32768, r.dnPeak)
        assertEquals(640, r.dnClip)
        assertEquals(10, r.flow.secs)
        assertEquals(1, r.flow.sent)
        assertEquals(2, r.flow.received)
        assertEquals("temp", r.mark)
        assertEquals("on", r.device.aec)
        assertEquals(12, r.device.readMaxMs)
    }

    @Test
    fun `FrameStats - an underrun event is a run of misses after audio started, counted once, and stops at the server's end`() {
        var t = 0L
        val s = FrameStats { t }
        s.onStarted()
        s.onConcealed() // nothing received yet
        s.onReceived(); s.onConcealed() // received but nothing played yet: not an underrun
        assertEquals(0, s.summary().underruns)
        s.onPlayed()
        s.onConcealed(); s.onConcealed(); s.onConcealed() // one run
        assertEquals(1, s.summary().underruns)
        assertEquals(4, s.summary().concealed)
        s.onPlayed(); s.onConcealed() // a second run
        assertEquals(2, s.summary().underruns)
        s.onPlayed()
        s.onEnd()
        s.onConcealed(); s.onPlayed(); s.onConcealed()
        assertEquals(2, s.summary().underruns, "the tail after the server's end is not counted")
    }

    @Test
    fun `FrameStats - p95 gap comes from the fixed histogram and the queue depth range from the samples`() {
        var t = 0L
        val s = FrameStats { t }
        s.onReceived()
        repeat(95) { t += 40; s.onReceived() } // 95 gaps of 40 ms
        repeat(5) { t += 250; s.onReceived() } // 5 gaps of 250 ms
        assertEquals(40L, s.summary().p95GapMs, "the 95th of 100 gaps is still 40")
        t += 250; s.onReceived() // 6 of 101 are slow: the 95th percentile moves up
        assertEquals(300L, s.summary().p95GapMs)
        assertEquals(250L, s.summary().maxGapMs)
        s.onQueue(3); s.onQueue(1); s.onQueue(5)
        assertEquals(1, s.summary().qMin); assertEquals(5, s.summary().qMax)
        assertTrue(FrameStats.GAP_BOUNDS.toList() == FrameStats.GAP_BOUNDS.sorted())
    }

    // ---- the settings, the mark and the path choice --------------------------------------------------------------------

    private class Mem : KvStore {
        val m = HashMap<String, Any>()
        override fun getLong(key: String, default: Long) = m[key] as? Long ?: default
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun getString(key: String, default: String?) = m[key] as? String ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private val h = 3_600_000L
    private var now = 10_000_000_000L
    private val store = Mem()
    private fun cfg(version: String = "0.6.89-alpha") = CallAudioConfig(store, { now }, version)

    @Test
    fun `default is wideband and Compatibility forces pcmu`() {
        assertTrue(cfg().choose().wideband)
        assertEquals("none", cfg().choose().why)
        cfg().setCompatibility(true)
        assertFalse(cfg().choose().wideband)
        assertEquals("compat", cfg().choose().why)
    }

    @Test
    fun `a start failure marks for 24 hours, the second within 7 days makes it permanent until an update or the toggle`() {
        val c = cfg()
        c.recordWidebandFailure()
        assertEquals(MarkState.TEMP, c.mark())
        assertEquals("mark", c.choose().why)
        now += 23 * h
        assertEquals(MarkState.TEMP, c.mark())
        now += 2 * h
        assertEquals(MarkState.NONE, c.mark(), "expired after 24 h")
        assertTrue(c.choose().wideband)
        now += 3 * 24 * h
        c.recordWidebandFailure() // second, inside 7 days of the first
        assertEquals(MarkState.PERMANENT, c.mark())
        now += 30 * 24 * h
        assertEquals(MarkState.PERMANENT, cfg().mark(), "permanent does not expire")
        assertEquals(MarkState.NONE, cfg("0.6.90-alpha").mark(), "an app update clears it")
        c.setCompatibility(true); c.setCompatibility(false)
        assertEquals(MarkState.NONE, cfg().mark(), "toggling Compatibility clears it")
    }

    @Test
    fun `a second failure more than 7 days after the first is a new first`() {
        val c = cfg()
        c.recordWidebandFailure()
        now += 8 * 24 * h
        c.recordWidebandFailure()
        assertEquals(MarkState.TEMP, c.mark())
    }

    @Test
    fun `the path choice resets to Automatic after 24 hours`() {
        val c = cfg()
        assertEquals(PathChoice.AUTO, c.pathChoice())
        c.setPathChoice(PathChoice.FRAMES)
        assertEquals(PathChoice.FRAMES, c.pathChoice())
        now += 23 * h
        assertEquals(PathChoice.FRAMES, c.pathChoice())
        now += 2 * h
        assertEquals(PathChoice.AUTO, c.pathChoice())
        assertEquals(PathChoice.AUTO, cfg().pathChoice(), "and it stays reset")
    }

    @Test
    fun `the path choice applies on the home network only`() {
        use(com.meda.portico.core.ha.LinkPath.DIRECT, PathChoice.WEBRTC).let { assertFalse(it.frames); assertEquals("forced-webrtc", it.why) }
        use(com.meda.portico.core.ha.LinkPath.DIRECT, PathChoice.FRAMES).let { assertTrue(it.frames); assertEquals("forced-frames", it.why) }
        use(com.meda.portico.core.ha.LinkPath.TAILNET, PathChoice.WEBRTC).let { assertTrue(it.frames, "a forced WebRTC cannot leave a tailnet call without audio"); assertEquals("tailnet+choice-ignored", it.why) }
        use(null, PathChoice.WEBRTC).let { assertTrue(it.frames); assertTrue("choice-ignored" in it.why) }
        use(com.meda.portico.core.ha.LinkPath.DIRECT, PathChoice.AUTO).let { assertFalse(it.frames); assertEquals("direct", it.why) }
        assertEquals(AwayPathPolicy.Decision(false, "path-unknown-last-direct"), AwayPathPolicy.decide(null, com.meda.portico.core.ha.LinkPath.DIRECT, PathChoice.AUTO))
    }

    private fun use(path: com.meda.portico.core.ha.LinkPath?, c: PathChoice) = AwayPathPolicy.decide(path, null, c)
}
