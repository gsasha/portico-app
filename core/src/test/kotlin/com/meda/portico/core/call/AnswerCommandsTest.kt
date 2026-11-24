package com.meda.portico.core.call

import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.HaInbound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnswerCommandsTest {

    private fun result(json: String): HaInbound.Result = HaProtocol.parse(json).single() as HaInbound.Result

    @Test
    fun `builders match the server's schema`() {
        assertEquals(setOf("door_id", "call_id", "client_kind"), AnswerCommands.answer("gate", "gso-1").keys)
        assertEquals(setOf("call_id", "door_id", "sdp"), AnswerCommands.webrtcOffer("gso-1", "gate", "v=0").keys)
        assertEquals(setOf("call_id", "reason"), AnswerCommands.hangup("gso-1", "user_hangup").keys)
        assertThrows<IllegalArgumentException> { AnswerCommands.answer("", "gso-1") }
        assertThrows<IllegalArgumentException> { AnswerCommands.answer("gate", "") }
        assertThrows<IllegalArgumentException> { AnswerCommands.webrtcOffer("gso-1", "gate", "") }
        assertThrows<IllegalArgumentException> { AnswerCommands.webrtcOffer("gso-1", "", "v=0") }
        assertThrows<IllegalArgumentException> { AnswerCommands.webrtcOffer("", "gate", "v=0") }
    }

    @Test
    fun `a granted claim carries its mode, a malformed one carries none`() {
        val ok = AnswerCommands.parseClaim(result("""{"id":3,"type":"result","success":true,"result":{"mode":"relay"}}""")) as AnswerCommands.Claim.Granted
        assertEquals("relay", ok.mode)
        val odd = AnswerCommands.parseClaim(result("""{"id":3,"type":"result","success":true,"result":null}""")) as AnswerCommands.Claim.Granted
        assertEquals(null, odd.mode)
    }

    @Test
    fun `an error result becomes the mapped failure`() {
        val r = result("""{"id":3,"type":"result","success":false,"error":{"code":"already_answered","message":"this call was already answered or has ended"}}""")
        val f = (AnswerCommands.parseClaim(r) as AnswerCommands.Claim.Refused).failure
        assertEquals(AnswerFailure.Kind.ALREADY_ANSWERED, f.kind)
        assertEquals("Someone else already answered this call", f.message)
    }

    @Test
    fun `the offer's reply must carry an sdp`() {
        val good = AnswerCommands.parseOffer(result("""{"id":4,"type":"result","success":true,"result":{"sdp":"v=0 answer"}}"""))
        assertEquals("v=0 answer", (good as AnswerCommands.Offer.Answer).sdp)
        for (bad in listOf("""{"id":4,"type":"result","success":true,"result":{}}""", """{"id":4,"type":"result","success":true,"result":{"sdp":""}}""", """{"id":4,"type":"result","success":true}""")) {
            assertTrue(AnswerCommands.parseOffer(result(bad)) is AnswerCommands.Offer.Refused, bad)
        }
    }

    @Test
    fun `already_answered and device_busy are different messages and only the first dismisses the ring`() {
        val a = AnswerErrors.fromCode("already_answered")
        val b = AnswerErrors.fromCode("device_busy")
        assertTrue(a.message != b.message)
        assertTrue(a.dismissRing)
        assertFalse(b.dismissRing)
        assertFalse(a.retryable)
    }

    @Test
    fun `refused is its own message, keeps the ring and may be tried again`() {
        val f = AnswerErrors.fromCode("refused", "this door does not let Portico answer")
        assertEquals(AnswerMessages.REFUSED, f.message)
        assertTrue(f.message != AnswerMessages.ALREADY_ANSWERED)
        assertFalse(f.message.contains("refused"))
        assertFalse(f.dismissRing)
        assertTrue(f.retryable)
        assertEquals(AnswerFailure.Kind.NOT_SUPPORTED, f.kind)
        assertEquals("refused", f.code)
    }

    @Test
    fun `contract 2's claims_disabled and unknown_device are gone and read as any other code`() {
        for (old in listOf("claims_disabled", "unknown_device")) {
            val f = AnswerErrors.fromCode(old)
            assertEquals(AnswerFailure.Kind.OTHER, f.kind, old)
            assertEquals("${AnswerMessages.OTHER} ($old).", f.message, old)
        }
    }

    @Test
    fun `not_available is a call that ended, and the ring goes`() {
        val f = AnswerErrors.fromCode("not_available", "this call is gone or this door cannot be answered now")
        assertEquals(AnswerFailure.Kind.CALL_ENDED, f.kind)
        assertEquals(AnswerMessages.CALL_ENDED, f.message, "the server's text is not shown")
        assertTrue(f.dismissRing)
        assertFalse(f.retryable)
        assertEquals("not_available", f.code)
    }

    @Test
    fun `unknown_door is its own failure and keeps the ring`() {
        val f = AnswerErrors.fromCode("unknown_door", "nope")
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, f.kind)
        assertEquals(AnswerMessages.UNKNOWN_DOOR, f.message)
        assertFalse(f.dismissRing)
        assertEquals(f, AnswerErrors.unknownDoor())
    }

    @Test
    fun `app_too_old and an unknown_command from the answer commands read as a server that does not take the app`() {
        // a contract-3 server never answers app_too_old to an answer; an old one answers unknown_command or invalid_format
        for (code in listOf("unknown_command", "invalid_format")) {
            assertEquals(AnswerFailure.Kind.SERVER_TOO_OLD, AnswerErrors.fromCode(code).kind, code)
        }
    }

    @Test
    fun `server text is capped and stripped`() {
        val long = AnswerErrors.fromCode("not_supported", "x".repeat(500)).message
        assertEquals(AnswerMessages.MAX_SERVER_TEXT, long.length)
        assertEquals(AnswerMessages.NOT_SUPPORTED, AnswerErrors.fromCode("not_supported", "  \u0000\n ").message)
        assertEquals(AnswerMessages.NOT_SUPPORTED, AnswerErrors.fromCode("not_supported", null).message)
    }
}
