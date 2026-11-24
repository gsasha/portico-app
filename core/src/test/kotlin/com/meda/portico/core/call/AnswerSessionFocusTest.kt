package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One session silenced by the audio focus (M7 H2): the media state of a hold, but not a hold. */
class AnswerSessionFocusTest {

    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val sched = FakeScheduler()
    private val snaps = mutableListOf<AnswerSnapshot>()
    private var away: FakeAway? = null

    private val s by lazy {
        AnswerSession("focus-call-1", "dev-1", cmds, media, FakeAudio(), { true }, sched, InMemoryActiveCallStore(), { 1_000_000L }, AnswerHooks.NONE, AnswerConfig(), onChange = { snaps += it }, away = away)
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
    fun `losing the audio silences the media without holding the call`() {
        val m = connected()
        val pending = sched.pending
        assertTrue(s.setAudioSuspended(true))
        assertTrue(m.heldNow, "microphone and speaker off")
        assertFalse(s.snapshot.held, "not a hold: no held flag, so nothing treats it as one")
        assertEquals(pending, sched.pending, "and no hold limit starts")
        assertTrue(s.audioSuspendedNow)
        assertEquals(0, m.closes)
    }

    @Test
    fun `getting the audio back restores the media`() {
        val m = connected()
        s.setAudioSuspended(true)
        assertTrue(s.setAudioSuspended(false))
        assertFalse(m.heldNow)
        assertFalse(s.audioSuspendedNow)
        assertEquals(listOf(true, false), m.holds)
    }

    @Test
    fun `the person's mute is still in force after the audio comes back`() {
        val m = connected()
        s.setMuted(true)
        s.setAudioSuspended(true)
        m.resumeForgetsMute = true // a media that forgets on resume: the session puts the mute back
        s.setAudioSuspended(false)
        assertTrue(m.micOff)
        assertFalse(m.micOpen)
    }

    @Test
    fun `asking twice touches the media once`() {
        val m = connected()
        s.setAudioSuspended(true)
        s.setAudioSuspended(true)
        assertEquals(listOf(true), m.holds)
        s.setAudioSuspended(false)
        s.setAudioSuspended(false)
        assertEquals(listOf(true, false), m.holds)
    }

    @Test
    fun `before the call is connected there is nothing to silence`() {
        assertFalse(s.setAudioSuspended(true), "IDLE")
        s.begin()
        assertFalse(s.setAudioSuspended(true), "PREPARING")
        assertTrue(media.calls.single().holds.isEmpty())
    }

    @Test
    fun `a held call is already silent and is left alone`() {
        val m = connected()
        assertTrue(s.setHeld(true))
        assertFalse(s.setAudioSuspended(true))
        assertEquals(listOf(true), m.holds)
        // and the regain does not wake a held call
        assertTrue(s.setAudioSuspended(false))
        assertTrue(m.heldNow)
    }

    @Test
    fun `a hold during the interruption wins, and the resume is the person's press`() {
        val m = connected()
        s.setAudioSuspended(true)
        assertTrue(s.setHeld(true))
        assertFalse(s.audioSuspendedNow, "the hold took over")
        assertTrue(s.setAudioSuspended(false))
        assertTrue(m.heldNow, "regaining the audio does not resume a held call")
        assertTrue(s.setHeld(false))
        assertFalse(m.heldNow)
    }

    @Test
    fun `a media that refuses to be silenced leaves the call as it was`() {
        val m = connected()
        m.failHold = true
        assertFalse(s.setAudioSuspended(true))
        assertFalse(s.audioSuspendedNow)
        assertTrue(m.micOpen)
    }

    @Test
    fun `the frames path is silenced and restored too`() {
        away = FakeAway(prefer = true)
        s.begin()
        val path = away!!.paths.single()
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        path.onResult!!(null)
        path.listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
        assertTrue(s.setAudioSuspended(true))
        assertTrue(path.heldNow)
        assertTrue(s.setAudioSuspended(false))
        assertFalse(path.heldNow)
    }

    @Test
    fun `ending the call clears the silencing`() {
        connected()
        s.setAudioSuspended(true)
        s.hangUp()
        assertFalse(s.audioSuspendedNow)
    }
}
