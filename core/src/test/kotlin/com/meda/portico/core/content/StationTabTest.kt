package com.meda.portico.core.content

import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** app-05 (D-20261110-78f9): a station ring selects the tab that holds the calling door's Talk button and draws the Ring page content. Pure. */
class StationTabTest {
    private val gate = "door-gate"
    private val endpoint = "portico_fake:gate-1"
    private fun talk(id: String) = ContentAction(id, id, "talk", null, false, null, true, null, null, null, true)
    private fun lamp(id: String) = ContentAction(id, id, "script_run", null, false, null, true)
    private fun page(id: String, vararg actions: ContentAction, door: String? = null) =
        ContentPage(id, id, emptyList(), emptyList(), blocks = listOf(ContentBlock("b-$id", null, emptyList(), actions.toList(), door = door, doorAvailable = true)))

    private val doors = listOf(Door(gate, "ref-gate", endpoint), Door("door-side", "ref-side", "portico_fake:side"))
    private fun profile(vararg pages: ContentPage, callers: Map<String, ContentCaller> = emptyMap()) =
        ContentProfile("", "r1", emptyList(), emptyList(), pages = pages.toList(), callers = callers)

    private fun call(phase: StationPhase = StationPhase.RINGING, id: String = "call1", door: String? = gate) =
        StationCall("ref-gate", door, id, phase, "Gate", canAnswer = true)

    // ---- which tab ----

    @Test fun `the tab is the first page holding the Talk button of the calling door`() {
        val p = profile(page("home", lamp("l1")), page("front", lamp("l2"), talk("t1"), door = gate), page("also", talk("t2"), door = gate))
        assertEquals("front", StationTab.pageFor(p, doors, call()))
    }

    @Test fun `a Talk button of another door does not count, nor a lamp`() {
        val p = profile(page("home", lamp("l1")), page("side", talk("t1"), door = "door-side"))
        assertNull(StationTab.pageFor(p, doors, call()))
    }

    @Test fun `no tab when the door id is unknown or there is no call`() {
        val p = profile(page("front", talk("t1"), door = gate))
        assertNull(StationTab.pageFor(p, doors, call(door = null)))
        assertNull(StationTab.pageFor(p, doors, null))
    }

    // ---- select on the new ring, restore at the end ----

    @Test fun `a new ring selects the target and the end gives the user's tab back`() {
        val m = StationTabMemory()
        assertEquals("front", m.update(call(), "home") { "front" })
        assertNull(m.update(call(), "front") { "front" }, "the same ring does not select again")
        assertNull(m.update(call(StationPhase.IN_CALL), "front") { "front" })
        assertEquals("home", m.update(null, "front") { "front" }, "ended: back to the user's tab")
        assertNull(m.update(null, "home") { "front" }, "and only once")
    }

    @Test fun `a ring whose door has no Talk page stays put and restores nothing`() {
        val m = StationTabMemory()
        assertNull(m.update(call(), "home") { null })
        assertNull(m.update(null, "home") { null })
    }

    @Test fun `already on the target tab, nothing to select, nothing to restore`() {
        val m = StationTabMemory()
        assertNull(m.update(call(), "front") { "front" })
        assertNull(m.update(null, "front") { "front" })
    }

    @Test fun `a tab the user chose during the call is kept at the end`() {
        val m = StationTabMemory()
        assertEquals("front", m.update(call(), "home") { "front" })
        assertNull(m.update(call(), "garden") { "front" }, "the user moved")
        assertNull(m.update(null, "garden") { "front" }, "nothing is restored over their choice")
    }

    @Test fun `declined or unanswered rings restore too - the call just disappears`() {
        val m = StationTabMemory()
        assertEquals("front", m.update(call(), "home") { "front" })
        assertEquals("home", m.update(null, "front") { "front" })
    }

    @Test fun `a call first seen already answered never moves the tab`() {
        val m = StationTabMemory()
        assertNull(m.update(call(StationPhase.IN_CALL), "home") { "front" })
        assertNull(m.update(null, "home") { "front" })
    }

    @Test fun `answering under another call id is the same call going on`() {
        val m = StationTabMemory()
        assertEquals("front", m.update(call(id = "ring-id"), "home") { "front" })
        assertNull(m.update(call(StationPhase.CONNECTING, id = "answer-id"), "front") { "other" })
        assertEquals("home", m.update(null, "front") { "front" })
    }

    @Test fun `a second ring is a new call and selects again`() {
        val m = StationTabMemory()
        assertEquals("front", m.update(call(id = "a"), "home") { "front" })
        assertEquals("home", m.update(null, "front") { "front" })
        assertEquals("front", m.update(call(id = "b"), "home") { "front" })
    }

    @Test fun `the memory survives a rotation through its saved state`() {
        val m = StationTabMemory()
        m.update(call(), "home") { "front" }
        val again = StationTabMemory(m.state)
        assertNull(again.update(call(), "front") { "front" })
        assertEquals("home", again.update(null, "front") { "front" })
    }

    // ---- the panel: only while ringing ----

    private val open = lamp("a-open").copy(kind = "portico_open", confirm = true)
    private val side = lamp("a-side")
    private val cam = ContentCamera("c-1", "Gate", null, true)
    private val caller = ContentCaller("Front gate", "door", listOf(cam), listOf(open), listOf(side))
    private val withCaller = profile(page("home", lamp("l1")), callers = mapOf(endpoint to caller))

    private fun panel(c: StationCall?, p: ContentProfile = withCaller, link: LinkState = LinkState.CONNECTED) =
        StationPanel.build(p, endpoint, c, link, ContentSupport.YES, emptySet(), emptySet())

    @Test fun `ringing draws the ring and call buttons merged, in order, de-duplicated`() {
        assertEquals(listOf("a-open", "a-side"), panel(call(StationPhase.RINGING))!!.buttons.map { it.id })
        assertEquals(listOf("c-1"), panel(call(StationPhase.RINGING))!!.cameras.map { it.id })
        val dup = withCaller.copy(callers = mapOf(endpoint to ContentCaller("G", "door", listOf(cam), listOf(open, side), listOf(side, lamp("a-x")))))
        assertEquals(listOf("a-open", "a-side", "a-x"), panel(call(StationPhase.RINGING), dup)!!.buttons.map { it.id })
    }

    @Test fun `answered or connected closes the panel - the ring view is over`() {
        assertNull(panel(call(StationPhase.CONNECTING)))
        assertNull(panel(call(StationPhase.IN_CALL)))
    }

    @Test fun `a door button keeps its confirmation in the panel and is off when not connected`() {
        assertEquals(true, panel(call())!!.buttons.first().confirm)
        assertEquals(ButtonState.OFFLINE, panel(call(), link = LinkState.WAITING_TO_RETRY)!!.buttons.first().state)
    }

    @Test fun `no call, no caller entry, or an entry with nothing draws no panel`() {
        assertNull(panel(null))
        assertNull(panel(call(), profile(page("home", lamp("l1")))))
        val empty = withCaller.copy(callers = mapOf(endpoint to ContentCaller("G", "door", emptyList(), emptyList(), emptyList())))
        assertNull(panel(call(StationPhase.RINGING), empty))
        val callOnly = withCaller.copy(callers = mapOf(endpoint to ContentCaller("G", "door", emptyList(), emptyList(), listOf(side))))
        assertEquals(listOf("a-side"), panel(call(StationPhase.RINGING), callOnly)!!.buttons.map { it.id })
    }
}
