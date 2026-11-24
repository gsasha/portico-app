package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A-03: the channel spec and its migration. A channel's bypass and sound cannot change after creation, so a change is a new id. */
class ChannelSpecTest {
    @Test
    fun `only the door ring channel asks to bypass Do Not Disturb`() {
        assertTrue(Channel.RING.bypassDnd)
        for (c in Channel.values().filter { it != Channel.RING }) assertFalse(c.bypassDnd, "${c.id} must stay quiet under DND")
    }

    @Test
    fun `the ring channel moved to v2 and v1 is retired, never an active id`() {
        assertEquals("portico_ring_v2", Channel.RING.id)
        assertTrue("portico_ring_v1" in Channel.RETIRED_IDS)
        val active = Channel.values().map { it.id }
        assertTrue(active.none { it in Channel.RETIRED_IDS }, "start-up would delete a live channel")
        assertEquals(active.size, active.toSet().size)
    }
}
