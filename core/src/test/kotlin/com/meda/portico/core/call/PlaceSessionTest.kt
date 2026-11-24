package com.meda.portico.core.call

import com.meda.portico.core.call.PlaceFailure.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The outgoing call against fakes: the order of messages, the error mapping, the Phase 2 rule (nothing
 * without a press), every way out through one finish(), and virtual time for every timer.
 */
class PlaceSessionTest {

    private val cmds = FakeCommands()
    private val media = FakePlaceMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private var micGranted = true
    private var wall = 5_000_000L
    private val snaps = mutableListOf<PlaceSnapshot>()
    private var micReady = 0
    private val ended = mutableListOf<PlaceSnapshot>()
    private val config = PlaceConfig()

    private val hooks = object : PlaceHooks {
        override fun onMicReady() { micReady++ }
        override fun onEnded(snapshot: PlaceSnapshot) { ended += snapshot }
    }

    private val request = PlaceRequest("talk-kitchen", "Kitchen tablet")
    private val s by lazy { PlaceSession(request, cmds, media, audio, { micGranted }, sched, { wall }, hooks, config, onChange = { snaps += it }) }

    private fun placeAndRing(callId: String = CALL) {
        s.begin()
        cmds.last("portico/content/run").ok(granted(callId))
    }

    private fun answerCall(callId: String = CALL) = s.onHouseCall(HouseCallEvent(callId, "answered", null))

    private fun fullCall(): FakePlaceLeg {
        placeAndRing()
        answerCall()
        val leg = media.legs.single()
        leg.listener.onConnected()
        return leg
    }

    private fun assertCleanedUp(leg: FakePlaceLeg? = null) {
        assertEquals(PlacePhase.ENDED, s.phase)
        if (leg != null) assertEquals(1, leg.closes, "leg closed exactly once")
        assertEquals(audio.enters, audio.leaves, "audio route left as often as entered")
        assertEquals(0, sched.pending, "no timer left armed")
        assertEquals(1, ended.size, "the ended hook ran once")
    }

    // ---- the sequence ----------------------------------------------------------------------------

    @Test
    fun `a press goes straight to content run - no confirm step - then rings, then connects`() {
        s.begin()
        assertEquals(PlacePhase.PLACING, s.phase)
        assertEquals(1, micReady, "the foreground service starts as soon as the microphone is allowed")
        val run = cmds.sent.single()
        assertEquals("portico/content/run", run.type)
        assertEquals(obj("action_id" to "talk-kitchen"), run.body, "only the action id: never an endpoint, a name or a target")
        assertTrue(media.legs.isEmpty() && audio.enters == 0, "no audio while the server is being asked")

        run.ok(granted())
        assertEquals(PlacePhase.RINGING, s.phase)
        assertEquals(CALL, s.snapshot.callId)
        assertTrue(media.legs.isEmpty() && audio.enters == 0, "the microphone stays closed while it rings")

        answerCall()
        assertEquals(PlacePhase.CONNECTING, s.phase)
        val leg = media.legs.single()
        assertEquals(FAKE_MEDIA, leg.descriptor, "the server's media descriptor goes to the leg untouched")
        assertEquals(1, audio.enters)

        leg.listener.onConnected()
        assertEquals(PlacePhase.IN_CALL, s.phase)
        assertEquals(wall, s.snapshot.connectedAtMs)
        assertEquals(listOf("portico/content/run"), cmds.types(), "nothing else is sent on the way up")
    }

    @Test
    fun `an active event while still ringing means the answered one was missed`() {
        placeAndRing()
        s.onHouseCall(HouseCallEvent(CALL, "active", null))
        assertEquals(PlacePhase.CONNECTING, s.phase)
        assertEquals(1, media.legs.size)
        s.onHouseCall(HouseCallEvent(CALL, "active", null))
        s.onHouseCall(HouseCallEvent(CALL, "answered", null))
        assertEquals(1, media.legs.size, "the leg opens once")
    }

    @Test
    fun `a ringing event changes nothing and a call without media still connects through the port`() {
        s.begin()
        cmds.last("portico/content/run").ok(granted(media = null))
        s.onHouseCall(HouseCallEvent(CALL, "ringing", null))
        assertEquals(PlacePhase.RINGING, s.phase)
        answerCall()
        assertNull(media.legs.single().descriptor)
    }

    // ---- the Phase 2 rule -------------------------------------------------------------------------

    @Test
    fun `nothing is opened or sent until begin - every other input leaves a fresh session silent`() {
        s.onMicResult(true)
        for (state in listOf("ringing", "answered", "active", "ended")) s.onHouseCall(HouseCallEvent(CALL, state, "hangup"))
        s.onDoorRinging()
        s.onLinkLost()
        s.onLinkRestored()
        s.setMuted(true)
        s.hangUp()
        s.onAppStopping()
        sched.advance(10 * 60_000)
        assertEquals(PlacePhase.IDLE, s.phase)
        assertTrue(cmds.sent.isEmpty(), "zero sends")
        assertTrue(media.legs.isEmpty())
        assertEquals(0, audio.enters)
        assertEquals(0, micReady)
        assertTrue(ended.isEmpty() && snaps.isEmpty())
    }

    @Test
    fun `a mic grant nobody asked for does not place a call`() {
        micGranted = false
        s.begin()
        assertEquals(PlacePhase.AWAITING_MIC, s.phase)
        assertTrue(cmds.sent.isEmpty(), "the microphone is asked for before anyone rings")
        s.hangUp() // the user backs out
        s.onMicResult(true) // a late dialog result
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertTrue(cmds.sent.isEmpty())
        assertEquals(0, micReady)
        assertCleanedUp()
    }

    @Test
    fun `the microphone dialog's yes places the call, its no ends it with a message`() {
        micGranted = false
        s.begin()
        s.onMicResult(true)
        assertEquals(PlacePhase.PLACING, s.phase)
        assertEquals(1, cmds.count("portico/content/run"))
        assertEquals(1, micReady)

        val denied = PlaceSession(request, FakeCommands(), media, audio, { false }, sched, { wall }, PlaceHooks.NONE, config)
        denied.begin()
        denied.onMicResult(false)
        assertEquals(Kind.MIC_DENIED, denied.snapshot.failure!!.kind)
        assertTrue(denied.snapshot.failure!!.retryable)
    }

    @Test
    fun `begin twice places once`() {
        s.begin()
        s.begin()
        cmds.last("portico/content/run").ok(granted())
        s.begin()
        assertEquals(1, cmds.count("portico/content/run"))
    }

    // ---- the placing step -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
        "not_available,NOT_AVAILABLE",
        "unknown_action,NOT_AVAILABLE",
        "unknown_command,NOT_AVAILABLE",
        "not_callable,NOT_CALLABLE",
        "target_offline,TARGET_OFFLINE",
        "target_dnd,TARGET_DND",
        "busy,BUSY",
        "declined,DECLINED",
        "no_answer,NO_ANSWER",
        "dial_disabled,DIAL_DISABLED",
        "gate_busy,GATE_BUSY",
        "yielded,DOOR_RINGING",
        "place_forbidden,FORBIDDEN",
        "unauthorized,FORBIDDEN",
        "rate_limited,RATE_LIMITED",
        "failed,FAILED",
        "something_new,OTHER",
    )
    fun `a refusal from the server ends the call with its message and sends nothing more`(code: String, kind: String) {
        s.begin()
        cmds.last("portico/content/run").error(code)
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(EndReason.FAILED, s.snapshot.endReason)
        assertEquals(Kind.valueOf(kind), s.snapshot.failure!!.kind)
        assertEquals(1, cmds.sent.size, "no hang-up: the server holds no call")
        assertTrue(media.legs.isEmpty() && audio.enters == 0)
        assertCleanedUp()
    }

    @Test
    fun `not connected sends nothing and says so`() {
        cmds.connected = false
        s.begin()
        assertEquals(Kind.NOT_CONNECTED, s.snapshot.failure!!.kind)
        assertTrue(s.snapshot.failure!!.retryable)
        assertTrue(cmds.sent.isEmpty(), "nothing is queued for later")
        assertCleanedUp()
    }

    @Test
    fun `the socket closing before the reply is a lost connection`() {
        s.begin()
        cmds.last("portico/content/run").lost()
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertEquals(Kind.NETWORK_LOST, s.snapshot.failure!!.kind)
        assertCleanedUp()
    }

    @Test
    fun `no reply in 10 seconds ends the call, and a grant that arrives later is released`() {
        s.begin()
        sched.advance(config.stepTimeoutMs)
        assertEquals(Kind.TIMEOUT, s.snapshot.failure!!.kind)
        assertCleanedUp()
        cmds.last("portico/content/run").ok(granted("hso-late"))
        val hangup = cmds.last("portico/house/hangup")
        assertEquals(obj("call_id" to "hso-late"), hangup.body, "the press was abandoned before anyone answered: the house hang-up names only the call")
        hangup.ok()
        assertFalse(s.hangupOwed)
        assertEquals(PlacePhase.ENDED, s.phase, "the session stays over")
    }

    @Test
    fun `a late refusal after the timeout sends nothing`() {
        s.begin()
        sched.advance(config.stepTimeoutMs)
        cmds.last("portico/content/run").error("busy")
        assertEquals(0, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `a result without a call id is not a call`() {
        s.begin()
        cmds.last("portico/content/run").ok(obj("ran" to "true"))
        assertEquals(PlacePhase.ENDED, s.phase)
        assertNotNull(s.snapshot.failure)
        assertEquals(0, cmds.count("portico/house/hangup"))
    }

    // ---- cancel -----------------------------------------------------------------------------------

    @Test
    fun `cancel while the server is being asked waits for the reply, then releases a grant`() {
        s.begin()
        s.hangUp()
        assertEquals(PlacePhase.PLACING, s.phase, "the reply decides")
        cmds.last("portico/content/run").ok(granted())
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertNull(s.snapshot.failure)
        assertEquals(obj("call_id" to CALL), cmds.last("portico/house/hangup").body)
        assertTrue(media.legs.isEmpty() && audio.enters == 0)
    }

    @Test
    fun `cancel while the server is being asked and the server refuses sends no hang-up`() {
        s.begin()
        s.hangUp()
        cmds.last("portico/content/run").error("busy")
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertNull(s.snapshot.failure, "the user's own cancel is not shown as a failure")
        assertEquals(0, cmds.count("portico/house/hangup"))
        assertCleanedUp()
    }

    @Test
    fun `cancel while it rings releases the call and never opens the microphone`() {
        placeAndRing()
        s.hangUp()
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertTrue(media.legs.isEmpty())
        assertEquals(0, audio.enters)
        assertCleanedUp()
    }

    @Test
    fun `a house call hangs up with the house command, never the door's portico hangup, and the body is the call id alone`() {
        placeAndRing()
        s.hangUp()
        assertEquals(0, cmds.count("portico/hangup"), "the door's hang-up does not know an hso- id")
        assertEquals(obj("call_id" to CALL), cmds.last("portico/house/hangup").body, "the server's schema refuses any other key")
    }

    @Test
    fun `a dialled call id still hangs up with the door's command and its reason`() {
        // the gate's `out-` id is not a house call: the command follows the id (HouseCommands.hangupFor)
        val (type, body) = HouseCommands.hangupFor("out-0123456789abcdef", "hangup")
        assertEquals("portico/hangup", type)
        assertEquals(obj("call_id" to "out-0123456789abcdef", "reason" to "hangup"), body)
        assertEquals("portico/house/hangup", HouseCommands.hangupFor("hso-1", "hangup").first)
    }

    @Test
    fun `a connected call ends with reason hangup, and a hang-up owed across a lost socket keeps its reason`() {
        val leg = fullCall()
        cmds.connected = false
        s.hangUp()
        assertTrue(s.hangupOwed)
        cmds.connected = true
        s.onLinkRestored()
        assertEquals(1, cmds.count("portico/house/hangup"), "the owed hang-up goes out once the socket is back")
        assertEquals(obj("call_id" to CALL), cmds.last("portico/house/hangup").body)
        assertEquals(1, leg.closes)
    }

    @Test
    fun `hang up in a connected call closes the leg and releases the call`() {
        val leg = fullCall()
        s.hangUp()
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
        // a second end of any kind changes nothing
        s.hangUp()
        s.onAppStopping()
        sched.advance(10 * 60_000)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertEquals(1, ended.size)
    }

    // ---- ringing ends -----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource("declined,DECLINED", "busy,BUSY", "no_answer,NO_ANSWER", "dnd,TARGET_DND", "error,AUDIO_NOT_CONNECTED")
    fun `the far end ending a ringing call with a reason shows that reason and sends no hang-up`(reason: String, kind: String) {
        placeAndRing()
        s.onHouseCall(HouseCallEvent(CALL, "ended", reason))
        assertEquals(EndReason.FAILED, s.snapshot.endReason)
        assertEquals(Kind.valueOf(kind), s.snapshot.failure!!.kind)
        assertTrue(s.snapshot.failure!!.message.startsWith("Kitchen tablet") || kind == "AUDIO_NOT_CONNECTED")
        assertEquals(0, cmds.count("portico/house/hangup"))
        assertTrue(media.legs.isEmpty())
        assertCleanedUp()
    }

    @Test
    fun `declined is not offered a retry and busy is`() {
        placeAndRing()
        s.onHouseCall(HouseCallEvent(CALL, "ended", "declined"))
        assertFalse(PlaceUi.controls(s.snapshot).retry)
        assertTrue(PlaceErrors.fromCode("busy", "X").retryable)
        assertTrue(PlaceErrors.fromCode("no_answer", "X").retryable)
        assertFalse(PlaceErrors.fromCode("declined", "X").retryable)
    }

    @Test
    fun `nobody answering within the ring wait ends it as no answer and releases the call`() {
        placeAndRing()
        sched.advance(config.ringTimeoutMs - 1)
        assertEquals(PlacePhase.RINGING, s.phase)
        sched.advance(1)
        assertEquals(Kind.NO_ANSWER, s.snapshot.failure!!.kind)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp()
    }

    private fun assertDoorCancelled() {
        assertEquals(PlacePhase.ENDED, s.phase)
        assertEquals(Kind.DOOR_RINGING, s.snapshot.failure!!.kind)
        assertEquals("Cancelled, the door is ringing.", s.snapshot.failure!!.message)
    }

    @Test
    fun `door ring while waiting for the microphone ends the call, nothing was sent`() {
        micGranted = false
        s.begin()
        assertEquals(PlacePhase.AWAITING_MIC, s.phase)
        s.onDoorRinging()
        assertDoorCancelled()
        assertTrue(cmds.sent.isEmpty(), "nothing was sent, so nothing to release")
        assertFalse(s.hangupOwed)
        s.onMicResult(true) // the dialog answers late: ignored
        assertTrue(cmds.sent.isEmpty())
        assertCleanedUp()
    }

    @Test
    fun `door ring while placing ends the call and releases the call the server grants afterwards`() {
        s.begin()
        val run = cmds.last("portico/content/run")
        s.onDoorRinging()
        assertDoorCancelled()
        assertEquals(0, cmds.count("portico/house/hangup"), "no call id yet")
        run.ok(granted())
        assertEquals(1, cmds.count("portico/house/hangup"), "the late grant is released at once")
        assertDoorCancelled()
        assertCleanedUp()
    }

    @Test
    fun `door ring while placing with no socket reply leaves nothing armed`() {
        s.begin()
        s.onDoorRinging()
        sched.advance(10 * 60_000)
        assertDoorCancelled() // the step timer must not rewrite the message
        assertCleanedUp()
    }

    @Test
    fun `door ring while ringing out ends the call and hangs up`() {
        placeAndRing()
        s.onDoorRinging()
        assertDoorCancelled()
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp()
    }

    @Test
    fun `door ring while connecting closes the leg, leaves audio and hangs up`() {
        placeAndRing()
        answerCall()
        assertEquals(PlacePhase.CONNECTING, s.phase)
        val leg = media.legs.single()
        s.onDoorRinging()
        assertDoorCancelled()
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertEquals(1, audio.enters)
        assertCleanedUp(leg)
    }

    @Test
    fun `door ring during a connected call ends it, closes the leg, leaves audio and hangs up`() {
        val leg = fullCall()
        assertEquals(PlacePhase.IN_CALL, s.phase)
        s.onDoorRinging()
        assertDoorCancelled()
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
        sched.advance(10 * 60_000)
        assertEquals(1, ended.size, "the call cap does not end it a second time")
    }

    @Test
    fun `door ring with no socket owes the hang-up and sends it when the link returns`() {
        placeAndRing()
        cmds.connected = false
        s.onDoorRinging()
        assertDoorCancelled()
        assertTrue(s.hangupOwed)
        assertEquals(0, cmds.count("portico/house/hangup"))
        cmds.connected = true
        s.onLinkRestored()
        assertEquals(1, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `a call with no door ring is untouched`() {
        val leg = fullCall()
        sched.advance(60_000)
        assertEquals(PlacePhase.IN_CALL, s.phase)
        assertEquals(0, cmds.count("portico/house/hangup"))
        assertEquals(0, leg.closes)
        assertNull(s.snapshot.failure)
    }

    @Test
    fun `events about another call are ignored`() {
        placeAndRing()
        s.onHouseCall(HouseCallEvent("hso-other", "ended", "declined"))
        s.onHouseCall(HouseCallEvent("hso-other", "answered", null))
        assertEquals(PlacePhase.RINGING, s.phase)
        assertTrue(media.legs.isEmpty())
    }

    // ---- connecting and talking -------------------------------------------------------------------

    @Test
    fun `no audio within 10 seconds of the answer is Could not connect`() {
        placeAndRing()
        answerCall()
        val leg = media.legs.single()
        sched.advance(config.connectTimeoutMs - 1)
        assertEquals(PlacePhase.CONNECTING, s.phase)
        sched.advance(1)
        assertEquals(Kind.AUDIO_NOT_CONNECTED, s.snapshot.failure!!.kind)
        assertEquals("Could not connect.", s.snapshot.failure!!.message)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
    }

    @Test
    fun `a leg that fails while connecting, or cannot open, ends with a message and releases the call`() {
        placeAndRing()
        answerCall()
        val leg = media.legs.single()
        leg.listener.onFailed()
        assertEquals(Kind.AUDIO_NOT_CONNECTED, s.snapshot.failure!!.kind)
        assertCleanedUp(leg)

        val s2 = PlaceSession(request, cmds, media.also { it.failOpen = true }, audio, { true }, sched, { wall })
        s2.begin()
        cmds.last("portico/content/run").ok(granted("hso-3"))
        s2.onHouseCall(HouseCallEvent("hso-3", "answered", null))
        assertEquals(Kind.MEDIA, s2.snapshot.failure!!.kind)
        assertEquals(2, cmds.count("portico/house/hangup"), "one release per call that was answered")
    }

    @Test
    fun `the 300 second cap counts from the answer and ends the call`() {
        placeAndRing()
        sched.advance(40_000)
        answerCall()
        val leg = media.legs.single()
        leg.listener.onConnected()
        sched.advance(config.callCapMs - 1)
        assertEquals(PlacePhase.IN_CALL, s.phase)
        sched.advance(1)
        assertEquals(EndReason.TIME_CAP, s.snapshot.endReason)
        assertNull(s.snapshot.failure)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
    }

    @Test
    fun `the far end hanging up ends a connected call quietly`() {
        val leg = fullCall()
        s.onHouseCall(HouseCallEvent(CALL, "ended", "hangup"))
        assertEquals(EndReason.REMOTE_HANGUP, s.snapshot.endReason)
        assertNull(s.snapshot.failure)
        assertEquals(0, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
    }

    @Test
    fun `an error end while talking is a lost call, not a call that never connected`() {
        fullCall()
        s.onHouseCall(HouseCallEvent(CALL, "ended", "error"))
        assertEquals(Kind.NETWORK_LOST, s.snapshot.failure!!.kind)
    }

    @Test
    fun `media lost in a call recovers within the grace and ends after it`() {
        val leg = fullCall()
        leg.listener.onDisconnected()
        sched.advance(config.graceMs - 1)
        leg.listener.onConnected()
        sched.advance(config.graceMs)
        assertEquals(PlacePhase.IN_CALL, s.phase)
        leg.listener.onDisconnected()
        sched.advance(config.graceMs)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
    }

    @Test
    fun `the socket lost in a connected call recovers within the grace`() {
        val leg = fullCall()
        s.onLinkLost()
        sched.advance(config.graceMs - 1)
        s.onLinkRestored()
        sched.advance(config.graceMs * 3)
        assertEquals(PlacePhase.IN_CALL, s.phase)
        s.onLinkLost()
        sched.advance(config.graceMs)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertCleanedUp(leg)
    }

    @Test
    fun `the socket lost while it rings ends at once, and the hang-up is owed until the socket is back`() {
        placeAndRing()
        cmds.connected = false
        s.onLinkLost()
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertTrue(s.hangupOwed)
        assertEquals(0, cmds.count("portico/house/hangup"), "nothing could be sent with no socket")
        cmds.connected = true
        s.onLinkRestored()
        val hangup = cmds.last("portico/house/hangup")
        hangup.ok()
        assertFalse(s.hangupOwed)
        assertCleanedUp()
    }

    @Test
    fun `an error reply to the hang-up settles it, a missing socket does not`() {
        placeAndRing()
        s.hangUp()
        cmds.last("portico/house/hangup").error("unknown_command")
        assertFalse(s.hangupOwed, "a server that cannot be told has nothing more to say")
    }

    @Test
    fun `the app stopping releases a live call and does nothing for an ended one`() {
        val leg = fullCall()
        s.onAppStopping()
        assertEquals(EndReason.APP_STOPPED, s.snapshot.endReason)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertCleanedUp(leg)
        s.onAppStopping()
        assertEquals(1, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `mute reaches the leg only while the leg exists`() {
        placeAndRing()
        s.setMuted(true)
        assertFalse(s.snapshot.muted, "nothing to mute while it rings")
        answerCall()
        val leg = media.legs.single()
        s.setMuted(true)
        assertTrue(leg.micOff && s.snapshot.muted)
    }

    @Test
    fun `no command ever carries more than the action id or the call id`() {
        val leg = fullCall()
        s.hangUp()
        for (c in cmds.sent) {
            when (c.type) {
                "portico/content/run" -> assertEquals(setOf("action_id"), c.body.keys)
                "portico/house/hangup" -> assertEquals(setOf("call_id"), c.body.keys)
                else -> throw AssertionError("unexpected command ${c.type}")
            }
        }
        assertEquals(1, leg.closes)
    }

    private companion object {
        const val CALL = "hso-1-a"
    }
}
