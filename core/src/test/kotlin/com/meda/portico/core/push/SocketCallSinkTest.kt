package com.meda.portico.core.push

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PR-E: D's [com.meda.portico.core.ha.CallStateSink] adapted onto B's one [CallStore]. */
class SocketCallSinkTest {
    private val door = "01J9ENTRYGATE"
    private val device = "gate-sip.example"
    private var now = 1_000_000L
    private val store = InMemoryCallStore()
    private val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door(device, door))))
    private val outcomes = ArrayList<Outcome>()
    private val logs = ArrayList<String>()
    private val sink = SocketCallSink(store, doors, { now }, { _, o -> outcomes += o }, { logs += it })

    @Test
    fun `a socket ring for a known door rings and is recorded as ws`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        val o = outcomes.single()
        assertTrue(o is Outcome.Ring, "$o")
        assertEquals(Source.WS, store.get(CallKey(door, "c1"))?.source)
    }

    @Test
    fun `cross-path dedup - a push ring after the socket ring is silent`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        val push = PushMessage.Ring(door, "c1", 1, now, now + 45_000)
        assertEquals(Outcome.Silent(SilentReason.STALE), Decision.receive(store, push, now + 500, doors.knownDoorRefs()))
    }

    @Test
    fun `cross-path dedup - a socket ring after the push ring is silent`() {
        Decision.receive(store, PushMessage.Ring(door, "c1", 1, now, now + 45_000), now, doors.knownDoorRefs())
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        assertEquals(listOf<Outcome>(Outcome.Silent(SilentReason.STALE)), outcomes)
    }

    @Test
    fun `answered and active stop a ringing call`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        sink.onCallEvent(CallEvent.Answered(device, door, "c1"))
        assertTrue(outcomes[1] is Outcome.Dismiss, "${outcomes[1]}")
        sink.onCallEvent(CallEvent.Ringing(device, door, "c2"))
        sink.onCallEvent(CallEvent.Active(device, door, "c2"))
        assertTrue(outcomes[3] is Outcome.Dismiss, "${outcomes[3]}")
    }

    @Test
    fun `an inferred end of a ringing call is missed`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        sink.onCallEvent(CallEvent.Ended(device, door, "c1", inferred = true))
        val o = outcomes[1] as Outcome.Missed
        assertEquals(MissedReason.ENDED_UNANSWERED, o.reason)
        assertEquals(CallState.ENDED, store.get(CallKey(door, "c1"))?.state)
    }

    @Test
    fun `a snapshot with no live call ends the door's ringing call (HA is authoritative)`() {
        Decision.receive(store, PushMessage.Ring(door, "c1", 1, now, now + 45_000), now, doors.knownDoorRefs())
        sink.onCallEvent(CallEvent.NoLiveCall(device, door))
        assertTrue(outcomes.single() is Outcome.Missed, "$outcomes")
    }

    @Test
    fun `a snapshot with no live call and nothing ringing does nothing`() {
        sink.onCallEvent(CallEvent.NoLiveCall(device, door))
        assertEquals(emptyList<Outcome>(), outcomes)
    }

    @Test
    fun `a door missing from the cache (no door ref) is silent and records nothing`() {
        sink.onCallEvent(CallEvent.Ringing("unknown.example", null, "c9"))
        assertEquals(emptyList<Outcome>(), outcomes)
        assertEquals(0, store.size)
        assertTrue(logs.any { "unknown door" in it }, "$logs")
    }

    @Test
    fun `a door ref the event carries is known to the decision even when the cache snapshot predates it`() {
        sink.onCallEvent(CallEvent.Ringing("new.example", "01J9ENTRYNEW", "c5"))
        assertTrue(outcomes.single() is Outcome.Ring, "$outcomes")
    }

    @Test
    fun `the decision line is logged`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        assertTrue(logs.any { it.startsWith("portico push: ring door=$door call=c1") && "via=ws" in it }, "$logs")
        assertNull(store.get(CallKey(door, "other")))
    }

    // ---- P3-C: the second layer - a dialled call never rings --------------------------------------------------

    private val dialled = "out-0123456789abcdef"

    @Test
    fun `an out- ring through the socket sink produces no outcome, no record and a journal line`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, dialled))
        sink.onCallEvent(CallEvent.Ringing(device, door, dialled, snapshot = true))
        assertEquals(emptyList<Outcome>(), outcomes, "no Ring, no Missed, no Dismiss: no notification, no full-screen intent")
        assertNull(store.get(CallKey(door, dialled)), "nothing recorded, so nothing can post a Missed call later")
        assertEquals(2, logs.count { "dialled call $dialled" in it && "never rings" in it }, "$logs")
    }

    @Test
    fun `every kind of event about a dialled call is dropped`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, dialled))
        sink.onCallEvent(CallEvent.Answered(device, door, dialled))
        sink.onCallEvent(CallEvent.Active(device, door, dialled))
        sink.onCallEvent(CallEvent.Ended(device, door, dialled, inferred = false))
        sink.onCallEvent(CallEvent.Ended(device, door, dialled, inferred = true))
        assertEquals(emptyList<Outcome>(), outcomes)
        assertNull(store.get(CallKey(door, dialled)))
    }

    @Test
    fun `a dialled call is dropped even for a door the cache does not know`() {
        sink.onCallEvent(CallEvent.Ringing("unknown.example", null, dialled))
        assertEquals(emptyList<Outcome>(), outcomes)
        assertTrue(logs.single().contains("never rings"), "$logs")
    }

    @Test
    fun `a normal ring still rings, before and after a dialled one, and its answer still dismisses`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, dialled))
        sink.onCallEvent(CallEvent.Ringing(device, door, "c1"))
        sink.onCallEvent(CallEvent.Ended(device, door, dialled, inferred = false))
        sink.onCallEvent(CallEvent.Answered(device, door, "c1"))
        assertEquals(2, outcomes.size, "$outcomes")
        assertTrue(outcomes[0] is Outcome.Ring, "${outcomes[0]}")
        assertTrue(outcomes[1] is Outcome.Dismiss, "${outcomes[1]}")
    }

    @Test
    fun `an id that only contains out- is an ordinary call and rings`() {
        sink.onCallEvent(CallEvent.Ringing(device, door, "gso-out-1"))
        assertTrue(outcomes.single() is Outcome.Ring, "$outcomes")
    }
}
