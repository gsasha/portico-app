package com.meda.portico.core.call

import com.meda.portico.core.call.HouseRingCoordinator.Dismiss
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 section 5 item 3 R8: while the panel's phone endpoint is unknown its house events are held (8, 10 s), answered and ended too. */
class HouseRingBufferTest {
    private val r = HouseRingRig().apply { buffer = true; endpoint = null }

    private fun ended(id: String) = r.c.onHouseCall(HouseCallEvent(id, "ended", "hangup", "kitchen", "ep-alice", false))

    @Test
    fun `a ring while the endpoint is unknown is held, and rings when the id becomes known`() {
        r.ring()
        assertTrue(r.shown.isEmpty())
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertEquals("hso-1", r.shown.single().callId)
    }

    @Test
    fun `a late ended for a held ring leaves no ring up - the ended is buffered with the ring and replayed after it`() {
        r.ring()
        ended("hso-1")
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertNull(r.c.state().ring)
        assertEquals(listOf("hso-1" to Dismiss.ENDED), r.dismissed)
    }

    @Test
    fun `an answered elsewhere replays in order too`() {
        r.ring()
        r.c.onHouseCall(HouseCallEvent("hso-1", "answered", null, "kitchen", "ep-alice", false))
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertNull(r.c.state().ring)
        assertEquals(Dismiss.ANSWERED_ELSEWHERE, r.dismissed.single().second)
    }

    @Test
    fun `an event older than 10 seconds is dropped when the id arrives`() {
        r.ring("hso-old")
        r.now += 10_001
        r.ring("hso-new")
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertEquals(listOf("hso-new"), r.shown.map { it.callId })
    }

    @Test
    fun `exactly 10 seconds is still inside the window`() {
        r.ring("hso-1")
        r.now += 10_000
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertEquals(listOf("hso-1"), r.shown.map { it.callId })
    }

    @Test
    fun `at most 8 events are held, the oldest go first`() {
        for (i in 1..10) r.ring("hso-$i")
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        // each replayed ring replaces the previous one on screen; the ones that were shown are the 8 newest, in order
        assertEquals((3..10).map { "hso-$it" }, r.shown.map { it.callId })
    }

    @Test
    fun `the buffer is drained once - a second onEndpointKnown replays nothing`() {
        r.ring()
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        r.c.onEndpointKnown()
        assertEquals(1, r.shown.size)
    }

    @Test
    fun `a closed socket drops what was held for it`() {
        r.ring()
        r.c.onLinkLost()
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertTrue(r.shown.isEmpty())
    }

    @Test
    fun `a held ring does not ring its own outgoing Everyone - the replay applies the same from-me rule`() {
        r.ring(callId = "hso-mine", from = "ep-alice", to = "ep-bob")
        r.endpoint = "ep-alice"
        r.c.onEndpointKnown()
        assertTrue(r.shown.isEmpty())
    }

    @Test
    fun `an endpoint that never becomes known rings nothing`() {
        r.ring()
        r.c.onEndpointKnown()
        assertTrue(r.shown.isEmpty())
    }

    @Test
    fun `with buffering off (a phone) an event with no endpoint is ignored as before and nothing is held`() {
        val p = HouseRingRig().apply { buffer = false; endpoint = null }
        p.ring()
        p.endpoint = "ep-alice"
        p.c.onEndpointKnown()
        assertTrue(p.shown.isEmpty())
    }

    @Test
    fun `with the endpoint known the buffer is not used - a ring is shown at once`() {
        r.endpoint = "ep-alice"
        r.ring()
        assertEquals(1, r.shown.size)
    }
}
