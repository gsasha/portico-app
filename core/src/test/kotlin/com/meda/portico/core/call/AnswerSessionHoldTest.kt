package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One session's hold (design 15.13): what [AnswerSession.setHeld] does to the media, the timers and the snapshot. */
class AnswerSessionHoldTest {

    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private val store = InMemoryActiveCallStore()
    private val snaps = mutableListOf<AnswerSnapshot>()
    private val config = AnswerConfig()
    private var away: FakeAway? = null

    private val s by lazy {
        AnswerSession(CALL, DEVICE, cmds, media, audio, { true }, sched, store, { 1_000_000L }, AnswerHooks.NONE, config, onChange = { snaps += it }, away = away)
    }

    private fun connected(): FakeMediaCall {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!("v=0 offer")
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to "v=0 answer"))
        m.answerCb!!(true)
        m.listener.onConnected()
        return m
    }

    @Test
    fun `hold is refused before the call is connected, and changes nothing`() {
        assertFalse(s.setHeld(true), "IDLE")
        s.begin()
        assertFalse(s.setHeld(true), "PREPARING")
        val m = media.calls.single()
        assertTrue(m.holds.isEmpty())
        m.offerCb!!("v=0")
        assertFalse(s.setHeld(true), "CLAIMING")
        assertFalse(s.snapshot.held)
        assertEquals(1, sched.pending, "only the claim's step timer")
    }

    @Test
    fun `hold keeps every leg up - the microphone and speaker go off, nothing is closed or sent`() {
        val m = connected()
        val sent = cmds.sent.size
        assertTrue(s.setHeld(true))
        assertTrue(s.snapshot.held)
        assertEquals(Phase.IN_CALL, s.phase, "a held session is IN_CALL, not a new phase")
        assertEquals(listOf(true), m.holds)
        assertFalse(m.micOpen)
        assertFalse(m.speakerOn)
        assertEquals(0, m.closes)
        assertEquals(0, audio.leaves, "the route is not left")
        assertEquals(sent, cmds.sent.size, "no command for a hold")
    }

    @Test
    fun `hold and resume are idempotent`() {
        val m = connected()
        assertTrue(s.setHeld(false), "already live")
        assertTrue(m.holds.isEmpty())
        assertTrue(s.setHeld(true))
        val pending = sched.pending
        assertTrue(s.setHeld(true))
        assertEquals(listOf(true), m.holds, "the media is told once")
        assertEquals(pending, sched.pending, "no second timer")
    }

    @Test
    fun `resume brings the call back and puts the person's mute back`() {
        val m = connected()
        s.setMuted(true)
        s.setHeld(true)
        m.resumeForgetsMute = true
        assertTrue(s.setHeld(false))
        assertTrue(m.micOff)
        assertTrue(s.snapshot.muted)
        assertFalse(s.snapshot.held)
        assertFalse(m.micOpen)
        assertEquals(listOf(true, false), m.holds)
    }

    @Test
    fun `mute presses while held are ignored`() {
        val m = connected()
        s.setHeld(true)
        s.setMuted(true)
        assertFalse(m.micOff)
        assertFalse(s.snapshot.muted)
    }

    @Test
    fun `a media that refuses to hold leaves the session exactly as it was`() {
        val m = connected()
        m.failHold = true
        val before = s.snapshot
        val pending = sched.pending
        assertFalse(s.setHeld(true))
        assertEquals(before, s.snapshot)
        assertEquals(pending, sched.pending)
        assertTrue(m.micOpen)
        m.failHold = false
        assertTrue(s.setHeld(true))
        m.failHold = true
        assertFalse(s.setHeld(false))
        assertTrue(s.snapshot.held, "still held")
    }

    @Test
    fun `the hold limit ends the call, releases the claim, and keeps the held flag so the end is quiet`() {
        val m = connected()
        s.setHeld(true)
        sched.advance(119_999)
        assertEquals(Phase.IN_CALL, s.phase)
        sched.advance(1)
        assertEquals(Phase.ENDED, s.phase)
        assertEquals(EndReason.HOLD_TIMEOUT, s.snapshot.endReason)
        assertTrue(s.snapshot.held)
        assertEquals(AnswerFailure.Kind.HELD_TOO_LONG, s.snapshot.failure!!.kind)
        assertEquals(1, m.closes)
        assertEquals("timeout", (cmds.last("portico/hangup").body["reason"] as JsonPrimitive).content)
        assertEquals(1, audio.leaves)
        assertEquals(0, sched.pending)
    }

    @Test
    fun `no timer is left armed after any end of a held call`() {
        connected()
        s.setHeld(true)
        s.hangUp()
        cmds.last("portico/hangup").ok()
        assertEquals(0, sched.pending)
    }

    @Test
    fun `a resume cancels the limit`() {
        connected()
        val base = sched.pending
        s.setHeld(true)
        assertEquals(base + 1, sched.pending, "the limit is armed")
        sched.advance(100_000)
        s.setHeld(false)
        assertEquals(base, sched.pending, "and disarmed by the resume")
        sched.advance(100_000)
        assertEquals(Phase.IN_CALL, s.phase)
    }

    @Test
    fun `a held call that loses its link has its own grace`() {
        connected()
        s.setHeld(true)
        s.onLinkLost()
        sched.advance(7_999)
        assertEquals(Phase.IN_CALL, s.phase)
        sched.advance(1)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertTrue(s.snapshot.held)
    }

    @Test
    fun `a held call that the door ends ends held`() {
        connected()
        s.setHeld(true)
        s.onCallEvent(CallEvent.Ended("d", "r", CALL, inferred = false))
        assertEquals(EndReason.REMOTE_HANGUP, s.snapshot.endReason)
        assertTrue(s.snapshot.held)
    }

    @Test
    fun `a media reconnect while held does not resume it`() {
        val m = connected()
        s.setHeld(true)
        m.listener.onDisconnected()
        m.listener.onConnected()
        assertTrue(s.snapshot.held)
        assertEquals(listOf(true), m.holds)
    }

    @Test
    fun `hold on the frames path goes to the frame path, which keeps the silent uplink`() {
        away = FakeAway(prefer = true)
        s.begin()
        val fp = away!!.paths.single()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        fp.listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
        assertTrue(s.setHeld(true))
        assertEquals(listOf(true), fp.holds)
        assertEquals(0, fp.closes)
        assertTrue(s.setHeld(false))
        assertEquals(listOf(true, false), fp.holds)
        fp.failHold = true
        assertTrue(s.setHeld(true).not(), "the frame path says no: refused")
    }

    @Test
    fun `a resume with the mute on re-mutes the frame path too`() {
        away = FakeAway(prefer = true)
        s.begin()
        val fp = away!!.paths.single()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        fp.listener.onConnected()
        s.setMuted(true)
        s.setHeld(true)
        fp.mutedNow = null
        s.setHeld(false)
        assertEquals(true, fp.mutedNow)
    }

    @Test
    fun `an ended session cannot be held or resumed`() {
        connected()
        s.hangUp()
        assertFalse(s.setHeld(true))
        assertFalse(s.setHeld(false))
    }

    companion object {
        const val CALL = "gso-0123456789"
        const val DEVICE = "gate-dev"
    }
}
