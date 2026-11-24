package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-705: a connection sequence from its trigger to its resolution, as the report the phone sends. */
class ConnLogTest {
    private var now = 1_761_000_000_000L
    private val out = mutableListOf<JsonObject>()
    private var ids = 0
    private var env = ConnEnv(fg = false, doze = true, saver = false, metered = true, procMs = 183_000, cold = false, paused = false)
    private val log = ConnLog("0.6.7-alpha", 35, { env }, { out.add(Json.parseToJsonElement(it).jsonObject) }, { now }, { "id${++ids}" })

    private val wifi = NetState(NetClass.WIFI, wifi = true, cell = true, vpn = false, validated = true, nid = 1)
    private val cell = NetState(NetClass.CELLULAR, wifi = false, cell = true, vpn = false, validated = true, nid = 2)
    private val none = NetState(NetClass.NONE, wifi = false, cell = false, vpn = false, validated = false, nid = 3)

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.events() = this["ev"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `lan to mobile - one sequence from the network change to connected`() {
        log.seedNet(wifi)
        now += 1000
        log.netChanged(cell)
        log.hint("TAILNET_FIRST", "mobile data, no validated Wi-Fi: not the home LAN", true)
        now += 6; log.event("tailnet_started")
        now += 7; log.event("direct_failed", cls = "NAME_NOT_RESOLVED")
        now += 2287; log.event("tailnet_open")
        log.tsnet("needslogin+13ms starting+31ms running+287ms")
        log.path("tailnet")
        now += 30; log.event("connected")
        log.resolve(ConnResult.CONNECTED)

        val r = out.single()
        assertEquals(1, r["v"]!!.jsonPrimitive.int)
        assertEquals("id1", r.s("id")); assertEquals("0.6.7-alpha", r.s("app")); assertEquals(35, r["sdk"]!!.jsonPrimitive.int)
        assertEquals(1_761_000_001_000L, r["t0"]!!.jsonPrimitive.long); assertEquals(2330L, r["dur"]!!.jsonPrimitive.long)
        assertEquals("connected", r.s("result"))
        val trig = r["trigger"]!!.jsonObject
        assertEquals("net_change", trig.s("kind")); assertEquals("wifi", trig.s("from")); assertEquals("cellular", trig.s("to"))
        assertEquals(listOf("tailnet_started", "direct_failed", "tailnet_open", "connected"), r.events().map { it.s("e") })
        assertEquals(listOf(6L, 13L, 2300L, 2330L), r.events().map { it["t"]!!.jsonPrimitive.long })
        assertEquals("NAME_NOT_RESOLVED", r.events()[1].s("cls"))
        assertEquals("TAILNET_FIRST", r["hint"]!!.jsonObject.s("mode"))
        assertEquals("tailnet", r.s("path"))
        assertEquals("needslogin+13ms starting+31ms running+287ms", r.s("tsnet"))
        assertTrue(r["env"]!!.jsonObject["doze"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(log.isOpen())
    }

    @Test
    fun `mobile to lan, lan to lan and no signal to signal are each a sequence with their own from and to`() {
        log.seedNet(cell)
        log.netChanged(wifi); now += 80; log.event("direct_open"); log.resolve(ConnResult.CONNECTED)
        now += 10_000
        val roam = wifi.copy(nid = 9) // the same kind, a new network: a roam
        log.netChanged(roam); now += 120; log.resolve(ConnResult.CONNECTED)
        now += 10_000
        log.netChanged(none); now += 60_000; log.netChanged(wifi); now += 90; log.event("direct_open"); log.resolve(ConnResult.CONNECTED)

        assertEquals(3, out.size)
        assertEquals(listOf("cellular" to "wifi", "wifi" to "wifi", "wifi" to "none"), out.map { it["trigger"]!!.jsonObject.s("from") to it["trigger"]!!.jsonObject.s("to") })
        // the outage is one sequence: the none state, then the return, with the whole duration
        val outage = out[2]
        assertEquals(60_090L, outage["dur"]!!.jsonPrimitive.long)
        assertEquals(listOf("none", "wifi"), outage["net"]!!.jsonArray.map { it.jsonObject.s("def") })
        assertEquals("none", outage["trigger"]!!.jsonObject.s("to"))
    }

    @Test
    fun `a change that changes nothing is ignored and a second change extends the open sequence`() {
        log.seedNet(wifi)
        log.netChanged(wifi)
        assertFalse(log.isOpen())
        log.netChanged(cell)
        now += 400; log.netChanged(cell.copy(validated = false))
        now += 200; log.netChanged(cell)
        assertEquals(1, run { log.resolve(ConnResult.CONNECTED); out.size })
        assertEquals(listOf(0L, 400L, 600L), out[0]["net"]!!.jsonArray.map { it.jsonObject["t"]!!.jsonPrimitive.long })
    }

    @Test
    fun `a reconnect with nothing open starts a sequence, with one open it is an event of it`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        now += 5; log.trigger(TriggerKind.USER)
        log.resolve(ConnResult.CONNECTED)
        val r = out.single()
        assertEquals("reconnect", r["trigger"]!!.jsonObject.s("kind"))
        assertEquals(listOf("trigger_user"), r.events().map { it.s("e") })
    }

    @Test
    fun `an app start supersedes an open sequence`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        log.trigger(TriggerKind.APP_START)
        log.resolve(ConnResult.CONNECTED)
        assertEquals(listOf("superseded", "connected"), out.map { it.s("result") })
        assertEquals(listOf("reconnect", "app_start"), out.map { it["trigger"]!!.jsonObject.s("kind") })
    }

    @Test
    fun `a stale sequence is closed when the next trigger arrives - no_network if the last network was none`() {
        log.seedNet(wifi)
        log.netChanged(none)
        now += ConnLog.STALE_MS + 1
        log.netChanged(wifi) // after 30 quiet minutes: the outage report closes, a new sequence starts
        log.resolve(ConnResult.CONNECTED)
        assertEquals(listOf("no_network", "connected"), out.map { it.s("result") })
        log.seedNet(wifi); log.trigger(TriggerKind.RECONNECT)
        now += ConnLog.STALE_MS + 1
        log.trigger(TriggerKind.RECONNECT)
        assertEquals("superseded", out[2].s("result"))
    }

    @Test
    fun `a gave_up resolution and an empty resolve`() {
        log.resolve(ConnResult.CONNECTED) // nothing open: nothing happens
        assertTrue(out.isEmpty())
        log.trigger(TriggerKind.USER)
        log.event("auth_rejected")
        log.resolve(ConnResult.GAVE_UP)
        assertEquals("gave_up", out.single().s("result"))
    }

    @Test
    fun `events and network entries are capped and the overflow is counted`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        repeat(100) { log.event("e$it") }
        repeat(30) { log.netChanged(cell.copy(nid = 100 + it)) }
        log.resolve(ConnResult.CONNECTED)
        val r = out.single()
        assertEquals(ConnLog.MAX_EVENTS, r.events().size)
        assertTrue(r["net"]!!.jsonArray.size <= ConnLog.MAX_NET)
        assertEquals(40 + 11, r["dropped"]!!.jsonPrimitive.int)
    }

    @Test
    fun `events outside a sequence are dropped silently, and a throwing env or sink never reaches the caller`() {
        log.event("orphan"); log.hint("X", "y", false); log.tsnet("z"); log.path("direct")
        assertTrue(out.isEmpty())
        val bad = ConnLog("0.6.7-alpha", 35, { throw IllegalStateException("env") }, { throw IllegalStateException("sink") }, { now })
        bad.trigger(TriggerKind.USER)
        bad.event("x")
        bad.resolve(ConnResult.CONNECTED) // a throwing sink costs the report, not the caller
        assertFalse(bad.isOpen())
    }

    @Test
    fun `a report holds only words and numbers - no address, name or url can come from the structure`() {
        log.seedNet(wifi)
        log.netChanged(cell)
        log.event("tailnet_open")
        log.resolve(ConnResult.CONNECTED)
        val text = out.single().toString()
        assertFalse(Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(text))
        assertFalse(text.contains("http") || text.contains("ssid", ignoreCase = true) || text.contains("tskey"))
        assertTrue(out.single()["net"] is JsonArray)
        assertNull(out.single()["dropped"])
    }

    @Test
    fun `reports have unique ids and fit the server's 4 KB limit even when full`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        repeat(60) { log.event("socket_open_event_name_x", cls = "SOME_FAILURE_CLASS_WORD", n = 123456789L) }
        repeat(30) { log.netChanged(cell.copy(nid = it + 100)) }
        log.hint("TAILNET_FIRST", "w".repeat(300), true)
        log.tsnet("t".repeat(300))
        log.resolve(ConnResult.CONNECTED)
        val r = out.single()
        assertTrue(r.toString().length < 4096, r.toString().length.toString())
        assertTrue(r["dropped"]!!.jsonPrimitive.int > 0)
        // the start and the end of the sequence survive the trimming
        assertEquals("socket_open_event_name_x", r.events().first().s("e"))
        assertEquals("connected", r.s("result"))
    }

    @Test
    fun `a long sequence keeps how it began and how it ended`() {
        log.seedNet(wifi)
        log.trigger(TriggerKind.RECONNECT)
        repeat(100) { log.event("e$it") }
        log.event("connected")
        log.resolve(ConnResult.CONNECTED)
        val ev = out.single().events().map { it.s("e") }
        assertEquals("e0", ev.first()); assertEquals("connected", ev.last()); assertEquals("e99", ev[ev.size - 2])
        assertEquals(ConnLog.MAX_EVENTS, ev.size)
        assertEquals(41, out.single()["dropped"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the sink runs after the lock is released - another thread can use the log meanwhile`() {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val ref = java.util.concurrent.atomic.AtomicReference<ConnLog>()
        val l = ConnLog("0.6.7-alpha", 35, { env }, { _ ->
            val t = Thread { ref.get().event("from_other_thread"); done.set(true) }
            t.start(); t.join(3000)
        }, { now }, { "y" })
        ref.set(l)
        l.trigger(TriggerKind.USER)
        l.resolve(ConnResult.CONNECTED)
        assertTrue(done.get(), "the other thread was blocked while the sink ran: the sink is called under the lock")
    }
}
