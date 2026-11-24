package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-705: the glue, scenario by scenario. Every test here is a defect an independent review found. */
class ConnTracerTest {
    private var now = 1_761_000_000_000L
    private val reports = mutableListOf<JsonObject>()
    private val kicks = mutableListOf<Long>()
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private var path: String? = "direct"

    private val log = ConnLog("0.6.7-alpha", 35, { ConnEnv() }, { reports.add(Json.parseToJsonElement(it).jsonObject) }, { now }, { "id${reports.size + tasks.size}" + now })
    private val tracer = ConnTracer(log, { path }, { kicks.add(it) }, { d, t -> tasks.add(d to t) })

    private val wifi = NetState(NetClass.WIFI, wifi = true, cell = true, vpn = false, validated = true, nid = 1)
    private val cell = NetState(NetClass.CELLULAR, wifi = false, cell = true, vpn = false, validated = true, nid = 2)
    private val none = NetState(NetClass.NONE, wifi = false, cell = false, vpn = false, validated = false, nid = 3)

    private fun st(s: String) = tracer.trace("state", s, null)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.evs() = this["ev"]!!.jsonArray.map { it.jsonObject.s("e") }
    private fun runTasks(minDelay: Long) {
        val due = tasks.filter { it.first >= minDelay }
        tasks.removeAll(due)
        due.forEach { it.second() }
    }
    private fun lastResult() = reports.last().s("result")

    @Test
    fun `cold start - app start, the socket connects, one report, the sender is kicked after the connect`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.APP_START)
        st("connecting"); tracer.trace("socket_connect", null, 1); now += 80; tracer.trace("socket_open", null, null); st("connected")
        val r = reports.single()
        assertEquals("app_start", r["trigger"]!!.jsonObject.s("kind")); assertEquals("connected", r.s("result")); assertEquals("direct", r.s("path"))
        assertEquals(listOf(ConnTracer.KICK_AFTER_CONNECT_MS), kicks)
    }

    @Test
    fun `wifi to mobile while connected - the socket dies and reconnects inside the same sequence`() {
        log.seedNet(wifi); st("connected")
        tracer.netChanged(cell)
        now += 20; tracer.trace("closed", "failure: SocketException", null)   // the old socket
        st("waiting_to_retry"); st("connecting"); now += 2000; path = "tailnet"; st("connected")
        val r = reports.single()
        assertEquals("net_change", r["trigger"]!!.jsonObject.s("kind")); assertEquals("wifi", r["trigger"]!!.jsonObject.s("from")); assertEquals("cellular", r["trigger"]!!.jsonObject.s("to"))
        assertTrue("closed" in r.evs()); assertEquals("tailnet", r.s("path"))
        assertEquals("failure_socketexception", r["ev"]!!.jsonArray.map { it.jsonObject }.first { it.s("e") == "closed" }.s("cls"))
        runTasks(0) // the watchdog finds nothing open
        assertEquals(1, reports.size)
    }

    @Test
    fun `a network change the socket survives is closed by the watchdog, not left open for hours`() {
        log.seedNet(wifi); st("connected")
        tracer.netChanged(wifi.copy(validated = false)) // a validation flip: the socket stays up
        assertTrue(log.isOpen())
        now += ConnTracer.WATCHDOG_MS; runTasks(ConnTracer.WATCHDOG_MS)
        assertEquals("connected", lastResult()); assertFalse(log.isOpen())
        // and a reconnect hours later is its own report
        now += 3 * 3600_000L
        tracer.trace("closed", "closed (1006)", null); st("waiting_to_retry"); st("connecting"); st("connected")
        assertEquals(2, reports.size); assertEquals("reconnect", reports[1]["trigger"]!!.jsonObject.s("kind")); assertTrue(reports[1]["dur"]!!.jsonPrimitive.content.toLong() < 60_000)
    }

    @Test
    fun `a network change while the link is still trying keeps the sequence open`() {
        log.seedNet(wifi); st("connecting")
        tracer.netChanged(cell)
        now += ConnTracer.WATCHDOG_MS; runTasks(ConnTracer.WATCHDOG_MS)
        assertTrue(log.isOpen(), "still connecting: not closed yet")
        st("connected")
        assertEquals("connected", lastResult())
    }

    @Test
    fun `a network change while nothing is trying to connect (stopped) is closed as superseded`() {
        log.seedNet(wifi); st("stopped")
        tracer.netChanged(cell)
        now += ConnTracer.WATCHDOG_MS; runTasks(ConnTracer.WATCHDOG_MS)
        assertEquals("superseded", lastResult())
    }

    @Test
    fun `a network change in a terminal state opens nothing - no connect will follow`() {
        log.seedNet(wifi)
        for (terminal in ConnTracer.TERMINAL) {
            st(terminal)
            tracer.netChanged(cell.copy(nid = cell.nid + terminal.length))
            assertFalse(log.isOpen(), terminal)
        }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `airplane mode is its own no_network report and the return of signal starts a fresh connect report`() {
        log.seedNet(wifi); st("connected")
        tracer.netChanged(none)
        st("waiting_to_retry"); tracer.trace("retry", null, 5000)
        now += ConnTracer.NO_NETWORK_AFTER_MS; runTasks(ConnTracer.NO_NETWORK_AFTER_MS)
        assertEquals("no_network", lastResult())
        now += 600_000
        tracer.netChanged(wifi); st("connecting"); st("connected")
        assertEquals(listOf("no_network", "connected"), reports.map { it.s("result") })
        assertEquals("none", reports[1]["trigger"]!!.jsonObject.s("from")); assertEquals("wifi", reports[1]["trigger"]!!.jsonObject.s("to"))
        assertTrue(reports[1]["dur"]!!.jsonPrimitive.content.toLong() < 60_000, "the connect report does not include the 10 minutes of outage")
    }

    @Test
    fun `signal back before the no_network timer - one report, and the timer finds nothing`() {
        log.seedNet(wifi); st("connected")
        tracer.netChanged(none); now += 5000
        tracer.netChanged(cell); st("connecting"); st("connected")
        runTasks(0)
        assertEquals(listOf("connected"), reports.map { it.s("result") })
    }

    @Test
    fun `a connecting state that nothing else explained opens a wake sequence`() {
        log.seedNet(wifi)
        st("stopped"); st("connecting"); st("connected")
        assertEquals("wake", reports.single()["trigger"]!!.jsonObject.s("kind"))
    }

    @Test
    fun `a user reconnect while connected is a sequence that ends when it connects again`() {
        log.seedNet(wifi); st("connected")
        tracer.trace("reconnect_requested", null, null)
        st("connecting"); st("connected")
        assertEquals("user", reports.single()["trigger"]!!.jsonObject.s("kind"))
    }

    @Test
    fun `auth rejected, paused and signed out give up`() {
        log.seedNet(wifi)
        for (t in listOf("auth_rejected", "paused", "signed_out", "url_refused", "not_configured")) {
            log.trigger(TriggerKind.USER); st(t)
            assertEquals("gave_up", lastResult(), t)
        }
    }

    @Test
    fun `a retry loop keeps how it ended`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        repeat(15) { st("connecting"); tracer.trace("socket_connect", null, it.toLong()); tracer.trace("closed", "failure: IOException", null); tracer.trace("retry", null, 1000); st("waiting_to_retry") }
        tracer.trace("direct_opened", null, null); st("connecting"); st("connected")
        val r = reports.single()
        assertEquals("connected", r.evs().last())
        assertTrue("direct_opened" in r.evs())
        assertTrue(r["dropped"]!!.jsonPrimitive.content.toInt() > 0)
    }

    @Test
    fun `no path known is reported as unknown, and a throwing collaborator never reaches the caller`() {
        path = null
        log.seedNet(wifi); st("connecting"); st("connected")
        assertEquals("unknown", reports.single().s("path"))
        val bad = ConnTracer(log, { throw IllegalStateException("x") }, { throw IllegalStateException("y") }, { _, _ -> throw IllegalStateException("z") })
        bad.trace("state", "connected", null); bad.netChanged(cell)
    }
}
