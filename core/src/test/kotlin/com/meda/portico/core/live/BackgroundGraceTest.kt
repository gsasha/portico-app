package com.meda.portico.core.live

import com.meda.portico.core.live.BackgroundGrace.Back
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundGraceTest {
    @Test
    fun `the grace is the offscreen one, thirty seconds`() {
        val g = BackgroundGrace()
        g.leave(0)
        assertTrue(g.holding(OffscreenGrace.GRACE_MS - 1))
        assertFalse(g.holding(OffscreenGrace.GRACE_MS))
        assertEquals(30_000L, OffscreenGrace.GRACE_MS)
    }

    @Test
    fun `leaves at t0, still live at 29 s, stopped at 30 s`() {
        val g = BackgroundGrace()
        assertTrue(g.leave(5_000))
        assertTrue(g.holding(5_000))
        assertTrue(g.holding(5_000 + 29_000), "29 s: still live")
        assertFalse(g.holding(5_000 + 30_000), "30 s: stopped")
        assertTrue(g.expire(), "the timer stops it once")
        assertFalse(g.expire(), "and only once")
        assertEquals(Back.NONE, g.back(99_000), "nothing left to cancel: the caller starts normally")
    }

    @Test
    fun `returns at 10 s, never stopped, no restart`() {
        val g = BackgroundGrace()
        g.leave(0)
        assertEquals(Back.CANCELLED, g.back(10_000))
        assertFalse(g.pending())
        assertFalse(g.expire(), "a timer that fires after the cancel stops nothing")
        assertFalse(g.holding(10_000))
    }

    @Test
    fun `returns at 31 s, expired, the caller stops (if the timer did not) and restarts normally`() {
        val g = BackgroundGrace()
        g.leave(0)
        assertEquals(Back.EXPIRED, g.back(31_000))
        assertEquals(Back.NONE, g.back(32_000))
    }

    @Test
    fun `back at 29999 ms is a cancel, at 30000 ms an expiry`() {
        val a = BackgroundGrace(); a.leave(0)
        assertEquals(Back.CANCELLED, a.back(29_999))
        val b = BackgroundGrace(); b.leave(0)
        assertEquals(Back.EXPIRED, b.back(30_000))
    }

    @Test
    fun `a second leave does not restart the clock, and a new visit gets a fresh 30 s`() {
        val g = BackgroundGrace()
        assertTrue(g.leave(0))
        assertFalse(g.leave(20_000), "already counting from 0")
        assertFalse(g.holding(30_000))
        g.back(30_000)
        assertTrue(g.leave(100_000))
        assertTrue(g.holding(129_999))
        assertEquals(10_000L, g.awayMs(110_000))
    }

    @Test
    fun `clear drops the countdown with no stop pending`() {
        val g = BackgroundGrace()
        g.leave(0)
        g.clear()
        assertFalse(g.pending())
        assertFalse(g.expire())
        assertEquals(Back.NONE, g.back(1_000))
    }

    @Test
    fun `the journal lines are one short line each`() {
        assertEquals("live grace start 30s", BackgroundGrace.line(BackgroundGrace.Step.START))
        assertEquals("live grace cancel after 10s", BackgroundGrace.line(BackgroundGrace.Step.CANCEL, 10_400))
        assertEquals("live grace expire", BackgroundGrace.line(BackgroundGrace.Step.EXPIRE))
    }
}
