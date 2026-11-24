package com.meda.portico.core.call

import com.meda.portico.core.push.CallKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure part of hold: who may be added to the set, and the shared audio route. */
class SessionSetTest {

    private val a = CallKey("gate", "A")
    private val b = CallKey("ent", "B")
    private val c = CallKey("gate", "C")

    private fun slot(key: CallKey, phase: Phase, held: Boolean = false) =
        SessionSet.Slot(key, key.doorRef, 1).also { it.snapshot = AnswerSnapshot(phase, held = held) }

    private fun set(vararg s: SessionSet.Slot) = SessionSet().also { for (x in s) it.add(x) }

    @Test
    fun `empty set - begin`() {
        assertEquals(SessionSet.Decision.BEGIN, SessionSet().decide(a, true))
        assertEquals(SessionSet.Decision.BEGIN, SessionSet().decide(a, false))
    }

    @Test
    fun `hold off - any session that has not ended refuses every other call`() {
        for (p in listOf(Phase.IDLE, Phase.AWAITING_MIC, Phase.PREPARING, Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING, Phase.IN_CALL)) {
            assertEquals(SessionSet.Decision.REFUSE_ANOTHER_CALL, set(slot(a, p)).decide(b, false), "$p")
            assertEquals(SessionSet.Decision.SAME_CALL, set(slot(a, p)).decide(a, false), "$p")
        }
        assertEquals(SessionSet.Decision.BEGIN, set(slot(a, Phase.ENDED)).decide(b, false), "an ended session does not count")
    }

    @Test
    fun `hold on - a connected live call offers hold-and-begin, an unconnected one refuses with its own reason`() {
        assertEquals(SessionSet.Decision.HOLD_AND_BEGIN, set(slot(a, Phase.IN_CALL)).decide(b, true))
        for (p in listOf(Phase.AWAITING_MIC, Phase.PREPARING, Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING)) {
            assertEquals(SessionSet.Decision.REFUSE_FIRST_NOT_CONNECTED, set(slot(a, p)).decide(b, true), "$p")
        }
    }

    @Test
    fun `hold on - two sessions refuse a third whatever their state`() {
        assertEquals(SessionSet.Decision.REFUSE_BOTH_IN_USE, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.IN_CALL)).decide(c, true))
        assertEquals(SessionSet.Decision.REFUSE_BOTH_IN_USE, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.PREPARING)).decide(c, true))
        assertEquals(SessionSet.Decision.REFUSE_BOTH_IN_USE, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.IN_CALL, held = true)).decide(c, true))
    }

    @Test
    fun `hold on - a held call alone, or with an ended one, leaves the way open`() {
        assertEquals(SessionSet.Decision.BEGIN, set(slot(a, Phase.IN_CALL, held = true)).decide(b, true))
        assertEquals(SessionSet.Decision.BEGIN, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.ENDED)).decide(c, true))
        assertEquals(SessionSet.Decision.HOLD_AND_BEGIN, set(slot(a, Phase.ENDED), slot(b, Phase.IN_CALL)).decide(c, true))
    }

    @Test
    fun `the same call is the same call in every state`() {
        assertEquals(SessionSet.Decision.SAME_CALL, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.IN_CALL)).decide(a, true))
        assertEquals(SessionSet.Decision.SAME_CALL, set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.IN_CALL)).decide(b, true))
    }

    @Test
    fun `live, held, active and the door lookup`() {
        val s = set(slot(a, Phase.IN_CALL, held = true), slot(b, Phase.IN_CALL), slot(c, Phase.ENDED))
        assertEquals("B", s.live()!!.callId)
        assertEquals(listOf("A"), s.heldSlots().map { it.callId })
        assertEquals(2, s.active().size)
        assertEquals(listOf("A", "C"), s.forDoor("gate").map { it.callId })
        assertEquals("C", s.find("C")!!.callId)
        assertEquals(listOf("C"), s.dropEnded().map { it.callId })
        assertNull(s.find("C"))
        assertTrue(set(slot(a, Phase.ENDED, held = true)).heldSlots().isEmpty(), "an ended slot is never held")
    }

    private class Spy : AudioRoute {
        var enters = 0
        var leaves = 0
        override fun enter() { enters++ }
        override fun leave() { leaves++ }
    }

    @Test
    fun `the shared route enters once and leaves at the last leave`() {
        val spy = Spy()
        val r = RefCountedAudioRoute(spy)
        r.enter()
        r.enter()
        assertEquals(1, spy.enters)
        r.leave()
        assertEquals(0, spy.leaves)
        r.leave()
        assertEquals(1, spy.leaves)
        r.leave()
        assertEquals(1, spy.leaves, "an extra leave is ignored")
        r.enter()
        assertEquals(2, spy.enters, "and the route can be entered again")
    }

    @Test
    fun `a route that fails to enter is not counted`() {
        var fail = true
        val spy = object : AudioRoute {
            var enters = 0
            var leaves = 0
            override fun enter() { if (fail) throw IllegalStateException("x"); enters++ }
            override fun leave() { leaves++ }
        }
        val r = RefCountedAudioRoute(spy)
        try { r.enter() } catch (_: IllegalStateException) {}
        fail = false
        r.enter()
        r.leave()
        assertEquals(1, spy.leaves)
    }

    @Test
    fun `the active call store is a set, and reads a single record as a set of one`() {
        val st = InMemoryActiveCallStore(ActiveCall("d", "x", 1))
        assertEquals(listOf("x"), st.loadAll().map { it.callId })
        st.save(ActiveCall("d2", "y", 2))
        st.save(ActiveCall("d2", "y", 3))
        assertEquals(listOf("x", "y"), st.loadAll().map { it.callId })
        assertEquals(3, st.loadAll()[1].claimedAtMs, "the same call id is replaced, not added")
        assertEquals("x", st.load()!!.callId)
        st.remove("x")
        assertEquals("y", st.value!!.callId)
        st.remove("nope")
        st.clear()
        assertTrue(st.loadAll().isEmpty())
        st.value = ActiveCall("d", "z", 1)
        assertEquals(1, st.loadAll().size)
    }

    @Test
    fun `AnswerSweep keeps the single-id form`() {
        val cmds = FakeCommands()
        val st = InMemoryActiveCallStore(ActiveCall("d", "x", 1))
        AnswerSweep(cmds, st).run("x")
        assertTrue(cmds.sent.isEmpty())
        AnswerSweep(cmds, st).run(null as String?)
        assertEquals(1, cmds.count("portico/hangup"))
    }
}
