package com.meda.portico.core.call

import com.meda.portico.core.push.CallKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every state of the hold screens, as the pure models decide it (M7 H2). The activities only map these to widgets. */
class HoldUiTest {

    private val names = mapOf("gate-ref" to "Gate", "ent-ref" to "Entrance")
    private val nameOf: (String) -> String? = { names[it] }
    private val limit = 120_000L

    private val gate = CallKey("gate-ref", "c-gate")
    private val ent = CallKey("ent-ref", "c-ent")

    private fun inCall(muted: Boolean = false, held: Boolean = false) =
        AnswerSnapshot(Phase.IN_CALL, muted = muted, connectedAtMs = 1_000L, held = held)

    private fun view(key: CallKey, snap: AnswerSnapshot, gen: Int = 1, heldAt: Long? = null) = SessionView(key, snap, gen, heldAt)
    private fun state(vararg v: SessionView, quiet: Int = 0, audio: Boolean = false) = SessionsState(v.toList(), 1, quiet, audio)
    private fun legacy(key: CallKey?, snap: AnswerSnapshot) = CallUiState(key, snap, 1)
    private fun ended(reason: EndReason, failure: AnswerFailure? = null, held: Boolean = false) =
        AnswerSnapshot(Phase.ENDED, reason, failure, held = held)

    // ---- the words ------------------------------------------------------------------------------

    @Test
    fun `clock and spoken durations`() {
        assertEquals("0:00", HoldText.clock(0))
        assertEquals("0:42", HoldText.clock(42))
        assertEquals("1:18", HoldText.clock(78))
        assertEquals("2:00", HoldText.clock(120))
        assertEquals("0:00", HoldText.clock(-5), "never negative")
        assertEquals("1 second", HoldText.spoken(1))
        assertEquals("42 seconds", HoldText.spoken(42))
        assertEquals("1 minute", HoldText.spoken(60))
        assertEquals("2 minutes", HoldText.spoken(120))
        assertEquals("1 minute 18 seconds", HoldText.spoken(78))
        assertEquals("0 seconds", HoldText.spoken(0))
    }

    @Test
    fun `the plain English lines`() {
        assertEquals("You are on a call with Gate.", HoldText.onCallWith("Gate"))
        assertEquals("Hold Gate and answer", HoldText.holdAndAnswer("Gate"))
        assertEquals("On hold: Entrance", HoldText.onHold("Entrance"))
        assertEquals("Resume Entrance", HoldText.resume("Entrance"))
        assertEquals("Gate call ended.", HoldText.ended("Gate"))
        assertEquals("Gate call ended: on hold too long.", HoldText.endedHeldTooLong("Gate"))
        assertEquals("Now talking to Entrance. Gate on hold.", HoldText.nowTalking("Entrance", "Gate"))
        assertEquals("Now talking to Entrance.", HoldText.nowTalking("Entrance", null))
        assertEquals("On a call: Gate", HoldText.notifTitleLive("Gate"))
        assertEquals("Entrance on hold", HoldText.notifTextHeld("Entrance"))
    }

    @Test
    fun `a switch that did not happen says why, one that did says nothing`() {
        assertEquals("Could not put Gate on hold, so nothing changed.", HoldText.switchFailed(SwitchResult.HOLD_FAILED, "Gate", "Entrance"))
        assertEquals("Could not go back to Entrance. Gate is as it was.", HoldText.switchFailed(SwitchResult.RESUME_FAILED, "Gate", "Entrance"))
        assertEquals(HoldText.WAIT_TO_SWITCH, HoldText.switchFailed(SwitchResult.LIVE_NOT_CONNECTED, "Gate", "Entrance"))
        for (ok in listOf(SwitchResult.SWITCHED, SwitchResult.RESUMED, SwitchResult.NOT_HELD, SwitchResult.DISABLED)) {
            assertNull(HoldText.switchFailed(ok, "Gate", "Entrance"), ok.name)
        }
        // Every result is covered (a new one would break this, and the `when` is exhaustive).
        assertEquals(SwitchResult.values().size, 7)
    }

    // ---- the ring screen ------------------------------------------------------------------------

    @Test
    fun `ring screen - the plain answer and the nothing cases add nothing`() {
        assertEquals(RingHoldView.NOTHING, RingHoldModel.build(RingOffer.ANSWER, null))
        assertEquals(RingHoldView.NOTHING, RingHoldModel.build(RingOffer.NONE, "Gate"))
    }

    @Test
    fun `ring screen - a second ring offers hold and answer plus decline`() {
        val v = RingHoldModel.build(RingOffer.HOLD_AND_ANSWER, "Gate")
        assertEquals("You are on a call with Gate.", v.onCallLine)
        assertEquals("Hold Gate and answer", v.holdAnswerLabel)
        assertTrue(v.holdAnswerEnabled)
        assertNull(v.reasonLine)
        assertTrue(v.declineIsDecline)
    }

    @Test
    fun `ring screen - the first call not connected shows the button disabled with the reason`() {
        val v = RingHoldModel.build(RingOffer.WAIT_FOR_FIRST, "Gate")
        assertEquals("Hold Gate and answer", v.holdAnswerLabel)
        assertFalse(v.holdAnswerEnabled)
        assertEquals(HoldText.WAIT_FOR_FIRST, v.reasonLine)
        assertTrue(v.declineIsDecline)
    }

    @Test
    fun `ring screen - a third ring has no answer button at all, only the reason`() {
        val v = RingHoldModel.build(RingOffer.BOTH_IN_USE, "Gate")
        assertNull(v.holdAnswerLabel, "no Answer of any kind")
        assertFalse(v.holdAnswerEnabled)
        assertEquals("Both of your calls are in use. You cannot answer this one.", v.reasonLine)
        assertTrue(v.declineIsDecline)
    }

    @Test
    fun `ring screen - an unknown door name falls back to the generic word`() {
        assertEquals("Hold Door and answer", RingHoldModel.build(RingOffer.HOLD_AND_ANSWER, null).holdAnswerLabel)
    }

    // ---- the in-call screen ------------------------------------------------------------------------

    @Test
    fun `in-call - one plain call adds nothing, which is every state while hold is off`() {
        val live = state(view(gate, inCall()))
        assertEquals(
            InCallHoldView(null, null, null, null, gate.callId, null, false),
            InCallHoldModel.build(legacy(gate, inCall()), live, 5_000L, limit, nameOf),
        )
        val connecting = state(view(gate, AnswerSnapshot(Phase.CONNECTING)))
        assertNull(InCallHoldModel.build(legacy(gate, AnswerSnapshot(Phase.CONNECTING)), connecting, 5_000L, limit, nameOf).strip)
        val over = state(view(gate, ended(EndReason.USER_HANGUP)))
        val v = InCallHoldModel.build(legacy(gate, ended(EndReason.USER_HANGUP)), over, 5_000L, limit, nameOf)
        assertFalse(v.heldExists)
        assertNull(v.liveEndedLine)
        assertNull(v.quietEndLine)
    }

    @Test
    fun `in-call - a held call shows the strip with its clock and the time left`() {
        val s = state(view(ent, inCall()), view(gate, inCall(held = true), heldAt = 100_000L))
        val v = InCallHoldModel.build(legacy(ent, inCall()), s, 142_000L, limit, nameOf)
        val strip = v.strip!!
        assertTrue(v.heldExists)
        assertEquals(gate.callId, strip.callId)
        assertEquals("On hold: Gate", strip.title)
        assertEquals("On hold for 0:42. 1:18 left.", strip.detail)
        assertFalse(strip.isResume)
        assertEquals("Switch", strip.switchLabel)
        assertTrue(strip.switchEnabled)
        assertNull(strip.disabledReason)
        assertEquals("Gate, on hold for 42 seconds, 1 minute 18 seconds left. Double tap to switch.", strip.description)
        assertEquals(ent.callId, v.liveCallId)
        assertEquals(gate.callId, v.heldCallId)
        assertNull(v.liveEndedLine)
    }

    @Test
    fun `in-call - the clock never runs backwards and time left stops at zero`() {
        val s = state(view(ent, inCall()), view(gate, inCall(held = true), heldAt = 200_000L))
        assertEquals("On hold for 0:00. 2:00 left.", InCallHoldModel.build(legacy(ent, inCall()), s, 100_000L, limit, nameOf).strip!!.detail)
        assertEquals("On hold for 3:20. 0:00 left.", InCallHoldModel.build(legacy(ent, inCall()), s, 400_000L, limit, nameOf).strip!!.detail)
        val none = state(view(ent, inCall()), view(gate, inCall(held = true), heldAt = null))
        assertEquals("On hold for 0:00. 2:00 left.", InCallHoldModel.build(legacy(ent, inCall()), none, 400_000L, limit, nameOf).strip!!.detail)
    }

    @Test
    fun `in-call - the second call still connecting disables Switch and says why`() {
        val connecting = AnswerSnapshot(Phase.NEGOTIATING)
        val s = state(view(gate, inCall(held = true), heldAt = 0L), view(ent, connecting))
        val strip = InCallHoldModel.build(legacy(ent, connecting), s, 10_000L, limit, nameOf).strip!!
        assertFalse(strip.switchEnabled)
        assertEquals(HoldText.WAIT_TO_SWITCH, strip.disabledReason)
        assertTrue(strip.description.endsWith(HoldText.WAIT_TO_SWITCH))
        assertEquals("Switch", strip.switchLabel)
    }

    @Test
    fun `in-call - the live call hung up while another is held turns Switch into Resume`() {
        val over = ended(EndReason.USER_HANGUP)
        val s = state(view(gate, inCall(held = true), heldAt = 0L), view(ent, over))
        val v = InCallHoldModel.build(legacy(ent, over), s, 30_000L, limit, nameOf)
        val strip = v.strip!!
        assertTrue(strip.isResume)
        assertEquals("Resume Gate", strip.switchLabel)
        assertTrue(strip.switchEnabled)
        assertEquals("Entrance call ended.", v.liveEndedLine)
        assertTrue(v.heldExists, "the screen must stay open for the Resume")
        assertNull(v.liveCallId)
        assertEquals("Entrance call ended. Gate, on hold for 30 seconds, 1 minute 30 seconds left. Double tap to resume.", strip.description)
    }

    @Test
    fun `in-call - a failed second answer keeps its own words and offers Resume of the first`() {
        val failure = AnswerErrors.micDenied()
        val over = ended(EndReason.FAILED, failure)
        val s = state(view(gate, inCall(held = true), heldAt = 0L), view(ent, over))
        val v = InCallHoldModel.build(legacy(ent, over), s, 5_000L, limit, nameOf)
        assertEquals(failure.message, v.liveEndedLine)
        assertTrue(v.strip!!.isResume)
        assertEquals("Resume Gate", v.strip!!.switchLabel)
    }

    @Test
    fun `in-call - a held call that ended quietly is one line, and hold timeout says why`() {
        val timeout = ended(EndReason.HOLD_TIMEOUT, AnswerErrors.heldTooLong(), held = true)
        val s = state(view(ent, inCall()), view(gate, timeout, heldAt = 0L), quiet = 1)
        val v = InCallHoldModel.build(legacy(ent, inCall()), s, 9_000L, limit, nameOf)
        assertNull(v.strip, "an ended call is not a strip")
        assertFalse(v.heldExists)
        assertEquals("Gate call ended: on hold too long.", v.quietEndLine)
        val gone = ended(EndReason.REMOTE_HANGUP, held = true)
        val s2 = state(view(ent, inCall()), view(gate, gone, heldAt = 0L), quiet = 1)
        assertEquals("Gate call ended.", InCallHoldModel.build(legacy(ent, inCall()), s2, 9_000L, limit, nameOf).quietEndLine)
    }

    @Test
    fun `in-call - audio taken by another app is shown, and only while it is`() {
        val s = state(view(gate, inCall()), audio = true)
        assertEquals(HoldText.INTERRUPTED, InCallHoldModel.build(legacy(gate, inCall()), s, 0, limit, nameOf).interruptedLine)
        assertNull(InCallHoldModel.build(legacy(gate, inCall()), state(view(gate, inCall())), 0, limit, nameOf).interruptedLine)
    }

    @Test
    fun `in-call - the strip's description never uses colour and always carries the door, the time and the action`() {
        val s = state(view(ent, inCall()), view(gate, inCall(held = true), heldAt = 0L))
        val d = InCallHoldModel.build(legacy(ent, inCall()), s, 61_000L, limit, nameOf).strip!!.description
        for (part in listOf("Gate", "on hold for 1 minute 1 second", "59 seconds left", "Double tap to switch")) assertTrue(part in d, "$part in <$d>")
    }

    @Test
    fun `in-call - a door the cache does not know is called Door`() {
        val s = state(view(CallKey("zzz", "c-z"), inCall()), view(CallKey("yyy", "c-y"), inCall(held = true), heldAt = 0L))
        val v = InCallHoldModel.build(legacy(CallKey("zzz", "c-z"), inCall()), s, 1_000L, limit, nameOf)
        assertEquals("On hold: Door", v.strip!!.title)
    }

    // ---- announcements -------------------------------------------------------------------------------

    @Test
    fun `a switch is announced, a refresh and a first draw are not`() {
        val nameOfCall = { id: String -> if (id == gate.callId) "Gate" else "Entrance" }
        val before = InCallHoldView(null, null, null, null, ent.callId, gate.callId, true)
        val after = InCallHoldView(null, null, null, null, gate.callId, ent.callId, true)
        assertEquals("Now talking to Gate. Entrance on hold.", InCallHoldModel.announcement(before, after, nameOfCall))
        assertNull(InCallHoldModel.announcement(before, before, nameOfCall), "a refresh")
        assertNull(InCallHoldModel.announcement(null, after, nameOfCall), "the first draw")
    }

    @Test
    fun `a resume after the live call ended is announced without a held call`() {
        val nameOfCall = { id: String -> if (id == gate.callId) "Gate" else "Entrance" }
        val before = InCallHoldView(null, null, null, null, null, gate.callId, true)
        val after = InCallHoldView(null, null, null, null, gate.callId, null, false)
        assertEquals("Now talking to Gate.", InCallHoldModel.announcement(before, after, nameOfCall))
    }

    @Test
    fun `a new call answered on top of a held one is not announced as a switch`() {
        val nameOfCall = { _: String -> "x" }
        val before = InCallHoldView(null, null, null, null, gate.callId, null, false)
        val after = InCallHoldView(null, null, null, null, ent.callId, gate.callId, true)
        assertNull(InCallHoldModel.announcement(before, after, nameOfCall))
        assertNull(InCallHoldModel.announcement(after, InCallHoldView.NOTHING, nameOfCall), "no live call, nothing to announce")
    }

    // ---- the notification --------------------------------------------------------------------------

    @Test
    fun `notification - nothing held is the unchanged single-call notification`() {
        assertNull(HoldNoticeModel.build(state(view(gate, inCall())), nameOf))
        assertNull(HoldNoticeModel.build(state(), nameOf))
        assertNull(HoldNoticeModel.build(state(view(gate, ended(EndReason.USER_HANGUP))), nameOf))
    }

    @Test
    fun `notification - one notice shows the live call and the held one`() {
        val n = HoldNoticeModel.build(state(view(gate, inCall(held = true), heldAt = 0L), view(ent, inCall())), nameOf)!!
        assertEquals("On a call: Entrance", n.title)
        assertEquals("Gate on hold", n.text)
        assertTrue(n.hangUp)
    }

    @Test
    fun `notification - while the second call connects it says connecting`() {
        val n = HoldNoticeModel.build(state(view(gate, inCall(held = true), heldAt = 0L), view(ent, AnswerSnapshot(Phase.CLAIMING))), nameOf)!!
        assertEquals("Connecting to Entrance", n.title)
        assertEquals("Gate on hold", n.text)
    }

    @Test
    fun `notification - only a held call left offers no hang up and says how to resume`() {
        val n = HoldNoticeModel.build(state(view(gate, inCall(held = true), heldAt = 0L), view(ent, ended(EndReason.REMOTE_HANGUP))), nameOf)!!
        assertEquals("Call on hold: Gate", n.title)
        assertEquals(HoldText.NOTIF_TEXT_ONLY_HELD, n.text)
        assertFalse(n.hangUp)
        assertNotNull(n)
    }
}
