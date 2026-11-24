package com.meda.portico.core.call

import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [PorticoLink.sendCall]: the answer path's commands over the real link, against a scripted socket. */
class PorticoLinkCallTest {

    private class Socket : WsTransport {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String) {}
    }

    private val link = PorticoLink(
        RegistrationMachine(RegistrationConfig("inst", "proj", "0.1"), InMemoryRegistrationStore(), { 0L }),
        DoorsCache(InMemoryDoorsCacheStore()),
        { },
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
    )

    private fun ready(): Socket {
        val t = Socket()
        link.onSocketOpen(t, "tok")
        link.onSocketText("""{"type":"auth_required"}""")
        link.onSocketText("""{"type":"auth_ok","ha_version":"2026.9"}""")
        return t
    }

    private fun lastId(t: Socket) = Json.parseToJsonElement(t.sent.last()).jsonObject["id"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `a call command is sent with its type and body and its reply reaches the callback`() {
        val t = ready()
        var got: HaInbound.Result? = null
        assertTrue(link.sendCall("portico/answer", AnswerCommands.answer("gate", "gso-1")) { got = it })
        val frame = Json.parseToJsonElement(t.sent.last()).jsonObject
        assertEquals("portico/answer", frame["type"]!!.jsonPrimitive.content)
        assertEquals("gso-1", frame["call_id"]!!.jsonPrimitive.content)
        assertEquals("browser", frame["client_kind"]!!.jsonPrimitive.content)
        link.onSocketText("""{"id":${lastId(t)},"type":"result","success":true,"result":{"mode":"relay"}}""")
        assertNotNull(got)
        assertTrue(got!!.success)
    }

    @Test
    fun `an error reply reaches the callback with its code`() {
        val t = ready()
        var got: HaInbound.Result? = null
        link.sendCall("portico/answer", AnswerCommands.answer("gate", "gso-1")) { got = it }
        link.onSocketText("""{"id":${lastId(t)},"type":"result","success":false,"error":{"code":"already_answered","message":"m"}}""")
        assertEquals("already_answered", got!!.error!!.code)
    }

    @Test
    fun `not ready - nothing is sent and the callback is never called`() {
        var called = false
        assertFalse(link.sendCall("portico/answer", AnswerCommands.answer("gate", "gso-1")) { called = true })
        assertFalse(called)
    }

    @Test
    fun `a socket that closes first reports null to every waiting call, once`() {
        ready()
        val got = mutableListOf<HaInbound.Result?>()
        link.sendCall("portico/answer", AnswerCommands.answer("gate", "gso-1")) { got += it }
        link.sendCall("portico/hangup", AnswerCommands.hangup("gso-1", "error")) { got += it }
        link.onSocketClosed("gone")
        assertEquals(listOf<HaInbound.Result?>(null, null), got)
        // A straggling reply on the next connection, with a reused id, reaches no one.
        ready()
        link.onSocketText("""{"id":1,"type":"result","success":true,"result":{}}""")
        link.onSocketClosed("again")
        assertEquals(2, got.size)
    }
}
