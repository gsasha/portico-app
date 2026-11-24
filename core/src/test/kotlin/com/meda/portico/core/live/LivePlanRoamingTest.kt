package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A-10 / DEV-702: what the roaming block does to the live plan. */
class LivePlanRoamingTest {
    private val g = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT, roamingBlocked = true)
    private fun tile(id: String, wants: Boolean = true, available: Boolean = true) = LiveTileIn(id, CameraSize.BIG, available, wants, true)

    @Test
    fun `roaming opens nothing and every usable tile shows the notice`() {
        val r = LivePlan.plan(g, listOf(tile("a"), tile("b", wants = false), tile("c", available = false)))
        assertTrue(r.wanted.isEmpty())
        assertEquals(StopWhy.ROAMING, r.idle)
        for (id in listOf("a", "b")) {
            val ui = TileLiveUi.of(r.tiles.getValue(id), null, null, "--", false, false)
            assertEquals(ChipLook.ROAMING, ui.chip, id)
            assertEquals(LiveWhy.ROAMING, ui.notice, id)
            assertFalse(ui.video, id)
        }
        assertEquals(ChipLook.NONE, TileLiveUi.of(r.tiles.getValue("c"), null, null, "--", false, false).chip)
    }

    @Test
    fun `stills are held back even with the live switch off, but not on a closed screen`() {
        val off = LivePlan.plan(g.copy(switchOn = false), listOf(tile("a")))
        assertEquals(LiveWhy.ROAMING, TileLiveUi.of(off.tiles.getValue("a"), null, null, "--", false, false).notice)
        for (closed in listOf(g.copy(shown = false), g.copy(paused = true), g.copy(connected = false))) {
            val r = LivePlan.plan(closed, listOf(tile("a")))
            assertTrue(r.tiles.isEmpty())
            assertTrue(r.wanted.isEmpty())
        }
    }

    @Test
    fun `lifting the block gives the ordinary plan back`() {
        val r = LivePlan.plan(g.copy(roamingBlocked = false), listOf(tile("a")))
        assertEquals(1, r.wanted.size)
        assertFalse(r.tiles.getValue("a").roaming)
    }
}
