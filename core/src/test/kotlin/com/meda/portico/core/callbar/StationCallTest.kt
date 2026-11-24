package com.meda.portico.core.callbar

import com.meda.portico.core.call.AnswerSnapshot
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.content.ButtonState
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.PressRoute
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** APP-03: a call from the station has no screen of its own; the state mapping the main screen draws from. All pure. */
class StationCallTest {
    private val idle = CallUiState(null, AnswerSnapshot(Phase.IDLE), 0)
    private fun answer(p: Phase, at: Long? = null) = CallUiState(CallKey("ref-gate", "call1"), AnswerSnapshot(p, connectedAtMs = at), 1)
    private val ring = RingNow("ref-gate", "call1")

    private fun station(ring: RingNow?, answer: CallUiState, canAnswer: Boolean = true) =
        StationCalls.of(ring, answer, { if (it == "ref-gate") "Gate" else null }, { if (it == "ref-gate") "door-gate" else null }, { canAnswer })

    private fun talkButton(id: String, door: String?) = ContentAction(id, id, "talk", null, false, null, true, null, null, null, true)

    private fun homeButtons(station: StationCall?, blockDoor: String?, link: LinkState = LinkState.CONNECTED): List<com.meda.portico.core.content.ButtonTile> {
        val block = ContentBlock("b1", null, emptyList(), listOf(talkButton("talk1", blockDoor), ContentAction("lamp", "Lamp", "button_press", null, false, null, true)), door = blockDoor, doorAvailable = true)
        val page = ContentPage("home", "Home", emptyList(), emptyList(), blocks = listOf(block))
        val v = HomeModel.build(ContentProfile("", "r", emptyList(), emptyList(), pages = listOf(page)), link, ContentSupport.YES, true, emptySet(), emptySet(), station = station)
        return v.buttons
    }

    // ---- incoming ringing -> main-screen state with the door id ----

    @Test fun `a ring that is up is a ringing station call carrying the door id`() {
        val s = station(ring, idle)!!
        assertEquals(StationPhase.RINGING, s.phase)
        assertEquals("door-gate", s.doorId)
        assertEquals("ref-gate", s.doorRef)
        assertEquals("call1", s.callId)
        assertEquals("Gate", s.name)
        assertTrue(s.canAnswer)
    }

    @Test fun `a ring-only door rings with no Answer`() {
        assertFalse(station(ring, idle, canAnswer = false)!!.canAnswer)
    }

    @Test fun `the ringing Talk button of that door is RINGING_IN, the tile does nothing, other buttons are untouched`() {
        val tiles = homeButtons(station(ring, idle), "door-gate")
        assertEquals(ButtonState.RINGING_IN, tiles.first { it.id == "talk1" }.state)
        assertEquals(ButtonState.READY, tiles.first { it.id == "lamp" }.state)
        assertEquals(PressRoute.NONE, PressRoute.of(tiles.first { it.id == "talk1" }))
    }

    @Test fun `a ring for another door leaves this Talk button alone`() {
        val other = StationCall("ref-x", "door-x", "c9", StationPhase.RINGING, "X", true)
        assertEquals(ButtonState.READY, homeButtons(other, "door-gate").first { it.id == "talk1" }.state)
    }

    @Test fun `the ring shows even when the link is down`() {
        assertEquals(ButtonState.RINGING_IN, homeButtons(station(ring, idle), "door-gate", LinkState.WAITING_TO_RETRY).first { it.id == "talk1" }.state)
    }

    // ---- answer -> in call ----

    @Test fun `an answer under way is connecting, then in call, on the same button and a press of it stops the call`() {
        val connecting = station(ring, answer(Phase.CONNECTING))!!
        assertEquals(StationPhase.CONNECTING, connecting.phase)
        assertEquals(ButtonState.TALK_CONNECTING, homeButtons(connecting, "door-gate").first { it.id == "talk1" }.state)
        val inCall = station(ring, answer(Phase.IN_CALL, 5))!!
        assertEquals(StationPhase.IN_CALL, inCall.phase)
        val tile = homeButtons(inCall, "door-gate").first { it.id == "talk1" }
        assertEquals(ButtonState.TALK_IN_CALL, tile.state) // exactly the state of a call started from the phone
        assertEquals(PressRoute.TALK_STOP, PressRoute.of(tile))
    }

    @Test fun `the answered call outranks a ring still recorded`() {
        assertEquals(StationPhase.IN_CALL, station(ring, answer(Phase.IN_CALL, 5))!!.phase)
    }

    // ---- end -> idle ----

    @Test fun `no ring and no answer is idle, and so is an ended answer`() {
        assertNull(station(null, idle))
        assertNull(station(null, answer(Phase.ENDED)))
        assertEquals(ButtonState.READY, homeButtons(null, "door-gate").first { it.id == "talk1" }.state)
        assertEquals(CallBarState.IDLE, CallBars.pick(CallBars.fromAnswer(answer(Phase.ENDED), "Gate"), CallBarState.IDLE, CallBarState.IDLE, CallBars.fromRing(null)))
    }

    // ---- the bar ----

    @Test fun `the ringing bar carries the ring to answer or decline, and the answered call is the ordinary bar`() {
        val bar = CallBars.fromRing(station(ring, idle))
        assertEquals(CallPhase.RINGING, bar.phase)
        assertEquals("Gate", bar.name)
        assertEquals(RingTarget("ref-gate", "call1", true), bar.ring)
        assertTrue(bar.isShown)
        assertFalse(bar.isActive, "no notification, no duration, no route chooser for a call not yet answered")
        assertEquals(bar, CallBars.pick(CallBarState.IDLE, CallBarState.IDLE, CallBarState.IDLE, bar))
        // answered: fromRing is idle, the answer bar is the same bar a phone-started call draws
        assertEquals(CallBarState.IDLE, CallBars.fromRing(station(ring, answer(Phase.IN_CALL, 5))))
        assertEquals(CallPhase.IN_CALL, CallBars.pick(CallBars.fromAnswer(answer(Phase.IN_CALL, 5), "Gate"), CallBarState.IDLE, CallBarState.IDLE, CallBars.fromRing(station(ring, answer(Phase.IN_CALL, 5)))).phase)
    }

    @Test fun `a live call keeps the bar when a second ring comes`() {
        val live = CallBarState(CallPhase.IN_CALL, "Kitchen", 1)
        val second = CallBars.fromRing(StationCall("r2", "d2", "c2", StationPhase.RINGING, "Gate", true))
        assertEquals(live, CallBars.pick(CallBarState.IDLE, live, CallBarState.IDLE, second))
    }

    // ---- a door without a Talk button -> call bar only ----

    @Test fun `a door with a Talk button on the page shows the button and the bar, one without shows the bar alone`() {
        val s = station(ring, idle)!!
        assertEquals(StationSurface.TALK_BUTTON_AND_BAR, StationCalls.surface(homeButtons(s, "door-gate"), s))
        assertEquals(StationSurface.BAR_ONLY, StationCalls.surface(homeButtons(s, "door-other"), s))
        assertEquals(StationSurface.BAR_ONLY, StationCalls.surface(emptyList(), s))
        // a door the cache does not know has no id to match: the bar alone, still labelled with what we have
        val unknown = StationCalls.of(RingNow("ref-zz", "c3"), idle, { null }, { null }, { true })!!
        assertNull(unknown.doorId)
        assertEquals(StationSurface.BAR_ONLY, StationCalls.surface(homeButtons(unknown, "door-gate"), unknown))
    }
}
