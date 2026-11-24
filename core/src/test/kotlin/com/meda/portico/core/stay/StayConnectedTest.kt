package com.meda.portico.core.stay

import com.meda.portico.core.ha.NetKind
import com.meda.portico.core.ha.OneShotTimer
import com.meda.portico.core.stay.StayConnectedPolicy.Inputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StayConnectedTest {
    private class Ports : StayPorts {
        val calls = ArrayList<String>()
        var startResult = true
        override fun startService(): Boolean { calls += "start"; return startResult }
        override fun stopService() { calls += "stop" }
        override fun connect() { calls += "connect" }
        override fun release() { calls += "release" }
        override fun updateNote(text: StayConnectedPolicy.Text) { calls += "note:$text" }
    }

    private var now = 0L
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val timer = OneShotTimer { d, t -> val e = now + d to t; timers += e; ({ timers.remove(e) }) }
    private val ports = Ports()
    private var i = Inputs(true, true, false, NetKind.WIFI, false, false, false, false, false, true)
    private val sc = StayConnected(ports, { i }, timer, { now })

    @Test fun `from an allowed context on wifi the service is started and the socket held`() {
        sc.evaluate(true, "test")
        assertEquals(listOf("start", "connect", "note:CONNECTED"), ports.calls)
    }

    @Test fun `from a network callback the service is never started, only the socket handled`() {
        i = i.copy(net = NetKind.CELLULAR, powerSave = true, serviceRunning = false)
        sc.evaluate(false, "net")
        assertEquals(listOf("release", "note:WAITING_FOR_WIFI"), ports.calls)
    }

    @Test fun `leaving wifi releases the socket and keeps the service`() {
        i = i.copy(serviceRunning = true, net = NetKind.CELLULAR, powerSave = true)
        sc.evaluate(false, "net")
        assertTrue("stop" !in ports.calls)
        assertTrue("release" in ports.calls)
    }

    @Test fun `a refused start does not throw and is not retried by itself`() {
        ports.startResult = false
        sc.evaluate(true, "a")
        sc.evaluate(false, "b")
        assertEquals(1, ports.calls.count { it == "start" })
    }

    @Test fun `pause stops the service and does not touch the socket's own pause handling`() {
        i = i.copy(serviceRunning = true, paused = true)
        sc.evaluate(false, "pause")
        assertEquals(listOf("stop", "connect"), ports.calls) // connect() is refused while paused by the signaling client
    }

    @Test fun `a push on mobile data holds the socket for the grace period, then releases it`() {
        i = i.copy(serviceRunning = true, net = NetKind.CELLULAR, powerSave = true)
        sc.onWoken()
        sc.evaluate(false, "wake")
        assertTrue("release" !in ports.calls)
        now += StayConnectedPolicy.GRACE_MS + 10
        timers.toList().forEach { it.second() }
        assertTrue("release" in ports.calls)
    }

    @Test fun `a screen of ours visible on mobile data defers, and hiding it releases`() {
        i = i.copy(serviceRunning = true, net = NetKind.CELLULAR, powerSave = true, uiVisible = true)
        sc.evaluate(false, "ui")
        assertTrue("release" !in ports.calls)
        i = i.copy(uiVisible = false)
        sc.evaluate(false, "ui hidden")
        assertTrue("release" in ports.calls)
    }

    @Test fun `unreadable inputs change nothing`() {
        val bad = StayConnected(ports, { error("boom") }, timer, { now })
        bad.evaluate(true, "x")
        assertTrue(ports.calls.isEmpty())
    }

    @Test fun `a port that throws does not stop the rest`() {
        val thrower = object : StayPorts by ports {
            override fun startService(): Boolean = error("boom")
        }
        StayConnected(thrower, { i }, timer, { now }).evaluate(true, "x")
        assertTrue("connect" in ports.calls)
    }

    // ---- 0.6.92: a ring is busy for the Stay policy ------------------------------------------------------------------------

    private fun fire() = timers.toList().forEach { it.second() }

    private fun mobileSaver() { i = i.copy(serviceRunning = true, net = NetKind.CELLULAR, powerSave = true) }

    @Test fun `a ring is up - a screen closing does not release the socket`() {
        mobileSaver()
        sc.onRingChanged(true)
        i = i.copy(uiVisible = false)
        sc.evaluate(false, "a screen closed")
        assertTrue("release" !in ports.calls)
    }

    @Test fun `the ring ends - released after the normal grace, not at once and not never`() {
        mobileSaver()
        sc.onRingChanged(true)
        now += 20_000
        sc.onRingChanged(false)
        assertTrue("release" !in ports.calls)
        now += StayConnectedPolicy.GRACE_MS - 1_000
        fire()
        assertTrue("release" !in ports.calls)
        now += 2_000
        fire()
        assertEquals(1, ports.calls.count { it == "release" })
    }

    @Test fun `a lost ended is capped - the socket is released when the ring cap passes`() {
        mobileSaver()
        sc.onRingChanged(true)
        sc.evaluate(false, "x")
        now += StayConnected.RING_CAP_MS - 1_000
        fire()
        assertTrue("release" !in ports.calls)
        now += 2_000
        fire()
        assertTrue("release" in ports.calls)
        assertTrue(!sc.ringLive())
    }

    @Test fun `a ring ended that was never up is ignored`() {
        mobileSaver()
        sc.onRingChanged(false)
        assertTrue("release" !in ports.calls)
        assertTrue(!sc.inGrace())
    }

    @Test fun `a second ring up while one is up does not restart the cap`() {
        mobileSaver()
        sc.onRingChanged(true)
        now += 50_000
        sc.onRingChanged(true)
        now += 11_000
        assertTrue(!sc.ringLive())
    }

    // ---- 0.6.92: every transition is journalled, fixed words only -----------------------------------------------------------

    private class Sink : StayJournalSink {
        val all = ArrayList<String>()

        /** The transition lines only (`stay-eval`, `stay-ring` and `stay-timer` are the 0.6.93 trace lines). */
        val events: List<String> get() = all.filter { !it.startsWith("stay-eval") && !it.startsWith("stay-ring") && !it.startsWith("stay-timer") }
        override fun event(event: String, fields: List<Pair<String, Any?>>) { all += event + " " + fields.joinToString(" ") { "${it.first}=${it.second}" } }
    }

    @Test fun `transitions are journalled once each with a fixed why word`() {
        val sink = Sink()
        val j = StayConnected(ports, { i }, timer, { now }, journal = sink)
        i = i.copy(serviceRunning = true, net = NetKind.CELLULAR, powerSave = true, uiVisible = true)
        j.evaluate(false, "a screen opened")
        j.evaluate(false, "a screen opened")
        i = i.copy(uiVisible = false)
        j.evaluate(false, "a screen closed")
        i = i.copy(net = NetKind.WIFI, powerSave = false)
        j.evaluate(false, "network changed")
        assertEquals(listOf("stay-defer why=screen-opened", "stay-release why=screen-closed", "stay-connect why=network"), sink.events)
    }

    @Test fun `an unknown why becomes other and a throwing sink is harmless`() {
        val sink = Sink()
        StayConnected(ports, { i }, timer, { now }, journal = sink).evaluate(false, "free text 10.1.2.3 secret")
        assertEquals(listOf("stay-connect why=other"), sink.events)
        StayConnected(ports, { i }, timer, { now }, journal = StayJournalSink { _, _ -> error("boom") }).evaluate(false, "network")
        assertTrue("connect" in ports.calls)
    }

    // ---- 0.6.93: a ring release can be traced to its cause ------------------------------------------------------------------

    @Test fun `every deferring or releasing evaluation logs ringLive, ring count and age, with the ring edge and the timers`() {
        val sink = Sink()
        var rings = 0
        val j = StayConnected(ports, { i }, timer, { now }, journal = sink, ringCount = { rings })
        mobileSaver()
        rings = 1
        j.onRingChanged(true)
        assertTrue(sink.all.any { it == "stay-ring act=up rings=1 rage=0" }, sink.all.toString())
        assertTrue(sink.all.any { it.startsWith("stay-timer act=armed kind=cap s=60") }, sink.all.toString())
        assertTrue(sink.all.any { it.startsWith("stay-eval why=ring-started dec=defer ring=true rings=1 rage=0") }, sink.all.toString())
        now += 20_000
        j.evaluate(false, "a screen closed")
        assertTrue(sink.all.any { it.startsWith("stay-eval why=screen-closed dec=defer ring=true rings=1 rage=20") }, sink.all.toString())
        rings = 0
        j.onRingChanged(false)
        assertTrue(sink.all.any { it.startsWith("stay-ring act=down rings=0") }, sink.all.toString())
        assertTrue(sink.all.any { it.startsWith("stay-timer act=armed kind=grace") }, sink.all.toString())
        now += 200_000
        fire()
        assertTrue(sink.all.any { it == "stay-timer act=fired kind=grace" }, sink.all.toString())
        assertTrue(sink.all.any { it.startsWith("stay-eval why=grace-ended dec=release ring=false rings=0") }, sink.all.toString())
    }

    @Test fun `the ring is live as soon as the ring edge is reported, so a snapshot taken after it reads ring yes`() {
        mobileSaver()
        assertTrue(!sc.ringLive())
        sc.onRingChanged(true)
        assertTrue(sc.currentInputs()!!.ringLive)
    }
}
