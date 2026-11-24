package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.live.LiveSession.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The state machine of one live tile (DEV-713 S1) against a fake peer connection, a fake socket and a fake clock. */
class LiveSessionTest {

    // ---- the exchange ----

    @Test
    fun `the offer goes out, the answer and the candidates are applied in order, the first frame is playing`() {
        val r = SessionRig(LiveKey("gate", CameraSize.SMALL))
        r.session.start()
        assertEquals(State.STARTING, r.session.state)
        assertEquals(1, r.wire.opens.size)
        assertEquals("gate", r.open.camera)
        assertEquals(CameraSize.SMALL, r.open.size)
        assertEquals(VIDEO_ONLY_SDP, r.open.sdp)
        r.peer.holdAnswer = true
        r.accept("live-7", "sub")
        assertEquals(listOf("live-7" to LiveCandidate.END), r.wire.ices, "the end-of-candidates mark, once the id is known (the offer is non-trickle)")
        r.answer("v=0 ANSWER-7")
        r.candidate(1) // before the answer is applied: waits
        assertEquals(listOf("v=0 ANSWER-7"), r.peer.answers)
        assertEquals(emptyList<LiveCandidate>(), r.peer.candidates)
        r.peer.heldAnswer!!.invoke(true)
        assertEquals(1, r.peer.candidates.size, "the queued candidate is added after the answer")
        r.candidate(2)
        assertEquals(2, r.peer.candidates.size, "later ones at once")
        r.clock.advance(840)
        r.peer.listener.onConnected()
        r.peer.listener.onFirstFrame()
        assertEquals(State.PLAYING, r.session.state)
        assertEquals("sub", r.session.stream)
        assertEquals(listOf("start gate|small stream=sub ttff=840"), r.log.lines)
        assertEquals(emptyList<String>(), r.wire.closes)
    }

    @Test
    fun `the session id comes from the event alone as well, and a repeat changes nothing`() {
        val r = SessionRig()
        r.session.start()
        r.open.sink.onEvent(LiveCommands.Event.Session("live-2", "main"))
        r.open.sink.onReply(LiveCommands.Reply.Opened("live-2", "main"))
        assertEquals(1, r.wire.ices.size)
        r.session.stop(StopWhy.HIDDEN)
        assertEquals(listOf("live-2"), r.wire.closes)
    }

    @Test
    fun `a server that sends a size the app never asked for is no matter, and a bad answer fails the attempt`() {
        val r = SessionRig()
        r.session.start()
        r.accept()
        r.peer.answerOk = false
        r.answer()
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
    }

    // ---- the video-only rule ----

    @Test
    fun `a peer whose offer has an audio line fails without a command`() {
        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val s = LiveSession(
            LiveKey("gate", CameraSize.BIG),
            { l -> FakePeer(clock, l).also { it.offerSdp = VIDEO_ONLY_SDP + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" } },
            wire, clock,
        )
        s.start()
        assertEquals(0, wire.opens.size, "nothing is sent")
        assertEquals(State.FAILED, s.state)
        clock.advance(60_000)
        assertEquals(0, wire.opens.size, "and it is not retried: the same peer would offer the same")
    }

    // ---- the 6 s deadline, the notice classes ----

    @Test
    fun `no first frame in 6 seconds falls back with the connect notice, a connected peer with the decoder notice`() {
        val r = SessionRig()
        r.session.start()
        r.accept()
        r.clock.advance(5_999)
        assertEquals(State.STARTING, r.session.state)
        r.clock.advance(1)
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.CONNECT, r.session.why)
        assertEquals(listOf("fail gate|big connect retry=0 next=true"), r.log.lines)

        val d = SessionRig()
        d.session.start()
        d.accept()
        d.peer.listener.onConnected()
        d.clock.advance(6_000)
        assertEquals(LiveWhy.DECODER, d.session.why, "the connection was up and no picture came")
    }

    @Test
    fun `the deadline is cancelled by the first frame`() {
        val r = SessionRig()
        r.play()
        r.clock.advance(60_000)
        assertEquals(State.PLAYING, r.session.state)
        assertEquals(1, r.wire.opens.size)
    }

    // ---- retries: 2 s, 5 s, 15 s, then the still until a tap ----

    @Test
    fun `an error event retries after 2 s, 5 s and 15 s and then stays failed until start is called again`() {
        val r = SessionRig()
        r.session.start()
        r.accept("live-1")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        assertEquals(State.RETRYING, r.session.state)
        assertEquals(LiveWhy.NO_LIVE, r.session.why)
        r.clock.advance(1_999)
        assertEquals(1, r.wire.opens.size)
        r.clock.advance(1)
        assertEquals(2, r.wire.opens.size, "after 2 s")
        r.accept("live-2")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        r.clock.advance(4_999)
        assertEquals(2, r.wire.opens.size)
        r.clock.advance(1)
        assertEquals(3, r.wire.opens.size, "5 s later")
        r.accept("live-3")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        r.clock.advance(14_999)
        assertEquals(3, r.wire.opens.size)
        r.clock.advance(1)
        assertEquals(4, r.wire.opens.size, "15 s later")
        r.accept("live-4")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        assertEquals(State.FAILED, r.session.state, "no fourth retry")
        r.clock.advance(600_000)
        assertEquals(4, r.wire.opens.size, "stays on the still")
        assertEquals(listOf("live-1", "live-2", "live-3", "live-4"), r.wire.closes, "each failed attempt closed its session")
        r.session.start() // a tap on the chip
        assertEquals(5, r.wire.opens.size)
        assertNull(r.session.why, "asking again clears the old notice")
    }

    @Test
    fun `a good run starts the count again`() {
        val r = SessionRig()
        r.session.start()
        r.accept("live-1")
        r.open.sink.onEvent(LiveCommands.Event.Error("x"))
        r.clock.advance(2_000)
        r.accept("live-2")
        r.answer()
        r.peer.listener.onFirstFrame()
        assertEquals(State.PLAYING, r.session.state)
        r.peer.listener.onFailed()
        assertEquals(State.RETRYING, r.session.state)
        r.clock.advance(1_999)
        assertEquals(2, r.wire.opens.size)
        r.clock.advance(1)
        assertEquals(3, r.wire.opens.size, "the first retry delay again (2 s), not the 5 s of a second failure")
    }

    @Test
    fun `an error answer to the command is final`() {
        for ((code, why) in listOf("live_unavailable" to LiveWhy.NO_LIVE, "unavailable" to LiveWhy.NO_LIVE, "too_many_live" to LiveWhy.TOO_MANY, "unknown_command" to LiveWhy.SERVER, "unauthorized" to LiveWhy.SERVER, "unknown_camera" to LiveWhy.SERVER, "invalid_format" to LiveWhy.SERVER)) {
            val r = SessionRig()
            r.session.start()
            r.open.sink.onReply(LiveCommands.Reply.Refused(code))
            assertEquals(State.FAILED, r.session.state, code)
            assertEquals(why, r.session.why, code)
            r.clock.advance(120_000)
            assertEquals(1, r.wire.opens.size, "$code is not retried")
            assertEquals(emptyList<String>(), r.wire.closes, "$code: it never had a session")
            assertEquals(1, r.peer.closed, "the peer is closed")
        }
    }

    @Test
    fun `a lost socket, a command that could not be sent and a failed offer retry`() {
        val lost = SessionRig()
        lost.play()
        lost.open.sink.onLost()
        assertEquals(State.RETRYING, lost.session.state)
        assertEquals(LiveWhy.CONNECT, lost.session.why)

        val notSent = SessionRig()
        notSent.session.start()
        notSent.open.sink.onNotSent()
        assertEquals(State.RETRYING, notSent.session.state)

        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val noOffer = LiveSession(LiveKey("g", CameraSize.BIG), { l -> FakePeer(clock, l).also { it.offerSdp = null } }, wire, clock)
        noOffer.start()
        assertEquals(State.RETRYING, noOffer.state)
        assertEquals(LiveWhy.CONNECT, noOffer.why)
        assertEquals(0, wire.opens.size)
    }

    @Test
    fun `no decoder or no peer connection is the decoder notice and is not retried`() {
        val r = SessionRig()
        r.failCreate = true
        r.session.start()
        assertEquals(State.FAILED, r.session.state)
        assertEquals(LiveWhy.DECODER, r.session.why)
        assertEquals(0, r.wire.opens.size)
        r.clock.advance(120_000)
        assertEquals(0, r.peers.size)
    }

    @Test
    fun `an ice disconnect that recovers in time is nothing, one that does not is a failure after 5 s`() {
        val r = SessionRig()
        r.play()
        r.peer.listener.onDisconnected()
        r.clock.advance(4_000)
        r.peer.listener.onConnected()
        r.clock.advance(60_000)
        assertEquals(State.PLAYING, r.session.state)
        r.peer.listener.onDisconnected()
        r.clock.advance(4_999)
        assertEquals(State.PLAYING, r.session.state)
        r.clock.advance(1)
        assertEquals(State.RETRYING, r.session.state)
    }

    // ---- teardown ----

    @Test
    fun `stop closes the session by name exactly once, however often it is called`() {
        val r = SessionRig()
        r.play("live-9")
        r.clock.advance(12_000)
        r.session.stop(StopWhy.HIDDEN)
        r.session.stop(StopWhy.HIDDEN)
        r.session.stop(StopWhy.BACKGROUND)
        assertEquals(listOf("live-9"), r.wire.closes)
        assertEquals(State.STOPPED, r.session.state)
        assertEquals(1, r.peer.closed)
        assertTrue(r.open.cancelled, "the stream is dropped")
        assertEquals("stop gate|big hidden played=12000", r.log.lines.last())
        assertEquals(2, r.log.lines.size, "start and one stop")
    }

    @Test
    fun `a failure closes the session it had, and a stop after it adds no second close`() {
        val r = SessionRig()
        r.session.start()
        r.accept("live-3")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        assertEquals(listOf("live-3"), r.wire.closes)
        r.session.stop(StopWhy.CLOSED)
        assertEquals(listOf("live-3"), r.wire.closes)
    }

    @Test
    fun `a session that opens after the tile let go is closed by name when its id arrives`() {
        val r = SessionRig()
        r.session.start()
        val late = r.open
        r.session.stop(StopWhy.HIDDEN) // before the result
        assertEquals(emptyList<String>(), r.wire.closes, "no id yet")
        assertTrue(late.cancelled)
        late.sink.onReply(LiveCommands.Reply.Opened("live-5", "main"))
        late.sink.onEvent(LiveCommands.Event.Session("live-5", "main"))
        assertEquals(listOf("live-5"), r.wire.closes, "closed once, by the reply (the event repeats the id)")
        assertEquals(emptyList<Pair<String, LiveCandidate>>(), r.wire.ices, "and no candidate is sent for it")
    }

    @Test
    fun `events of an attempt that is over change nothing`() {
        val r = SessionRig()
        r.session.start()
        val first = r.open
        r.accept("live-1")
        r.open.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        r.clock.advance(2_000)
        assertEquals(2, r.wire.opens.size)
        val answersBefore = r.peers.sumOf { it.answers.size }
        first.sink.onEvent(LiveCommands.Event.Answer("old"))
        first.sink.onEvent(LiveCommands.Event.Error("live_unavailable"))
        first.sink.onLost()
        assertEquals(answersBefore, r.peers.sumOf { it.answers.size })
        assertEquals(State.STARTING, r.session.state, "the new attempt is untouched")
        assertEquals(2, r.wire.opens.size)
    }

    @Test
    fun `stopping while waiting to retry cancels the retry and sends nothing`() {
        val r = SessionRig()
        r.session.start()
        r.accept("live-1")
        r.open.sink.onEvent(LiveCommands.Event.Error("x"))
        assertEquals(State.RETRYING, r.session.state)
        r.session.stop(StopWhy.BACKGROUND)
        r.clock.advance(120_000)
        assertEquals(1, r.wire.opens.size)
        assertEquals(0, r.clock.pending())
        assertEquals(listOf("live-1"), r.wire.closes)
    }

    @Test
    fun `start is a no-op on a running, retrying or stopped session`() {
        val r = SessionRig()
        r.play()
        r.session.start()
        assertEquals(1, r.wire.opens.size)
        r.session.stop(StopWhy.HIDDEN)
        r.session.start()
        assertEquals(1, r.wire.opens.size, "a stopped session is not reused: the manager makes a new one")
    }

    // ---- the frame rate ----

    @Test
    fun `the frame rate label is -- until two seconds are watched, then N fps, then 0 fps when stalled`() {
        val r = SessionRig()
        r.play()
        assertEquals("--", r.session.fpsLabel)
        r.clock.advance(1_000)
        assertEquals("--", r.session.fpsLabel, "the first reading only sets the baseline")
        r.peer.frames = 24
        r.clock.advance(1_000)
        assertEquals("--", r.session.fpsLabel, "one second watched: not enough")
        r.peer.frames = 48
        r.clock.advance(1_000)
        assertEquals("24 fps", r.session.fpsLabel)
        r.peer.frames = 72
        r.clock.advance(1_000)
        assertEquals("24 fps", r.session.fpsLabel)
        r.clock.advance(1_000) // one silent second
        r.clock.advance(1_000)
        assertEquals("0 fps", r.session.fpsLabel, "no frame for 2 s")
    }
}
