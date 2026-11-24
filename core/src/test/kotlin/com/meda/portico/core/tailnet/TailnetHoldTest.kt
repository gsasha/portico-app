package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-694: a node that just carried a connect stays warm for a while after the direct path wins. */
class TailnetHoldTest {
    private val engine = FakeEngine().apply { identity = true; status = "up\t100.70.97.30" }
    private val keys = FakeKeys()

    private class Timer {
        var task: (() -> Unit)? = null
        var delay = -1L
        var cancels = 0
        fun schedule(ms: Long, t: () -> Unit): () -> Unit {
            task = t; delay = ms
            return { cancels++; task = null }
        }
        fun fire() { val t = task!!; task = null; t() }
    }

    private val timer = Timer()
    private fun controller(hold: Long = 180_000) = TailnetController(
        engine, keys, hostname = { "p" }, target = { "192.168.1.5:443" }, holdMs = hold, holdTimer = timer::schedule,
    )

    @Test
    fun `an Up node is kept when the direct path wins, and stopped when the hold runs out`() {
        val c = controller()
        val lease = c.newLease()
        assertEquals(TailnetState.Up("100.70.97.30"), c.abandonWarm(lease))
        assertEquals(0, engine.stops)
        assertEquals(180_000L, timer.delay)
        timer.fire()
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a new connect within the hold takes the node over - the timer is cancelled and fires nothing`() {
        val c = controller()
        c.abandonWarm(c.newLease())
        val pending = timer.task
        c.newLease()
        assertEquals(1, timer.cancels)
        pending!!.invoke() // even a timer that already fired finds a newer lease and does nothing
        assertEquals(0, engine.stops)
    }

    @Test
    fun `a node still joining is a speculative start nobody wanted - stopped at once`() {
        engine.status = "joining"
        val c = controller()
        c.abandonWarm(c.newLease())
        assertEquals(1, engine.stops)
        assertTrue(timer.task == null)
    }

    @Test
    fun `no hold configured - stopped at once, as before`() {
        val c = controller(hold = 0)
        c.abandonWarm(c.newLease())
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a pending single-use key at fire time keeps the node`() {
        val c = controller()
        c.abandonWarm(c.newLease())
        engine.status = "joining" // the node went back to joining on an enrollment key
        keys.key = PendingKey("tskey-auth-kTEST-SECRET99", System.currentTimeMillis())
        timer.fire()
        assertEquals(0, engine.stops)
    }

    @Test
    fun `pause and sleep cancel the hold and stop now`() {
        val c = controller()
        c.abandonWarm(c.newLease())
        c.pause()
        assertEquals(1, timer.cancels)
        assertEquals(1, engine.stops)

        engine.status = "up\t100.70.97.30"
        val c2 = controller()
        timer.cancels = 0
        c2.abandonWarm(c2.newLease())
        c2.sleep()
        assertEquals(1, timer.cancels)
        assertEquals(2, engine.stops)
    }

    @Test
    fun `a second abandon re-arms the hold`() {
        val c = controller()
        val lease = c.newLease()
        c.abandonWarm(lease)
        c.abandonWarm(lease)
        assertEquals(1, timer.cancels)
        assertFalse(timer.task == null)
    }

    @Test
    fun `the hold running out after the node is already gone stops nothing`() {
        val c = controller()
        c.abandonWarm(c.newLease())
        engine.status = "idle"
        timer.fire()
        assertEquals(0, engine.stops)
    }

    @Test
    fun `a stale abandon from an older connect does not start a hold`() {
        val c = controller()
        val old = c.newLease()
        c.newLease()
        c.abandonWarm(old)
        assertTrue(timer.task == null)
        assertEquals(0, engine.stops)
    }
}
