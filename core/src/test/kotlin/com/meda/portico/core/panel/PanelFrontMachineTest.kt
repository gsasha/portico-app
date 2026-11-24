package com.meda.portico.core.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 slice 2: the return rule (design section 7) with a fake clock. */
class PanelFrontMachineTest {
    private var now = 1_000_000L
    private var idle = 5 * 60_000L
    private val m = PanelFrontMachine({ now }, { idle })

    private fun hand() = m.update(active = true, inFront = true, ringUp = false, callLive = false)
    private fun ring() = m.update(true, true, ringUp = true, callLive = false)
    private fun call() = m.update(true, true, ringUp = false, callLive = true)
    private fun away() = m.update(true, false, false, false)
    private fun at(ms: Long): FrontAction { now += ms; return m.tick() }

    // ---- the idle timeout --------------------------------------------------------------------

    @Test
    fun `opened by hand, no bar until the last 10 seconds, then the return`() {
        hand()
        assertEquals(FrontReason.HAND, m.reason)
        assertEquals(FrontAction.HideBar, at(1_000))
        assertEquals(FrontAction.HideBar, at(4 * 60_000L))
        assertEquals(FrontAction.HideBar, at(40_000), "19 s left: still no bar")
        val bar = at(15_000) as FrontAction.ShowBar // 4 s left
        assertTrue(bar.remainingMs in 1..10_000, "$bar")
        assertEquals(FrontAction.ReturnNow(1, FrontReason.HAND), at(5_000))
    }

    @Test
    fun `a touch starts the idle count over, also inside the bar`() {
        hand()
        at(4 * 60_000L + 55_000L) // 5 s left: the bar is up
        assertTrue(m.tick() is FrontAction.ShowBar)
        m.touch()
        assertEquals(FrontAction.HideBar, m.tick(), "a full timeout again, no bar")
        assertEquals(FrontAction.HideBar, at(4 * 60_000L))
        assertTrue(at(55_000) is FrontAction.ShowBar)
    }

    @Test
    fun `the idle setting is read each time, and zero means no idle return`() {
        idle = 0
        hand()
        assertEquals(FrontAction.HideBar, at(60 * 60_000L))
        assertFalse(m.overdue(), "no timeout, never overdue")
    }

    @Test
    fun `the idle count is suspended while a ring or a call is up`() {
        hand()
        at(60_000)
        ring()
        assertEquals(FrontReason.RING, m.reason)
        assertEquals(FrontAction.HideBar, at(30 * 60_000L), "a ring up for half an hour never returns")
        call()
        assertEquals(FrontReason.CALL, m.reason)
        assertEquals(FrontAction.HideBar, at(30 * 60_000L))
    }

    // ---- after a call or a ring ----------------------------------------------------------------

    @Test
    fun `a call that ends counts a 10 second bar from the end, then returns`() {
        call()
        at(120_000)
        hand() // the call ended, a screen of ours is still in front
        assertEquals(FrontReason.HAND, m.reason)
        val bar = m.tick() as FrontAction.ShowBar
        assertEquals(PanelFrontMachine.RETURN_BAR_MS, bar.totalMs)
        assertEquals(10_000L, bar.remainingMs)
        assertTrue(at(5_000) is FrontAction.ShowBar)
        assertEquals(FrontAction.ReturnNow(1, FrontReason.CALL), at(5_000))
    }

    @Test
    fun `a ring that ends unanswered does the same, and a touch restarts the 10 seconds`() {
        ring()
        at(20_000)
        hand()
        at(9_000)
        m.touch()
        val bar = m.tick() as FrontAction.ShowBar
        assertEquals(10_000L, bar.remainingMs)
        assertEquals(FrontAction.ReturnNow(1, FrontReason.RING), at(10_000))
    }

    @Test
    fun `a ring during a hand-opened session turns the reason to ring, then call, and the rule applies after it`() {
        hand()
        at(30_000)
        ring()
        assertEquals(FrontReason.RING, m.reason)
        call()
        assertEquals(FrontReason.CALL, m.reason)
        hand()
        assertTrue(m.tick() is FrontAction.ShowBar, "after the call: the 10 s bar, not the 5 min idle count")
        assertEquals(FrontAction.ReturnNow(1, FrontReason.CALL), at(10_000))
    }

    @Test
    fun `noteEnded counts at once even when the poll never saw the ring`() {
        hand()
        m.noteEnded() // the ring window closed within one poll
        assertTrue(m.tick() is FrontAction.ShowBar)
        assertEquals(FrontAction.ReturnNow(1, FrontReason.RING), at(10_000))
    }

    @Test
    fun `noteEnded while the reason is still ring waits for the next update`() {
        ring()
        m.noteEnded()
        hand()
        assertTrue(m.tick() is FrontAction.ShowBar)
    }

    @Test
    fun `not in front or not active, nothing counts`() {
        call()
        away()
        assertEquals(FrontAction.HideBar, at(60 * 60_000L))
        m.update(active = false, inFront = true, ringUp = false, callLive = false)
        assertEquals(FrontAction.HideBar, at(60 * 60_000L), "Panel mode off or the app not enrolled")
    }

    // ---- Close and the failed return -------------------------------------------------------------

    @Test
    fun `Close returns at once`() {
        hand()
        m.closeNow()
        assertEquals(FrontAction.ReturnNow(1, FrontReason.HAND), m.tick())
    }

    @Test
    fun `a return that took leaves the front, a failed one is retried once and then reported`() {
        hand()
        m.closeNow()
        assertEquals(FrontAction.ReturnNow(1, FrontReason.HAND), m.tick())
        assertEquals(FrontAction.None, at(2_999), "judged only 3 s after it was issued")
        assertEquals(FrontAction.ReturnFailed(willRetry = true), at(2))
        assertEquals(2, m.retryAttempt)
        assertEquals(FrontAction.None, at(1_000))
        assertEquals(FrontAction.ReturnFailed(willRetry = false), at(2_500))
        assertEquals(FrontAction.HideBar, at(1_000), "after the last failure nothing keeps retrying")
    }

    @Test
    fun `leaving the front after a return was issued is success`() {
        hand()
        m.closeNow()
        m.tick()
        away()
        assertEquals(FrontAction.HideBar, at(10_000))
        hand()
        assertEquals(FrontReason.HAND, m.reason)
        assertEquals(FrontAction.HideBar, at(1_000), "a fresh idle count, not a failed return")
    }

    @Test
    fun `a touch does not cancel a return that is under way`() {
        hand()
        m.closeNow()
        m.tick()
        m.touch()
        assertEquals(FrontAction.ReturnFailed(true), at(3_000))
    }

    // ---- overdue ----------------------------------------------------------------------------------

    @Test
    fun `overdue is hand past the timeout plus 2 minutes, and a touch clears it`() {
        hand()
        at(5 * 60_000L + 119_000L)
        assertFalse(m.overdue())
        at(2_000)
        assertTrue(m.overdue())
        m.touch()
        assertFalse(m.overdue())
    }

    @Test
    fun `overdue is never true for a ring, a call or out of front`() {
        ring()
        at(60 * 60_000L)
        assertFalse(m.overdue())
        call()
        assertFalse(m.overdue())
        away()
        assertFalse(m.overdue())
    }

    @Test
    fun `the idle choices and their conversion`() {
        assertEquals(5, PanelFrontMachine.DEFAULT_IDLE_MIN)
        assertTrue(5 in PanelFrontMachine.IDLE_CHOICES_MIN)
        assertEquals(300_000L, PanelFrontMachine.idleMs(5))
        assertEquals(60_000L, PanelFrontMachine.idleMs(0), "never below a minute")
        assertEquals(3_600_000L, PanelFrontMachine.idleMs(500), "never above an hour")
    }

    // ---- the touch guard and the ring log -----------------------------------------------------------

    // ---- 0.6.97: the events the Journal writes ------------------------------------------------

    private fun ticks(totalMs: Long, stepMs: Long = 250): List<FrontAction> = (0 until totalMs / stepMs).map { at(stepMs) }

    @Test
    fun `a hand-opened screen walks the whole way with a 250 ms tick, arms, shows the bar once, and closes`() {
        hand()
        assertEquals(listOf<FrontEvent>(FrontEvent.Armed(idle)), m.drainEvents())
        val acts = ticks(idle - 11_000)
        assertTrue(acts.all { it == FrontAction.HideBar })
        assertTrue(m.drainEvents().isEmpty(), "nothing is logged while nothing happens")
        val bars = ticks(10_000 - 250).filterIsInstance<FrontAction.ShowBar>()
        assertTrue(bars.isNotEmpty() && bars.first().totalMs == 10_000L && bars.last().remainingMs <= 1_500L, "the bar counts down: $bars")
        assertEquals(listOf<FrontEvent>(FrontEvent.BarStart("idle", 10_000, idle)), m.drainEvents(), "the bar start is one event, not one per tick")
        val close = ticks(2_000).first { it is FrontAction.ReturnNow }
        assertEquals(FrontAction.ReturnNow(1, FrontReason.HAND), close)
        val ev = m.drainEvents().single() as FrontEvent.Closing
        assertEquals("idle", ev.why)
        assertEquals(idle, ev.idleMs)
        assertTrue(ev.sinceTouchMs in idle..idle + 1_000, "$ev")
    }

    @Test
    fun `a touch in the bar cancels it with a reason and the count starts over`() {
        hand(); m.drainEvents()
        at(idle - 5_000)
        assertTrue(m.tick() is FrontAction.ShowBar)
        m.drainEvents()
        m.touch()
        assertEquals(listOf<FrontEvent>(FrontEvent.Cancelled("touch", "idle", barShown = true)), m.drainEvents())
        assertEquals(FrontAction.HideBar, m.tick())
        at(idle - 5_000)
        assertTrue(m.tick() is FrontAction.ShowBar)
        assertEquals(1, m.drainEvents().count { it is FrontEvent.BarStart }, "the bar starts again for the new count")
    }

    @Test
    fun `a touch before the bar restarts the count without a cancel event`() {
        hand(); m.drainEvents()
        at(60_000)
        m.touch()
        assertTrue(m.drainEvents().isEmpty())
    }

    @Test
    fun `a ring or a call during the count cancels it with that reason and never closes`() {
        hand(); m.drainEvents()
        at(idle - 4_000)
        assertTrue(m.tick() is FrontAction.ShowBar)
        m.drainEvents()
        ring()
        assertEquals(listOf<FrontEvent>(FrontEvent.Cancelled("ring", "idle", barShown = true)), m.drainEvents())
        assertEquals(FrontAction.HideBar, at(60_000))
        call()
        assertEquals(FrontAction.HideBar, at(idle * 3))
        assertTrue(m.drainEvents().none { it is FrontEvent.Closing })
    }

    @Test
    fun `leaving the front or Panel mode going off mid-count is a cancel, a return that worked is not`() {
        hand(); m.drainEvents()
        at(30_000)
        away()
        assertEquals(listOf<FrontEvent>(FrontEvent.Cancelled("left-front", "idle", barShown = false)), m.drainEvents())
        hand(); m.drainEvents()
        m.update(active = false, inFront = true, ringUp = false, callLive = false)
        assertEquals(listOf<FrontEvent>(FrontEvent.Cancelled("panel-off", "idle", barShown = false)), m.drainEvents())
        assertEquals(FrontAction.HideBar, at(idle * 2), "panel off: no count at all")
        // a return that worked
        m.update(true, true, false, false); m.drainEvents()
        assertTrue(at(idle) is FrontAction.ReturnNow)
        m.drainEvents()
        away()
        assertTrue(m.drainEvents().isEmpty(), "leaving the front after a return is success, not a cancel")
    }

    @Test
    fun `after a call the bar and the close are reported as after-call, a missed ring as after-ring, Close as close`() {
        hand(); call(); m.drainEvents()
        hand()
        assertEquals(FrontAction.ShowBar(10_000, 10_000), m.tick())
        assertEquals(listOf<FrontEvent>(FrontEvent.BarStart("after-call", 10_000, idle)), m.drainEvents())
        at(10_000)
        assertEquals("after-call", (m.drainEvents().single() as FrontEvent.Closing).why)
        away(); hand(); m.drainEvents()
        m.noteEnded(); m.tick(); m.drainEvents()
        m.touch()
        assertEquals(FrontEvent.Cancelled("touch", "after-ring", true), m.drainEvents().single())
        m.closeNow()
        assertTrue(m.tick() is FrontAction.ReturnNow)
        assertEquals("close", (m.drainEvents().last() as FrontEvent.Closing).why)
    }

    @Test
    fun `zero idle arms with idle zero and never counts`() {
        idle = 0
        hand()
        assertEquals(listOf<FrontEvent>(FrontEvent.Armed(0)), m.drainEvents())
        assertEquals(FrontAction.HideBar, at(60 * 60_000L))
    }

    @Test
    fun `the touch guard swallows the first 700 ms`() {
        val g = TouchGuard(shownAtMs = 10_000)
        assertFalse(g.accepts(10_000))
        assertFalse(g.accepts(10_699))
        assertTrue(g.accepts(10_700))
    }

    @Test
    fun `the ring log keeps the last ring and counts direct and fsi as shown`() {
        val log = PanelRingLog()
        assertNull(log.last)
        assertFalse(log.shown())
        log.record("gso-1", "d-gate", RingPath.DIRECT)
        assertTrue(log.shown())
        log.record("gso-1", "d-gate", RingPath.BANNER)
        assertFalse(log.shown(), "a banner is not a window")
        log.record("gso-2", "d-front", RingPath.FSI)
        assertTrue(log.shown())
        assertEquals("gso-2", log.last?.callId)
        log.record("gso-3", "d-front", RingPath.FILTERED)
        assertFalse(log.shown())
        assertEquals(listOf("direct", "fsi", "banner", "failed", "filtered"), RingPath.entries.map { it.wire })
    }
}
