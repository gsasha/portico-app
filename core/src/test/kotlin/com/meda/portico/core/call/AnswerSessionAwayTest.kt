package com.meda.portico.core.call

import com.meda.portico.core.call.AnswerSessionTest.Companion.ANSWER
import com.meda.portico.core.call.AnswerSessionTest.Companion.CALL
import com.meda.portico.core.call.AnswerSessionTest.Companion.DEVICE
import com.meda.portico.core.call.AnswerSessionTest.Companion.OFFER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The away media hook: frames when signaling runs through the tailnet, or after the WebRTC audio did not connect. */
class AnswerSessionAwayTest {
    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private val store = InMemoryActiveCallStore()
    private val away = FakeAway()
    private val config = AnswerConfig()

    private fun session(withAway: Boolean = true) = AnswerSession(
        CALL, DEVICE, cmds, media, audio, { true }, sched, store, { 1L }, AnswerHooks.NONE, config,
        away = if (withAway) away else null,
    )

    // ---- tailnet signaling: frames from the start ------------------------------------------------

    @Test
    fun `on the tailnet the call skips WebRTC - claim without an offer, then frames`() {
        away.prefer = true
        val s = session()
        s.begin()
        assertTrue(media.calls.isEmpty(), "no peer connection when frames are preferred")
        assertEquals(Phase.CLAIMING, s.phase)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        assertEquals(Phase.CONNECTING, s.phase)
        assertEquals(CALL to DEVICE, away.paths.single().started)
        assertFalse(cmds.types().contains("portico/webrtc_offer"))
        assertEquals(1, audio.enters)
        away.paths.single().onResult!!(null)
        away.paths.single().listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
    }

    @Test
    fun `a refused channel fails the call and releases the claim`() {
        away.prefer = true
        val s = session()
        s.begin()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        away.paths.single().onResult!!(AnswerErrors.fromCode("away_media_disabled"))
        assertEquals(Phase.ENDED, s.phase)
        assertEquals("away_media_disabled", s.snapshot.failure!!.code)
        assertEquals(1, away.paths.single().closes)
        assertEquals("error", cmds.last("portico/hangup").body["reason"].toString().trim('"'))
        assertEquals(audio.enters, audio.leaves)
        assertEquals(0, sched.pending)
    }

    @Test
    fun `frames that never connect end the call after the connect timeout`() {
        away.prefer = true
        val s = session()
        s.begin()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        away.paths.single().onResult!!(null)
        sched.advance(config.connectTimeoutMs + 1)
        assertEquals(Phase.ENDED, s.phase)
        assertEquals("audio_not_connected", s.snapshot.failure!!.code)
    }

    @Test
    fun `mute, hang up and the end of the call reach the frame path`() {
        away.prefer = true
        val s = session()
        s.begin()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        val p = away.paths.single()
        p.onResult!!(null)
        p.listener.onConnected()
        s.setMuted(true)
        assertEquals(true, p.mutedNow)
        s.hangUp()
        assertEquals(Phase.ENDED, s.phase)
        assertEquals(1, p.closes)
        assertEquals("user_hangup", cmds.last("portico/hangup").body["reason"].toString().trim('"'))
        assertEquals(0, sched.pending)
    }

    @Test
    fun `a frame path that cannot open is a media failure before any claim`() {
        away.prefer = true
        away.failOpen = true
        val s = session()
        s.begin()
        assertEquals(Phase.ENDED, s.phase)
        assertEquals("media", s.snapshot.failure!!.code)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `media failing while in a frame call ends it as network lost`() {
        away.prefer = true
        val s = session()
        s.begin()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        val p = away.paths.single()
        p.onResult!!(null)
        p.listener.onConnected()
        p.listener.onFailed()
        assertEquals(Phase.ENDED, s.phase)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
    }

    // ---- UDP first, then frames -------------------------------------------------------------------

    private fun toConnecting(s: AnswerSession): FakeMediaCall {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        return m
    }

    @Test
    fun `audio that connects in time never touches frames`() {
        val s = session()
        val m = toConnecting(s)
        sched.advance(config.fallbackAfterMs - 1)
        m.listener.onConnected()
        sched.advance(config.fallbackAfterMs * 3)
        assertEquals(Phase.IN_CALL, s.phase)
        assertTrue(away.paths.isEmpty())
        assertEquals(0, m.closes)
    }

    @Test
    fun `audio that has not connected after the short timeout moves the call to frames`() {
        val s = session()
        val m = toConnecting(s)
        sched.advance(config.fallbackAfterMs)
        assertEquals(1, m.closes, "the peer connection is closed")
        assertEquals(CALL to DEVICE, away.paths.single().started)
        assertEquals(Phase.CONNECTING, s.phase)
        assertEquals(1, audio.enters, "the audio route is entered once")
        away.paths.single().onResult!!(null)
        away.paths.single().listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
        // The old peer connection waking up later changes nothing.
        m.listener.onFailed()
        m.listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
        s.hangUp()
        assertEquals(0, sched.pending)
        assertEquals(1, away.paths.single().closes)
        assertEquals(1, m.closes)
    }

    @Test
    fun `a failed peer connection moves to frames at once instead of ending the call`() {
        val s = session()
        val m = toConnecting(s)
        m.listener.onFailed()
        assertEquals(Phase.CONNECTING, s.phase)
        assertEquals(1, away.paths.size)
        assertNull(s.snapshot.failure)
    }

    @Test
    fun `the move happens once`() {
        val s = session()
        val m = toConnecting(s)
        m.listener.onFailed()
        sched.advance(config.fallbackAfterMs * 2)
        m.listener.onFailed()
        assertEquals(1, away.paths.size)
    }

    @Test
    fun `without the hook the old behaviour stands - the call fails when audio does not connect`() {
        val s = session(withAway = false)
        val m = toConnecting(s)
        sched.advance(config.connectTimeoutMs + 1)
        assertEquals(Phase.ENDED, s.phase)
        assertEquals("audio_not_connected", s.snapshot.failure!!.code)
        assertTrue(away.paths.isEmpty())
        assertEquals(1, m.closes)
    }

    @Test
    fun `without the hook a failed peer connection still ends the call`() {
        val s = session(withAway = false)
        val m = toConnecting(s)
        m.listener.onFailed()
        assertEquals(Phase.ENDED, s.phase)
    }
}
