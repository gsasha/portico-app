package com.meda.portico.core.tailnet

import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.ConnectTimeline.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** DEV-694 P0: the controller marks the library load, the node coming up and the forwarder, only inside a connect. */
class TailnetTimelineTest {
    private var now = 1_000_000L
    private val timeline = ConnectTimeline { now }
    private val engine = FakeEngine()
    private val keys = FakeKeys()

    private fun controller() = TailnetController(
        engine, keys, hostname = { "portico-pixel" }, target = { "192.168.1.5:443" }, wallClockMs = { now },
        sleeper = { now += it }, timeline = timeline,
    )

    private fun stages() = timeline.snapshot()!!.marks.map { it.stage }

    @Test
    fun `a cold rejoin marks the library, the node and the forwarder in order`() {
        engine.identity = true
        timeline.begin()
        // the join goes up on the second status call
        engine.onStatus = { e -> if (e.statusCalls >= 2) e.status = "up\t100.70.97.30" }
        assertNotNull(controller().awaitForwarder(10_000))
        assertEquals(listOf(Stage.LIBRARY_READY, Stage.NODE_UP, Stage.FORWARDER_READY), stages())
    }

    @Test
    fun `a node that is already up marks no library load`() {
        engine.identity = true
        engine.status = "up\t100.70.97.30"
        timeline.begin()
        assertNotNull(controller().awaitForwarder(10_000))
        assertEquals(listOf(Stage.NODE_UP, Stage.FORWARDER_READY), stages())
    }

    @Test
    fun `a Setup refresh or an enrollment save marks nothing`() {
        engine.identity = true
        engine.status = "up\t100.70.97.30"
        timeline.begin()
        val c = controller()
        c.refresh()
        c.sleep()
        keys.key = PendingKey("tskey-auth-kTEST-SECRET99", now)
        c.onEnrollmentSaved()
        assertEquals(emptyList<Stage>(), stages())
    }
}
