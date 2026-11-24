package com.meda.portico.core.panel

import com.meda.portico.core.call.Cancellable
import com.meda.portico.core.call.PlaceErrors
import com.meda.portico.core.call.PlacePhase
import com.meda.portico.core.call.PlaceRequest
import com.meda.portico.core.call.PlaceSnapshot
import com.meda.portico.core.call.PlaceUiState
import com.meda.portico.core.call.EndReason
import com.meda.portico.core.call.Scheduler
import com.meda.portico.core.ha.RosterEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D-20261121-c98a: the panel's "Call phones" request, from the server's event to its answer (design: portico_panel_call_phones_design.md). */
class PanelCallTest {
    private val id = "0123456789abcdef"
    private val id2 = "fedcba9876543210"

    private class Timers : Scheduler {
        class T(val at: Long, val task: () -> Unit) { var live = true }
        val all = mutableListOf<T>()
        var now = 0L
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val t = T(now + delayMs, task)
            all += t
            return Cancellable { t.live = false }
        }
        fun advance(ms: Long) {
            now += ms
            all.filter { it.live && it.at <= now }.forEach { it.live = false; it.task() }
        }
    }

    private val timers = Timers()
    private val replies = mutableListOf<List<Any?>>()
    private val notes = mutableListOf<String>()
    private val presses = mutableListOf<PlaceRequest>()
    private var gate = PanelCallGate(panelMode = true, busy = false, paused = false)
    private var row: RosterEntry? = RosterEntry("r-abc", "everyone", "group", "Everyone", available = true, reason = null, count = 3)
    private var onUp: (() -> Unit)? = null
    private var onFailed: ((String) -> Unit)? = null
    private var fronts = 0
    private var frontThrows = false

    private val c = PanelCallCoordinator(
        gate = { gate },
        everyone = { row },
        front = { up, failed -> fronts++; if (frontThrows) error("boom"); onUp = up; onFailed = failed },
        press = { presses += it },
        reply = { rid, ok, code, text -> replies += listOf(rid, ok, code, text) },
        scheduler = timers,
        note = { ev, rid, code -> notes += "$ev:$code" },
    )

    private fun state(phase: PlacePhase, failure: com.meda.portico.core.call.PlaceFailure? = null, action: String = "r-abc", answering: Boolean = false) =
        PlaceUiState(PlaceRequest(action, "Everyone"), PlaceSnapshot(phase, if (phase == PlacePhase.ENDED) EndReason.FAILED else null, failure, answering = answering), 1)

    // ---- the wire ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `only a panel_call for everyone with a 16-hex id parses`() {
        fun ev(type: String = "panel_call", target: String? = "everyone", rid: String? = id) = buildJsonObject {
            put("type", type); if (target != null) put("target", target); if (rid != null) put("request_id", rid)
        }
        assertEquals(id, PanelCallWire.parse(ev())!!.requestId)
        assertNull(PanelCallWire.parse(ev(type = "house_call")))
        assertNull(PanelCallWire.parse(ev(target = "kitchen")))
        assertNull(PanelCallWire.parse(ev(target = null)))
        assertNull(PanelCallWire.parse(ev(rid = null)))
        assertNull(PanelCallWire.parse(ev(rid = "SHORT")))
        assertNull(PanelCallWire.parse(ev(rid = id + "0")))
    }

    @Test
    fun `the answer carries the id, ok, and a bounded code and text and nothing else`() {
        val b = PanelCallWire.result(id, false, "x".repeat(60), "y".repeat(200))
        assertEquals(setOf("request_id", "ok", "code", "text"), b.keys)
        assertEquals(40, b["code"].toString().trim('"').length)
        assertEquals(120, b["text"].toString().trim('"').length)
        assertEquals(setOf("request_id", "ok"), PanelCallWire.result(id, true).keys)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { PanelCallWire.result("bad", true) }
    }

    // ---- refusals before anything is shown or placed ---------------------------------------------------------------------------

    private fun assertRefusedBeforeAnything(code: String) {
        c.onRequest(PanelCallRequest(id))
        assertEquals(listOf(listOf(id, false, code, replies.single()[3])), replies)
        assertNotNull(replies.single()[3])
        assertEquals(0, fronts, "nothing is brought to the front for a refused request")
        assertTrue(presses.isEmpty(), "nothing is placed for a refused request")
        assertFalse(c.inFlight)
    }

    @Test fun `Panel mode off is refused`() { gate = gate.copy(panelMode = false); assertRefusedBeforeAnything("not_panel_mode") }
    @Test fun `paused is refused`() { gate = gate.copy(paused = true); assertRefusedBeforeAnything("paused") }
    @Test fun `a call or ring live is refused`() { gate = gate.copy(busy = true); assertRefusedBeforeAnything("busy") }
    @Test fun `no Everyone row is refused`() { row = null; assertRefusedBeforeAnything("no_everyone") }

    @Test
    fun `an unavailable Everyone row is refused with its reason in words`() {
        row = row!!.copy(available = false, reason = "no_receivers")
        assertRefusedBeforeAnything("no_everyone")
        assertTrue("receive house calls" in replies.single()[3] as String)
    }

    // ---- the happy path and its order -------------------------------------------------------------------------------------------

    @Test
    fun `the screen comes up first, then the call is pressed, then RINGING answers ok`() {
        c.onRequest(PanelCallRequest(id))
        assertEquals(1, fronts)
        assertTrue(presses.isEmpty() && replies.isEmpty(), "nothing is placed until the screen is up")
        onUp!!()
        assertEquals(listOf(PlaceRequest("r-abc", "Everyone")), presses, "the press names the row's action id and its name, nothing else")
        assertTrue(replies.isEmpty())
        c.onPlaceState(state(PlacePhase.AWAITING_MIC)); c.onPlaceState(state(PlacePhase.PLACING))
        assertTrue(replies.isEmpty(), "still waiting: nobody rings yet")
        c.onPlaceState(state(PlacePhase.RINGING))
        assertEquals(listOf(listOf(id, true, null, null)), replies)
        assertFalse(c.inFlight)
        c.onPlaceState(state(PlacePhase.IN_CALL)) // a later state is nothing to answer
        assertEquals(1, replies.size)
    }

    @Test
    fun `a server refusal of the placing is the scripts error and shows the screens own text`() {
        c.onRequest(PanelCallRequest(id)); onUp!!()
        c.onPlaceState(state(PlacePhase.ENDED, PlaceErrors.anotherCall()))
        val r = replies.single()
        assertEquals(false, r[1]); assertEquals("refused", r[2]); assertEquals("You are already on a call.", r[3])
    }

    @Test
    fun `a state of another action, or before the press, is not ours`() {
        c.onPlaceState(state(PlacePhase.ENDED, PlaceErrors.anotherCall())) // nothing in flight
        c.onRequest(PanelCallRequest(id))
        c.onPlaceState(state(PlacePhase.RINGING)) // still fronting: not placing yet
        c.onRequest(PanelCallRequest(id))        // the same id again: one request, one answer
        assertTrue(replies.isEmpty())
        onUp!!()
        c.onPlaceState(state(PlacePhase.ENDED, PlaceErrors.anotherCall(), action = "call-someone-else"))
        assertTrue(replies.isEmpty())
    }

    // ---- fronting fails or hangs -----------------------------------------------------------------------------------------------

    @Test
    fun `a front that fails is answered no_front and nothing is placed`() {
        c.onRequest(PanelCallRequest(id))
        onFailed!!("start blocked")
        val r = replies.single()
        assertEquals("no_front", r[2]); assertTrue("could not come to the front" in r[3] as String)
        assertTrue(presses.isEmpty())
        onUp?.invoke()
        assertTrue(presses.isEmpty(), "a screen that comes up after the failure places nothing")
    }

    @Test
    fun `a front that never answers times out at 4 s as no_front`() {
        c.onRequest(PanelCallRequest(id))
        timers.advance(PanelCallCoordinator.FRONT_MS - 1)
        assertTrue(replies.isEmpty())
        timers.advance(1)
        assertEquals("no_front", replies.single()[2])
        onUp!!()
        assertTrue(presses.isEmpty())
    }

    @Test
    fun `a front that throws is no_front`() {
        frontThrows = true
        c.onRequest(PanelCallRequest(id))
        assertEquals("no_front", replies.single()[2])
        assertFalse(c.inFlight)
    }

    @Test
    fun `a placing that never reaches RINGING times out at 14 s`() {
        c.onRequest(PanelCallRequest(id)); onUp!!()
        timers.advance(PanelCallCoordinator.PLACE_MS)
        assertEquals("timeout", replies.single()[2])
    }

    @Test
    fun `a call or ring that began while the screen came up wins and nothing is placed`() {
        c.onRequest(PanelCallRequest(id))
        gate = gate.copy(busy = true)
        onUp!!()
        assertEquals("busy", replies.single()[2])
        assertTrue(presses.isEmpty())
    }

    // ---- one at a time ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a second request while one is in flight is refused busy and the first goes on`() {
        c.onRequest(PanelCallRequest(id))
        c.onRequest(PanelCallRequest(id2))
        assertEquals(listOf(listOf(id2, false, "busy", PanelCallCoordinator.Texts.BUSY)), replies)
        onUp!!()
        c.onPlaceState(state(PlacePhase.RINGING))
        assertEquals(2, replies.size)
        assertEquals(true, replies[1][1])
        assertEquals(1, presses.size)
    }

    @Test
    fun `a user hang-up during the placing is answered, not left hanging`() {
        c.onRequest(PanelCallRequest(id)); onUp!!()
        c.onPlaceState(state(PlacePhase.ENDED, null))
        assertEquals("refused", replies.single()[2])
        assertEquals("The call was cancelled.", replies.single()[3])
    }

    @Test
    fun `cancelAll answers a waiting request`() {
        c.onRequest(PanelCallRequest(id))
        c.cancelAll()
        assertEquals("not_panel_mode", replies.single()[2])
        c.cancelAll()
        assertEquals(1, replies.size)
    }

    @Test
    fun `every step is noted for the Journal`() {
        c.onRequest(PanelCallRequest(id)); onUp!!(); c.onPlaceState(state(PlacePhase.RINGING))
        assertEquals(listOf("accepted:null", "placing:null", "ringing:null"), notes)
    }

    // ---- the ringback ------------------------------------------------------------------------------------------------------------

    @Test
    fun `ringback plays only while a placed call rings, in Panel mode`() {
        assertTrue(RingbackPolicy.wanted(true, state(PlacePhase.RINGING)))
        assertFalse(RingbackPolicy.wanted(false, state(PlacePhase.RINGING)), "phones keep today's behaviour")
        for (p in listOf(PlacePhase.IDLE, PlacePhase.AWAITING_MIC, PlacePhase.PLACING, PlacePhase.CONNECTING, PlacePhase.IN_CALL, PlacePhase.ENDED)) {
            assertFalse(RingbackPolicy.wanted(true, state(p)), p.name)
        }
        assertFalse(RingbackPolicy.wanted(true, state(PlacePhase.RINGING, answering = true)), "an answered ring is not a ringback")
        assertTrue(RingbackPolicy.MAX_MS > 60_000, "the cap is over the session's own 60 s ring limit")
    }
}
