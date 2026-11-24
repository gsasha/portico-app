package com.meda.portico.core.ha

import com.meda.portico.core.ha.HaProtocol.Action
import com.meda.portico.core.ha.WsContract.HA_TOKEN
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HaProtocolTest {

    /** An event frame built inline (the recorded ones are replayed by [WsContractReplayTest]). */
    private fun event(id: Int, doorId: String, callId: String?, state: String) =
        """{"id":$id,"type":"event","event":{"type":"call_state_changed","door_id":"$doorId","call_id":${callId?.let { "\"$it\"" } ?: "null"},"state":"$state"}}"""

    private fun ready(): HaProtocol = HaProtocol(HA_TOKEN).also {
        it.onText(WsContract.AUTH_REQUIRED)
        it.onText(WsContract.AUTH_OK)
    }

    @Test
    fun `auth_required is answered with the auth frame, auth_ok makes it ready`() {
        val p = HaProtocol(HA_TOKEN)
        assertNull(p.command("portico/devices"), "nothing is sent before auth_ok")
        val first = p.onText(WsContract.AUTH_REQUIRED).single() as Action.Send
        val auth = Json.parseToJsonElement(first.text).jsonObject
        assertEquals("auth", auth["type"]!!.jsonPrimitive.content)
        assertEquals(HA_TOKEN, auth["access_token"]!!.jsonPrimitive.content)
        assertEquals(HaProtocol.Phase.AUTHENTICATING, p.phase)
        assertEquals(Action.Authenticated("2026.10.0"), p.onText(WsContract.AUTH_OK).single())
        assertEquals(HaProtocol.Phase.READY, p.phase)
    }

    @Test
    fun `auth_invalid is a rejection and nothing can be sent after it`() {
        val p = HaProtocol(HA_TOKEN)
        p.onText(WsContract.AUTH_REQUIRED)
        assertEquals(Action.AuthRejected("Invalid access token or password"), p.onText(WsContract.AUTH_INVALID).single())
        assertEquals(HaProtocol.Phase.AUTH_FAILED, p.phase)
        assertNull(p.command("portico/devices"))
    }

    @Test
    fun `auth_ok without auth_required, and a second auth_required, are ignored`() {
        val p = HaProtocol(HA_TOKEN)
        assertTrue(p.onText(WsContract.AUTH_OK).single() is Action.Ignored)
        p.onText(WsContract.AUTH_REQUIRED)
        assertTrue(p.onText(WsContract.AUTH_REQUIRED).single() is Action.Ignored)
    }

    @Test
    fun `ids increase from 1 and the command carries id and type`() {
        val p = ready()
        val (id1, t1) = p.command("portico/version")!!
        val (id2, _) = p.command("portico/doors")!!
        assertEquals(1, id1)
        assertEquals(2, id2)
        val o = Json.parseToJsonElement(t1).jsonObject
        assertEquals(1, o["id"]!!.jsonPrimitive.int)
        assertEquals("portico/version", o["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `results and events before auth_ok are not delivered`() {
        val p = HaProtocol(HA_TOKEN)
        p.onText(WsContract.AUTH_REQUIRED)
        assertTrue(p.onText(WsContract.reply("push register: ok", 1)).single() is Action.Ignored)
        assertTrue(p.onText(event(1, "d", "c", "ringing")).single() is Action.Ignored)
    }

    @Test
    fun `result, error and event frames parse`() {
        val p = ready()
        val ok = (p.onText(WsContract.reply("push register: rotation (same installation_id, new registration)", 7)).single() as Action.Deliver).frame as HaInbound.Result
        assertEquals(7, ok.id)
        assertTrue(ok.success)
        val err = (p.onText("""{"id":8,"type":"result","success":false,"error":{"code":"project_mismatch","message":"m"}}""").single() as Action.Deliver).frame as HaInbound.Result
        assertFalse(err.success)
        assertEquals(HaError("project_mismatch", "m"), err.error)
        val ev = (p.onText(event(3, "d", null, "idle")).single() as Action.Deliver).frame as HaInbound.Event
        assertEquals(3, ev.id)
        assertEquals("idle", ev.event["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an error without a code is unknown_error, never a crash`() {
        val r = HaProtocol.parse("""{"id":1,"type":"result","success":false}""").single() as HaInbound.Result
        assertEquals("unknown_error", r.error!!.code)
    }

    @Test
    fun `a coalesced array delivers every frame in order`() {
        val p = ready()
        val actions = p.onText("[${WsContract.reply("calls subscribe: result, then one snapshot per door", 1)},${event(1, "d", "c1", "ringing")}]")
        assertEquals(2, actions.size)
        assertTrue(((actions[0] as Action.Deliver).frame) is HaInbound.Result)
        assertTrue(((actions[1] as Action.Deliver).frame) is HaInbound.Event)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "not json", "{", "42", "\"auth_ok\"", "null", "[]", "[1,2]", "{}",
            """{"type":5}""",
            """{"type":"result"}""",
            """{"type":"result","id":"1","success":true}""",
            """{"type":"result","id":1,"success":"true"}""",
            """{"type":"result","id":1.5,"success":true}""",
            """{"type":"event","id":1}""",
            """{"type":"event","id":1,"event":"ringing"}""",
            """{"type":"pong"}""",
            """{"type":"something_new","id":1}""",
        ],
    )
    fun `malformed frames are ignored, never thrown`(text: String) {
        val p = ready()
        val actions = p.onText(text)
        assertTrue(actions.isNotEmpty())
        assertTrue(actions.all { it is Action.Ignored }, "$text -> $actions")
        assertEquals(HaProtocol.Phase.READY, p.phase)
    }

    @Test
    fun `toString never shows the token`() {
        val p = HaProtocol(HA_TOKEN)
        val send = p.onText(WsContract.AUTH_REQUIRED).single()
        assertFalse(p.toString().contains(HA_TOKEN))
        assertFalse(send.toString().contains(HA_TOKEN))
        // a doors result prints without its body (contract 3 carries no secrets, but a body is never printed)
        val doors = HaProtocol.parse(WsContract.reply("doors: enrolled phone", 1)).single().toString()
        assertEquals("Result(id=1, success=true)", doors)
        assertFalse(doors.contains("192.168.1.7"))
    }
}
