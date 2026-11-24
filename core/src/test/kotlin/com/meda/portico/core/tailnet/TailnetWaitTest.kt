package com.meda.portico.core.tailnet

import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.ConnectTimeline.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-694: the controller waits for the Go side's change instead of polling, and records tsnet's own phase times. */
class TailnetWaitTest {
    private var now = 1_000_000L
    private val timeline = ConnectTimeline { now }
    private val engine = FakeEngine().apply { identity = true }
    private val keys = FakeKeys()
    private val sleeps = mutableListOf<Long>()

    private fun controller() = TailnetController(
        engine, keys, hostname = { "p" }, target = { "192.168.1.5:443" }, wallClockMs = { now },
        sleeper = { sleeps.add(it); now += it }, timeline = timeline,
    )

    private fun upOnSecondStatus() {
        engine.onStatus = { e -> if (e.joins.isNotEmpty() && e.statusCalls >= 5) e.status = "up\t100.70.97.30" }
    }

    @Test
    fun `an engine that blocks for the change replaces the sleep`() {
        engine.handlesWait = true
        upOnSecondStatus()
        timeline.begin()
        assertNotNull(controller().awaitForwarder(10_000))
        assertTrue(engine.waits > 0)
        assertTrue(sleeps.isEmpty(), "the wait was done by the engine; sleeping as well would add 250 ms")
    }

    @Test
    fun `an engine that cannot wait keeps the old poll-and-sleep`() {
        engine.handlesWait = false
        upOnSecondStatus()
        timeline.begin()
        assertNotNull(controller().awaitForwarder(10_000))
        assertTrue(sleeps.isNotEmpty() && sleeps.all { it == TailnetController.POLL_MS })
    }

    @Test
    fun `an engine whose wait throws falls back to sleeping`() {
        val e = object : FakeEngine() {
            override fun awaitStatusChange(timeoutMs: Long): Boolean = throw IllegalStateException("go")
        }.apply { identity = true }
        e.onStatus = { x -> if (x.joins.isNotEmpty() && x.statusCalls >= 5) x.status = "up\t100.70.97.30" }
        timeline.begin()
        val c = TailnetController(e, keys, hostname = { "p" }, target = { "192.168.1.5:443" }, wallClockMs = { now }, sleeper = { sleeps.add(it); now += it }, timeline = timeline)
        assertNotNull(c.awaitForwarder(10_000))
        assertTrue(sleeps.isNotEmpty())
    }

    @Test
    fun `tsnet's own phase times join the timeline when this connect brought the node up`() {
        engine.phases = "needslogin+13ms starting+31ms running+287ms"
        engine.handlesWait = true
        upOnSecondStatus()
        timeline.begin()
        assertNotNull(controller().awaitForwarder(10_000))
        val s = timeline.snapshot()!!
        assertEquals("needslogin+13ms starting+31ms running+287ms", s.note)
        assertTrue(ConnectTimeline.summary(s)!!.endsWith("[tsnet needslogin+13ms starting+31ms running+287ms]"), ConnectTimeline.summary(s))
    }

    @Test
    fun `a node that was already warm adds no stale phases`() {
        engine.phases = "starting+31ms running+287ms"
        engine.status = "up\t100.70.97.30"
        timeline.begin()
        assertNotNull(controller().awaitForwarder(10_000))
        assertNull(timeline.snapshot()!!.note)
        assertTrue(Stage.LIBRARY_READY !in timeline.snapshot()!!.marks.map { it.stage })
    }

    // ---- the timeline's note itself ----

    private val t = ConnectTimeline { now }

    @Test
    fun `a note needs a connect, is words and numbers only, and a new connect clears it`() {
        t.note("starting+1ms") // no connect begun
        assertNull(t.snapshot())
        t.begin()
        t.mark(Stage.TAILNET_OPENED)
        t.note("starting+31ms running+287ms")
        assertEquals("starting+31ms running+287ms", t.snapshot()!!.note)
        t.note("see http://evil.example/x?tskey=abc")
        assertNull(t.snapshot()!!.note, "anything that is not words and numbers is dropped")
        t.note("a".repeat(121))
        assertNull(t.snapshot()!!.note)
        t.note("running+9ms")
        t.begin()
        assertNull(t.snapshot()!!.note)
        t.note("   ")
        assertNull(t.snapshot()!!.note)
    }

    @Test
    fun `has reports the recorded stages of the current connect only`() {
        assertEquals(false, t.has(Stage.LIBRARY_READY))
        t.begin()
        t.mark(Stage.LIBRARY_READY)
        assertEquals(true, t.has(Stage.LIBRARY_READY))
        t.begin()
        assertEquals(false, t.has(Stage.LIBRARY_READY))
    }

    @Test
    fun `no marks means no summary even with a note`() {
        t.begin()
        t.note("running+9ms")
        assertNull(ConnectTimeline.summary(t.snapshot()))
    }
}
