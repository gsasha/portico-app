package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.64: one ring channel, the original id; every id an earlier version derived from a sound or a mode is stale. */
class RingChannelIdsTest {
    @Test
    fun `the one ring channel keeps the id of every version before the ringing modes`() {
        assertEquals("portico_ring_v2", RingChannelIds.BASE_ID)
        assertEquals(Channel.RING.id, RingChannelIds.BASE_ID)
    }

    @Test
    fun `the ring family is the base id and the ids derived from a sound or a mode`() {
        for (id in listOf("portico_ring_v2", "portico_ring_v2_s0a1b2c3d", "portico_ring_v2_mr", "portico_ring_v2_mr_s0a1b2c3d", "portico_ring_v2_mv", "portico_ring_v2_mq")) {
            assertTrue(RingChannelIds.isRingId(id), id)
        }
        for (id in listOf("portico_ring_v1", "portico_status_v1", "portico_paused_v1", "portico_connection_v1", "portico_ring_v2_sZZZZZZZZ", "portico_ring_v2_s1234", "portico_ring_v2_mx")) {
            assertFalse(RingChannelIds.isRingId(id), id)
        }
    }

    @Test
    fun `stale ids are the other ring-family ids plus the retired ones, never the base id or another channel`() {
        val existing = listOf(
            "portico_ring_v1", "portico_ring_v2", "portico_ring_v2_s0a1b2c3d", "portico_ring_v2_mr", "portico_ring_v2_mv", "portico_ring_v2_mq",
            "portico_status_v1", "portico_paused_v1", "portico_connection_v1",
        )
        val stale = RingChannelIds.staleIds(existing)
        assertEquals(setOf("portico_ring_v1", "portico_ring_v2_s0a1b2c3d", "portico_ring_v2_mr", "portico_ring_v2_mv", "portico_ring_v2_mq"), stale.toSet())
        assertFalse("portico_ring_v2" in stale)
        assertFalse("portico_status_v1" in stale)
        // idempotent: after the cleanup only the retired ids are ever listed
        assertEquals(Channel.RETIRED_IDS.toSet(), RingChannelIds.staleIds(listOf("portico_ring_v2", "portico_status_v1")).toSet())
        assertFalse("portico_ring_v2" in RingChannelIds.staleIds(emptyList()))
    }

    @Test
    fun `0_6_76 - the quiet window channel is not in the ring family and the cleanup never lists it`() {
        assertEquals("portico_ring_window", Channel.RING_WINDOW.id)
        assertFalse(RingChannelIds.isRingId(Channel.RING_WINDOW.id))
        val existing = listOf("portico_ring_v1", "portico_ring_v2", "portico_ring_window", "portico_ring_v2_mq", "portico_status_v1")
        val stale = RingChannelIds.staleIds(existing)
        assertFalse("portico_ring_window" in stale)
        assertEquals(setOf("portico_ring_v1", "portico_ring_v2_mq"), stale.toSet())
        // even if it were passed as "active" nothing breaks, and a ring-family look-alike is still swept
        assertFalse("portico_ring_window" in RingChannelIds.staleIds(listOf("portico_ring_window")))
        assertEquals(Importance.LOW, Channel.RING_WINDOW.importance)
        assertFalse(Channel.RING_WINDOW.bypassDnd)
    }
}
