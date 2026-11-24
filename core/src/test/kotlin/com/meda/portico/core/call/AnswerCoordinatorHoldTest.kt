package com.meda.portico.core.call

import com.meda.portico.core.call.HoldHarness.Companion.A
import com.meda.portico.core.call.HoldHarness.Companion.B
import com.meda.portico.core.call.HoldHarness.Companion.C
import com.meda.portico.core.call.HoldHarness.Companion.ENT
import com.meda.portico.core.call.HoldHarness.Companion.GATE
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.push.CallState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hold and switch (design 15.13) through the real coordinator: every transition and failure of the rule, the gate, and the
 * races between a switch and the doors' own events. A is the gate call, B the entrance call; A rings and is answered first.
 */
class AnswerCoordinatorHoldTest {

    private fun on() = HoldHarness(holdEnabled = true)
    private fun off() = HoldHarness(holdEnabled = false)

    private fun HoldHarness.endedEvent(ref: String, call: String, door: String) =
        coordinator.onCallEvent(CallEvent.Ended(door, ref, call, inferred = false))

    // ---- the gate -----------------------------------------------------------------------------------

    @Test
    fun `the gate constant is off, and a coordinator built without the flag uses it`() {
        assertFalse(HoldConfig.HOLD_ENABLED)
        assertEquals(120_000L, HoldConfig.HOLD_LIMIT_MS)
        assertEquals(HoldConfig.HOLD_LIMIT_MS, AnswerConfig().holdLimitMs)
        assertEquals(2, HoldConfig.MAX_DOOR_SESSIONS)
        val h = off()
        // The default-flag coordinator is the one the harness builds with holdEnabled=false; build one without naming it.
        val plain = AnswerCoordinator(
            newSession = { callId, doorId, hooks, onChange ->
                AnswerSession(callId, doorId, h.cmds, SerialMedia(h.media) { it() }, h.route, { true }, h.sched, h.active, { h.now }, hooks, AnswerConfig(), onChange = onChange)
            },
            callStore = h.store, doors = h.doors, sweep = AnswerSweep(h.cmds, h.active), post = { it() },
            stopRing = { _, _, _ -> }, serviceStart = {}, serviceStop = {}, paused = { false }, wallClockMs = { h.now },
        )
        h.ring(GATE, A)
        plain.press(GATE, A)
        h.ring(ENT, B)
        plain.holdAndAnswer(ENT, B)
        assertEquals("another_call", plain.state().snapshot.failure!!.code)
        assertEquals(1, h.media.calls.size, "no second session")
    }

    @Test
    fun `gate off - a second ring while on a call is refused with the old text, and nothing is held`() {
        val h = off()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        assertEquals(RingOffer.NONE, h.coordinator.ringOffer(ENT, B))
        assertFalse(h.coordinator.canAnswer(ENT, B))
        h.coordinator.press(ENT, B)
        val st = h.coordinator.state()
        assertEquals("You are already on a call.", st.snapshot.failure!!.message)
        assertEquals("another_call", st.snapshot.failure!!.code)
        assertEquals(1, h.media.calls.size)
        assertTrue(a.holds.isEmpty())
        assertEquals(CallState.RINGING, h.store.get(h.key(ENT, B))?.state, "the refused ring is untouched")
        assertTrue(h.ringStops.none { it.contains(B) })
    }

    @Test
    fun `gate off - holdAndAnswer is the same refusal as a plain press`() {
        val h = off()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals("You are already on a call.", h.coordinator.state().snapshot.failure!!.message)
        assertEquals(1, h.media.calls.size)
        assertTrue(a.holds.isEmpty())
        assertFalse(a.heldNow)
        assertTrue(a.micOpen)
    }

    @Test
    fun `gate off - resume does nothing`() {
        val h = off()
        h.connect(GATE, A)
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.DISABLED, r)
    }

    @Test
    fun `gate off - after a refused second ring the phone still refuses the next one (the old state-clobber let a second session start)`() {
        val h = off()
        h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.press(ENT, B)
        h.ring(ENT, C)
        h.coordinator.press(ENT, C)
        assertEquals(1, h.media.calls.size, "still one session")
        assertTrue(h.coordinator.hasActiveSession(), "the phone stays busy for a call button")
        assertEquals(1, h.coordinator.sessionsState().activeCount)
    }

    @Test
    fun `gate on - a plain press on a connected live call is still the old refusal`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.press(ENT, B)
        assertEquals("You are already on a call.", h.coordinator.state().snapshot.failure!!.message)
        assertEquals(1, h.media.calls.size)
        assertTrue(a.holds.isEmpty())
    }

    // ---- ring while idle, ring while live ---------------------------------------------------------------

    @Test
    fun `ring while idle - the plain Answer is offered and begins`() {
        val h = on()
        h.ring(GATE, A)
        assertEquals(RingOffer.ANSWER, h.coordinator.ringOffer(GATE, A))
        assertTrue(h.coordinator.canAnswer(GATE, A))
        h.coordinator.press(GATE, A)
        assertEquals(1, h.media.calls.size)
        assertEquals(1, h.serviceStarts)
    }

    @Test
    fun `ring while live - offered hold-and-answer, and the live call goes on hold BEFORE the new one opens its microphone`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        assertEquals(RingOffer.HOLD_AND_ANSWER, h.coordinator.ringOffer(ENT, B))
        assertFalse(h.coordinator.canAnswer(ENT, B), "the plain Answer is not what this ring offers")
        h.order.clear()
        h.coordinator.holdAndAnswer(ENT, B)
        val b = h.media.calls.last()
        assertEquals(listOf("c1 open", "c1 held=true", "c2 open"), h.media.trace, "old off, then new on")
        assertTrue(a.heldNow)
        assertFalse(a.micOpen)
        assertFalse(a.speakerOn)
        assertEquals(0, a.closes, "hold never closes the PeerConnection")
        assertEquals(listOf("before-begin"), h.order)
        h.finishConnecting(B)
        assertTrue(b.micOpen)
        assertEquals(CallState.ANSWERED, h.store.get(h.key(ENT, B))?.state)
        assertEquals(1, h.ringStops.count { it.contains(B) }, "the ring for B is silenced by the press")
        assertTrue(h.ringStops.none { it.contains(A) && it.endsWith("hold") })
        assertEquals(CallKey2(ENT, B), CallKey2(h.coordinator.state().key!!.doorRef, h.coordinator.state().key!!.callId), "the screen follows the live call")
        assertEquals(Phase.IN_CALL, h.coordinator.state().snapshot.phase)
        assertFalse(h.coordinator.state().snapshot.held)
    }

    private data class CallKey2(val ref: String, val call: String)

    @Test
    fun `no command is sent for a hold - nothing but the second call's own answer and offer`() {
        val h = on()
        h.connect(GATE, A)
        val before = h.cmds.sent.size
        h.connectOnHold(ENT, B)
        val newTypes = h.cmds.sent.drop(before).map { it.type }
        assertEquals(listOf("portico/answer", "portico/webrtc_offer"), newTypes)
        assertEquals(0, h.cmds.count("portico/hangup"))
    }

    @Test
    fun `ring while the first call is still connecting - refused with its own text, nothing held`() {
        val h = on()
        h.ring(GATE, A)
        h.coordinator.press(GATE, A) // PREPARING
        h.ring(ENT, B)
        assertEquals(RingOffer.WAIT_FOR_FIRST, h.coordinator.ringOffer(ENT, B))
        h.coordinator.holdAndAnswer(ENT, B)
        val f = h.coordinator.state().snapshot.failure!!
        assertEquals("Wait until the first call is connected.", f.message)
        assertEquals(AnswerFailure.Kind.FIRST_NOT_CONNECTED, f.kind)
        assertEquals(1, h.media.calls.size)
        assertEquals(CallState.RINGING, h.store.get(h.key(ENT, B))?.state)
        // The same is true at every phase before IN_CALL.
        h.media.calls.single().offerCb!!("v=0")
        h.coordinator.holdAndAnswer(ENT, B) // CLAIMING
        assertEquals(AnswerFailure.Kind.FIRST_NOT_CONNECTED, h.coordinator.state().snapshot.failure!!.kind)
        h.cmd("portico/answer", A).ok(obj("mode" to "relay")) // NEGOTIATING
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(AnswerFailure.Kind.FIRST_NOT_CONNECTED, h.coordinator.state().snapshot.failure!!.kind)
        assertEquals(1, h.media.calls.size)
    }

    @Test
    fun `third ring - no Answer, no hold-and-answer, and the two calls are untouched`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.ring(GATE, C)
        assertEquals(RingOffer.BOTH_IN_USE, h.coordinator.ringOffer(GATE, C))
        assertFalse(h.coordinator.canAnswer(GATE, C))
        h.coordinator.press(GATE, C)
        h.coordinator.holdAndAnswer(GATE, C)
        assertEquals("You are already on a call.", h.coordinator.state().snapshot.failure!!.message)
        assertEquals(2, h.media.calls.size)
        assertTrue(a.heldNow)
        assertFalse(b.heldNow)
        assertEquals(listOf(true), a.holds)
        assertEquals(emptyList<Boolean>(), b.holds)
        assertEquals(0, h.count("portico/answer", C))
        assertEquals(CallState.RINGING, h.store.get(h.key(GATE, C))?.state)
    }

    @Test
    fun `third ring while the second is still connecting is refused as well`() {
        val h = on()
        h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B) // B is PREPARING
        h.ring(GATE, C)
        assertEquals(RingOffer.BOTH_IN_USE, h.coordinator.ringOffer(GATE, C))
        h.coordinator.holdAndAnswer(GATE, C)
        assertEquals(2, h.media.calls.size)
    }

    @Test
    fun `the same call pressed twice is a no-op, not a refusal and not a second session`() {
        val h = on()
        h.connect(GATE, A)
        val gen = h.coordinator.state()
        h.coordinator.press(GATE, A)
        h.coordinator.holdAndAnswer(GATE, A)
        assertSame(gen, h.coordinator.state())
        assertEquals(1, h.media.calls.size)
    }

    @Test
    fun `only a held call is left - a new ring is a plain Answer`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.endedEvent(ENT, B, "ent-dev") // the live one ends; A stays held
        assertTrue(a.heldNow)
        h.ring(GATE, C)
        assertEquals(RingOffer.ANSWER, h.coordinator.ringOffer(GATE, C))
        h.coordinator.press(GATE, C)
        assertEquals(3, h.media.calls.size)
        assertTrue(a.heldNow, "A is still held under the new live call")
        assertEquals(2, h.coordinator.sessionsState().activeCount)
    }

    // ---- refusals are checked BEFORE anything is held ----------------------------------------------------

    @Test
    fun `a refusal that would stop a plain answer holds nothing - paused, unknown door, ended call`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.paused = true
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(AnswerFailure.Kind.PAUSED, h.coordinator.state().snapshot.failure!!.kind)
        assertTrue(a.holds.isEmpty())
        h.paused = false
        h.coordinator.holdAndAnswer("no-such-door", "gso-zzzzzzzz")
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, h.coordinator.state().snapshot.failure!!.kind)
        assertTrue(a.holds.isEmpty())
        h.coordinator.onCallEvent(CallEvent.Ringing("ent-dev", ENT, B))
        com.meda.portico.core.push.Decision.receive(h.store, com.meda.portico.core.push.SocketEvents.ended(ENT, B, h.now), h.now, setOf(GATE, ENT))
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(AnswerFailure.Kind.CALL_ENDED, h.coordinator.state().snapshot.failure!!.kind)
        assertTrue(a.holds.isEmpty())
        assertTrue(a.micOpen)
        assertEquals(1, h.media.calls.size)
    }

    @Test
    fun `the live call cannot be held - nothing changes and the ring is still ringing`() {
        val h = on()
        val a = h.connect(GATE, A)
        a.failHold = true
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(AnswerFailure.Kind.HOLD_FAILED, h.coordinator.state().snapshot.failure!!.kind)
        assertEquals(1, h.media.calls.size)
        assertTrue(a.micOpen && a.speakerOn)
        assertEquals(CallState.RINGING, h.store.get(h.key(ENT, B))?.state)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        assertFalse(h.snapshotOf(A)!!.held)
        assertEquals(0, h.serviceStops)
    }

    @Test
    fun `a media that never learned to hold fails safe - the default throws, the hold is refused`() {
        val h = on()
        val plain = object : MediaCall {
            override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) {}
            override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) {}
            override fun setMuted(muted: Boolean) {}
            override fun close() {}
        }
        val threw = try {
            plain.setHeld(true)
            false
        } catch (e: UnsupportedOperationException) {
            true
        }
        assertTrue(threw)
        val away = object : FramePath {
            override fun start(callId: String, doorId: String, onResult: (AnswerFailure?) -> Unit) {}
            override fun setMuted(muted: Boolean) {}
            override fun close() {}
        }
        assertTrue(try { away.setHeld(true); false } catch (e: UnsupportedOperationException) { true })
        h.connect(GATE, A)
    }

    // ---- hold, switch ------------------------------------------------------------------------------------

    @Test
    fun `switch - the live call goes off first, then the held one comes on, and back`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.media.trace.clear()
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.SWITCHED, r)
        assertEquals(listOf("c2 held=true", "c1 held=false"), h.media.trace)
        assertTrue(b.heldNow && !a.heldNow)
        assertEquals(CallKey2(GATE, A), CallKey2(h.coordinator.state().key!!.doorRef, h.coordinator.state().key!!.callId))
        h.media.trace.clear()
        h.coordinator.resume(B) { r = it }
        assertEquals(SwitchResult.SWITCHED, r)
        assertEquals(listOf("c1 held=true", "c2 held=false"), h.media.trace)
        assertEquals(0, a.closes + b.closes, "no leg is ever closed by a switch")
        assertEquals(0, h.cmds.count("portico/hangup"))
    }

    @Test
    fun `at no instant are two microphones open - checked across a whole sequence`() {
        val h = on()
        val calls = mutableListOf<FakeMediaCall>()
        fun open() = calls.count { it.micOpen && it.speakerOn }
        calls += h.connect(GATE, A)
        assertEquals(1, open())
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(0, calls.count { it.micOpen }, "A is off before B exists")
        calls += h.media.calls.last()
        h.finishConnecting(B)
        assertEquals(1, open())
        repeat(3) {
            h.coordinator.resume(A)
            assertTrue(open() <= 1)
            h.coordinator.resume(B)
            assertTrue(open() <= 1)
        }
        assertEquals(1, open())
    }

    @Test
    fun `mute and hold are separate - the mute the person set is back after a resume, even from a media that forgets it`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.coordinator.setMuted(true)
        assertTrue(a.micOff)
        h.connectOnHold(ENT, B)
        a.resumeForgetsMute = true
        h.coordinator.resume(A)
        assertTrue(a.micOff, "A is still muted")
        assertFalse(a.micOpen)
        assertTrue(h.snapshotOf(A)!!.muted)
        // B was never muted and comes back unmuted.
        h.coordinator.resume(B)
        assertTrue(h.media.calls[1].micOpen)
    }

    @Test
    fun `mute presses while a call is held reach the live call only`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.setMuted(true)
        assertTrue(b.micOff)
        assertFalse(a.micOff)
        assertFalse(h.snapshotOf(A)!!.muted)
        assertTrue(h.snapshotOf(B)!!.muted)
    }

    @Test
    fun `a failed resume puts the live call back - never two held calls and nobody left on a silent line`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        a.failHold = true // A cannot be resumed (it is held now)
        // B is live and A is held, so the resume of A holds B, then A fails to come back: B is restored.
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.RESUME_FAILED, r)
        assertTrue(b.micOpen && b.speakerOn, "B is live again")
        assertEquals(listOf(true, false), b.holds)
        assertTrue(a.heldNow)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
    }

    @Test
    fun `the live call cannot be held for a switch - nothing changes`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        b.failHold = true
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.HOLD_FAILED, r)
        assertTrue(b.micOpen)
        assertTrue(a.heldNow)
    }

    @Test
    fun `resume of a call that is not held changes nothing`() {
        val h = on()
        h.connect(GATE, A)
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.NOT_HELD, r)
        h.coordinator.resume("gso-unknown") { r = it }
        assertEquals(SwitchResult.NOT_HELD, r)
    }

    @Test
    fun `a switch is refused while the live call is still connecting`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.resume(A) // A live, B held
        h.endedEvent(GATE, A, "gate-dev") // A ends; B is held and alone
        h.ring(GATE, C)
        h.coordinator.press(GATE, C) // C is live, PREPARING; B held
        var r: SwitchResult? = null
        h.coordinator.resume(B) { r = it }
        assertEquals(SwitchResult.LIVE_NOT_CONNECTED, r)
        assertTrue(h.media.calls[1].heldNow)
        assertEquals(1, a.closes)
    }

    // ---- live ends, held ends --------------------------------------------------------------------------------

    @Test
    fun `the live door hangs up - the held call stays held and silent until a press`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.endedEvent(ENT, B, "ent-dev")
        assertEquals(Phase.ENDED, h.snapshotOf(B)!!.phase)
        assertEquals(EndReason.REMOTE_HANGUP, h.snapshotOf(B)!!.endReason)
        assertTrue(a.heldNow)
        assertFalse(a.micOpen)
        assertFalse(a.speakerOn)
        assertEquals(listOf(true), a.holds, "no resume on its own")
        h.sched.advance(60_000) // nothing wakes it either
        assertEquals(listOf(true), a.holds)
        assertEquals(0, h.serviceStops, "the service stays for the held call")
        // The screen is told the live call ended...
        assertEquals(Phase.ENDED, h.coordinator.state().snapshot.phase)
        // ...but the phone is still busy.
        assertTrue(h.coordinator.hasActiveSession())
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.RESUMED, r)
        assertTrue(a.micOpen && a.speakerOn)
        assertEquals(Phase.IN_CALL, h.coordinator.state().snapshot.phase)
        assertEquals(CallKey2(GATE, A), CallKey2(h.coordinator.state().key!!.doorRef, h.coordinator.state().key!!.callId))
    }

    @Test
    fun `the live door hangs up by the user - the held call stays held`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.hangUp()
        assertEquals(EndReason.USER_HANGUP, h.snapshotOf(B)!!.endReason)
        assertEquals(1, h.count("portico/hangup", B))
        assertEquals(0, h.count("portico/hangup", A))
        assertTrue(a.heldNow)
        assertEquals(0, h.serviceStops)
    }

    @Test
    fun `the held call ends - quietly, the live call and the screen are untouched`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        val screen = h.coordinator.state()
        val screenEvents = mutableListOf<CallUiState>()
        h.coordinator.addListener { screenEvents += it }
        screenEvents.clear()
        h.endedEvent(GATE, A, "gate-dev")
        assertEquals(Phase.ENDED, h.snapshotOf(A)!!.phase)
        assertTrue(h.snapshotOf(A)!!.held, "it ended while held")
        assertEquals(1, a.closes)
        assertSame(screen, h.coordinator.state(), "the legacy state is not touched")
        assertTrue(screenEvents.isEmpty(), "no screen is told")
        assertEquals(0, b.closes)
        assertTrue(b.micOpen)
        assertEquals(1, h.coordinator.sessionsState().quietEnds)
        assertEquals(A, h.coordinator.sessionsState().lastQuietEnd!!.key.callId)
        assertEquals(0, h.serviceStops)
        assertEquals(1, h.coordinator.sessionsState().activeCount)
        // The last call ends: the service stops, once.
        h.endedEvent(ENT, B, "ent-dev")
        assertEquals(1, h.serviceStops)
    }

    @Test
    fun `the hold limit - 120 s on hold ends the held call quietly with a release and the live call goes on`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        val screen = h.coordinator.state()
        h.sched.advance(119_999)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        h.sched.advance(1)
        val s = h.snapshotOf(A)!!
        assertEquals(Phase.ENDED, s.phase)
        assertEquals(EndReason.HOLD_TIMEOUT, s.endReason)
        assertEquals("The call ended: on hold too long.", s.failure!!.message)
        assertEquals(1, h.count("portico/hangup", A))
        assertEquals("timeout", (h.cmd("portico/hangup", A).body["reason"] as JsonPrimitive).content)
        assertEquals(1, a.closes)
        assertSame(screen, h.coordinator.state())
        assertTrue(b.micOpen)
        assertEquals(0, h.serviceStops)
    }

    @Test
    fun `the hold limit is a constant that can be changed, and a resume cancels it`() {
        val h = HoldHarness(holdEnabled = true, config = AnswerConfig(holdLimitMs = 30_000))
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.sched.advance(29_000)
        h.coordinator.resume(A) // A back at 29 s; B goes on hold now
        h.sched.advance(29_000) // A's old limit (30 s from its hold) would have fired
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        h.sched.advance(1_001) // B's own 30 s
        assertEquals(Phase.ENDED, h.snapshotOf(B)!!.phase)
        assertEquals(EndReason.HOLD_TIMEOUT, h.snapshotOf(B)!!.endReason)
    }

    @Test
    fun `every hold starts a fresh limit`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.sched.advance(100_000)
        h.coordinator.resume(A)
        h.coordinator.resume(B) // A held again, now for a fresh 120 s
        h.sched.advance(100_000)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        h.sched.advance(20_000)
        assertEquals(EndReason.HOLD_TIMEOUT, h.snapshotOf(A)!!.endReason)
    }

    @Test
    fun `the 300 s cap of a call does not pause for hold`() {
        val h = HoldHarness(holdEnabled = true, config = AnswerConfig(holdLimitMs = 10_000_000))
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.sched.advance(299_999)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        h.sched.advance(1)
        assertEquals(EndReason.TIME_CAP, h.snapshotOf(A)!!.endReason)
    }

    @Test
    fun `end held call - hangs up the held call only, quietly`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.endHeld(B) // B is live: nothing
        assertEquals(0, h.count("portico/hangup", B))
        h.coordinator.endHeld(A)
        assertEquals(1, h.count("portico/hangup", A))
        assertEquals(EndReason.USER_HANGUP, h.snapshotOf(A)!!.endReason)
        assertEquals(1, a.closes)
        assertTrue(b.micOpen)
        assertEquals(0, h.serviceStops)
    }

    @Test
    fun `hang up ends the live call only`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.hangUp()
        assertEquals(Phase.ENDED, h.snapshotOf(B)!!.phase)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        h.coordinator.hangUp() // nothing live: the held one is not hung up by it
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        assertTrue(a.heldNow)
    }

    @Test
    fun `the second call is refused by the server - the first stays held until a press`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        h.media.calls.last().offerCb!!("v=0")
        h.cmd("portico/answer", B).error("already_answered")
        assertEquals(AnswerFailure.Kind.ALREADY_ANSWERED, h.coordinator.state().snapshot.failure!!.kind)
        assertTrue(a.heldNow, "never resumed by an event")
        assertEquals(listOf(true), a.holds)
        assertEquals(0, h.serviceStops)
        var r: SwitchResult? = null
        h.coordinator.resume(A) { r = it }
        assertEquals(SwitchResult.RESUMED, r)
        assertTrue(a.micOpen)
    }

    @Test
    fun `the second call is refused for the microphone - the first stays held, the service stays`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.mic = false
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        assertEquals(Phase.AWAITING_MIC, h.coordinator.state().snapshot.phase)
        h.coordinator.onMicResult(false)
        assertEquals(AnswerFailure.Kind.MIC_DENIED, h.coordinator.state().snapshot.failure!!.kind)
        assertTrue(a.heldNow)
        assertEquals(0, h.serviceStops, "B never started the service and A is still using it")
    }

    // ---- the service, the route, the store ------------------------------------------------------------------

    @Test
    fun `the service stops only when the last session has ended`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        assertEquals(2, h.serviceStarts)
        h.coordinator.hangUp() // B
        assertEquals(0, h.serviceStops)
        h.coordinator.resume(A)
        h.coordinator.hangUp() // A
        assertEquals(1, h.serviceStops)
    }

    @Test
    fun `the audio route is entered once and left once for any number of sessions`() {
        val h = on()
        h.connect(GATE, A)
        assertEquals(1, h.realAudio.enters)
        h.connectOnHold(ENT, B)
        assertEquals(1, h.realAudio.enters, "the second session joins the route")
        h.endedEvent(ENT, B, "ent-dev")
        assertEquals(0, h.realAudio.leaves, "the held call still needs it")
        h.endedEvent(GATE, A, "gate-dev")
        assertEquals(1, h.realAudio.leaves)
    }

    @Test
    fun `both calls are recorded for a restart, and each is taken out when its hang-up settles`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        assertEquals(listOf(A, B), h.active.loadAll().map { it.callId })
        h.coordinator.hangUp() // B
        h.cmd("portico/hangup", B).ok()
        assertEquals(listOf(A), h.active.loadAll().map { it.callId })
        h.coordinator.resume(A)
        h.coordinator.hangUp()
        h.cmd("portico/hangup", A).ok()
        assertTrue(h.active.loadAll().isEmpty())
    }

    @Test
    fun `app stopping hangs up every session`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.appStopping()
        assertEquals("timeout", (h.cmd("portico/hangup", A).body["reason"] as JsonPrimitive).content)
        assertEquals("timeout", (h.cmd("portico/hangup", B).body["reason"] as JsonPrimitive).content)
        assertEquals(EndReason.APP_STOPPED, h.snapshotOf(A)!!.endReason)
        assertEquals(EndReason.APP_STOPPED, h.snapshotOf(B)!!.endReason)
        assertEquals(1, a.closes)
        assertEquals(1, b.closes)
        assertFalse(h.coordinator.hasActiveSession())
    }

    @Test
    fun `a restart finds both leftovers and releases each, and keeps the one whose release did not settle`() {
        val h = on()
        h.active.save(ActiveCall("gate-dev", A, 1))
        h.active.save(ActiveCall("ent-dev", B, 2))
        h.coordinator.onLinkReady()
        assertEquals(2, h.cmds.count("portico/hangup"))
        assertEquals("error", (h.cmd("portico/hangup", A).body["reason"] as JsonPrimitive).content)
        h.cmd("portico/hangup", A).ok()
        h.cmd("portico/hangup", B).lost()
        assertEquals(listOf(B), h.active.loadAll().map { it.callId })
    }

    @Test
    fun `a sweep never releases a call a live session owns`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.onLinkReady()
        assertEquals(0, h.cmds.count("portico/hangup"))
    }

    // ---- link loss ----------------------------------------------------------------------------------------

    @Test
    fun `link lost and back inside the grace - both sessions recover and neither is released`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.onLinkLost()
        h.sched.advance(7_000)
        h.coordinator.onLinkReady()
        h.sched.advance(60_000)
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        assertEquals(Phase.IN_CALL, h.snapshotOf(B)!!.phase)
        assertTrue(a.heldNow)
        assertTrue(b.micOpen)
    }

    @Test
    fun `link lost past the grace - each session has its own 8 s and both end, the held one quietly`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val screen = h.coordinator.state()
        h.coordinator.onLinkLost()
        h.sched.advance(8_000)
        assertEquals(EndReason.NETWORK_LOST, h.snapshotOf(A)!!.endReason)
        assertEquals(EndReason.NETWORK_LOST, h.snapshotOf(B)!!.endReason)
        assertTrue(h.snapshotOf(A)!!.held)
        assertEquals(1, h.coordinator.sessionsState().quietEnds)
        assertEquals(EndReason.NETWORK_LOST, h.coordinator.state().snapshot.endReason, "the live call's end is the screen's")
        assertTrue(screen !== h.coordinator.state())
        assertEquals(1, h.serviceStops)
    }

    // ---- routing by call id, and the races ---------------------------------------------------------------

    @Test
    fun `events are routed by call id - another call's events do not move this one`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.onCallEvent(CallEvent.Answered("gate-dev", GATE, "gso-other"))
        h.coordinator.onCallEvent(CallEvent.Active("ent-dev", ENT, "gso-other"))
        h.coordinator.onCallEvent(CallEvent.Ended("gate-dev", GATE, "gso-other", inferred = false))
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase)
        assertEquals(Phase.IN_CALL, h.snapshotOf(B)!!.phase)
        h.endedEvent(ENT, B, "ent-dev")
        assertEquals(Phase.IN_CALL, h.snapshotOf(A)!!.phase, "B's end does not touch A")
        assertEquals(Phase.ENDED, h.snapshotOf(B)!!.phase)
    }

    @Test
    fun `a door going idle ends only the session on that door`() {
        val h = on()
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.onCallEvent(CallEvent.NoLiveCall("gate-dev", GATE))
        assertEquals(Phase.ENDED, h.snapshotOf(A)!!.phase)
        assertEquals(Phase.IN_CALL, h.snapshotOf(B)!!.phase)
        assertFalse(h.media.calls[1].heldNow)
    }

    @Test
    fun `RACE - the held door goes idle while the answer of the second call is in flight`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B) // A held, B PREPARING
        h.media.calls.last().offerCb!!("v=0") // B CLAIMING
        h.coordinator.onCallEvent(CallEvent.NoLiveCall("gate-dev", GATE)) // A's door idles now
        assertEquals(Phase.ENDED, h.snapshotOf(A)!!.phase)
        assertEquals(Phase.CLAIMING, h.snapshotOf(B)!!.phase, "B's claim is not disturbed")
        h.cmd("portico/answer", B).ok(obj("mode" to "relay"))
        h.cmd("portico/webrtc_offer", B).ok(obj("sdp" to "v=0 answer"))
        val b = h.media.calls.last()
        b.answerCb!!(true)
        b.listener.onConnected()
        assertEquals(Phase.IN_CALL, h.snapshotOf(B)!!.phase)
        assertTrue(b.micOpen)
        assertEquals(1, a.closes)
        assertEquals(0, h.serviceStops)
    }

    @Test
    fun `RACE - the live door hangs up while the second call is being answered on top of it`() {
        val h = on()
        h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        // A is held and its door hangs up (A ends quietly) while B is still PREPARING, then B's claim is refused.
        h.coordinator.onCallEvent(CallEvent.Ended("gate-dev", GATE, A, inferred = true))
        h.media.calls.last().offerCb!!("v=0")
        h.cmd("portico/answer", B).error("already_answered")
        assertFalse(h.coordinator.hasActiveSession())
        assertEquals(1, h.serviceStops, "exactly one stop, when the last session was gone")
        assertEquals(AnswerFailure.Kind.ALREADY_ANSWERED, h.coordinator.state().snapshot.failure!!.kind)
    }

    @Test
    fun `RACE - the live call's end arrives BEFORE the switch press - the press resumes the held one and holds nothing`() {
        val h = on()
        val a = h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.resume(A) // A live, B held
        h.endedEvent(GATE, A, "gate-dev") // the idle of the live door arrives first
        var r: SwitchResult? = null
        h.coordinator.resume(B) { r = it }
        assertEquals(SwitchResult.RESUMED, r)
        assertTrue(b.micOpen && b.speakerOn)
        assertEquals(listOf(true, false), b.holds, "B: held once, resumed once")
        assertEquals(listOf(true, false), a.holds, "A: held when B was answered, resumed by the first switch, and never held by the stale press")
        assertFalse(a.heldNow)
    }

    @Test
    fun `RACE - the held call's end arrives BEFORE the press to resume it - nothing is switched and the live call is not held`() {
        val h = on()
        val a = h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.coordinator.resume(A) // A live, B held
        val b = h.media.calls[1]
        h.endedEvent(ENT, B, "ent-dev")
        var r: SwitchResult? = null
        h.coordinator.resume(B) { r = it }
        assertEquals(SwitchResult.NOT_HELD, r)
        assertTrue(a.micOpen && a.speakerOn)
        assertEquals(listOf(true, false), a.holds, "A: held once then resumed, and no more")
        assertEquals(1, b.closes)
    }

    @Test
    fun `RACE - a late end for a call whose slot is gone is ignored`() {
        val h = on()
        h.connect(GATE, A)
        h.endedEvent(GATE, A, "gate-dev")
        h.ring(GATE, C)
        h.coordinator.press(GATE, C) // drops A's ended slot
        h.endedEvent(GATE, A, "gate-dev") // the stale one, again
        h.coordinator.onCallEvent(CallEvent.Ended("gate-dev", GATE, A, inferred = true))
        assertEquals(Phase.PREPARING, h.snapshotOf(C)!!.phase)
        assertNull(h.snapshotOf(A))
    }

    @Test
    fun `a double tap on Switch switches once`() {
        val h = on()
        h.connect(GATE, A)
        val b = h.connectOnHold(ENT, B)
        h.coordinator.resume(A)
        h.coordinator.resume(A) // a double tap
        assertEquals(listOf(true, false), h.media.calls[0].holds, "A: held when B was answered, resumed once; the second tap finds it live")
        assertTrue(b.heldNow)
    }

    // ---- what the screens read -----------------------------------------------------------------------------

    @Test
    fun `the sessions state lists live, held and ended, and a listener follows it`() {
        val h = on()
        val seen = mutableListOf<SessionsState>()
        h.coordinator.addSessionsListener { seen += it }
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val st = h.coordinator.sessionsState()
        assertEquals(B, st.live!!.key.callId)
        assertEquals(A, st.held!!.key.callId)
        assertEquals(2, st.activeCount)
        assertTrue(seen.zipWithNext().all { (x, y) -> y.seq > x.seq })
        assertEquals(st, seen.last())
        h.coordinator.hangUp()
        assertTrue(h.coordinator.sessionsState().sessions.first { it.key.callId == B }.ended)
        assertNotNull(h.coordinator.sessionsState().held)
    }

    @Test
    fun `an ended slot is dropped at the next press, after its owed hang-up has been tried`() {
        val h = on()
        h.cmds.connected = true
        h.connect(GATE, A)
        h.cmds.connected = false
        h.coordinator.hangUp() // the hang-up cannot be sent: owed
        assertEquals(Phase.ENDED, h.snapshotOf(A)!!.phase)
        h.cmds.connected = true
        h.ring(ENT, B)
        h.coordinator.press(ENT, B)
        assertEquals(1, h.cmds.count("portico/hangup"), "the owed release went out at the press")
        assertNull(h.snapshotOf(A))
    }

    @Test
    fun `CallUi keeps the screen open while a held call exists`() {
        val ended = CallUiState(com.meda.portico.core.push.CallKey(GATE, A), AnswerSnapshot(Phase.ENDED, EndReason.USER_HANGUP), 1)
        assertTrue(CallUi.shouldFinish(ended))
        assertTrue(CallUi.shouldFinish(ended, heldExists = false))
        assertFalse(CallUi.shouldFinish(ended, heldExists = true))
        val none = CallUiState(null, AnswerSnapshot(Phase.IDLE), 0)
        assertFalse(CallUi.shouldFinish(none, heldExists = true))
    }
}
