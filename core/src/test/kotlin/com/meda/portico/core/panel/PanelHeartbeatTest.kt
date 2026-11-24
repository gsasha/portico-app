package com.meda.portico.core.panel

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 section 9: the heartbeat's body, its reply, and when it is sent. */
class PanelHeartbeatTest {
    private fun facts(
        panel: Boolean = true,
        wake: Int? = null,
        front: FrontReason = FrontReason.HAND,
        phone: String? = "ep-panel",
        call: String? = null,
        path: String? = null,
        door: String? = null,
        mic: Boolean = true,
    ) = PanelFacts(
        appVersion = "0.6.85-alpha", panelMode = panel, endpointOk = true, foreground = true, frontReason = front, overdue = false,
        overlayOk = true, fsiOk = true, microphoneOk = mic, notificationsOk = true, ringAudible = true, batteryExempt = true,
        stayConnected = true, ringLevelOk = true, wakeDoors = wake, lastRingCall = call, lastRingPath = path, lastRingDoor = door,
        phoneEndpoint = phone, houseSwitch = true, houseGate = true, autoRevokeExempt = true,
    )

    @Test
    fun `the body carries exactly the fields of section 9 - and not battery_temp, thermal or any foreground-sensor field`() {
        val b = PanelHeartbeatCommands.heartbeat(facts())
        assertEquals(
            setOf(
                "app_version", "panel_mode", "endpoint_ok", "foreground", "front_reason", "overdue", "overlay_ok", "fsi_ok", "microphone_ok",
                "notifications_ok", "ring_audible", "battery_exempt", "stay_connected", "ring_level_ok", "wake_all", "phone_endpoint",
                "house_switch", "house_gate", "auto_revoke_exempt", "panel_call",
            ),
            b.keys,
        )
        assertEquals("hand", b["front_reason"]!!.jsonPrimitive.content)
        assertTrue(b["panel_mode"]!!.jsonPrimitive.boolean)
        assertEquals("ep-panel", b["phone_endpoint"]!!.jsonPrimitive.content)
        assertFalse(b.keys.any { it == "battery_temp" || it == "thermal" || it.contains("foreground_app") })
    }

    @Test
    fun `an absent wake is wake_all true, a known one is wake_doors with the count, never both`() {
        val all = PanelHeartbeatCommands.heartbeat(facts(wake = null))
        assertTrue(all["wake_all"]!!.jsonPrimitive.boolean)
        assertFalse("wake_doors" in all)
        val two = PanelHeartbeatCommands.heartbeat(facts(wake = 2))
        assertEquals(2, two["wake_doors"]!!.jsonPrimitive.content.toInt())
        assertFalse("wake_all" in two)
        assertEquals(0, PanelHeartbeatCommands.heartbeat(facts(wake = 0))["wake_doors"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the last-ring fields are sent only when valid - the full call id by the push payload's id rule, the path from the closed list`() {
        val ok = PanelHeartbeatCommands.heartbeat(facts(call = "gso-1759700000000000000-1-a1b2c3", path = "fsi", door = "gate"))
        assertEquals("gso-1759700000000000000-1-a1b2c3", ok["last_ring_call"]!!.jsonPrimitive.content)
        assertEquals("fsi", ok["last_ring_path"]!!.jsonPrimitive.content)
        assertEquals("gate", ok["last_ring_door"]!!.jsonPrimitive.content)
        val bad = PanelHeartbeatCommands.heartbeat(facts(call = "has space", path = "overlay", door = "x".repeat(200)))
        assertFalse("last_ring_call" in bad)
        assertFalse("last_ring_path" in bad)
        assertFalse("last_ring_door" in bad)
        assertFalse("last_ring_call" in PanelHeartbeatCommands.heartbeat(facts(call = "a".repeat(129))))
        assertTrue("last_ring_call" in PanelHeartbeatCommands.heartbeat(facts(call = "a".repeat(128))))
    }

    @Test
    fun `an unknown phone endpoint is left out, not sent as empty`() {
        assertFalse("phone_endpoint" in PanelHeartbeatCommands.heartbeat(facts(phone = null)))
        assertFalse("phone_endpoint" in PanelHeartbeatCommands.heartbeat(facts(phone = "bad id")))
    }

    private fun ok(o: JsonObject?) = HaInbound.Result(1, true, o, null)

    @Test
    fun `the reply is {house_ok, phone_endpoint}, and a missing field is null, never a guess`() {
        val full = PanelHeartbeatCommands.outcome(ok(buildJsonObject { put("house_ok", true); put("phone_endpoint", "ep-panel") }))
        assertEquals(HeartbeatOutcome.Ok(HeartbeatReply(true, "ep-panel")), full)
        val old = PanelHeartbeatCommands.outcome(ok(buildJsonObject { put("ok", true) }))
        assertEquals(HeartbeatOutcome.Ok(HeartbeatReply(null, null)), old)
        val mistyped = PanelHeartbeatCommands.outcome(ok(buildJsonObject { put("house_ok", "yes"); put("phone_endpoint", 5) }))
        assertEquals(HeartbeatOutcome.Ok(HeartbeatReply(null, null)), mistyped)
        assertEquals(HeartbeatOutcome.Ok(HeartbeatReply(null, null)), PanelHeartbeatCommands.outcome(ok(null)))
    }

    @Test
    fun `an error reply names its code, a closed socket is lost`() {
        val e = HaInbound.Result(1, false, null, HaError("unknown_command", "x"))
        assertEquals(HeartbeatOutcome.Error("unknown_command"), PanelHeartbeatCommands.outcome(e))
        assertEquals(HeartbeatOutcome.Lost, PanelHeartbeatCommands.outcome(null))
    }

    @Test
    fun `the content is fetched again only when the server names a phone endpoint that differs from the cached one`() {
        assertTrue(PanelHeartbeatCommands.needsRefetch(HeartbeatReply(true, "ep-new"), "ep-old"))
        assertTrue(PanelHeartbeatCommands.needsRefetch(HeartbeatReply(true, "ep-new"), null))
        assertFalse(PanelHeartbeatCommands.needsRefetch(HeartbeatReply(true, "ep-1"), "ep-1"))
        assertFalse(PanelHeartbeatCommands.needsRefetch(HeartbeatReply(null, null), "ep-1")) // an old server: nothing to compare
    }

    // ---- the driver ----------------------------------------------------------------------------------------------

    private class Rig {
        var now = 1_000L
        var panel = true
        var current = facts0()
        val sent = mutableListOf<JsonObject>()
        var accept = true
        var onReply: ((HeartbeatOutcome) -> Unit)? = null
        val outcomes = mutableListOf<HeartbeatOutcome>()
        private class T(val at: Long, val run: () -> Unit) { var dead = false }
        private val tasks = mutableListOf<T>()

        val d = PanelHeartbeatDriver(
            enabled = { panel },
            facts = { current },
            send = { body, cb -> if (accept) { sent += body; onReply = cb }; accept },
            schedule = { delay, run -> val t = T(now + delay, run); tasks += t; ({ t.dead = true }) },
            clockMs = { now },
            onOutcome = { outcomes += it },
        )

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.dead && it.at <= end }.minByOrNull { it.at } ?: break
                now = next.at
                next.dead = true
                next.run()
            }
            now = end
        }

        val pending get() = tasks.count { !it.dead }

        companion object {
            fun facts0() = PanelFacts(
                "0.6.85-alpha", true, true, true, FrontReason.HAND, false, true, true, true, true, true, true, true, true, null, null, null, null,
                "ep-panel", true, true, true,
            )
        }
    }

    @Test
    fun `sent at once on socket open, then every 60 seconds`() {
        val r = Rig()
        r.d.onSocketOpen()
        assertEquals(1, r.sent.size)
        r.advance(59_999)
        assertEquals(1, r.sent.size)
        r.advance(1)
        assertEquals(2, r.sent.size)
        r.advance(120_000)
        assertEquals(4, r.sent.size)
    }

    @Test
    fun `kicks inside two seconds coalesce into one send that carries the LAST state`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.advance(500)
        r.current = r.current.copy(frontReason = FrontReason.RING)
        r.d.kick()
        r.advance(100)
        r.current = r.current.copy(frontReason = FrontReason.CALL)
        r.d.kick()
        r.d.kick()
        assertEquals(1, r.sent.size) // nothing yet: the gap is 2 s
        r.advance(1_500)
        assertEquals(2, r.sent.size)
        assertEquals("call", r.sent.last()["front_reason"]!!.jsonPrimitive.content)
        r.advance(2_100)
        assertEquals(2, r.sent.size) // no stray third send
    }

    @Test
    fun `a kick after the gap goes out immediately`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.advance(2_000)
        r.d.kick()
        assertEquals(2, r.sent.size)
    }

    @Test
    fun `a periodic send follows the last send by 60 seconds, not the first`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.advance(30_000)
        r.d.kick()
        assertEquals(2, r.sent.size)
        r.advance(59_999)
        assertEquals(2, r.sent.size)
        r.advance(1)
        assertEquals(3, r.sent.size)
    }

    @Test
    fun `inputsMayHaveChanged sends only when a ready input differs`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.advance(5_000)
        r.d.inputsMayHaveChanged()
        assertEquals(1, r.sent.size)
        r.current = r.current.copy(microphoneOk = false)
        r.d.inputsMayHaveChanged()
        assertEquals(2, r.sent.size)
        assertFalse(r.sent.last()["microphone_ok"]!!.jsonPrimitive.boolean)
        r.advance(5_000)
        r.current = r.current.copy(lastRingCall = "gso-1", lastRingPath = "fsi") // a ring field is not a ready input
        r.d.inputsMayHaveChanged()
        assertEquals(2, r.sent.size)
    }

    @Test
    fun `unknown_command stops the heartbeat for this connection - nothing every minute - and the next socket open asks again`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.onReply!!(HeartbeatOutcome.Error("unknown_command"))
        r.advance(300_000)
        assertEquals(1, r.sent.size)
        r.d.kick()
        assertEquals(1, r.sent.size)
        r.d.onSocketOpen()
        assertEquals(2, r.sent.size)
        assertEquals(1, r.outcomes.size)
    }

    @Test
    fun `another error code does not stop it`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.onReply!!(HeartbeatOutcome.Error("rate_limited"))
        r.advance(60_000)
        assertEquals(2, r.sent.size)
    }

    @Test
    fun `a closed socket cancels the timers, nothing ticks against it`() {
        val r = Rig()
        r.d.onSocketOpen()
        r.d.kick()
        r.d.onSocketClosed()
        assertEquals(0, r.pending)
        r.advance(300_000)
        assertEquals(1, r.sent.size)
    }

    @Test
    fun `not in Panel mode nothing is sent at all`() {
        val r = Rig()
        r.panel = false
        r.d.onSocketOpen()
        r.d.kick()
        r.d.inputsMayHaveChanged()
        r.advance(300_000)
        assertEquals(0, r.sent.size)
    }

    @Test
    fun `a socket that is not ready sends nothing and leaves no timer running, the next open sends`() {
        val r = Rig()
        r.accept = false
        r.d.onSocketOpen()
        assertEquals(0, r.pending)
        r.accept = true
        r.d.onSocketOpen()
        assertEquals(1, r.sent.size)
    }

    @Test
    fun `facts that throw send nothing and do not break the driver`() {
        var boom = true
        val sent = mutableListOf<JsonObject>()
        val d = PanelHeartbeatDriver(
            enabled = { true }, facts = { if (boom) error("x") else Rig.facts0() },
            send = { b, _ -> sent += b; true }, schedule = { _, _ -> {} }, clockMs = { 0L },
        )
        d.onSocketOpen()
        d.inputsMayHaveChanged()
        assertEquals(0, sent.size)
        boom = false
        d.onSocketOpen()
        assertEquals(1, sent.size)
    }
}
