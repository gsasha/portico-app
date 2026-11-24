package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The order and the memory of mute and hold for one call (M7 H2), against a recording sink standing in for libwebrtc. */
class AudioGateTest {

    private class Sink : AudioGateSink {
        val log = mutableListOf<String>()
        var failOn: String? = null
        private fun step(s: String) {
            if (failOn == s) throw IllegalStateException("boom $s")
            log += s
        }

        override fun track(enabled: Boolean) = step("track=$enabled")
        override fun microphone(mute: Boolean) = step("mic.mute=$mute")
        override fun speaker(mute: Boolean) = step("spk.mute=$mute")
    }

    private val sink = Sink()
    private val gate = AudioGate(sink)

    @Test
    fun `hold turns the track, the microphone and then the speaker off`() {
        gate.setHeld(true)
        assertEquals(listOf("track=false", "mic.mute=true", "spk.mute=true"), sink.log)
        assertTrue(gate.held)
    }

    @Test
    fun `resume turns the speaker on first, then the microphone, then the track`() {
        gate.setHeld(true)
        sink.log.clear()
        gate.setHeld(false)
        assertEquals(listOf("spk.mute=false", "mic.mute=false", "track=true"), sink.log)
        assertFalse(gate.held)
    }

    @Test
    fun `a mute the person set survives hold and resume`() {
        gate.setMuted(true)
        gate.setHeld(true)
        sink.log.clear()
        gate.setHeld(false)
        assertEquals(listOf("spk.mute=false", "mic.mute=false", "track=false"), sink.log, "the track stays off: the person muted it")
        assertTrue(gate.muted)
    }

    @Test
    fun `a mute asked while held is remembered and not applied until the resume`() {
        gate.setHeld(true)
        sink.log.clear()
        gate.setMuted(true)
        assertTrue(sink.log.isEmpty(), "no switch is touched while the call is held")
        gate.setHeld(false)
        assertEquals("track=false", sink.log.last())
        gate.setMuted(false)
        assertEquals("track=true", sink.log.last())
    }

    @Test
    fun `mute alone only touches the track`() {
        gate.setMuted(true)
        gate.setMuted(false)
        assertEquals(listOf("track=false", "track=true"), sink.log)
    }

    @Test
    fun `holding twice or resuming twice does nothing the second time`() {
        gate.setHeld(true)
        gate.setHeld(true)
        assertEquals(3, sink.log.size)
        gate.setHeld(false)
        gate.setHeld(false)
        assertEquals(6, sink.log.size)
        val fresh = Sink()
        AudioGate(fresh).setHeld(false)
        assertTrue(fresh.log.isEmpty(), "resuming a call that is not held touches nothing")
    }

    @Test
    fun `a hold that fails halfway is rolled back and rethrown, so the call is as it was`() {
        sink.failOn = "spk.mute=true"
        assertThrows(IllegalStateException::class.java) { gate.setHeld(true) }
        assertFalse(gate.held)
        sink.failOn = null
        // the rollback re-enabled the track and un-muted what the failed hold had touched
        assertEquals(listOf("track=false", "mic.mute=true", "spk.mute=false", "mic.mute=false", "track=true"), sink.log)
    }

    @Test
    fun `a hold that fails halfway rolls back to the person's mute, not to open`() {
        gate.setMuted(true)
        sink.log.clear()
        sink.failOn = "mic.mute=true"
        assertThrows(IllegalStateException::class.java) { gate.setHeld(true) }
        sink.failOn = null
        assertEquals("track=false", sink.log.last(), "muted stays muted after a failed hold")
    }

    @Test
    fun `a resume that fails halfway goes back to held and silent, never half open`() {
        gate.setHeld(true)
        sink.log.clear()
        sink.failOn = "mic.mute=false"
        assertThrows(IllegalStateException::class.java) { gate.setHeld(false) }
        sink.failOn = null
        assertTrue(gate.held)
        assertEquals(listOf("spk.mute=false", "track=false", "mic.mute=true", "spk.mute=true"), sink.log)
    }

    @Test
    fun `focus events - a loss is lost, a gain is regained, ducking is ignored`() {
        assertEquals(AudioFocusPolicy.Event.LOST, AudioFocusPolicy.interpret(AudioFocusPolicy.LOSS))
        assertEquals(AudioFocusPolicy.Event.LOST, AudioFocusPolicy.interpret(AudioFocusPolicy.LOSS_TRANSIENT))
        assertEquals(AudioFocusPolicy.Event.IGNORE, AudioFocusPolicy.interpret(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK))
        for (g in 1..4) assertEquals(AudioFocusPolicy.Event.REGAINED, AudioFocusPolicy.interpret(g), "gain $g")
        assertEquals(AudioFocusPolicy.Event.IGNORE, AudioFocusPolicy.interpret(0))
        assertEquals(AudioFocusPolicy.Event.IGNORE, AudioFocusPolicy.interpret(99))
    }

    @Test
    fun `the focus constants are the ones Android uses`() {
        // AudioManager.AUDIOFOCUS_GAIN=1, _GAIN_TRANSIENT=2, _MAY_DUCK=3, _EXCLUSIVE=4, LOSS=-1, _TRANSIENT=-2, _CAN_DUCK=-3
        assertEquals(listOf(1, 2, 3, 4, -1, -2, -3), listOf(
            AudioFocusPolicy.GAIN, AudioFocusPolicy.GAIN_TRANSIENT, AudioFocusPolicy.GAIN_TRANSIENT_MAY_DUCK,
            AudioFocusPolicy.GAIN_TRANSIENT_EXCLUSIVE, AudioFocusPolicy.LOSS, AudioFocusPolicy.LOSS_TRANSIENT,
            AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK,
        ))
    }
}
