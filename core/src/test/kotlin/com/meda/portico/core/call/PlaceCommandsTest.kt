package com.meda.portico.core.call

import com.meda.portico.core.call.PlaceCommands.Placed
import com.meda.portico.core.call.PlaceFailure.Kind
import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlaceCommandsTest {
    private fun ok(json: String) = HaInbound.Result(1, true, Json.parseToJsonElement(json), null)
    private fun err(code: String) = HaInbound.Result(1, false, null, HaError(code, "server text that must not reach the screen"))

    @Test
    fun `place carries the action id only and hangup the call id only`() {
        assertEquals(setOf("action_id"), PlaceCommands.place("talk").keys)
        assertEquals(setOf("call_id", "reason"), PlaceCommands.hangup("hso-1", PlaceCommands.REASON_CANCELLED).keys)
        assertThrows<IllegalArgumentException> { PlaceCommands.place("") }
        assertThrows<IllegalArgumentException> { PlaceCommands.hangup("", PlaceCommands.REASON_HANGUP) }
        assertThrows<IllegalArgumentException> { PlaceCommands.hangup("hso-1", "") }
        assertEquals("portico/content/run", PlaceCommands.RUN)
        assertEquals("portico/hangup", PlaceCommands.HANGUP, "contract 3 has one hangup; there is no portico/house/hangup")
        assertEquals(AnswerCommands.HANGUP, PlaceCommands.HANGUP)
        assertEquals("cancelled", PlaceCommands.REASON_CANCELLED)
        assertEquals("hangup", PlaceCommands.REASON_HANGUP)
    }

    @Test
    fun `a granted run has a call id and keeps the media descriptor opaque`() {
        val p = PlaceCommands.parsePlaced(ok("""{"ran":true,"call_id":"hso-1","media":{"x":[1,2]}}"""), "K") as Placed.Granted
        assertEquals("hso-1", p.callId)
        assertEquals("""{"x":[1,2]}""", p.media.toString())
        val bare = PlaceCommands.parsePlaced(ok("""{"ran":true,"call_id":"hso-2"}"""), "K") as Placed.Granted
        assertNull(bare.media)
    }

    @Test
    fun `ran without a call id, or a plain error-free reply of the wrong shape, is a refusal`() {
        for (json in listOf("""{"ran":true}""", """{"ran":false,"call_id":"x"}""", """{"call_id":"x"}""", """{"ran":true,"call_id":""}""", "[]", "null")) {
            val p = PlaceCommands.parsePlaced(ok(json), "K")
            assertTrue(p is Placed.Refused, json)
        }
    }

    @Test
    fun `an error frame maps by code and never shows the server's text`() {
        val p = PlaceCommands.parsePlaced(err("target_dnd"), "Bob") as Placed.Refused
        assertEquals(Kind.TARGET_DND, p.failure.kind)
        assertEquals("Bob has Do Not Disturb on.", p.failure.message)
        assertFalse(p.failure.message.contains("server text"))
    }

    @Test
    fun `a house_call event parses, anything else does not`() {
        val e = HouseCallEvent.parse(Json.parseToJsonElement("""{"type":"house_call","call_id":"hso-1","state":"ended","reason":"declined","from":"a"}""").jsonObject)!!
        assertEquals(HouseCallEvent("hso-1", "ended", "declined", from = "a"), e)
        assertNull(HouseCallEvent.parse(Json.parseToJsonElement("""{"type":"call_state_changed","call_id":"x","state":"ended"}""").jsonObject))
        assertNull(HouseCallEvent.parse(Json.parseToJsonElement("""{"type":"house_call","state":"ended"}""").jsonObject))
        assertNull(HouseCallEvent.parse(Json.parseToJsonElement("""{"type":"house_call","call_id":"x"}""").jsonObject))
        assertNull(HouseCallEvent.parse(Json.parseToJsonElement("""{"type":"house_call","call_id":"x","state":"ringing"}""").jsonObject)!!.reason)
    }

    @Test
    fun `the messages say who, fall back when there is no name, and stay plain text`() {
        assertEquals("Kitchen tablet is on a call.", PlaceErrors.fromCode("busy", "Kitchen tablet").message)
        assertEquals("They did not answer.", PlaceErrors.fromCode("no_answer", null).message)
        assertEquals("They declined.", PlaceErrors.fromCode("declined", "  ").message)
        assertEquals("The call could not be placed (weird).", PlaceErrors.fromCode("weird", "X").message)
        assertEquals("This Home Assistant cannot place calls yet.", PlaceErrors.fromCode("unknown_action").message)
        assertEquals("The gate is busy.", PlaceErrors.fromCode("gate_busy").message)
        assertEquals("Talk to the gate is switched off.", PlaceErrors.fromCode("dial_disabled").message)
        assertEquals("The door is ringing.", PlaceErrors.fromCode("yielded").message)
        val nasty = PlaceErrors.fromCode("busy", "A\u0007<b>x</b>\n" + "y".repeat(500)).message
        assertTrue(nasty.length < 260 && !nasty.contains('\u0007') && !nasty.contains('\n'))
    }

    @Test
    fun `retry is offered only where trying again can help`() {
        val retry = listOf("target_offline", "busy", "no_answer", "gate_busy", "yielded", "rate_limited", "failed", "weird")
        val noRetry = listOf("not_available", "unknown_action", "not_callable", "target_dnd", "declined", "dial_disabled", "place_forbidden", "receive_off")
        for (c in retry) assertTrue(PlaceErrors.fromCode(c, "X").retryable, c)
        for (c in noRetry) assertFalse(PlaceErrors.fromCode(c, "X").retryable, c)
        assertTrue(PlaceErrors.networkLost().retryable && PlaceErrors.notConnected().retryable && PlaceErrors.timeout().retryable)
        assertFalse(PlaceErrors.anotherCall().retryable)
    }
}
