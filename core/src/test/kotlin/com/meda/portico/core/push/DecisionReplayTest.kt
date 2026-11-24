package com.meda.portico.core.push

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pause's replay rule inside the decision: a ring from before the resume is history, never a ring. */
class DecisionReplayTest {
    private val door = "01JABCDEFGHJKMNPQRSTVWXYZ0"
    private val known = setOf(door)
    private val t0 = 1_760_000_000_000L
    private val resumed = t0 + 3_600_000L
    private val store = InMemoryCallStore()

    private fun ring(call: String, t: Long) = PushMessage.parse(
        mapOf("pv" to "2", "kind" to "ring", "door_ref" to door, "call_id" to call, "seq" to "1", "t" to "$t", "exp" to "${t + 45_000}"),
    )

    private fun ended(call: String, t: Long) = PushMessage.parse(
        mapOf("pv" to "2", "kind" to "ended", "door_ref" to door, "call_id" to call, "seq" to "3", "t" to "$t"),
    )

    @Test
    fun `a pushed ring stamped before the resume is recorded as missed and alerts nothing`() {
        val o = Decision.receive(store, ring("gso-1760003590000000000-1-a", resumed - 10_000), resumed + 2_000, known, resumedAtMs = resumed)
        assertEquals(Outcome.Silent(SilentReason.BEFORE_RESUME, o.update), o)
        assertEquals(CallState.MISSED, store.get(CallKey(door, "gso-1760003590000000000-1-a"))?.state)
    }

    @Test
    fun `its later ended stays silent (no Missed call for a ring that never rang)`() {
        val call = "gso-1760003590000000000-1-a"
        Decision.receive(store, ring(call, resumed - 10_000), resumed + 2_000, known, resumedAtMs = resumed)
        val o = Decision.receive(store, ended(call, resumed + 5_000), resumed + 6_000, known, resumedAtMs = resumed)
        assertEquals(SilentReason.AFTER_TERMINAL, (o as Outcome.Silent).reason)
    }

    @Test
    fun `a pushed ring stamped after the resume rings`() {
        val o = Decision.receive(store, ring("gso-1760003601000000000-1-b", resumed + 1_000), resumed + 2_000, known, resumedAtMs = resumed)
        assertInstanceOf(Outcome.Ring::class.java, o)
    }

    @Test
    fun `never paused - resumedAt 0 - is exactly the old behaviour`() {
        val o = Decision.receive(store, ring("gso-1760000000000000000-1-c", t0), t0 + 1_000, known)
        assertInstanceOf(Outcome.Ring::class.java, o)
    }

    @Test
    fun `the socket's snapshot ring for a call begun during the pause is withheld, by the id's stamp`() {
        val doors = DoorsCache(InMemoryDoorsCacheStore()).also { it.replaceAll(listOf(Door("gate-sip.example", door))) }
        val outcomes = ArrayList<Outcome>()
        val sink = SocketCallSink(store, doors, { resumed + 4_000 }, { _, o -> outcomes += o }, {}, resumedAt = { resumed })
        sink.onCallEvent(CallEvent.Ringing("gate-sip.example", door, "gso-1760003000000000000-1-d", snapshot = true))
        assertEquals(SilentReason.BEFORE_RESUME, (outcomes.single() as Outcome.Silent).reason)
        sink.onCallEvent(CallEvent.Ringing("gate-sip.example", door, "gso-1760003603000000000-1-e"))
        assertInstanceOf(Outcome.Ring::class.java, outcomes.last(), "a call begun after the resume rings")
    }

    @Test
    fun `answered and ended are not touched by the replay rule`() {
        val call = "gso-1760003590000000000-1-f"
        Decision.receive(store, ring(call, resumed + 1), resumed + 2, known, resumedAtMs = resumed)
        val o = Decision.receive(store, ended(call, resumed - 1), resumed + 3, known, resumedAtMs = resumed)
        assertTrue(o is Outcome.Missed, "an ended for a ringing call still stops it: $o")
    }
}
