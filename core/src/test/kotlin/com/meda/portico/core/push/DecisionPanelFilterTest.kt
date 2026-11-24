package com.meda.portico.core.push

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.panel.PanelWake
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 slice 2: the panel's door filter sits in the one decision both routes use. */
class DecisionPanelFilterTest {
    private val gateRef = "gate-ref"
    private val frontRef = "front-ref"
    private val doorList = listOf(Door("d-gate", gateRef, "fake:gate"), Door("d-front", frontRef, "fake:front"))
    private val doors = DoorsCache(InMemoryDoorsCacheStore(doorList))
    private var wake: List<String>? = listOf("d-gate")
    private val taken: (String) -> Boolean = { ref -> PanelWake.accepts(wake, ref, doorList.firstOrNull { it.doorRef == ref }) }
    private val now = 1_000_000L

    private fun ring(door: String, call: String) = PushMessage.Ring(door, call, 1, now, now + 45_000)

    @Test
    fun `a ring for a door in wake rings, one outside it is filtered and records nothing`() {
        val store = InMemoryCallStore()
        val ok = Decision.receive(store, ring(gateRef, "c1"), now, doors.knownDoorRefs(), doorTaken = taken)
        assertTrue(ok is Outcome.Ring, "$ok")
        val no = Decision.receive(store, ring(frontRef, "c2"), now, doors.knownDoorRefs(), doorTaken = taken)
        assertEquals(Outcome.Silent(SilentReason.FILTERED, null, frontRef, "c2"), no)
        assertNull(store.get(CallKey(frontRef, "c2")), "nothing recorded for a filtered ring")
    }

    @Test
    fun `an absent wake rings for every door, an empty one for none`() {
        val store = InMemoryCallStore()
        wake = null
        assertTrue(Decision.receive(store, ring(frontRef, "c1"), now, doors.knownDoorRefs(), doorTaken = taken) is Outcome.Ring)
        wake = emptyList()
        val no = Decision.receive(store, ring(gateRef, "c2"), now, doors.knownDoorRefs(), doorTaken = taken)
        assertEquals(SilentReason.FILTERED, (no as Outcome.Silent).reason)
    }

    @Test
    fun `without a filter nothing changes - a phone`() {
        val store = InMemoryCallStore()
        assertTrue(Decision.receive(store, ring(frontRef, "c1"), now, doors.knownDoorRefs()) is Outcome.Ring)
    }

    @Test
    fun `a ring already taken is followed to its end even if wake changes meanwhile`() {
        val store = InMemoryCallStore()
        assertTrue(Decision.receive(store, ring(gateRef, "c1"), now, doors.knownDoorRefs(), doorTaken = taken) is Outcome.Ring)
        wake = listOf("d-front")
        val ended = Decision.receive(store, PushMessage.Ended(gateRef, "c1", 3, now + 1_000), now + 1_000, doors.knownDoorRefs(), doorTaken = taken)
        assertTrue(ended is Outcome.Missed, "$ended")
    }

    @Test
    fun `the socket route filters the same way, and journals nothing it did not decide`() {
        val store = InMemoryCallStore()
        val outcomes = ArrayList<Outcome>()
        val sink = SocketCallSink(store, doors, { now }, { _, o -> outcomes += o }, { }, doorTaken = taken)
        sink.onCallEvent(CallEvent.Ringing("d-gate", gateRef, "c1"))
        sink.onCallEvent(CallEvent.Ringing("d-front", frontRef, "c2"))
        assertTrue(outcomes[0] is Outcome.Ring)
        val f = outcomes[1] as Outcome.Silent
        assertEquals(SilentReason.FILTERED, f.reason)
        assertEquals("c2", f.callId)
        assertEquals(frontRef, f.doorRef)
    }

    @Test
    fun `the push route and the socket route agree on a call both carry`() {
        val store = InMemoryCallStore()
        val sink = SocketCallSink(store, doors, { now }, { _, _ -> }, { }, doorTaken = taken)
        sink.onCallEvent(CallEvent.Ringing("d-gate", gateRef, "c1"))
        val push = Decision.receive(store, ring(gateRef, "c1"), now + 100, doors.knownDoorRefs(), doorTaken = taken)
        assertEquals(Outcome.Silent(SilentReason.STALE), push, "rung once, by whichever came first")
    }
}
