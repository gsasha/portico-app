package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** While the phone is paused the tailnet node sleeps and nothing may wake it. */
class TailnetPauseTest {
    private var now = 1_000_000_000L
    private val engine = FakeEngine()
    private val keys = FakeKeys()
    private var paused = true
    private var pausedThrows = false

    private fun controller() = TailnetController(
        engine, keys, hostname = { "portico-pixel" }, target = { "192.168.1.5:443" }, wallClockMs = { now },
        sleeper = { now += it },
        paused = { if (pausedThrows) error("unreadable") else paused },
    )

    @Test
    fun `ensureUp refuses while paused - a stored identity is not rejoined`() {
        engine.identity = true
        controller().ensureUp()
        assertTrue(engine.joins.isEmpty(), "joined while paused: ${engine.joins}")
    }

    @Test
    fun `awaitForwarder refuses while paused - no join, no forwarder, no waiting`() {
        engine.identity = true
        assertNull(controller().awaitForwarder(45_000))
        assertTrue(engine.joins.isEmpty())
        assertTrue(engine.forwarderTargets.isEmpty())
    }

    @Test
    fun `a Setup save with a key while paused keeps the key for the resume and joins nothing`() {
        keys.key = PendingKey("tskey-auth-kTEST-SECRET99", now)
        controller().onEnrollmentSaved()
        assertTrue(engine.joins.isEmpty())
        assertEquals(0, engine.wipes)
        assertNotNull(keys.key, "the key waits for the resume (it still expires on its own clock)")
    }

    @Test
    fun `pause stops a node that is up, and one that is still joining`() {
        engine.status = "up\t100.64.0.7"
        controller().pause()
        assertEquals(1, engine.stops)
        engine.status = "joining"
        controller().pause()
        assertEquals(2, engine.stops)
    }

    @Test
    fun `pause never loads the library just to stop it`() {
        engine.started = false
        controller().pause()
        assertEquals(0, engine.stops)
        assertEquals(0, engine.readyCalls)
    }

    @Test
    fun `after the resume ensureUp rejoins as before`() {
        engine.identity = true
        val c = controller()
        c.ensureUp()
        paused = false
        c.ensureUp()
        assertEquals(listOf<String?>(null), engine.joins)
    }

    @Test
    fun `FAIL OPEN - an unreadable pause flag does not keep the tailnet down`() {
        engine.identity = true
        pausedThrows = true
        controller().ensureUp()
        assertEquals(listOf<String?>(null), engine.joins)
    }
}
