package com.meda.portico.core.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OffscreenGraceTest {
    @Test
    fun `the grace is thirty seconds`() = assertEquals(30_000L, OffscreenGrace.GRACE_MS)

    @Test
    fun `leaves, still live at 29 s, stopped at 30 s`() {
        val g = OffscreenGrace()
        assertTrue(g.onScreen("a", true, 0))
        assertTrue(g.onScreen("a", false, 1_000), "just left: kept")
        assertTrue(g.onScreen("a", false, 1_000 + 29_000), "29 s after leaving: still live")
        assertFalse(g.onScreen("a", false, 1_000 + 30_000), "30 s after leaving: stopped")
        assertFalse(g.onScreen("a", false, 99_000), "and stays stopped")
    }

    @Test
    fun `returns before 30 s, never stopped, and the clock restarts at the next leave`() {
        val g = OffscreenGrace()
        g.onScreen("a", true, 0)
        g.onScreen("a", false, 0)
        assertTrue(g.onScreen("a", false, 29_999))
        assertTrue(g.onScreen("a", true, 29_999), "back in view")
        assertFalse(g.pending("a", 29_999))
        assertTrue(g.onScreen("a", false, 40_000), "left again: a fresh 30 s")
        assertTrue(g.onScreen("a", false, 69_999))
        assertFalse(g.onScreen("a", false, 70_000))
    }

    @Test
    fun `a tile never in view gets no grace, forget and clear cancel the pending stop, tiles are independent`() {
        val g = OffscreenGrace()
        assertFalse(g.onScreen("never", false, 0), "below the fold from the start: no stream")
        g.onScreen("a", true, 0); g.onScreen("b", true, 0)
        g.onScreen("a", false, 0); g.onScreen("b", false, 0)
        assertTrue(g.pending("a", 10_000))
        g.forget("a")
        assertFalse(g.pending("a", 10_000))
        assertTrue(g.pending("b", 10_000))
        g.clear()
        assertFalse(g.pending("b", 10_000))
        assertFalse(g.onScreen("b", false, 10_000), "after a clear nothing is kept")
    }
}
