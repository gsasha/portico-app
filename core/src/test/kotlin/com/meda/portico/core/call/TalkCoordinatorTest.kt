package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The talk path as the app wires it: one session, the gate, the other calls, the line that stays 4 seconds. */
class TalkCoordinatorTest {

    private class Rig(gateOpen: Boolean = true) {
        val cmds = FakeCommands()
        val media = FakeTalkMedia()
        val audio = FakeAudio()
        val sched = FakeScheduler()
        val later = FakeScheduler()
        var gate = gateOpen
        var paused = false
        var otherLive = false
        var sessions = 0
        var serviceStarts = 0
        var serviceStops = 0
        var gateThrows = false
        var talkOvers = 0
        val seen = mutableListOf<TalkUiState>()

        val c = TalkCoordinator(
            newSession = { request, hooks, g, onChange ->
                sessions++
                TalkSession(request, cmds, media, audio, { true }, sched, { 1L }, g, hooks, onChange = onChange)
            },
            post = { it() },
            serviceStart = { serviceStarts++ },
            serviceStop = { serviceStops++ },
            paused = { paused },
            otherCallLive = { otherLive },
            gate = { if (gateThrows) error("unreadable") else gate },
            later = later,
            onTalkOver = { talkOvers++ },
        )

        init {
            c.addListener { seen += it }
        }

        val request = TalkRequest("talk", "Talk", TALK_DOOR)
        fun grant() = cmds.last("portico/content/run").ok(talkStarted())
    }

    @Test
    fun `a press of a button the server does not call ready does nothing - no session, no state, no command`() {
        val r = Rig(gateOpen = false)
        repeat(50) { r.c.press(r.request) }
        r.c.onMicResult(true)
        r.c.hangUp()
        r.c.onCallEvent(answeredEv())
        r.c.onDoorRinging()
        r.c.endForDoorAnswer()
        r.c.onLinkLost()
        r.c.onLinkReady()
        r.c.appStopping()
        r.later.advance(60_000)
        r.sched.advance(60_000)
        assertEquals(0, r.sessions)
        assertEquals(emptyList<String>(), r.cmds.types())
        assertEquals(0, r.media.opens + r.audio.enters + r.serviceStarts + r.serviceStops)
        assertEquals(TalkPhase.IDLE, r.c.state().snapshot.phase)
        assertEquals(1, r.seen.size, "only the listener's first delivery of the idle state")
        assertNull(r.c.state().request)
    }

    @Test
    fun `an unreadable document never means ready`() {
        val r = Rig()
        r.gateThrows = true
        r.c.press(r.request)
        assertEquals(0, r.sessions)
        assertEquals(emptyList<String>(), r.cmds.types())
    }

    @Test
    fun `a ready press starts one session and the service, the run carries the action id only`() {
        val r = Rig()
        r.c.press(r.request)
        assertEquals(1, r.sessions)
        assertEquals(1, r.serviceStarts)
        assertEquals(TalkPhase.STARTING, r.c.state().snapshot.phase)
        assertTrue(r.c.state().isLive)
        assertEquals(obj("action_id" to "talk"), r.cmds.last("portico/content/run").body)
    }

    @Test
    fun `the button pressed again while its talk is live is the same talk, not a second one`() {
        val r = Rig()
        r.c.press(r.request)
        r.c.press(r.request)
        assertEquals(1, r.sessions)
        assertEquals(1, r.cmds.count("portico/content/run"))
    }

    @Test
    fun `a different Talk button while one is live is refused with a line`() {
        val r = Rig()
        r.c.press(r.request)
        r.c.press(TalkRequest("talk-2", "Talk 2", TALK_DOOR))
        assertEquals(1, r.sessions)
        val s = r.c.state()
        assertEquals("talk-2", s.request?.actionId)
        assertEquals(TalkFailure.Kind.ANOTHER_CALL, s.snapshot.failure?.kind)
        assertEquals(1, r.cmds.count("portico/content/run"))
    }

    @Test
    fun `another call live or the phone paused refuses the press and sends nothing`() {
        val a = Rig()
        a.otherLive = true
        a.c.press(a.request)
        assertEquals(TalkFailure.Kind.ANOTHER_CALL, a.c.state().snapshot.failure?.kind)
        assertEquals(0, a.sessions)
        assertEquals(emptyList<String>(), a.cmds.types())

        val b = Rig()
        b.paused = true
        b.c.press(b.request)
        assertEquals(TalkFailure.Kind.PAUSED, b.c.state().snapshot.failure?.kind)
        assertEquals(0, b.sessions)
        assertEquals(emptyList<String>(), b.cmds.types())
    }

    @Test
    fun `the whole talk through the coordinator, and the service stops when it ends`() {
        val r = Rig()
        r.c.press(r.request)
        r.grant()
        r.c.onCallEvent(answeredEv())
        r.media.legs.single().listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, r.c.state().snapshot.phase)
        r.c.hangUp()
        assertEquals(TalkPhase.ENDED, r.c.state().snapshot.phase)
        assertEquals(1, r.serviceStops)
        assertEquals(listOf("portico/content/run", "portico/hangup"), r.cmds.types())
    }

    @Test
    fun `the line saying why it ended stays 4 seconds and then the state is idle again`() {
        val r = Rig()
        r.c.press(r.request)
        r.cmds.last("portico/content/run").error("busy")
        assertEquals(TalkPhase.ENDED, r.c.state().snapshot.phase)
        assertEquals("The gate is busy.", TalkErrors.endLine(r.c.state().snapshot))
        r.later.advance(3_999)
        assertEquals(TalkPhase.ENDED, r.c.state().snapshot.phase)
        r.later.advance(1)
        assertEquals(TalkPhase.IDLE, r.c.state().snapshot.phase)
        assertNull(r.c.state().request)
    }

    @Test
    fun `the document is read again when a talk ends and when its line goes - Home must not keep a mid-call in_call`() {
        val r = Rig()
        r.c.press(r.request)
        r.grant()
        r.c.onCallEvent(answeredEv())
        assertEquals(0, r.talkOvers, "nothing while the call is going")
        r.c.hangUp()
        assertEquals(1, r.talkOvers, "at the end")
        r.later.advance(TalkConfig().endedShownMs)
        assertEquals(2, r.talkOvers, "and once the hang-up has reached the server")
    }

    @Test
    fun `a press that is refused also reads the document again - the button may be stale`() {
        val r = Rig()
        r.c.press(r.request)
        r.cmds.last("portico/content/run").error("busy")
        assertEquals(1, r.talkOvers)
    }

    @Test
    fun `a throwing refresh never stops the talk path`() {
        val r = Rig()
        val c = TalkCoordinator(
            newSession = { request, hooks, g, onChange -> TalkSession(request, r.cmds, r.media, r.audio, { true }, r.sched, { 1L }, g, hooks, onChange = onChange) },
            post = { it() }, serviceStart = {}, serviceStop = {}, paused = { false }, gate = { true }, later = r.later,
            onTalkOver = { error("boom") },
        )
        c.press(r.request)
        r.cmds.last("portico/content/run").error("busy")
        assertEquals(TalkPhase.ENDED, c.state().snapshot.phase)
        r.later.advance(TalkConfig().endedShownMs)
        assertEquals(TalkPhase.IDLE, c.state().snapshot.phase)
    }

    @Test
    fun `an old line's expiry does not clear a newer talk`() {
        val r = Rig()
        r.c.press(r.request)
        r.cmds.sent.last().error("busy")
        r.later.advance(2_000)
        r.c.press(r.request) // a new press while the old line shows
        assertEquals(2, r.sessions)
        r.later.advance(2_000) // the first line's 4 s are up
        assertEquals(TalkPhase.STARTING, r.c.state().snapshot.phase, "the new talk is not cleared by the old line's timer")
    }

    @Test
    fun `the door answer, a door ring and a call event reach the live session`() {
        val a = Rig()
        a.c.press(a.request)
        a.grant()
        a.c.endForDoorAnswer()
        assertEquals(TalkFailure.Kind.DOOR_RINGING, a.c.state().snapshot.failure?.kind)
        assertEquals(1, a.cmds.count("portico/hangup"))

        val b = Rig()
        b.c.press(b.request)
        b.grant()
        b.c.onDoorRinging()
        assertEquals(TalkFailure.Kind.DOOR_RINGING, b.c.state().snapshot.failure?.kind)

        val c = Rig()
        c.c.press(c.request)
        c.grant()
        c.c.onCallEvent(endedEv())
        assertEquals(TalkFailure.Kind.STATION_ENDED, c.c.state().snapshot.failure?.kind)
    }

    @Test
    fun `microphone result and link events reach the session`() {
        val r = Rig()
        val noMic = TalkCoordinator(
            newSession = { request, hooks, g, onChange -> TalkSession(request, r.cmds, r.media, r.audio, { false }, r.sched, { 1L }, g, hooks, onChange = onChange) },
            post = { it() }, serviceStart = {}, serviceStop = {}, paused = { false }, gate = { true }, later = r.later,
        )
        noMic.press(r.request)
        assertEquals(TalkPhase.AWAITING_MIC, noMic.state().snapshot.phase)
        assertEquals(1, noMic.state().generation)
        noMic.onMicResult(true)
        assertEquals(TalkPhase.STARTING, noMic.state().snapshot.phase)
        r.grant()
        noMic.onLinkLost()
        r.sched.advance(TalkConfig().graceMs)
        assertEquals(TalkFailure.Kind.NETWORK_LOST, noMic.state().snapshot.failure?.kind)
    }

    @Test
    fun `the app stopping releases a live talk`() {
        val r = Rig()
        r.c.press(r.request)
        r.grant()
        r.c.appStopping()
        assertEquals(EndReason.APP_STOPPED, r.c.state().snapshot.endReason)
        assertEquals(1, r.cmds.count("portico/hangup"))
        assertFalse(r.c.state().isLive)
    }

    @Test
    fun `each attempt has its own generation`() {
        val r = Rig()
        r.c.press(r.request)
        val first = r.c.state().generation
        r.cmds.last("portico/content/run").error("busy")
        r.c.press(r.request)
        assertTrue(r.c.state().generation > first)
    }

    // ---- P3-C ----------------------------------------------------------------------------------------------------

    private val outId = "out-0123456789abcdef"

    private fun ts(state: com.meda.portico.core.ha.TalkWireState, reason: String? = null, call: String = outId) =
        com.meda.portico.core.ha.TalkStateEvent(TALK_DOOR, call, state, reason)

    @Test
    fun `a talk_state with no session reaches nothing - no session, no state, no command`() {
        val r = Rig()
        for (st in com.meda.portico.core.ha.TalkWireState.entries) r.c.onTalkState(ts(st, "busy"))
        r.later.advance(60_000)
        r.sched.advance(60_000)
        assertEquals(0, r.sessions)
        assertEquals(emptyList<String>(), r.cmds.types())
        assertEquals(0, r.media.opens + r.audio.enters + r.serviceStarts + r.serviceStops)
        assertEquals(TalkPhase.IDLE, r.c.state().snapshot.phase)
        assertEquals(1, r.seen.size, "only the listener's first delivery of the idle state")
    }

    @Test
    fun `a talk_state moves the live session, and the service stops when it ends`() {
        val r = Rig()
        r.c.press(r.request)
        r.cmds.last("portico/content/run").ok(talkStarted(outId))
        r.c.onTalkState(ts(com.meda.portico.core.ha.TalkWireState.IN_CALL))
        assertEquals(1, r.media.opens)
        r.c.onTalkState(ts(com.meda.portico.core.ha.TalkWireState.ENDED, "hangup"))
        assertEquals(TalkPhase.ENDED, r.c.state().snapshot.phase)
        assertEquals(EndReason.REMOTE_HANGUP, r.c.state().snapshot.endReason)
        assertEquals(1, r.serviceStops)
    }

    @Test
    fun `a talk_state for a call the phone did not start is ignored by the live session`() {
        val r = Rig()
        r.c.press(r.request)
        r.cmds.last("portico/content/run").ok(talkStarted(outId))
        r.c.onTalkState(ts(com.meda.portico.core.ha.TalkWireState.ENDED, "busy", call = "out-fedcba9876543210"))
        assertEquals(TalkPhase.CONNECTING, r.c.state().snapshot.phase)
    }
}
