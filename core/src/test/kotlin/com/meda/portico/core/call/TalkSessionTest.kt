package com.meda.portico.core.call

import com.meda.portico.core.call.TalkFailure.Kind
import com.meda.portico.core.ha.TalkWireState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The talk against fakes: the order of messages, every transition, every failure, the inert rule (nothing without a
 * READY press), every way out through one finish(), and virtual time for every timer.
 */
class TalkSessionTest {

    private fun TalkRig.assertCleanedUp(leg: FakeTalkLeg? = null) {
        assertEquals(TalkPhase.ENDED, s.phase)
        if (leg != null) assertEquals(1, leg.closes, "leg closed exactly once")
        assertEquals(audio.enters, audio.leaves, "audio route left as often as entered")
        assertEquals(0, sched.pending, "no timer left armed")
        assertEquals(1, ended.size, "the ended hook ran once")
    }

    // ---- the sequence --------------------------------------------------------------------------------------

    @Test
    fun `a press goes straight to content run with the action id only, then follows the call, then attaches the audio`() {
        val r = TalkRig()
        r.s.begin()
        assertEquals(TalkPhase.STARTING, r.phase)
        assertEquals(1, r.micReady, "the foreground service starts as soon as the microphone is allowed")
        val run = r.cmds.sent.single()
        assertEquals("portico/content/run", run.type)
        assertEquals(obj("action_id" to "talk"), run.body, "only the action id: never a door, an endpoint or a name")
        assertTrue(r.media.opens == 0 && r.audio.enters == 0, "no audio while the server is being asked")

        run.ok(talkStarted())
        assertEquals(TalkPhase.CONNECTING, r.phase)
        assertEquals(TALK_CALL, r.s.snapshot.callId, "the call id comes from the run reply")
        assertFalse(r.s.snapshot.stationAnswered)

        r.s.onCallEvent(ringing())
        assertTrue(r.media.opens == 0 && r.audio.enters == 0, "the microphone stays closed while the station rings")
        assertEquals(TalkPhase.CONNECTING, r.phase)

        r.s.onCallEvent(answeredEv())
        assertEquals(TalkPhase.CONNECTING, r.phase, "answered is not audio: still connecting")
        assertTrue(r.s.snapshot.stationAnswered)
        val leg = r.media.legs.single()
        assertEquals(TALK_CALL to TALK_DOOR, leg.callId to leg.doorId, "the audio attaches to the dialled call id on the block's door")
        assertEquals(1, r.audio.enters)

        r.wall = 9_000_000L
        leg.listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, r.phase)
        assertEquals(9_000_000L, r.s.snapshot.connectedAtMs)
        assertEquals(listOf("portico/content/run"), r.types(), "so far the run is the only command: the offer is the leg's")
    }

    @Test
    fun `Stop in the call hangs up by the call id with reason hangup, through the existing portico hangup`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.s.hangUp()
        assertEquals(EndReason.USER_HANGUP, r.s.snapshot.endReason)
        assertNull(r.s.snapshot.failure)
        val hang = r.cmds.last("portico/hangup")
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "hangup"), hang.body)
        assertTrue(r.s.hangupOwed)
        hang.ok()
        assertFalse(r.s.hangupOwed)
        r.assertCleanedUp(leg)
        assertEquals(listOf("portico/content/run", "portico/hangup"), r.types())
    }

    @Test
    fun `Cancel while connecting hangs up with reason cancelled and never opens the audio`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.hangUp()
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "cancelled"), r.cmds.last("portico/hangup").body)
        assertEquals(0, r.media.opens)
        r.assertCleanedUp()
    }

    @Test
    fun `Cancel while the run is in flight waits for the reply and then releases the dialled call once`() {
        val r = TalkRig()
        r.s.begin()
        r.s.hangUp()
        assertEquals(TalkPhase.STARTING, r.phase, "the server may already be dialling: wait for its word")
        assertEquals(0, r.cmds.count("portico/hangup"))
        r.grant()
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(1, r.cmds.count("portico/hangup"))
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "cancelled"), r.cmds.last("portico/hangup").body)
        assertEquals(0, r.media.opens)
        r.assertCleanedUp()
    }

    @Test
    fun `Cancel while the run is in flight and the server refuses sends no hang-up`() {
        val r = TalkRig()
        r.s.begin()
        r.s.hangUp()
        r.cmds.last("portico/content/run").error("busy")
        assertEquals(TalkPhase.ENDED, r.phase)
        assertNull(r.s.snapshot.failure, "the user cancelled: no failure line")
        assertEquals(0, r.cmds.count("portico/hangup"))
    }

    // ---- the microphone ------------------------------------------------------------------------------------

    @Test
    fun `without the microphone the session waits for the dialog and sends nothing until it is allowed`() {
        val r = TalkRig(micGranted = false)
        r.s.begin()
        assertEquals(TalkPhase.AWAITING_MIC, r.phase)
        assertEquals(0, r.micReady)
        assertEquals(emptyList<String>(), r.types())
        r.s.onMicResult(true)
        assertEquals(TalkPhase.STARTING, r.phase)
        assertEquals(1, r.micReady)
        assertEquals(listOf("portico/content/run"), r.types())
    }

    @Test
    fun `a denied microphone ends the talk with a message and sends nothing`() {
        val r = TalkRig(micGranted = false)
        r.s.begin()
        r.s.onMicResult(false)
        assertEquals(Kind.MIC_DENIED, r.s.snapshot.failure?.kind)
        assertEquals(emptyList<String>(), r.types())
        assertEquals(0, r.micReady)
        r.assertCleanedUp()
    }

    @Test
    fun `a microphone answer nobody asked for is ignored`() {
        val r = TalkRig()
        r.s.onMicResult(true)
        r.assertUntouched()
        r.s.begin()
        r.s.onMicResult(false) // already past the dialog
        assertEquals(TalkPhase.STARTING, r.phase)
    }

    @Test
    fun `Cancel at the microphone dialog sends nothing`() {
        val r = TalkRig(micGranted = false)
        r.s.begin()
        r.s.hangUp()
        assertEquals(EndReason.USER_HANGUP, r.s.snapshot.endReason)
        assertEquals(emptyList<String>(), r.types())
    }

    // ---- the inert rule ------------------------------------------------------------------------------------

    @Test
    fun `with the gate shut a press does nothing at all, whatever follows`() {
        val r = TalkRig(gateOpen = false, micGranted = false)
        r.s.begin()
        r.s.onMicResult(true)
        r.s.hangUp()
        r.s.onCallEvent(ringing())
        r.s.onCallEvent(answeredEv())
        r.s.onCallEvent(activeEv())
        r.s.onCallEvent(endedEv())
        r.s.onCallEvent(idleEv())
        r.s.onDoorRinging()
        r.s.onLinkLost()
        r.s.onLinkRestored()
        r.s.onAppStopping()
        r.sched.advance(10 * 60_000)
        r.assertUntouched()
        // and the same with the microphone already allowed
        val r2 = TalkRig(gateOpen = false)
        r2.s.begin()
        r2.s.onMicResult(true)
        r2.sched.advance(10 * 60_000)
        r2.assertUntouched()
    }

    @Test
    fun `a button the server switches off while the microphone dialog is up sends nothing`() {
        val r = TalkRig(micGranted = false)
        r.s.begin()
        assertEquals(TalkPhase.AWAITING_MIC, r.phase)
        r.gate = false
        r.s.onMicResult(true)
        assertEquals(emptyList<String>(), r.types(), "the gate is asked again right before the run")
        assertEquals(Kind.SWITCHED_OFF, r.s.snapshot.failure?.kind)
        assertEquals(0, r.micReady)
        r.assertCleanedUp()
    }

    // ---- what the server refuses -----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
        "not_available,SWITCHED_OFF,false",
        "unavailable,SWITCHED_OFF,false",
        "not_allowed,NOT_ALLOWED,false",
        "unauthorized,NOT_ALLOWED,false",
        "no_door,NO_DOOR,false",
        "not_connected,GATE_NOT_CONNECTED,true",
        "busy,GATE_BUSY,true",
        "rate_limited,RATE_LIMITED,true",
        "failed,FAILED,true",
        "unknown_command,SERVER_TOO_OLD,false",
        "unknown_action,SERVER_TOO_OLD,false",
        "invalid_format,SERVER_TOO_OLD,false",
        "something_new,OTHER,true",
    )
    fun `a refusal ends the talk with its own message and sends nothing more`(code: String, kind: String, retryable: Boolean) {
        val r = TalkRig()
        r.s.begin()
        r.cmds.last("portico/content/run").error(code, "server text that must not be shown")
        assertNotNull(r.s.snapshot.failure)
        val f = r.s.snapshot.failure!!
        assertEquals(Kind.valueOf(kind), f.kind, code)
        assertEquals(code, f.code)
        assertEquals(retryable, f.retryable, code)
        assertFalse(f.message.contains("server text"), "the server's own words are not shown")
        assertTrue(f.message.isNotBlank())
        assertEquals(listOf("portico/content/run"), r.types())
        assertEquals(0, r.media.opens)
        r.assertCleanedUp()
    }

    @Test
    fun `every refusal has a distinct user-readable sentence ending in a full stop`() {
        val messages = listOf("not_available", "not_allowed", "no_door", "not_connected", "busy", "rate_limited", "failed", "unknown_command")
            .map { TalkErrors.fromCode(it).message }
        assertEquals(messages.size, messages.toSet().size)
        for (m in messages + TalkErrors.fromCode("zzz").message) assertTrue(m.endsWith("."), m)
        assertTrue(TalkErrors.fromCode("zzz").message.contains("zzz"))
    }

    @Test
    fun `no socket and a lost socket are told apart from a refusal`() {
        val a = TalkRig()
        a.cmds.connected = false
        a.s.begin()
        assertEquals(Kind.LINK_DOWN, a.s.snapshot.failure?.kind)
        assertEquals(TalkPhase.ENDED, a.phase)

        val b = TalkRig()
        b.s.begin()
        b.cmds.last("portico/content/run").lost()
        assertEquals(Kind.NETWORK_LOST, b.s.snapshot.failure?.kind)
        assertEquals(EndReason.NETWORK_LOST, b.s.snapshot.endReason)
        assertEquals(0, b.cmds.count("portico/hangup"))
    }

    @Test
    fun `a reply with no call id, and a reply that says it hung up an old call, are failures`() {
        val a = TalkRig()
        a.s.begin()
        a.cmds.last("portico/content/run").ok(buildJsonObject { put("ran", true) })
        assertEquals(Kind.OTHER, a.s.snapshot.failure?.kind)
        assertEquals("malformed_result", a.s.snapshot.failure?.code)

        val b = TalkRig()
        b.s.begin()
        b.cmds.last("portico/content/run").ok(buildJsonObject {
            put("ran", true)
            put("call_id", "old")
            put("hung_up", true)
        })
        assertEquals(Kind.PREVIOUS_ENDED, b.s.snapshot.failure?.kind)
        assertEquals(0, b.media.opens)
        assertEquals(0, b.cmds.count("portico/hangup"), "the server already hung it up")
    }

    @Test
    fun `a run that gets no reply in 10 s fails, and a call dialled after that is released at once`() {
        val r = TalkRig()
        r.s.begin()
        r.sched.advance(r.config.runTimeoutMs)
        assertEquals(Kind.TIMEOUT, r.s.snapshot.failure?.kind)
        assertEquals(0, r.cmds.count("portico/hangup"))
        r.grant("late-call")
        val hang = r.cmds.last("portico/hangup")
        assertEquals(obj("call_id" to "late-call", "reason" to "cancelled"), hang.body)
        assertEquals(0, r.media.opens)
    }

    // ---- the call's events -----------------------------------------------------------------------------------

    @Test
    fun `active counts as answered, and a second answered opens nothing twice`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(activeEv())
        r.s.onCallEvent(answeredEv())
        r.s.onCallEvent(activeEv())
        assertEquals(1, r.media.opens)
        assertEquals(1, r.audio.enters)
    }

    @Test
    fun `events for another call or another door do nothing`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(answeredEv(call = "someone-else"))
        r.s.onCallEvent(endedEv(call = "someone-else"))
        r.s.onCallEvent(answeredEv(door = "http://other"))
        r.s.onCallEvent(endedEv(door = "http://other"))
        assertEquals(TalkPhase.CONNECTING, r.phase)
        assertFalse(r.s.snapshot.stationAnswered)
        assertEquals(0, r.media.opens)
    }

    @Test
    fun `an event that arrives before the run reply is kept and replayed once the call id is known`() {
        val r = TalkRig()
        r.s.begin()
        r.s.onCallEvent(ringing())
        r.s.onCallEvent(answeredEv())
        r.s.onCallEvent(answeredEv(call = "not-ours"))
        assertEquals(0, r.media.opens, "no call id yet: nothing can be matched")
        r.grant()
        assertEquals(1, r.media.opens, "the early answer is applied to the granted call")
        assertEquals(TALK_CALL, r.media.legs.single().callId)
        assertTrue(r.s.snapshot.stationAnswered)
    }

    @Test
    fun `an early ring of another call is not mistaken for a visitor`() {
        val r = TalkRig()
        r.s.begin()
        r.s.onCallEvent(ringing())
        r.grant()
        assertEquals(TalkPhase.CONNECTING, r.phase, "the ring was our own call's")
    }

    @Test
    fun `the station ending the call before it answered is a failure and sends no hang-up`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(endedEv())
        assertEquals(Kind.STATION_ENDED, r.s.snapshot.failure?.kind)
        assertEquals(0, r.cmds.count("portico/hangup"), "the server says it is over: nothing to release")
        assertEquals(0, r.media.opens)
        r.assertCleanedUp()
    }

    @Test
    fun `the station hanging up in the call ends it without a failure and without a hang-up`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.s.onCallEvent(endedEv())
        assertEquals(EndReason.REMOTE_HANGUP, r.s.snapshot.endReason)
        assertNull(r.s.snapshot.failure)
        assertEquals(0, r.cmds.count("portico/hangup"))
        r.assertCleanedUp(leg)
        assertEquals("The gate hung up.", TalkErrors.endLine(r.s.snapshot))
    }

    @Test
    fun `an inferred end of the followed call ends it too`() {
        val r = TalkRig()
        r.fullTalk()
        r.s.onCallEvent(endedEv(inferred = true))
        assertEquals(EndReason.REMOTE_HANGUP, r.s.snapshot.endReason)
    }

    @Test
    fun `an idle door snapshot does not end a talk`() {
        val r = TalkRig()
        r.fullTalk()
        r.s.onCallEvent(idleEv())
        assertEquals(TalkPhase.IN_CALL, r.phase)
    }

    @Test
    fun `another call ringing on the door ends the talk and releases it, a snapshot of it does not`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(ringing(call = "visitor", snapshot = true))
        assertEquals(TalkPhase.CONNECTING, r.phase)
        r.s.onCallEvent(ringing(call = "visitor"))
        assertEquals(Kind.DOOR_RINGING, r.s.snapshot.failure?.kind)
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "cancelled"), r.cmds.last("portico/hangup").body)
        r.assertCleanedUp()
    }

    // ---- timeouts ----------------------------------------------------------------------------------------------

    @Test
    fun `the station not answering in 20 seconds ends the talk, says so and cancels the call`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.sched.advance(r.config.connectTimeoutMs - 1)
        assertEquals(TalkPhase.CONNECTING, r.phase)
        r.sched.advance(1)
        assertEquals(Kind.NO_ANSWER, r.s.snapshot.failure?.kind)
        assertEquals("The gate did not answer.", r.s.snapshot.failure?.message)
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "cancelled"), r.cmds.last("portico/hangup").body)
        r.assertCleanedUp()
    }

    @Test
    fun `the 20 seconds run from the send, so a slow reply leaves the rest of the wait only`() {
        val r = TalkRig()
        r.s.begin()
        r.sched.advance(8_000)
        r.grant()
        r.sched.advance(11_999)
        assertEquals(TalkPhase.CONNECTING, r.phase)
        r.sched.advance(1)
        assertEquals(Kind.NO_ANSWER, r.s.snapshot.failure?.kind)
    }

    @Test
    fun `the answer cancels the 20 second wait`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(answeredEv())
        assertEquals(2, r.sched.pending, "the 20 s wait and the run timer are gone: only the cap and the audio timer are armed")
        r.sched.advance(r.config.audioTimeoutMs - 1)
        assertEquals(TalkPhase.CONNECTING, r.phase)
        assertNull(r.s.snapshot.failure)
    }

    @Test
    fun `no audio within 10 seconds of the answer ends the talk and hangs up`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onCallEvent(answeredEv())
        val leg = r.media.legs.single()
        r.sched.advance(r.config.audioTimeoutMs)
        assertEquals(Kind.AUDIO_NOT_CONNECTED, r.s.snapshot.failure?.kind)
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "hangup"), r.cmds.last("portico/hangup").body)
        r.assertCleanedUp(leg)
    }

    @Test
    fun `a talk is capped at 120 seconds from the answer`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.sched.advance(r.config.callCapMs - 1)
        assertEquals(TalkPhase.IN_CALL, r.phase)
        r.sched.advance(1)
        assertEquals(EndReason.TIME_CAP, r.s.snapshot.endReason)
        assertNull(r.s.snapshot.failure)
        assertEquals("hangup", (r.cmds.last("portico/hangup").body["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
        r.assertCleanedUp(leg)
        assertEquals("Time limit reached.", TalkErrors.endLine(r.s.snapshot))
    }

    // ---- the link and the media --------------------------------------------------------------------------------

    @Test
    fun `losing the link in the call waits 8 seconds, then ends and owes the hang-up`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.s.onLinkLost()
        r.sched.advance(r.config.graceMs - 1)
        assertEquals(TalkPhase.IN_CALL, r.phase)
        r.cmds.connected = false
        r.sched.advance(1)
        assertEquals(Kind.NETWORK_LOST, r.s.snapshot.failure?.kind)
        assertTrue(r.s.hangupOwed, "no socket: the hang-up is owed")
        r.cmds.connected = true
        r.s.onLinkRestored()
        val hang = r.cmds.last("portico/hangup")
        assertEquals(obj("call_id" to TALK_CALL, "reason" to "hangup"), hang.body)
        hang.ok()
        assertFalse(r.s.hangupOwed)
        r.assertCleanedUp(leg)
    }

    @Test
    fun `the link coming back within the grace keeps the talk`() {
        val r = TalkRig()
        r.fullTalk()
        r.s.onLinkLost()
        r.sched.advance(5_000)
        r.s.onLinkRestored()
        r.sched.advance(60_000)
        assertEquals(TalkPhase.IN_CALL, r.phase)
    }

    @Test
    fun `a lost link while connecting gets the same grace`() {
        val r = TalkRig()
        r.s.begin()
        r.grant()
        r.s.onLinkLost()
        r.sched.advance(r.config.graceMs)
        assertEquals(Kind.NETWORK_LOST, r.s.snapshot.failure?.kind)
    }

    @Test
    fun `media dropping in the call gets the grace, and coming back cancels it`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        leg.listener.onDisconnected()
        r.sched.advance(4_000)
        leg.listener.onConnected()
        r.sched.advance(60_000)
        assertEquals(TalkPhase.IN_CALL, r.phase)
        leg.listener.onDisconnected()
        r.sched.advance(r.config.graceMs)
        assertEquals(Kind.NETWORK_LOST, r.s.snapshot.failure?.kind)
    }

    @Test
    fun `media failing is final in the call and an audio failure while connecting`() {
        val a = TalkRig()
        val leg = a.fullTalk()
        leg.listener.onFailed()
        assertEquals(Kind.NETWORK_LOST, a.s.snapshot.failure?.kind)
        a.assertCleanedUp(leg)

        val b = TalkRig()
        b.s.begin()
        b.grant()
        b.s.onCallEvent(answeredEv())
        b.media.legs.single().listener.onFailed()
        assertEquals(Kind.AUDIO_NOT_CONNECTED, b.s.snapshot.failure?.kind)
        assertEquals(1, b.cmds.count("portico/hangup"))
    }

    @Test
    fun `a media layer that cannot open ends the talk with a message and releases the call`() {
        val r = TalkRig()
        r.media.failOpen = true
        r.s.begin()
        r.grant()
        r.s.onCallEvent(answeredEv())
        assertEquals(Kind.MEDIA, r.s.snapshot.failure?.kind)
        assertEquals(1, r.cmds.count("portico/hangup"))
        r.assertCleanedUp()
    }

    @Test
    fun `a block with no door has nothing to attach the audio to`() {
        val r = TalkRig(doorId = null)
        r.s.begin()
        r.grant()
        r.s.onCallEvent(answeredEv())
        assertEquals(Kind.MEDIA, r.s.snapshot.failure?.kind)
        assertEquals(0, r.media.opens)
        assertEquals(1, r.cmds.count("portico/hangup"))
    }

    // ---- other ways out -----------------------------------------------------------------------------------------

    @Test
    fun `a door ringing ends the talk in every live state`() {
        val mic = TalkRig(micGranted = false)
        mic.s.begin()
        mic.s.onDoorRinging()
        assertEquals(Kind.DOOR_RINGING, mic.s.snapshot.failure?.kind)
        assertEquals(emptyList<String>(), mic.types())

        val starting = TalkRig()
        starting.s.begin()
        starting.s.onDoorRinging()
        assertEquals(Kind.DOOR_RINGING, starting.s.snapshot.failure?.kind)
        starting.grant()
        assertEquals(1, starting.cmds.count("portico/hangup"), "the grant that arrives after is released")

        val call = TalkRig()
        val leg = call.fullTalk()
        call.s.onDoorRinging()
        assertEquals(1, call.cmds.count("portico/hangup"))
        call.assertCleanedUp(leg)
        assertEquals("Cancelled, the door is ringing.", TalkErrors.endLine(call.s.snapshot))
    }

    @Test
    fun `the app stopping releases the call`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.s.onAppStopping()
        assertEquals(EndReason.APP_STOPPED, r.s.snapshot.endReason)
        assertEquals(1, r.cmds.count("portico/hangup"))
        r.assertCleanedUp(leg)
        assertNull(TalkErrors.endLine(r.s.snapshot))
    }

    @Test
    fun `every way out goes through one finish - a second end does nothing`() {
        val r = TalkRig()
        val leg = r.fullTalk()
        r.s.hangUp()
        r.s.hangUp()
        r.s.onDoorRinging()
        r.s.onAppStopping()
        r.s.onCallEvent(endedEv())
        leg.listener.onFailed()
        r.sched.advance(10 * 60_000)
        assertEquals(1, r.cmds.count("portico/hangup"))
        assertEquals(EndReason.USER_HANGUP, r.s.snapshot.endReason)
        r.assertCleanedUp(leg)
    }

    @Test
    fun `any reply to the hang-up settles it, even unknown_call`() {
        val r = TalkRig()
        r.fullTalk()
        r.s.hangUp()
        r.cmds.last("portico/hangup").error("unknown_call")
        assertFalse(r.s.hangupOwed)
    }

    @Test
    fun `a listener that throws does not break the session`() {
        val r = TalkRig()
        val s = TalkSession(r.request, r.cmds, r.media, r.audio, { true }, r.sched, { 0L }, { true }, onChange = { error("boom") })
        s.begin()
        assertEquals(TalkPhase.STARTING, s.phase)
    }

    @Test
    fun `a press is a no-op once the session has started`() {
        val r = TalkRig()
        r.s.begin()
        r.s.begin()
        assertEquals(1, r.cmds.count("portico/content/run"))
    }

    @Test
    fun `the end line is only for a talk that ended on its own`() {
        assertNull(TalkErrors.endLine(TalkSnapshot(TalkPhase.IN_CALL)))
        assertNull(TalkErrors.endLine(TalkSnapshot(TalkPhase.ENDED, EndReason.USER_HANGUP)))
        assertEquals(TalkMessages.NO_ANSWER, TalkErrors.endLine(TalkSnapshot(TalkPhase.ENDED, EndReason.FAILED, TalkErrors.noAnswer())))
    }

    // ---- P3-C: talk_state from the calls stream ----------------------------------------------------------------

    private val outId = "out-0123456789abcdef"

    private fun ts(state: TalkWireState, reason: String? = null, call: String = outId, door: String = TALK_DOOR) =
        com.meda.portico.core.ha.TalkStateEvent(door, call, state, reason)

    private fun TalkRig.connecting(call: String = outId) {
        s.begin()
        grant(call)
        assertEquals(TalkPhase.CONNECTING, phase)
    }

    @Test
    fun `talk_state dialing changes nothing, in_call attaches the audio to the out- id, then the audio makes it a call`() {
        val r = TalkRig()
        r.connecting()
        val snapsBefore = r.snaps.size
        r.s.onTalkState(ts(TalkWireState.DIALING))
        assertEquals(snapsBefore, r.snaps.size, "dialing is the state the session is already in")
        assertEquals(0, r.media.opens)
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        assertTrue(r.s.snapshot.stationAnswered)
        assertEquals(outId to TALK_DOOR, r.media.legs.single().let { it.callId to it.doorId })
        assertEquals(TalkPhase.CONNECTING, r.phase, "answered is not audio")
        r.media.legs.single().listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, r.phase)
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        assertEquals(1, r.media.opens, "a repeat opens nothing")
    }

    @Test
    fun `talk_state ended in the call is the gate hanging up - ended with no failure and no hang-up sent`() {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        val leg = r.media.legs.single()
        leg.listener.onConnected()
        r.s.onTalkState(ts(TalkWireState.ENDED, "hangup"))
        assertEquals(EndReason.REMOTE_HANGUP, r.s.snapshot.endReason)
        assertNull(r.s.snapshot.failure)
        assertEquals(0, r.cmds.count("portico/hangup"), "the server says it is over: nothing to release")
        r.assertCleanedUp(leg)
    }

    @Test
    fun `talk_state ended with time_cap in the call is the time cap`() {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        r.media.legs.single().listener.onConnected()
        r.s.onTalkState(ts(TalkWireState.ENDED, "time_cap"))
        assertEquals(EndReason.TIME_CAP, r.s.snapshot.endReason)
        assertEquals(TalkMessages.TIME_CAP, TalkErrors.endLine(r.s.snapshot))
    }

    @ParameterizedTest
    @CsvSource(
        "busy,GATE_BUSY",
        "BUSY,GATE_BUSY",
        "not_connected,GATE_NOT_CONNECTED",
        "failed,FAILED",
        "not_available,SWITCHED_OFF",
        "no_answer,NO_ANSWER",
        "timeout,NO_ANSWER",
        "declined,STATION_ENDED",
        "cancelled,STATION_ENDED",
        "brand_new_reason,STATION_ENDED",
    )
    fun `talk_state ended before the station answered maps its reason to B7b's failure lines`(reason: String, kind: String) {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.ENDED, reason))
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(EndReason.FAILED, r.s.snapshot.endReason)
        assertEquals(Kind.valueOf(kind), r.s.snapshot.failure!!.kind)
        assertEquals(0, r.cmds.count("portico/hangup"))
        assertEquals(0, r.media.opens)
        r.assertCleanedUp()
    }

    @Test
    fun `talk_state ended with no reason before the station answered is the gate ending the call`() {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.ENDED))
        assertEquals(TalkMessages.STATION_ENDED, TalkErrors.endLine(r.s.snapshot))
    }

    @Test
    fun `a talk_state for a call this session did not start is ignored, whatever it says`() {
        val r = TalkRig()
        r.connecting()
        val other = "out-fedcba9876543210"
        r.s.onTalkState(ts(TalkWireState.IN_CALL, call = other))
        r.s.onTalkState(ts(TalkWireState.ENDED, "busy", call = other))
        assertEquals(TalkPhase.CONNECTING, r.phase)
        assertFalse(r.s.snapshot.stationAnswered)
        assertEquals(0, r.media.opens)
        assertEquals(0, r.cmds.count("portico/hangup"))
    }

    @Test
    fun `a talk_state for another door is ignored`() {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.ENDED, "busy", door = "http://192.168.1.9:8091"))
        assertEquals(TalkPhase.CONNECTING, r.phase)
    }

    @Test
    fun `with no talk live every talk_state is ignored - idle, waiting for the microphone, and after the end`() {
        val idle = TalkRig()
        for (st in TalkWireState.entries) idle.s.onTalkState(ts(st, "busy"))
        idle.assertUntouched()

        val mic = TalkRig(micGranted = false)
        mic.s.begin()
        assertEquals(TalkPhase.AWAITING_MIC, mic.phase)
        for (st in TalkWireState.entries) mic.s.onTalkState(ts(st, "busy"))
        assertEquals(TalkPhase.AWAITING_MIC, mic.phase)
        assertEquals(emptyList<String>(), mic.cmds.types())

        val done = TalkRig()
        done.connecting()
        done.s.hangUp()
        val sent = done.cmds.sent.size
        val snaps = done.snaps.size
        for (st in TalkWireState.entries) done.s.onTalkState(ts(st, "busy"))
        assertEquals(sent, done.cmds.sent.size, "an ended session sends nothing more")
        assertEquals(snaps, done.snaps.size)
        assertEquals(0, done.media.opens)
    }

    @Test
    fun `a talk_state that arrives before the run reply is kept and replayed once the id is known`() {
        val r = TalkRig()
        r.s.begin()
        r.s.onTalkState(ts(TalkWireState.DIALING))
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        assertEquals(0, r.media.opens, "no id yet")
        r.grant(outId)
        assertTrue(r.s.snapshot.stationAnswered)
        assertEquals(outId, r.media.legs.single().callId)
    }

    @Test
    fun `an early talk_state for some other call is dropped on the replay`() {
        val r = TalkRig()
        r.s.begin()
        r.s.onTalkState(ts(TalkWireState.ENDED, "busy", call = "out-fedcba9876543210"))
        r.grant(outId)
        assertEquals(TalkPhase.CONNECTING, r.phase)
    }

    @Test
    fun `an early ended replays as the end of the call`() {
        val r = TalkRig()
        r.s.begin()
        r.s.onTalkState(ts(TalkWireState.ENDED, "busy"))
        r.grant(outId)
        assertEquals(TalkPhase.ENDED, r.phase)
        assertEquals(Kind.GATE_BUSY, r.s.snapshot.failure!!.kind)
    }

    @Test
    fun `talk_state and the old call events can both arrive - the call is attached once`() {
        val r = TalkRig()
        r.connecting()
        r.s.onTalkState(ts(TalkWireState.IN_CALL))
        r.s.onCallEvent(answeredEv(outId))
        r.s.onCallEvent(activeEv(outId))
        assertEquals(1, r.media.opens)
    }

    @Test
    fun `the talk_state mapping helpers`() {
        assertEquals(Kind.GATE_BUSY, TalkErrors.fromTalkEnd("busy").kind)
        assertEquals(Kind.STATION_ENDED, TalkErrors.fromTalkEnd(null).kind)
        assertTrue(TalkErrors.isTimeCap("time_cap") && TalkErrors.isTimeCap("CAP"))
        assertFalse(TalkErrors.isTimeCap("hangup") || TalkErrors.isTimeCap(null))
    }
}
