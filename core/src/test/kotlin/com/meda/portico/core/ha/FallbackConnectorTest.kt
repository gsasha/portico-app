package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/** Every branch of the direct-first, tailnet-second path choice, with scripted connectors. */
class FallbackConnectorTest {

    /** A connector whose sockets the test drives by hand. */
    class FakeConnector(private val throwOnOpen: Exception? = null) : Connector {
        inner class Sock(val listener: WsListener) : WsTransport {
            val sent = mutableListOf<String>()
            var closedWith: String? = null
            var acceptsSend = true
            override fun send(text: String): Boolean = acceptsSend.also { if (it) sent.add(text) }
            override fun close(code: Int, reason: String) {
                closedWith = reason
            }
        }

        val socks = mutableListOf<Sock>()
        val last get() = socks.last()

        /** The cancellation probe of the latest open (what a blocking connector would poll). */
        var cancelledProbe: (() -> Boolean)? = null

        override fun open(url: String, listener: WsListener, cancelled: () -> Boolean): WsTransport {
            cancelledProbe = cancelled
            return open(url, listener)
        }

        override fun open(url: String, listener: WsListener): WsTransport {
            throwOnOpen?.let { throw it }
            return Sock(listener).also { socks.add(it) }
        }
    }

    class ManualTimer : OneShotTimer {
        var pending: (() -> Unit)? = null
        var delay = -1L
        var cancelled = false
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            pending = task
            delay = delayMs
            return { cancelled = true; pending = null }
        }
        fun fire() = pending!!.invoke()
    }

    class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.add(command)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    class Recorder : WsListener {
        val events = mutableListOf<String>()
        override fun onOpen() {
            events.add("open")
        }
        override fun onText(text: String) {
            events.add("text:$text")
        }
        override fun onClosed(reason: String) {
            events.add("closed:$reason")
        }
    }

    private val direct = FakeConnector()
    private val tailnet = FakeConnector()
    private val timer = ManualTimer()
    private val worker = QueueExecutor()
    private val rec = Recorder()
    private val paths = mutableListOf<LinkPath?>()
    private var usable = true
    private var directOpens = 0
    private var landedLate = 0

    private fun fc(tn: Connector? = tailnet) = FallbackConnector(
        direct, tn, { usable }, timer, worker, directTimeoutMs = 5_000,
        onPath = { paths.add(it) }, onDirectOpen = { directOpens++ }, onLandedAfterDeadline = { landedLate++ },
    )

    private val URL = "wss://haos.example.net/api/websocket"

    @Test
    fun `closing an attempt that is waiting on the tailnet cancels the wait`() {
        val t = fc().open(URL, rec)
        direct.last.listener.onClosed("NXDOMAIN")
        worker.runAll()
        val probe = tailnet.cancelledProbe!!
        assertFalse(probe())
        t.close(1000, "bye")
        assertTrue(probe())
    }

    @Test
    fun `the direct path winning cancels the tailnet wait`() {
        fc().open(URL, rec)
        timer.fire() // slow: tailnet starts, direct stays alive
        worker.runAll()
        val probe = tailnet.cancelledProbe!!
        assertFalse(probe())
        direct.last.listener.onOpen()
        assertTrue(probe())
    }

    @Test
    fun `a stale socket closing after the next one opened does not erase the new path (0_6_50 connected instead of LAN)`() {
        val c = fc()
        val first = c.open(URL, rec)
        direct.last.listener.onOpen()
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
        val firstDirect = direct.last
        // the caller replaces the socket (recheck / network change): the new one opens BEFORE the old one reports its close
        first.close(1000, "recheck")
        val second = c.open(URL, Recorder())
        direct.last.listener.onOpen()
        firstDirect.listener.onClosed("closed by us")
        assertEquals(LinkPath.DIRECT, paths.last())
        assertFalse(paths.drop(1).any { it == null })
        // the current socket closing still clears the path
        direct.last.listener.onClosed("gone")
        assertNull(paths.last())
        second.close(1000, "done")
    }

    @Test
    fun `the only socket closing clears the path`() {
        val c = fc()
        c.open(URL, rec)
        direct.last.listener.onOpen()
        direct.last.listener.onClosed("gone")
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT, null), paths)
    }

    @Test
    fun `direct opens - that is the socket, the tailnet is never tried`() {
        val t = fc().open(URL, rec)
        assertEquals(5_000, timer.delay)
        direct.last.listener.onOpen()
        direct.last.listener.onText("auth_required")
        assertTrue(t.send("auth"))
        assertEquals(listOf("auth"), direct.last.sent)
        assertEquals(listOf("open", "text:auth_required"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
        assertEquals(1, directOpens)
        assertTrue(timer.cancelled)
        assertTrue(worker.queue.isEmpty())
        assertTrue(tailnet.socks.isEmpty())
    }

    @Test
    fun `direct fails fast (NXDOMAIN away from home) - the tailnet is opened on the worker`() {
        val t = fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        assertTrue(tailnet.socks.isEmpty(), "the tailnet open must not run on the caller's thread")
        worker.runAll()
        tailnet.last.listener.onOpen()
        tailnet.last.listener.onText("auth_required")
        assertTrue(t.send("auth"))
        assertEquals(listOf("auth"), tailnet.last.sent)
        assertEquals(listOf("open", "text:auth_required"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
        assertEquals(0, directOpens)
    }

    @Test
    fun `direct silent until the deadline - the tailnet starts and the direct attempt is NOT closed`() {
        fc().open(URL, rec)
        timer.fire()
        assertNull(direct.last.closedWith, "the slow direct attempt must stay alive")
        worker.runAll()
        assertEquals(1, tailnet.socks.size)
        tailnet.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
        assertEquals("tailnet opened first", direct.last.closedWith) // the loser is closed once the tailnet wins
        direct.last.listener.onClosed("closed (1000)")
        direct.last.listener.onOpen() // ignored: a socket never switches paths
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `slow direct opens while the tailnet is still connecting - direct wins, the tailnet is cancelled`() {
        fc().open(URL, rec)
        timer.fire() // deadline: tailnet starts
        worker.runAll()
        direct.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
        assertEquals(1, directOpens) // the app puts the tailnet node back to sleep
        assertEquals("direct opened first", tailnet.last.closedWith)
        tailnet.last.listener.onOpen() // late: ignored
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `slow direct opens before the tailnet connector returned - the late tailnet socket is closed`() {
        fc().open(URL, rec)
        timer.fire()
        direct.last.listener.onOpen() // worker has not run yet
        worker.runAll()
        assertEquals("closed while connecting", tailnet.last.closedWith)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
    }

    @Test
    fun `tailnet fails while the slow direct is still going - no close yet, direct can still win`() {
        fc().open(URL, rec)
        timer.fire()
        worker.runAll()
        tailnet.last.listener.onClosed("failure: node not up")
        assertTrue(rec.events.isEmpty())
        direct.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
    }

    @Test
    fun `both lose after the deadline - one close naming both, whichever fails last`() {
        fc().open(URL, rec)
        timer.fire()
        worker.runAll()
        direct.last.listener.onClosed("failure: SocketTimeoutException")
        assertTrue(rec.events.isEmpty()) // tailnet still coming up
        tailnet.last.listener.onClosed("failure: node not up")
        assertEquals(listOf("closed:direct: no answer within 5000 ms; tailnet: failure: node not up"), rec.events)
        assertEquals(listOf<LinkPath?>(null), paths)
    }

    @Test
    fun `a silent direct attempt is closed after the grace so a failed tailnet cannot wait forever`() {
        val c = FallbackConnector(direct, tailnet, { true }, timer, worker, directTimeoutMs = 5_000, directGraceMs = 10_000, onPath = { paths.add(it) })
        c.open(URL, rec)
        timer.fire()
        assertEquals(10_000, timer.delay) // the grace timer
        worker.runAll()
        tailnet.last.listener.onClosed("failure: node not up")
        assertTrue(rec.events.isEmpty())
        timer.fire()
        assertEquals("direct path timed out", direct.last.closedWith)
        assertEquals(listOf("closed:direct: no answer within 5000 ms; tailnet: failure: node not up"), rec.events)
    }

    @Test
    fun `a hard direct failure still falls back at once and does not race`() {
        fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        worker.runAll()
        tailnet.last.listener.onOpen()
        assertEquals(listOf<LinkPath?>(LinkPath.TAILNET), paths)
        assertEquals(0, landedLate)
    }

    @Test
    fun `landing on the tailnet after the deadline is announced, after a hard failure it is not`() {
        fc().open(URL, rec)
        timer.fire()
        worker.runAll()
        tailnet.last.listener.onOpen()
        assertEquals(1, landedLate)
    }

    @Test
    fun `without a usable tailnet the deadline closes the direct attempt as before`() {
        usable = false
        fc().open(URL, rec)
        timer.fire()
        assertEquals("direct path timed out", direct.last.closedWith)
        assertEquals(listOf("closed:direct: no answer within 5000 ms"), rec.events)
    }

    @Test
    fun `no tailnet connector - the direct failure closes the socket as before`() {
        fc(tn = null).open(URL, rec)
        direct.last.listener.onClosed("failure: ConnectException")
        assertEquals(listOf("closed:direct: failure: ConnectException"), rec.events)
        assertTrue(worker.queue.isEmpty())
        assertEquals(listOf<LinkPath?>(null), paths)
    }

    @Test
    fun `tailnet not usable (no identity, no key) - the direct failure closes the socket`() {
        usable = false
        fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        assertEquals(listOf("closed:direct: failure: UnknownHostException"), rec.events)
        assertTrue(worker.queue.isEmpty())
    }

    @Test
    fun `a throwing usability check counts as not usable`() {
        val c = FallbackConnector(direct, tailnet, { throw IllegalStateException("boom") }, timer, worker)
        c.open(URL, rec)
        direct.last.listener.onClosed("x")
        assertEquals(listOf("closed:direct: x"), rec.events)
    }

    @Test
    fun `both paths fail - one close naming both reasons`() {
        fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        worker.runAll()
        tailnet.last.listener.onClosed("failure: SSLHandshakeException")
        tailnet.last.listener.onClosed("again")
        assertEquals(listOf("closed:direct: failure: UnknownHostException; tailnet: failure: SSLHandshakeException"), rec.events)
        assertEquals(listOf<LinkPath?>(null), paths)
    }

    @Test
    fun `the tailnet connector throwing (node did not come up) closes with its message`() {
        val c = FallbackConnector(direct, FakeConnector(IllegalStateException("tailnet not up: KeyExpired")), { true }, timer, worker)
        c.open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        worker.runAll()
        assertEquals(listOf("closed:direct: failure: UnknownHostException; tailnet: tailnet not up: KeyExpired"), rec.events)
    }

    @Test
    fun `an open socket's close is reported once, unchanged`() {
        fc().open(URL, rec)
        direct.last.listener.onOpen()
        direct.last.listener.onClosed("closed by server (1001)")
        direct.last.listener.onClosed("again")
        assertEquals(listOf("open", "closed:closed by server (1001)"), rec.events)
        assertEquals(listOf(LinkPath.DIRECT, null), paths)
        assertTrue(worker.queue.isEmpty(), "an open socket never switches paths")
    }

    @Test
    fun `send before open is refused`() {
        val t = fc().open(URL, rec)
        assertFalse(t.send("early"))
        assertTrue(direct.last.sent.isEmpty())
    }

    @Test
    fun `caller closes while direct is connecting - reported once, nothing else opens`() {
        val t = fc().open(URL, rec)
        t.close(1000, "reconnect requested")
        assertEquals("reconnect requested", direct.last.closedWith)
        assertEquals(listOf("closed:closed while connecting: reconnect requested"), rec.events)
        direct.last.listener.onClosed("closed (1000)")
        direct.last.listener.onOpen()
        assertEquals(1, rec.events.size)
        assertTrue(worker.queue.isEmpty())
    }

    @Test
    fun `caller closes while the tailnet is connecting - the late tailnet socket is closed`() {
        val t = fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        t.close(1000, "stopped")
        worker.runAll() // the tailnet open was already queued
        assertEquals("closed while connecting", tailnet.last.closedWith)
        tailnet.last.listener.onOpen()
        assertEquals(listOf("closed:closed while connecting: stopped"), rec.events)
    }

    @Test
    fun `caller closes an open socket - the socket reports its own close`() {
        val t = fc().open(URL, rec)
        direct.last.listener.onOpen()
        t.close(1000, "stopped")
        assertEquals("stopped", direct.last.closedWith)
        assertEquals(listOf("open"), rec.events)
        direct.last.listener.onClosed("closed (1000)")
        assertEquals(listOf("open", "closed:closed (1000)"), rec.events)
    }

    @Test
    fun `a refused URL still throws from open, as the direct connector did`() {
        val c = FallbackConnector(FakeConnector(IllegalArgumentException("refused by the network policy: HTTP_PUBLIC_HOST")), tailnet, { true }, timer, worker)
        assertThrows(IllegalArgumentException::class.java) { c.open("ws://example.com/api/websocket", rec) }
        assertTrue(tailnet.socks.isEmpty())
        assertNull(timer.pending)
    }

    @Test
    fun `texts from the abandoned direct socket never reach the caller`() {
        fc().open(URL, rec)
        timer.fire()
        worker.runAll()
        direct.last.listener.onText("stale")
        tailnet.last.listener.onOpen()
        direct.last.listener.onText("stale2")
        tailnet.last.listener.onText("fresh")
        assertEquals(listOf("open", "text:fresh"), rec.events)
    }

    @Test
    fun `a failing onDirectOpen hook does not break the socket`() {
        val c = FallbackConnector(direct, tailnet, { true }, timer, worker, onDirectOpen = { throw IllegalStateException("x") })
        c.open(URL, rec)
        direct.last.listener.onOpen()
        assertEquals(listOf("open"), rec.events)
    }

    @Test
    fun `a candidate publishes no path and restarts no timeline until promoted, then its own marks and path go out`() {
        var now = 1_000L
        val tl = ConnectTimeline { now }
        val attempts = mutableListOf<PathAttempt>()
        val c = FallbackConnector(
            direct, tailnet, { usable }, timer, worker, directTimeoutMs = 5_000,
            onPath = { paths.add(it) }, onAttempt = { attempts.add(it) }, wallClockMs = { now }, timeline = tl,
        )
        c.open(URL, rec)
        direct.last.listener.onOpen()
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT), paths)
        assertEquals(1, attempts.size)
        now = 5_000
        val cand = c.openCandidate(URL, Recorder())
        direct.last.listener.onOpen()
        assertEquals(1, paths.size, "a candidate publishes no path")
        assertEquals(1, attempts.size, "a candidate's outcome is held back")
        assertEquals(1_000L, tl.snapshot()!!.startedAtMs, "a candidate restarts no timeline")
        now = 9_000
        cand.promote()
        assertEquals(listOf<LinkPath?>(LinkPath.DIRECT, LinkPath.DIRECT), paths)
        assertEquals(2, attempts.size)
        val snap = tl.snapshot()!!
        assertEquals(9_000L, snap.startedAtMs, "the promoted connect's own timeline starts at the swap")
        assertTrue(snap.marks.any { it.stage == ConnectTimeline.Stage.DIRECT_OPENED })
    }
}
