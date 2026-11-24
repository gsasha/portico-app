package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.live.LiveSession.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A fake go2rtc pull: records what it was asked and lets a test play the data and control events. */
class FakeRtspSource(val url: String, val listener: RtspListener) : RtspSource {
    var stops = 0
    override fun stop() {
        stops++
    }

    fun format() = listener.onFormat(byteArrayOf(0, 0, 0, 1, 0x67), byteArrayOf(0, 0, 0, 1, 0x68), 1280, 720)
    fun idr(pts: Long = 0) = listener.onNal(byteArrayOf(0, 0, 0, 1, 0x65, 1), pts, true)
    fun slice(pts: Long = 0) = listener.onNal(byteArrayOf(0, 0, 0, 1, 0x41, 1), pts, false)
}

class FakeRtspDecoder(val listener: RtspDecoderListener) : RtspDecoder {
    val formats = ArrayList<IntArray>()
    val nals = ArrayList<Pair<Boolean, Long>>()
    var frames = 0L
    var releases = 0
    override fun format(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        formats += intArrayOf(width, height)
    }

    override fun nal(nal: ByteArray, ptsUs: Long, key: Boolean) {
        nals += key to ptsUs
    }

    override fun frameCount() = frames
    override fun release() {
        releases++
    }
}

class RtspRig(
    val key: LiveKey = LiveKey("gate", CameraSize.BIG, "gate_intercom"),
    val url: String = "rtsp://192.168.1.6:8554/gate_intercom",
    config: RtspLiveSession.Config = RtspLiveSession.Config(),
) {
    val clock = FakeLiveClock()
    val log = FakeLiveLog()
    val sources = ArrayList<FakeRtspSource>()
    val decoders = ArrayList<FakeRtspDecoder>()
    var sourceThrows = false
    var decoderThrows = false
    var changes = 0
    val session = RtspLiveSession(
        key, url,
        { u, l ->
            if (sourceThrows) throw IllegalStateException("no client")
            FakeRtspSource(u, l).also { sources += it }
        },
        { _, l ->
            if (decoderThrows) throw IllegalStateException("no decoder")
            FakeRtspDecoder(l).also { decoders += it }
        },
        clock, log, config, onChange = { changes++ },
    )
    val source get() = sources.last()
    val decoder get() = decoders.last()

    /** The tile is fed: parameter sets, an IDR, and the decoder reports its first frame. */
    fun play() {
        source.format()
        source.idr()
        decoder.frames = 1
        decoder.listener.onFirstFrame()
    }
}

class RtspLiveSessionTest {
    @Test
    fun `the url goes to the source and nothing is decoded before the parameter sets and an IDR`() {
        val r = RtspRig()
        r.session.start()
        assertEquals(State.STARTING, r.session.state)
        assertEquals("rtsp://192.168.1.6:8554/gate_intercom", r.source.url)
        r.source.slice(1) // no parameter sets yet, and not an IDR
        r.source.idr(2) // an IDR, but no parameter sets yet
        assertEquals(emptyList<Pair<Boolean, Long>>(), r.decoder.nals)
        r.source.format()
        r.source.slice(3) // the gate is still shut: not an IDR
        r.source.slice(4)
        assertEquals(emptyList<Pair<Boolean, Long>>(), r.decoder.nals, "first IDR required")
        r.source.idr(5)
        r.source.slice(6)
        assertEquals(listOf(true to 5L, false to 6L), r.decoder.nals)
        assertEquals(1, r.decoder.formats.size)
        assertEquals(1280, r.decoder.formats.single()[0])
    }

    @Test
    fun `the first decoded frame is PLAYING and writes the start line with the stream name`() {
        val r = RtspRig()
        r.session.start()
        r.clock.advance(1_500)
        r.play()
        assertEquals(State.PLAYING, r.session.state)
        assertEquals(listOf("start gate|big|rtsp:gate_intercom stream=gate_intercom ttff=1500"), r.log.lines)
        assertEquals("--", r.session.fpsLabel)
        // 50 frames over two seconds read 25 fps
        r.clock.advance(1_000)
        r.decoder.frames = 26
        r.clock.advance(1_000)
        r.decoder.frames = 51
        r.clock.advance(1_000)
        assertEquals("25 fps", r.session.fpsLabel)
    }

    @Test
    fun `a first frame after the session left STARTING changes nothing`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        r.decoder.listener.onFirstFrame()
        assertEquals(1, r.log.lines.size)
    }

    @Test
    fun `no first frame in ten seconds is a failure - connect when nothing came, decoder when units came`() {
        val a = RtspRig()
        a.session.start()
        a.clock.advance(9_999)
        assertEquals(State.STARTING, a.session.state)
        a.clock.advance(1)
        assertEquals(State.RETRYING, a.session.state)
        assertEquals(LiveWhy.CONNECT, a.session.why)
        val b = RtspRig()
        b.session.start()
        b.source.format()
        b.source.idr()
        for (i in 1..10) { // the video keeps coming but no picture is ever shown
            b.clock.advance(1_000)
            if (i < 10) b.source.slice(i.toLong())
        }
        assertEquals(State.RETRYING, b.session.state)
        assertEquals(LiveWhy.DECODER, b.session.why)
        assertEquals(RtspLiveSession.FIRST_FRAME_MS, 10_000L)
    }

    @Test
    fun `the watchdog - no unit for stallMs after some came ends the attempt and reconnects, waiting for an IDR again`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        r.clock.advance(1_000)
        r.source.slice(1)
        r.clock.advance(5_000) // 5 s of silence: still fine (the Go client's own watchdog is 5 s)
        assertEquals(State.PLAYING, r.session.state)
        r.clock.advance(1_000) // 6 s
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
        assertEquals(1, r.source.stops, "the stalled connection is closed")
        assertEquals(1, r.decoder.releases)
        assertEquals(1, r.sources.size)
        r.clock.advance(2_000) // the first backoff
        assertEquals(2, r.sources.size, "reconnected")
        assertEquals(State.STARTING, r.session.state)
        r.source.format()
        r.source.slice(9) // the new attempt's gate is shut
        assertEquals(emptyList<Pair<Boolean, Long>>(), r.decoder.nals)
        r.source.idr(10)
        assertEquals(listOf(true to 10L), r.decoder.nals)
        r.decoder.listener.onFirstFrame()
        assertEquals(State.PLAYING, r.session.state)
        assertEquals(listOf("start", "fail", "start"), r.log.lines.map { it.substringBefore(' ') })
        assertTrue(r.log.lines[1].contains("connect retry=0 next=true"), r.log.lines[1])
    }

    @Test
    fun `a stall before any unit is not the watchdog's - the deadline owns a slow start`() {
        val r = RtspRig()
        r.session.start()
        r.clock.advance(8_000) // slow dial: no unit yet
        assertEquals(State.STARTING, r.session.state)
    }

    @Test
    fun `backoff 2 s, 5 s, 15 s and then it gives up until the tile is shown again`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        for ((i, wait) in listOf(2_000L, 5_000L, 15_000L).withIndex()) {
            r.source.listener.onEnded("lost")
            assertEquals(State.RETRYING, r.session.state, "failure ${i + 1}")
            r.clock.advance(wait - 1)
            assertEquals(i + 1, r.sources.size, "not before $wait ms")
            r.clock.advance(1)
            assertEquals(i + 2, r.sources.size, "after $wait ms")
        }
        r.source.listener.onEnded("lost") // the 4th failure
        assertEquals(State.FAILED, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
        r.clock.advance(60_000)
        assertEquals(4, r.sources.size, "gave up: nothing more is opened")
        assertEquals(listOf(true, true, true, false), r.log.lines.filter { it.startsWith("fail") }.map { it.endsWith("next=true") })
        // a tap on the chip asks again from the start
        r.session.start()
        assertEquals(5, r.sources.size)
        assertEquals(State.STARTING, r.session.state)
    }

    @Test
    fun `a good run starts the count again`() {
        val r = RtspRig()
        r.session.start()
        r.source.listener.onEnded("dial")
        r.clock.advance(2_000)
        r.source.listener.onEnded("dial")
        r.clock.advance(5_000)
        r.play() // third attempt plays
        r.source.listener.onEnded("lost")
        assertTrue(r.log.lines.last().contains("retry=0 next=true"), "the count restarted: ${r.log.lines.last()}")
        r.clock.advance(2_000)
        assertEquals(4, r.sources.size)
    }

    @Test
    fun `stop closes the connection once and releases the decoder once, and says why`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        r.clock.advance(3_000)
        r.session.stop(StopWhy.HIDDEN)
        r.session.stop(StopWhy.BACKGROUND)
        assertEquals(State.STOPPED, r.session.state)
        assertEquals(1, r.source.stops)
        assertEquals(1, r.decoder.releases)
        assertEquals("stop gate|big|rtsp:gate_intercom hidden played=3000", r.log.lines.last())
        assertEquals(2, r.log.lines.size)
        assertEquals(0, r.clock.pending(), "no timer outlives the session")
    }

    @Test
    fun `stop while retrying cancels the pending reconnect`() {
        val r = RtspRig()
        r.session.start()
        r.source.listener.onEnded("lost")
        assertEquals(State.RETRYING, r.session.state)
        r.session.stop(StopWhy.CLOSED)
        r.clock.advance(60_000)
        assertEquals(1, r.sources.size)
        assertEquals(1, r.source.stops, "closed once, at the failure; not again")
        assertEquals(1, r.decoder.releases)
        assertEquals(0, r.clock.pending())
    }

    @Test
    fun `units and events from a torn-down attempt change nothing`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        val old = r.source
        val oldDecoder = r.decoder
        r.session.stop(StopWhy.PAUSED)
        val before = oldDecoder.nals.size
        old.idr(7)
        old.format()
        old.listener.onEnded("lost")
        oldDecoder.listener.onFailed()
        assertEquals(before, oldDecoder.nals.size)
        assertEquals(State.STOPPED, r.session.state)
        assertEquals(2, r.log.lines.size)
    }

    @Test
    fun `a decoder that cannot be made is final - the still with the decoder notice, no retry`() {
        val r = RtspRig()
        r.decoderThrows = true
        r.session.start()
        assertEquals(State.FAILED, r.session.state)
        assertEquals(LiveWhy.DECODER, r.session.why)
        assertEquals(0, r.sources.size, "no connection is opened for a video that cannot be shown")
        r.clock.advance(60_000)
        assertEquals(0, r.sources.size)
        assertEquals(listOf("fail gate|big|rtsp:gate_intercom decoder retry=0 next=false"), r.log.lines)
        assertEquals(0, r.clock.pending())
    }

    @Test
    fun `a decoder that breaks while running retries, and the old decoder is released`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        r.decoder.listener.onFailed()
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.DECODER, r.session.why)
        assertEquals(1, r.decoder.releases)
        assertEquals(1, r.source.stops)
        r.clock.advance(2_000)
        assertEquals(2, r.decoders.size, "a new decoder for the new attempt")
    }

    @Test
    fun `a source that cannot be opened retries as a connect failure`() {
        val r = RtspRig()
        r.sourceThrows = true
        r.session.start()
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
        assertEquals(1, r.decoders.single().releases, "the decoder made for it is released")
    }

    @Test
    fun `a source that ends inside open() is closed and retried`() {
        val clock = FakeLiveClock()
        var made: FakeRtspSource? = null
        val session = RtspLiveSession(
            LiveKey("g", CameraSize.SMALL, "x"), "rtsp://h/x",
            { u, l -> l.onEnded("dial"); FakeRtspSource(u, l).also { made = it } },
            { _, l -> FakeRtspDecoder(l) },
            clock,
        )
        session.start()
        assertEquals(State.RETRYING, session.state)
        assertEquals(1, made!!.stops)
    }

    @Test
    fun `a Go-side end during playing is a connect failure with a retry`() {
        val r = RtspRig()
        r.session.start()
        r.play()
        r.source.listener.onEnded("stalled")
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
    }

    @Test
    fun `the journal lines carry the stream name and never the address`() {
        val r = RtspRig(key = LiveKey("gate", CameraSize.SMALL, "gate_intercom_sub"), url = "rtsp://192.168.1.6:8554/gate_intercom_sub")
        r.session.start()
        r.play()
        r.source.listener.onEnded("lost")
        r.session.stop(StopWhy.CLOSED)
        assertTrue(r.log.lines.isNotEmpty())
        for (l in r.log.lines) {
            assertFalse(l.contains("192.168.1.6") || l.contains("8554") || l.contains("rtsp://") || l.contains("@"), l)
            assertTrue(l.contains("gate_intercom_sub"), l)
        }
    }

    @Test
    fun `the session never exposes the address`() {
        val r = RtspRig()
        assertFalse(r.session.toString().contains("192.168.1.6"))
        assertNotNull(r.session.key.stream)
    }
}
