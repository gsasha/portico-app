package com.meda.portico.core.call

import com.meda.portico.core.call.HoldHarness.Companion.A
import com.meda.portico.core.call.HoldHarness.Companion.B
import com.meda.portico.core.call.HoldHarness.Companion.C
import com.meda.portico.core.call.HoldHarness.Companion.ENT
import com.meda.portico.core.call.HoldHarness.Companion.GATE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M7 H2 through the real coordinator: what the screens need from it (the hold clock, the third-ring quiet flag, hang up all),
 * the audio focus, and the screens' models driven by real session states. The rule itself is H1's `AnswerCoordinatorHoldTest`.
 */
class AnswerCoordinatorH2Test {

    private fun on() = HoldHarness(holdEnabled = true)
    private fun off() = HoldHarness(holdEnabled = false)
    private val names = mapOf(GATE to "Gate", ENT to "Entrance")
    private val nameOf: (String) -> String? = { names[it] }

    private fun HoldHarness.hold(nowMs: Long = now) =
        InCallHoldModel.build(coordinator.state(), coordinator.sessionsState(), nowMs, HoldConfig.HOLD_LIMIT_MS, nameOf)

    private fun HoldHarness.view(call: String) = coordinator.sessionsState().sessions.last { it.key.callId == call }

    // ---- the hold clock the strip shows -------------------------------------------------------------------

    @Test
    fun `a call's hold clock starts when it is put on hold and is cleared when it resumes`() {
        val h = on()
        h.connect(GATE, A)
        assertNull(h.view(A).heldAtMs)
        h.now += 5_000
        h.connectOnHold(ENT, B)
        assertEquals(1_005_000L, h.view(A).heldAtMs)
        assertNull(h.view(B).heldAtMs)
        h.now += 30_000
        h.coordinator.resume(A)
        assertNull(h.view(A).heldAtMs, "live again")
        assertEquals(1_035_000L, h.view(B).heldAtMs, "B was held at the switch")
    }

    @Test
    fun `the clock keeps its start while the held call is refreshed by other changes`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val at = h.view(A).heldAtMs
        h.now += 10_000
        h.coordinator.setMuted(true) // a change to the live call publishes the set again
        assertEquals(at, h.view(A).heldAtMs)
    }

    @Test
    fun `a call that ends while held keeps its held flag for the quiet line`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.sched.advance(HoldConfig.HOLD_LIMIT_MS + 1)
        val v = h.view(A)
        assertEquals(Phase.ENDED, v.snapshot.phase)
        assertEquals(EndReason.HOLD_TIMEOUT, v.snapshot.endReason)
        val screen = h.hold()
        assertEquals("Gate call ended: on hold too long.", screen.quietEndLine)
        assertNull(screen.strip)
    }

    // ---- the screens driven by a whole flow ---------------------------------------------------------------

    @Test
    fun `second ring, hold and answer, switch, switch back - the strip and the notice follow every step`() {
        val h = on()
        h.connect(GATE, A)
        h.ring(ENT, B)
        // the ring screen
        val ringView = RingHoldModel.build(h.coordinator.ringOffer(ENT, B), "Gate")
        assertEquals("Hold Gate and answer", ringView.holdAnswerLabel)
        assertTrue(ringView.holdAnswerEnabled)
        h.coordinator.holdAndAnswer(ENT, B)
        // B is still connecting: the strip shows Switch disabled
        var screen = h.hold()
        assertFalse(screen.strip!!.switchEnabled)
        assertEquals("Connecting to Entrance", HoldNoticeModel.build(h.coordinator.sessionsState(), nameOf)!!.title)
        h.finishConnecting(B)
        h.now += 42_000
        screen = h.hold()
        assertTrue(screen.strip!!.switchEnabled)
        assertEquals("Gate", screen.strip!!.doorName)
        assertEquals("On hold for 0:42. 1:18 left.", screen.strip!!.detail)
        assertEquals("On a call: Entrance", HoldNoticeModel.build(h.coordinator.sessionsState(), nameOf)!!.title)
        val before = screen
        var result: SwitchResult? = null
        h.coordinator.resume(A) { result = it }
        assertEquals(SwitchResult.SWITCHED, result)
        screen = h.hold()
        assertEquals("Entrance", screen.strip!!.doorName)
        assertEquals("On a call: Gate", HoldNoticeModel.build(h.coordinator.sessionsState(), nameOf)!!.title)
        assertEquals("Now talking to Gate. Entrance on hold.", InCallHoldModel.announcement(before, screen) { id -> if (id == A) "Gate" else "Entrance" })
        assertEquals(GATE, h.coordinator.state().key!!.doorRef, "the screen's call is the live one")
    }

    @Test
    fun `the live call hangs up - the held one stays held and silent, the screen offers Resume and stays open`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.hangUp()
        val screen = h.hold()
        assertTrue(screen.strip!!.isResume)
        assertEquals("Resume Gate", screen.strip!!.switchLabel)
        assertEquals("Entrance call ended.", screen.liveEndedLine)
        assertFalse(CallUi.shouldFinish(h.coordinator.state(), screen.heldExists), "the screen stays open")
        assertTrue(CallUi.shouldFinish(h.coordinator.state(), false), "and would close with no held call")
        assertTrue(a.heldNow, "no microphone opens by itself")
        assertEquals(1, b.closes)
        val notice = HoldNoticeModel.build(h.coordinator.sessionsState(), nameOf)!!
        assertEquals("Call on hold: Gate", notice.title)
        assertFalse(notice.hangUp)
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.RESUMED, r)
        assertFalse(a.heldNow)
        assertNull(HoldNoticeModel.build(h.coordinator.sessionsState(), nameOf), "back to the plain notification")
    }

    @Test
    fun `the second answer fails after the first was held - the screen says why and offers Resume`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        h.media.calls.last().offerCb!!("v=0 offer")
        h.cmd("portico/answer", B).error("another_call", "")
        assertTrue(a.heldNow, "the first stays held and silent until a press")
        val screen = h.hold()
        assertNotNull(screen.liveEndedLine)
        assertTrue(screen.strip!!.isResume)
        assertEquals("Resume Gate", screen.strip!!.switchLabel)
        assertTrue(screen.heldExists)
        h.coordinator.resume(A)
        assertFalse(a.heldNow)
        assertTrue(a.micOpen)
        assertNull(h.hold().strip)
    }

    @Test
    fun `end held call ends only the held one, quietly`() {
        val h = on()
        h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.endHeld(A)
        assertNull(h.hold().strip)
        assertEquals("Gate call ended.", h.hold().quietEndLine)
        assertTrue(b.micOpen)
        assertEquals(Phase.IN_CALL, h.coordinator.state().snapshot.phase)
    }

    // ---- the audio focus -------------------------------------------------------------------------------

    @Test
    fun `focus lost silences the live call and says so, focus back restores it`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        assertFalse(a.micOpen)
        assertFalse(a.speakerOn)
        assertTrue(h.coordinator.sessionsState().audioInterrupted)
        assertEquals(HoldText.INTERRUPTED, h.hold().interruptedLine)
        assertFalse(h.coordinator.sessionsState().live!!.snapshot.held, "not a hold")
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertTrue(a.micOpen)
        assertTrue(a.speakerOn)
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
        assertNull(h.hold().interruptedLine)
        assertEquals(0, a.closes)
        assertEquals(0, h.cmds.count("portico/hangup"))
    }

    @Test
    fun `a permanent loss silences too`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS)
        assertFalse(a.micOpen)
        assertTrue(h.coordinator.sessionsState().audioInterrupted)
    }

    @Test
    fun `ducking does nothing`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(a.micOpen)
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
    }

    @Test
    fun `the person's mute is still in force after the audio comes back`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.coordinator.setMuted(true)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertFalse(a.micOpen, "still muted")
        assertTrue(a.speakerOn)
    }

    @Test
    fun `a held call stays held and silent when the audio comes back`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        assertFalse(b.micOpen)
        assertTrue(a.heldNow)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertTrue(b.micOpen, "the live one is back")
        assertTrue(a.heldNow, "the held one is not woken by the system")
        assertTrue(h.view(A).snapshot.held)
    }

    @Test
    fun `focus lost with only the connecting call touches no media, and the regain is harmless`() {
        val h = on()
        h.ring(GATE, A)
        h.coordinator.press(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        assertTrue(h.media.calls.single().holds.isEmpty())
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertTrue(h.media.calls.single().holds.isEmpty())
    }

    @Test
    fun `focus events with no call at all are ignored`() {
        val h = on()
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS)
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
        h.connect(GATE, A)
        assertFalse(h.coordinator.sessionsState().audioInterrupted, "an old loss does not leak into the next call")
    }

    @Test
    fun `the interruption is forgotten when the last call ends`() {
        val h = on()
        h.connect(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        h.coordinator.hangUp()
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
        h.connect(ENT, B)
        assertTrue(h.media.calls.last().micOpen, "the next call is not silenced by the old loss")
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
    }

    @Test
    fun `a switch during an interruption is the person's press and turns the other call on`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        h.coordinator.resume(A)
        assertTrue(b.heldNow, "the live one is held by the switch")
        assertTrue(a.micOpen, "the person asked for this call")
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertTrue(a.micOpen)
        assertTrue(b.heldNow, "and the held one stays held")
    }

    @Test
    fun `gate off - the audio focus does nothing at all`() {
        val h = off()
        val a = h.connect(GATE, A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        assertTrue(a.holds.isEmpty(), "the media is never told")
        assertTrue(a.micOpen)
        assertFalse(h.coordinator.sessionsState().audioInterrupted)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertTrue(a.holds.isEmpty())
    }

    // ---- the third ring ----------------------------------------------------------------------------------

    @Test
    fun `the third ring is quiet only with two calls in use, and never with the gate off`() {
        val h = on()
        assertFalse(h.coordinator.thirdRingIsQuiet())
        h.connect(GATE, A)
        assertFalse(h.coordinator.thirdRingIsQuiet(), "one call: the second ring still rings")
        h.connectOnHold(ENT, B)
        assertTrue(h.coordinator.thirdRingIsQuiet())
        h.coordinator.endHeld(A)
        assertFalse(h.coordinator.thirdRingIsQuiet(), "an ended call no longer counts")
        val g = off()
        g.connect(GATE, A)
        g.ring(ENT, B)
        g.coordinator.press(ENT, B)
        assertFalse(g.coordinator.thirdRingIsQuiet())
    }

    // ---- hang up all -------------------------------------------------------------------------------------

    @Test
    fun `hang up all releases the live and the held call, hang up only the live one`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.hangUp()
        assertEquals(1, h.cmds.count("portico/hangup"))
        assertTrue(h.coordinator.hasActiveSession(), "the held call is still there")
        val g = on()
        g.connect(GATE, A)
        g.connectOnHold(ENT, B)
        g.coordinator.hangUpAll()
        assertEquals(2, g.cmds.count("portico/hangup"))
        assertFalse(g.coordinator.hasActiveSession())
        assertEquals(1, g.serviceStops, "the service stops once, with the last")
    }

    @Test
    fun `gate off - hang up all is a hang up`() {
        val h = off()
        h.connect(GATE, A)
        h.coordinator.hangUpAll()
        assertEquals(1, h.cmds.count("portico/hangup"))
        assertFalse(h.coordinator.hasActiveSession())
        h.coordinator.hangUpAll() // nothing to hang up: harmless
        assertEquals(1, h.cmds.count("portico/hangup"))
    }

    // ---- with the gate off, no screen is ever changed ------------------------------------------------------

    @Test
    fun `gate off - every state of a plain call and of a refused second ring adds nothing to any screen`() {
        val h = off()
        fun nothingNew() {
            val sessions = h.coordinator.sessionsState()
            val v = h.hold()
            assertNull(v.strip)
            assertNull(v.quietEndLine)
            assertNull(v.interruptedLine)
            assertFalse(v.heldExists)
            assertNull(HoldNoticeModel.build(sessions, nameOf))
            assertEquals(RingHoldView.NOTHING, RingHoldModel.build(h.coordinator.ringOffer(ENT, B), "Gate"))
            assertFalse(h.coordinator.thirdRingIsQuiet())
        }
        h.ring(ENT, B)
        nothingNew()
        h.ring(GATE, A)
        h.coordinator.press(GATE, A)
        nothingNew()
        h.media.calls.single().offerCb!!("v=0")
        nothingNew()
        h.cmd("portico/answer", A).ok(obj("mode" to "relay"))
        h.cmd("portico/webrtc_offer", A).ok(obj("sdp" to "v=0 answer"))
        h.media.calls.single().answerCb!!(true)
        h.media.calls.single().listener.onConnected()
        nothingNew()
        h.coordinator.press(ENT, B) // refused: "You are already on a call."
        nothingNew()
        h.coordinator.setMuted(true)
        nothingNew()
        h.coordinator.hangUp()
        nothingNew()
        assertTrue(CallUi.shouldFinish(h.coordinator.state(), h.hold().heldExists), "a plain hang-up still closes the screen")
    }

    @Test
    fun `a third ring while both are in use is refused and offers nothing`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.ring(GATE, C)
        val v = RingHoldModel.build(h.coordinator.ringOffer(GATE, C), "Entrance")
        assertNull(v.holdAnswerLabel)
        assertEquals(HoldText.BOTH_IN_USE, v.reasonLine)
        h.coordinator.holdAndAnswer(GATE, C)
        assertEquals(2, h.coordinator.sessionsState().activeCount)
    }
}
