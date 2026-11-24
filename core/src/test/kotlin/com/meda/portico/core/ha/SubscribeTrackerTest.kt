package com.meda.portico.core.ha

import com.meda.portico.core.ha.CallEvent.Active
import com.meda.portico.core.ha.CallEvent.Answered
import com.meda.portico.core.ha.CallEvent.Ended
import com.meda.portico.core.ha.CallEvent.NoLiveCall
import com.meda.portico.core.ha.CallEvent.Ringing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The `portico/calls/subscribe` events (contract 3). The recorded exchange itself is replayed by [WsContractReplayTest]. */
class SubscribeTrackerTest {
    private val gate = WsContract.GATE
    private val gateRef = WsContract.GATE_REF
    private val cache = DoorsCache(InMemoryDoorsCacheStore(listOf(Door(gate, gateRef))))
    private val tracker = SubscribeTracker(cache)

    private fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"

    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun feed(json: String) = tracker.onEvent(ev(json))

    /** A live event: `{type, door_id, call_id, state, prev_state}`. */
    private fun live(doorId: String, callId: String?, state: String, prev: String = "idle") =
        """{"type":"call_state_changed","door_id":"$doorId","call_id":${q(callId)},"state":"$state","prev_state":"$prev"}"""

    /** A snapshot: `{type, snapshot: true, door_id, call_id, state}`. */
    private fun snapshot(doorId: String, callId: String?, state: String) =
        """{"type":"call_state_changed","snapshot":true,"door_id":"$doorId","call_id":${q(callId)},"state":"$state"}"""

    @Test
    fun `ring, answer, active, then ended names the call - and no idle follows`() {
        assertEquals(listOf(Ringing(gate, gateRef, "c1")), feed(live(gate, "c1", "ringing")))
        assertEquals("c1", tracker.followedCall(gate))
        assertEquals(listOf(Answered(gate, gateRef, "c1")), feed(live(gate, "c1", "answered", "ringing")))
        assertEquals(listOf(Active(gate, gateRef, "c1")), feed(live(gate, "c1", "active", "answered")))
        assertEquals(listOf(Ended(gate, gateRef, "c1", inferred = false)), feed(live(gate, "c1", "ended", "active")))
        assertNull(tracker.followedCall(gate))
        assertEquals(emptyList<CallEvent>(), feed(live(gate, "c1", "ended", "active")), "a repeat of the end is dropped")
    }

    @Test
    fun `a live idle means nothing - contract 3 sends no idle echo, so an end is never inferred from one`() {
        feed(live(gate, "c1", "ringing"))
        assertEquals(emptyList<CallEvent>(), feed(live(gate, null, "idle", "ringing")))
        assertEquals("c1", tracker.followedCall(gate), "the call is still followed until it is ended by name")
    }

    @Test
    fun `an ended for a call this phone never saw is still reported (the store records it)`() {
        assertEquals(listOf(Ended(gate, gateRef, "c9", inferred = false)), feed(live(gate, "c9", "ended", "ringing")))
    }

    @Test
    fun `an ended with no call id ends the followed call, as an inferred end`() {
        feed(live(gate, "c1", "ringing"))
        assertEquals(listOf(Ended(gate, gateRef, "c1", inferred = true)), feed(live(gate, null, "ended", "ringing")))
    }

    @Test
    fun `a snapshot idle says no live call, and ends a call this phone was still following`() {
        assertEquals(listOf(NoLiveCall(gate, gateRef)), feed(snapshot(gate, null, "idle")))
        feed(live(gate, "c1", "ringing"))
        assertEquals(
            listOf(Ended(gate, gateRef, "c1", inferred = true), NoLiveCall(gate, gateRef)),
            feed(snapshot(gate, null, "idle")),
        )
    }

    @Test
    fun `a door that left is a snapshot with state unavailable - no live call, the followed one ended`() {
        feed(live(gate, "c1", "ringing"))
        assertEquals(
            listOf(Ended(gate, gateRef, "c1", inferred = true), NoLiveCall(gate, gateRef)),
            feed(snapshot(gate, null, "unavailable")),
        )
        assertNull(tracker.followedCall(gate))
        // only a snapshot can say it: a live `unavailable` is not in the contract
        assertEquals(emptyList<CallEvent>(), feed(live(gate, null, "unavailable")))
    }

    @Test
    fun `a snapshot of a live call is flagged as a snapshot`() {
        assertEquals(listOf(Ringing(gate, gateRef, "c2", snapshot = true)), feed(snapshot(gate, "c2", "ringing")))
        assertEquals(listOf(Answered(gate, gateRef, "c2", snapshot = true)), feed(snapshot(gate, "c2", "answered")))
    }

    @Test
    fun `the old snapshot markers are not read - only snapshot true is one`() {
        // contract 2's app also accepted type "snapshot" / "call_state_snapshot"; the server never sent them
        assertEquals(emptyList<CallEvent>(), tracker.onEvent(ev("""{"type":"snapshot","door_id":"$gate","call_id":"c3","state":"ringing"}""")))
        assertEquals(emptyList<CallEvent>(), tracker.onEvent(ev("""{"type":"call_state_snapshot","door_id":"$gate","call_id":"c3","state":"ringing"}""")))
    }

    @Test
    fun `a new call replacing the followed one ends it`() {
        feed(live(gate, "c1", "ringing"))
        assertEquals(
            listOf(Ended(gate, gateRef, "c1", inferred = true), Ringing(gate, gateRef, "c2")),
            feed(live(gate, "c2", "ringing")),
        )
    }

    @Test
    fun `the door_ref comes from the cache - an unknown door has a null door_ref`() {
        assertEquals(listOf(Ringing("unknown-door", null, "c5")), feed(live("unknown-door", "c5", "ringing")))
        // a ref the event itself might carry is not read: contract 3 events carry door_id only
        assertEquals(
            listOf(Ringing(gate, gateRef, "c6")),
            tracker.onEvent(ev("""{"type":"call_state_changed","door_id":"$gate","door_ref":"d-not-the-cache","entry_id":"OTHER","call_id":"c6","state":"ringing"}""")),
        )
    }

    @Test
    fun `an event keyed by contract 2's device_id is not a call event`() {
        assertEquals(emptyList<CallEvent>(), tracker.onEvent(ev("""{"type":"call_state_changed","device_id":"$gate","call_id":"c1","state":"ringing"}""")))
    }

    @Test
    fun `devices_changed is not an event the app reads - the server never sent one`() {
        assertEquals(emptyList<CallEvent>(), tracker.onEvent(ev("""{"type":"devices_changed"}""")))
        assertNull(SubscribeTracker.doorOf(ev("""{"type":"devices_changed"}""")))
    }

    @Test
    fun `the end memory is bounded`() {
        repeat(SubscribeTracker.RECENT_ENDS + 10) { feed(live(gate, "c$it", "ended", "ringing")) }
        assertEquals(1, feed(live(gate, "c0", "ended", "ringing")).size, "c0 fell out of the bounded memory")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{}""",
            """{"type":"call_state_changed"}""",
            """{"type":"call_state_changed","door_id":5,"call_id":"c","state":"ringing"}""",
            """{"type":"call_state_changed","door_id":"d","call_id":null,"state":"ringing"}""",
            """{"type":"call_state_changed","door_id":"d","call_id":"c","state":"exploded"}""",
            """{"type":"call_state_changed","door_id":"d","call_id":"c"}""",
            """{"type":"call_state_changed","door_id":"d","call_id":null,"state":"ended"}""",
            """{"type":"something_else","door_id":"d","call_id":"c","state":"ringing"}""",
            """{"type":"house_call","door_id":"d","call_id":"c","state":"ringing"}""",
        ],
    )
    fun `malformed or foreign events produce nothing`(event: String) {
        assertEquals(emptyList<CallEvent>(), tracker.onEvent(Json.parseToJsonElement(event).jsonObject))
    }

    @Test
    fun `doorOf and doorLeft read only call_state_changed events`() {
        assertEquals(gate, SubscribeTracker.doorOf(ev(live(gate, "c", "ringing"))))
        assertNull(SubscribeTracker.doorOf(ev("""{"type":"content_changed","door_id":"x"}""")))
        assertEquals(gate, SubscribeTracker.doorLeft(ev(snapshot(gate, null, "unavailable"))))
        assertNull(SubscribeTracker.doorLeft(ev(snapshot(gate, null, "idle"))))
        assertNull(SubscribeTracker.doorLeft(ev(live(gate, null, "unavailable"))))
    }

    // ---- P3-C: a dialled call is never followed, and talk_state is not call state ------------------------------

    @Test
    fun `a dialled call's ring is dropped - nothing followed, and a real followed call is not replaced by it`() {
        feed(live(gate, "c1", "ringing"))
        assertEquals(emptyList<CallEvent>(), feed(live(gate, "out-0123456789abcdef", "ringing")))
        assertEquals("c1", tracker.followedCall(gate), "the real call is still the followed one (no inferred end)")
        assertEquals(emptyList<CallEvent>(), feed(live(gate, "out-0123456789abcdef", "answered", "ringing")))
        assertEquals(emptyList<CallEvent>(), feed(live(gate, "out-0123456789abcdef", "active", "answered")))
        assertEquals(emptyList<CallEvent>(), feed(live(gate, "out-0123456789abcdef", "ended", "active")))
        assertEquals("c1", tracker.followedCall(gate))
        assertEquals(listOf(Answered(gate, gateRef, "c1")), feed(live(gate, "c1", "answered", "ringing")), "the real call carries on")
    }

    @Test
    fun `a dialled call's snapshot is dropped too`() {
        assertEquals(emptyList<CallEvent>(), feed(snapshot(gate, "out-0123456789abcdef", "ringing")))
        assertNull(tracker.followedCall(gate))
    }

    @Test
    fun `an id that only contains out- or differs in case is an ordinary call`() {
        assertEquals(Ringing(gate, gateRef, "gso-out-1"), feed(live(gate, "gso-out-1", "ringing")).last())
        assertEquals(Ringing(gate, gateRef, "OUT-1"), feed(live(gate, "OUT-1", "ringing")).last())
        assertEquals(Ringing(gate, gateRef, "outbound-1"), feed(live(gate, "outbound-1", "ringing")).last())
    }

    @Test
    fun `a talk_state event is not call state - the tracker yields nothing and follows nothing`() {
        feed(live(gate, "c1", "ringing"))
        for (state in listOf("dialing", "in_call", "ended")) {
            assertEquals(
                emptyList<CallEvent>(),
                feed("""{"type":"talk_state","door_id":"$gate","call_id":"out-0123456789abcdef","state":"$state","reason":"busy"}"""),
            )
        }
        assertEquals("c1", tracker.followedCall(gate))
        assertNull(SubscribeTracker.doorOf(ev("""{"type":"talk_state","door_id":"$gate","call_id":"out-0123456789abcdef","state":"in_call"}""")))
    }
}
