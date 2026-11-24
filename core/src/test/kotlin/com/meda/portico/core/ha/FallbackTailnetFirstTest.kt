package com.meda.portico.core.ha

import com.meda.portico.core.ha.FallbackConnectorTest.FakeConnector
import com.meda.portico.core.ha.FallbackConnectorTest.ManualTimer
import com.meda.portico.core.ha.FallbackConnectorTest.QueueExecutor
import com.meda.portico.core.ha.FallbackConnectorTest.Recorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-694 P1c: on mobile data the tailnet connector is started at once, alongside the direct
 * attempt (the "staggered" state `FallbackConnector` already had for a SLOW direct attempt, entered
 * at t=0). Every ordering of who opens, fails or times out first, and what each leaves behind.
 */
class FallbackTailnetFirstTest {
    private val direct = FakeConnector()
    private val tailnet = FakeConnector()
    private val timer = ManualTimer()
    private val worker = QueueExecutor()
    private val rec = Recorder()
    private val attempts = mutableListOf<PathAttempt>()
    private val paths = mutableListOf<LinkPath?>()
    private val mobile = mutableListOf<Boolean>()
    private val wonLeases = mutableListOf<Long>()
    private var directOpens = 0
    private var usable = true
    private var nextLease = 0L
    private var hint: () -> Hint = { Hint(PathMode.TAILNET_FIRST, "mobile", mobileOnly = true) }

    private fun fc() = FallbackConnector(
        direct, tailnet, { usable }, timer, worker, directTimeoutMs = 5_000,
        onPath = { paths.add(it) }, onDirectOpen = { directOpens++ }, onAttempt = { attempts.add(it) },
        hint = { hint() }, onMobileDirect = { mobile.add(it) }, newLease = { ++nextLease }, onDirectWon = { wonLeases.add(it) },
    )

    private val URL = "wss://haos.example.net/api/websocket"
    private fun outcomes() = attempts.map { it.path to it.failure?.cls }

    @Test
    fun `the tailnet connector is posted at once and direct keeps a 5 s deadline`() {
        fc().open(URL, rec)
        assertEquals(1, worker.queue.size, "the tailnet connect is on the worker already")
        assertEquals(5_000L, timer.delay)
        worker.runAll()
        assertEquals(1, tailnet.socks.size)
        assertTrue(attempts.isEmpty(), "nothing has failed yet, so nothing is recorded")
    }

    @Test
    fun `the tailnet wins - direct is closed and shown as not needed`() {
        fc().open(URL, rec)
        worker.runAll()
        tailnet.last.listener.onOpen()
        assertNotNull(direct.last.closedWith)
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
        assertEquals(listOf(LinkPath.TAILNET to null, LinkPath.DIRECT to FailureClass.SUPERSEDED), outcomes())
        assertEquals(listOf("open"), rec.events)
        assertEquals(0, directOpens)
        assertTrue(mobile.isEmpty(), "a superseded direct attempt is not evidence that direct fails")
    }

    @Test
    fun `direct wins - the tailnet attempt is cancelled and closed, the node is told it is not needed`() {
        fc().open(URL, rec)
        worker.runAll()
        val probe = tailnet.cancelledProbe!!
        assertFalse(probe())
        direct.last.listener.onOpen()
        assertTrue(probe())
        assertNotNull(tailnet.last.closedWith)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
        assertEquals(listOf(LinkPath.DIRECT to null, LinkPath.TAILNET to FailureClass.SUPERSEDED), outcomes())
        assertEquals(listOf(1L), wonLeases)
        assertEquals(1, directOpens)
        assertEquals(listOf(true), mobile) // mobile-only and direct opened: it works from outside
    }

    @Test
    fun `direct wins before the tailnet connector even ran - its late transport is closed on assignment`() {
        fc().open(URL, rec)
        direct.last.listener.onOpen() // worker has not run yet
        worker.runAll()
        assertNotNull(tailnet.last.closedWith)
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `direct is silent for 5 s - it is closed and recorded as timed out, and the tailnet still wins`() {
        fc().open(URL, rec)
        worker.runAll()
        timer.fire()
        assertNotNull(direct.last.closedWith)
        assertEquals(listOf(LinkPath.DIRECT to FailureClass.TIMED_OUT), outcomes())
        assertTrue(mobile.isEmpty(), "the deadline alone is not evidence that direct is unreachable (a slow 4G handshake)")
        assertTrue(rec.events.isEmpty(), "no report: the tailnet may still open")
        tailnet.last.listener.onOpen()
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `a real socket error on mobile data does feed the flag`() {
        fc().open(URL, rec)
        worker.runAll()
        direct.last.listener.onFailure(LinkFailure(FailureClass.UNREACHABLE))
        direct.last.listener.onClosed("failure: ConnectException")
        assertEquals(listOf(false), mobile)
    }

    @Test
    fun `the tailnet fails first, direct is still pending - no report yet, then direct opens`() {
        fc().open(URL, rec)
        worker.runAll()
        tailnet.last.listener.onClosed("failure: IOException")
        assertTrue(rec.events.isEmpty())
        direct.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
    }

    @Test
    fun `the tailnet fails, then direct times out - reported once, with both reasons`() {
        fc().open(URL, rec)
        worker.runAll()
        tailnet.last.listener.onClosed("failure: IOException")
        timer.fire()
        assertEquals(1, rec.events.size)
        assertTrue(rec.events.single().startsWith("closed:direct: no answer within 5000 ms; tailnet: "), rec.events.single())
    }

    @Test
    fun `direct fails for real after the tailnet started - that is its only outcome, recorded once`() {
        fc().open(URL, rec)
        worker.runAll()
        direct.last.listener.onFailure(LinkFailure(FailureClass.NAME_NOT_RESOLVED))
        direct.last.listener.onClosed("failure: UnknownHostException")
        assertEquals(listOf(LinkPath.DIRECT to FailureClass.NAME_NOT_RESOLVED), outcomes())
        assertEquals(listOf(false), mobile)
        assertTrue(rec.events.isEmpty(), "the tailnet is still coming up")
        tailnet.last.listener.onClosed("failure: IOException")
        assertEquals(1, rec.events.size)
        assertTrue(rec.events.single().contains("direct: failure: UnknownHostException"), rec.events.single())
    }

    @Test
    fun `direct fails before open returns - the old path, the tailnet starts once`() {
        val failing = FakeConnector()
        val c = FallbackConnector(
            failing, tailnet, { usable }, timer, worker, onAttempt = { attempts.add(it) },
            hint = { Hint(PathMode.TAILNET_FIRST, "mobile", true) },
        )
        c.open(URL, rec)
        failing.last.listener.onClosed("failure: UnknownHostException") // synchronous NXDOMAIN after open()
        worker.runAll()
        assertEquals(1, tailnet.socks.size)
    }

    @Test
    fun `close while racing closes both and reports once`() {
        val t = fc().open(URL, rec)
        worker.runAll()
        t.close(1000, "bye")
        assertNotNull(direct.last.closedWith)
        assertNotNull(tailnet.last.closedWith)
        assertEquals(listOf("closed:closed while connecting: bye"), rec.events)
        assertTrue(tailnet.cancelledProbe!!())
    }

    @Test
    fun `the tailnet not usable - direct first with the usual deadline and nothing posted`() {
        usable = false
        fc().open(URL, rec)
        assertTrue(worker.queue.isEmpty())
        assertEquals(5_000L, timer.delay)
    }

    @Test
    fun `a hint that throws is direct first`() {
        hint = { throw IllegalStateException("no network service") }
        fc().open(URL, rec)
        assertTrue(worker.queue.isEmpty())
        direct.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `a DIRECT_FIRST hint behaves as before, and still teaches the mobile flag from a real error`() {
        hint = { Hint(PathMode.DIRECT_FIRST, "direct works from outside", mobileOnly = true) }
        fc().open(URL, rec)
        assertTrue(worker.queue.isEmpty())
        assertEquals(5_000L, timer.delay)
        direct.last.listener.onFailure(LinkFailure(FailureClass.NAME_NOT_RESOLVED)) // OkHttpConnector classifies just before it closes
        direct.last.listener.onClosed("failure: UnknownHostException")
        assertEquals(listOf(false), mobile)
        worker.runAll()
        assertEquals(1, tailnet.socks.size)
    }

    @Test
    fun `not mobile only - nothing is reported to the flag`() {
        hint = { Hint(PathMode.DIRECT_FIRST, "wifi", mobileOnly = false) }
        fc().open(URL, rec)
        direct.last.listener.onOpen()
        assertTrue(mobile.isEmpty())
    }

    @Test
    fun `a reconnect gets a fresh lease, so a late call about the old one is distinguishable`() {
        val c = fc()
        c.open(URL, rec)
        worker.runAll()
        direct.last.listener.onOpen()
        c.open(URL, Recorder())
        worker.runAll()
        direct.last.listener.onOpen()
        assertEquals(listOf(1L, 2L), wonLeases)
        assertNull(null)
    }

    @Test
    fun `a capped hint closes the racing direct attempt after the cap, not after the 5 s deadline`() {
        hint = { Hint(PathMode.TAILNET_FIRST, "tailnet won here", mobileOnly = false, directCapMs = 3_000) }
        fc().open(URL, rec)
        assertEquals(1, worker.queue.size, "the tailnet is posted at once")
        assertEquals(3_000L, timer.delay)
        timer.fire()
        assertNotNull(direct.last.closedWith)
        worker.runAll()
        tailnet.last.listener.onOpen()
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
    }
}
