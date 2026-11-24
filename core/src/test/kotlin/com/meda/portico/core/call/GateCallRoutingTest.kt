package com.meda.portico.core.call

import com.meda.portico.core.content.ButtonState
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.GateCall
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.PressRoute
import com.meda.portico.core.content.TalkGate
import com.meda.portico.core.ha.CallTarget
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.RosterEntry
import com.meda.portico.core.ha.TalkStateEvent
import com.meda.portico.core.ha.TalkWireState
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-692 T2: a `call` button aimed at the gate starts the app's Talk session (D1), never a house-call session; one Talk is
 * capped at 120 s in the app (D2). The whole press path is driven the way Home does it: the model draws the tile, [PressRoute]
 * decides, and the real [TalkCoordinator] / [PlaceCoordinator] are wired to fakes. A [PlaceCoordinator] session here is a failure.
 */
class GateCallRoutingTest {
    private val gateEndpoint = "fake:$TALK_DOOR"
    private val frontEndpoint = "fake:vto-front.example"
    private val out = "out-0123456789abcdef"

    private val doors = listOf(
        Door(TALK_DOOR, "d-gate", gateEndpoint, "fake", "Vehicle gate"),
        Door("vto-front.example", "d-front", frontEndpoint, "fake", "Front door"),
    )

    private fun rosterRow(endpoint: String, canDial: Boolean) =
        RosterEntry("r-$endpoint", endpoint, "door", "Door", available = canDial, reason = if (canDial) null else "not_available", canDial = canDial)

    private fun call(id: String, type: String, target: String, available: Boolean = true, reason: String? = null, gateCall: Boolean = false) =
        ContentAction(id, "Call $id", "call", null, false, null, available, CallTarget(type, target), reason, gateCall = gateCall)

    private fun profile(roster: List<RosterEntry>, vararg actions: ContentAction): ContentProfile {
        val page = ContentPage.ofBlocks("p", "P", listOf(ContentBlock("b", "B", emptyList(), actions.toList(), null)))
        return ContentProfile("", "r", page.cameras, page.actions, pages = listOf(page), roster = roster)
    }

    private fun tiles(
        p: ContentProfile,
        ui: TalkUiState? = null,
        known: List<Door> = doors,
        link: LinkState = LinkState.CONNECTED,
    ): List<ButtonTile> =
        HomeModel.build(p, link, ContentSupport.YES, true, emptySet(), emptySet(), talk = ui, doors = known).buttons

    private val gateOnly = listOf(rosterRow(gateEndpoint, true), rosterRow(frontEndpoint, false))

    // ---- the rig: both coordinators, real, with a place session that must never exist ----------------------------

    private inner class Rig(profile: ContentProfile) {
        val cmds = FakeCommands()
        val media = FakeTalkMedia()
        val audio = FakeAudio()
        val sched = FakeScheduler()
        val later = FakeScheduler()
        var otherLive = false
        var talkSessions = 0
        var placeSessions = 0
        var serviceStarts = 0
        var profile = profile

        val talk: TalkCoordinator = TalkCoordinator(
            newSession = { request, hooks, g, onChange ->
                talkSessions++
                TalkSession(request, cmds, media, audio, { true }, sched, { 1L }, g, hooks, onChange = onChange)
            },
            post = { it() },
            serviceStart = { serviceStarts++ },
            serviceStop = {},
            paused = { false },
            otherCallLive = { otherLive },
            gate = { r ->
                val a = this.profile.findAction(r.actionId)
                TalkGate.ready(a, true, GateCall.of(a, this.profile.roster, doors))
            },
            later = later,
        )

        val place: PlaceCoordinator = PlaceCoordinator(
            newSession = { _, _, _ -> placeSessions++; error("a house-call session was created") },
            post = { it() },
            serviceStart = {},
            serviceStop = {},
            paused = { false },
            otherCallLive = { talk.state().isLive || otherLive },
        )

        /** What Home does on a press of [id] (HomeActivity.onPress, minus the widgets). Returns the route taken. */
        fun press(id: String): PressRoute {
            val tile = tiles(profile, talk.state()).single { it.id == id }
            val route = PressRoute.of(tile)
            when (route) {
                PressRoute.PLACE -> place.press(PlaceRequest(tile.id, tile.label))
                PressRoute.TALK_START -> talk.press(TalkRequest(tile.id, tile.label, tile.door))
                PressRoute.TALK_STOP -> talk.hangUp()
                else -> Unit
            }
            return route
        }

        fun grant() = cmds.last("portico/content/run").ok(talkStarted(out))
        fun answered() = talk.onTalkState(TalkStateEvent(TALK_DOOR, out, TalkWireState.IN_CALL, null))
    }

    // ---- GateCall.of: which call buttons are the gate ------------------------------------------------------------

    @Test
    fun `a call button to a door whose roster row says can_dial is a gate call with the door's socket id`() {
        val a = call("g", "door", gateEndpoint)
        assertEquals(GateCall(TALK_DOOR), GateCall.of(a, gateOnly, doors))
    }

    @Test
    fun `no can_dial, another door, another target type, another kind or no roster row is not a gate call`() {
        val a = call("g", "door", gateEndpoint)
        assertNull(GateCall.of(a, listOf(rosterRow(gateEndpoint, false)), doors), "the row exists but cannot dial")
        assertNull(GateCall.of(a, emptyList(), doors), "the roster does not list the door")
        assertNull(GateCall.of(call("f", "door", frontEndpoint), gateOnly, doors), "the other door cannot dial")
        // a phone or screen whose id happens to equal the gate's endpoint id is still not a door target
        assertNull(GateCall.of(call("p", "phone", gateEndpoint), gateOnly, doors))
        assertNull(GateCall.of(call("d", "dashboard", gateEndpoint), gateOnly, doors))
        assertNull(GateCall.of(ContentAction("t", "Talk", "talk", null, false, null, true, null, null), gateOnly, doors))
        assertNull(GateCall.of(null, gateOnly, doors))
        // a row of another kind with the same id does not count
        assertNull(GateCall.of(a, listOf(RosterEntry("r", gateEndpoint, "phone", "x", true, null, canDial = true)), doors))
    }

    @Test
    fun `a gate call whose door is not in the doors cache is still a gate call, with no door`() {
        assertEquals(GateCall(null), GateCall.of(call("g", "door", gateEndpoint), gateOnly, emptyList()))
    }

    // ---- DEV-695: the action's own marker, read before the roster ------------------------------------------------------

    /** A phone whose document hides the gate (`roster_hide`, or a template roster list without it): no gate row to detect. */
    private val hiddenGate = listOf(rosterRow(frontEndpoint, false))

    @Test
    fun `a marked call button is a gate call even when the roster has no row for the gate`() {
        val a = call("g", "door", gateEndpoint, gateCall = true)
        assertEquals(GateCall(TALK_DOOR), GateCall.of(a, hiddenGate, doors))
        assertEquals(GateCall(TALK_DOOR), GateCall.of(a, emptyList(), doors))
        assertEquals(GateCall(TALK_DOOR), GateCall.of(a, listOf(rosterRow(gateEndpoint, false)), doors), "the marker wins over a row that says can_dial is off")
    }

    @Test
    fun `an old server sends no marker - the roster row still decides, and a hidden gate is then not detected`() {
        val a = call("g", "door", gateEndpoint)
        assertFalse(a.gateCall)
        assertEquals(GateCall(TALK_DOOR), GateCall.of(a, gateOnly, doors), "the roster fallback")
        assertNull(GateCall.of(a, hiddenGate, doors), "what the marker is for")
    }

    @Test
    fun `a marker is read for a door target only - never on another target type or another kind`() {
        assertNull(GateCall.of(call("p", "phone", gateEndpoint, gateCall = true), hiddenGate, doors))
        assertNull(GateCall.of(call("d", "dashboard", gateEndpoint, gateCall = true), hiddenGate, doors))
        assertNull(GateCall.of(ContentAction("t", "Talk", "talk", null, false, null, true, null, null, gateCall = true), hiddenGate, doors))
    }

    @Test
    fun `a marked button with no known door is a gate call with no door - drawn off, never a house call`() {
        val t = tiles(profile(hiddenGate, call("g", "door", gateEndpoint, gateCall = true)), known = emptyList()).single()
        assertTrue(t.talkRoute)
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertEquals(PressRoute.NONE, PressRoute.of(t))
    }

    @Test
    fun `a hidden-gate roster with the marker starts Talk and never a house-call session`() {
        val r = Rig(profile(hiddenGate, call("g", "door", gateEndpoint, gateCall = true)))
        val tile = tiles(r.profile, r.talk.state()).single()
        assertTrue(tile.talkRoute)
        assertEquals(ButtonState.READY, tile.state)
        assertEquals(TALK_DOOR, tile.door)
        assertEquals(PressRoute.TALK_START, r.press("g"))
        assertEquals(1, r.talkSessions)
        assertEquals(0, r.placeSessions, "a PlaceSession here would time out after 60 s while the gate call is live")
        assertEquals(listOf("portico/content/run"), r.cmds.types())
        r.grant()
        r.answered()
        assertEquals(out, r.media.legs.single().callId)
    }

    @Test
    fun `the same hidden-gate roster without the marker takes the house-call path - the old server's behaviour is unchanged`() {
        val r = Rig(profile(hiddenGate, call("g", "door", gateEndpoint)))
        val tile = tiles(r.profile, r.talk.state()).single()
        assertFalse(tile.talkRoute)
        assertEquals(PressRoute.PLACE, PressRoute.of(tile))
    }

    @Test
    fun `a house call to the front door is untouched next to a marked gate button`() {
        val t = tiles(profile(hiddenGate, call("g", "door", gateEndpoint, gateCall = true), call("f", "door", frontEndpoint))).toList()
        assertTrue(t[0].talkRoute)
        assertFalse(t[1].talkRoute)
        assertEquals(PressRoute.PLACE, PressRoute.of(t[1]))
    }

    // ---- the tile -------------------------------------------------------------------------------------------------

    @Test
    fun `an available gate call button is a ready call-kind tile on the Talk route with the door for the audio`() {
        val t = tiles(profile(gateOnly, call("g", "door", gateEndpoint))).single()
        assertEquals("call", t.kind)
        assertEquals(ButtonState.READY, t.state)
        assertTrue(t.talkRoute)
        assertEquals(TALK_DOOR, t.door)
        assertFalse(t.confirm)
    }

    @Test
    fun `a gate call the server says is off keeps the call button's own off state and reason`() {
        val reasons = mapOf("busy" to ButtonState.TARGET_BUSY, "offline" to ButtonState.TARGET_OFFLINE, "unknown_target" to ButtonState.TARGET_GONE, "not_available" to ButtonState.CALLS_NOT_AVAILABLE)
        for ((reason, state) in reasons) {
            val t = tiles(profile(gateOnly, call("g", "door", gateEndpoint, available = false, reason = reason))).single()
            assertEquals(state, t.state, reason)
            assertEquals(PressRoute.NONE, PressRoute.of(t), reason)
        }
    }

    @Test
    fun `a gate call with no known door is drawn off and presses nothing - it never falls back to a house call`() {
        val t = tiles(profile(gateOnly, call("g", "door", gateEndpoint)), known = emptyList()).single()
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertTrue(t.talkRoute)
        assertEquals(PressRoute.NONE, PressRoute.of(t))
    }

    @Test
    fun `a gate call is off without a link`() {
        val t = tiles(profile(gateOnly, call("g", "door", gateEndpoint)), link = LinkState.CONNECTING).single()
        assertEquals(ButtonState.OFFLINE, t.state)
        assertEquals(PressRoute.NONE, PressRoute.of(t))
    }

    @Test
    fun `a door call button that cannot dial is exactly the call button it was`() {
        val rows = listOf(rosterRow(gateEndpoint, false), rosterRow(frontEndpoint, false))
        val ready = tiles(profile(rows, call("f", "door", frontEndpoint))).single()
        assertFalse(ready.talkRoute)
        assertNull(ready.door)
        assertEquals(ButtonState.READY, ready.state)
        assertEquals(PressRoute.PLACE, PressRoute.of(ready))
        val off = tiles(profile(rows, call("f", "door", frontEndpoint, available = false, reason = "not_available"))).single()
        assertEquals(ButtonState.CALLS_NOT_AVAILABLE, off.state)
        assertFalse(off.talkRoute)
        // dial off entirely: the very same tile as one with no can_dial key anywhere, whatever the doors cache holds
        assertEquals(off, tiles(profile(emptyList(), call("f", "door", frontEndpoint, available = false, reason = "not_available")), known = emptyList()).single())
    }

    @Test
    fun `a phone or screen call button is untouched by a gate in the roster`() {
        val t = tiles(profile(gateOnly, call("k", "dashboard", "ep-1"), call("s", "phone", "ep-2"))).toList()
        assertTrue(t.all { !it.talkRoute && it.state == ButtonState.READY && PressRoute.of(it) == PressRoute.PLACE })
    }

    // ---- the press ---------------------------------------------------------------------------------------------------

    @Test
    fun `pressing a gate call button starts the Talk session and never a house-call session`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        assertEquals(PressRoute.TALK_START, r.press("g"))
        assertEquals(1, r.talkSessions)
        assertEquals(0, r.placeSessions)
        assertEquals(1, r.serviceStarts)
        assertEquals(obj("action_id" to "g"), r.cmds.last("portico/content/run").body)
        assertEquals(listOf("portico/content/run"), r.cmds.types(), "the phone sends the action id and nothing else; no dial of its own")
        assertEquals(TalkPhase.STARTING, r.talk.state().snapshot.phase)
    }

    @Test
    fun `the whole call from the gate button follows talk_state, opens the audio on the dialled id, and stops from the same button`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.grant()
        assertEquals(TalkPhase.CONNECTING, r.talk.state().snapshot.phase)
        assertEquals(0, r.media.opens, "no audio while it rings")
        r.answered()
        assertEquals(1, r.media.opens)
        assertEquals(out, r.media.legs.single().callId)
        assertEquals(TALK_DOOR, r.media.legs.single().doorId)
        r.media.legs.single().listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, r.talk.state().snapshot.phase)
        // the tile now shows the talk, and its press stops it
        val live = tiles(r.profile, r.talk.state()).single()
        assertEquals(ButtonState.TALK_IN_CALL, live.state)
        assertEquals(PressRoute.TALK_STOP, PressRoute.of(live))
        assertEquals(PressRoute.TALK_STOP, r.press("g"))
        assertEquals(TalkPhase.ENDED, r.talk.state().snapshot.phase)
        assertEquals(listOf("portico/content/run", "portico/hangup"), r.cmds.types())
        assertEquals(0, r.placeSessions)
    }

    @Test
    fun `the server answers the run only after the station picked up - talk_state in_call before the reply still opens the audio`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.talk.onTalkState(TalkStateEvent(TALK_DOOR, out, TalkWireState.IN_CALL, null))
        assertEquals(0, r.media.opens, "held until the reply names the call")
        r.grant()
        assertEquals(1, r.media.opens)
        assertEquals(out, r.media.legs.single().callId)
    }

    @Test
    fun `no house-call timeout is armed by a gate call - 60 s with no house_call answer does not end it`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.grant()
        r.answered()
        r.media.legs.single().listener.onConnected()
        r.sched.advance(60_000)
        r.sched.advance(30_000)
        assertEquals(TalkPhase.IN_CALL, r.talk.state().snapshot.phase)
    }

    @Test
    fun `a press while the server says the gate is off dials nothing`() {
        val off = profile(gateOnly, call("g", "door", gateEndpoint, available = false, reason = "busy"))
        val r = Rig(off)
        assertEquals(PressRoute.NONE, r.press("g"))
        // even a forced coordinator press is refused by the gate
        r.talk.press(TalkRequest("g", "G", TALK_DOOR))
        assertEquals(emptyList<String>(), r.cmds.types())
        assertEquals(0, r.talkSessions + r.placeSessions)
    }

    @Test
    fun `a press while another call is live does not dial`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.otherLive = true
        assertEquals(PressRoute.TALK_START, r.press("g"))
        assertEquals(emptyList<String>(), r.cmds.types())
        assertEquals(0, r.talkSessions)
        assertEquals(TalkFailure.Kind.ANOTHER_CALL, r.talk.state().snapshot.failure?.kind)
    }

    @Test
    fun `a house call button pressed while a gate talk is live does not place`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint), call("k", "dashboard", "ep-1")))
        r.press("g")
        r.press("k")
        assertEquals(0, r.placeSessions, "PlaceCoordinator refuses while a Talk is live")
        assertEquals(1, r.cmds.count("portico/content/run"))
    }

    @Test
    fun `a visitor ringing ends the gate talk`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.grant()
        r.talk.onDoorRinging()
        assertEquals(TalkFailure.Kind.DOOR_RINGING, r.talk.state().snapshot.failure?.kind)
        assertEquals("portico/hangup", r.cmds.types().last())
        assertEquals(1, r.cmds.count("portico/content/run"), "nothing was dialled again")
    }

    // ---- the 120 s cap and the ended lines ----------------------------------------------------------------------------

    @Test
    fun `the app caps one talk at 120 seconds from the station's answer, hangs up and says why`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.grant()
        r.sched.advance(15_000) // ringing time does not count against the cap (the 20 s connect wait is the bound)
        r.answered()
        r.media.legs.single().listener.onConnected()
        r.sched.advance(APP_TALK_CAP_MS - 1)
        assertEquals(TalkPhase.IN_CALL, r.talk.state().snapshot.phase)
        r.sched.advance(1)
        val s = r.talk.state().snapshot
        assertEquals(TalkPhase.ENDED, s.phase)
        assertEquals(EndReason.TIME_CAP, s.endReason)
        assertEquals("Time limit reached.", TalkErrors.endLine(s))
        assertEquals("portico/hangup", r.cmds.types().last())
        assertEquals(1, r.media.legs.single().closes)
        // the tile carries the line, then goes back to following the server after 4 s
        val ended = tiles(r.profile, r.talk.state()).single()
        assertEquals("Time limit reached.", ended.endLine)
        r.later.advance(TalkConfig().endedShownMs)
        assertEquals(TalkPhase.IDLE, r.talk.state().snapshot.phase)
        assertNull(tiles(r.profile, r.talk.state()).single().endLine)
    }

    @Test
    fun `the cap numbers - the app's 120 s is inside gate-sip's 300 s hard limit, both pinned`() {
        assertEquals(120_000L, APP_TALK_CAP_MS)
        assertEquals(300_000L, GATE_SIP_HARD_CAP_MS)
        assertEquals(APP_TALK_CAP_MS, TalkConfig().callCapMs)
        assertTrue(TalkConfig().callCapMs < GATE_SIP_HARD_CAP_MS)
    }

    @Test
    fun `a server time_cap still ends with the same line, and a gate hang-up keeps its own`() {
        val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r.press("g")
        r.grant()
        r.answered()
        r.talk.onTalkState(TalkStateEvent(TALK_DOOR, out, TalkWireState.ENDED, "time_cap"))
        assertEquals("Time limit reached.", TalkErrors.endLine(r.talk.state().snapshot))
        val r2 = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
        r2.press("g")
        r2.grant()
        r2.answered()
        r2.talk.onTalkState(TalkStateEvent(TALK_DOOR, out, TalkWireState.ENDED, null))
        assertEquals("The gate hung up.", TalkErrors.endLine(r2.talk.state().snapshot))
    }

    @Test
    fun `ended before the station answers gives the dial's refusal lines`() {
        for ((reason, kind) in mapOf("busy" to TalkFailure.Kind.GATE_BUSY, "no_answer" to TalkFailure.Kind.NO_ANSWER, "not_connected" to TalkFailure.Kind.GATE_NOT_CONNECTED)) {
            val r = Rig(profile(gateOnly, call("g", "door", gateEndpoint)))
            r.press("g")
            r.grant()
            r.talk.onTalkState(TalkStateEvent(TALK_DOOR, out, TalkWireState.ENDED, reason))
            assertEquals(kind, r.talk.state().snapshot.failure?.kind, reason)
            assertEquals(0, r.media.opens)
        }
    }
}
