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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [PorticoLink.openStream], [PorticoLink.sendQuiet]: the away media channel's use of the one socket. */
class PorticoLinkStreamTest {

    private class Socket : WsTransport {
        val sent = mutableListOf<String>()
        var queued = 0L
        override fun send(text: String): Boolean = sent.add(text)
        override fun queueSize(): Long = queued
        override fun close(code: Int, reason: String) {}
    }

    private val logged = mutableListOf<String>()

    private val link = PorticoLink(
        RegistrationMachine(RegistrationConfig("inst", "proj", "0.1"), InMemoryRegistrationStore(), { 0L }),
        DoorsCache(InMemoryDoorsCacheStore()),
        { },
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
        log = { _, message -> logged += message },
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
    fun `a stream gets its reply and then every event with its command id`() {
        val t = ready()
        var reply: HaInbound.Result? = null
        val events = mutableListOf<JsonObject>()
        val id = link.openStream("portico/media_open", MediaCommands.open("gate", "c1"), { reply = it }, { events += it }, {})
        assertNotNull(id)
        assertEquals(id, lastId(t))
        link.onSocketText("""{"id":$id,"type":"result","success":true,"result":{"codec":"pcmu"}}""")
        assertTrue(reply!!.success)
        link.onSocketText("""{"id":$id,"type":"event","event":{"t":"f","d":"QUJD"}}""")
        link.onSocketText("""{"id":${id!! + 99},"type":"event","event":{"t":"f","d":"XXXX"}}""")
        assertEquals(1, events.size)
        assertEquals("QUJD", events[0]["d"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a closed socket tells the stream it was lost, and the reply too if it was waiting`() {
        ready()
        var lost = 0
        var reply: HaInbound.Result? = HaInbound.Result(0, true, null, null)
        link.openStream("portico/media_open", MediaCommands.open("gate", "c1"), { reply = it }, {}, { lost++ })
        link.onSocketClosed("test")
        assertEquals(1, lost)
        assertNull(reply)
        link.onSocketClosed("again")
        assertEquals(1, lost)
    }

    @Test
    fun `a dropped stream gets no more events`() {
        ready()
        val events = mutableListOf<JsonObject>()
        val id = link.openStream("portico/media_open", MediaCommands.open("gate", "c1"), {}, { events += it }, {})!!
        link.dropStream(id)
        link.onSocketText("""{"id":$id,"type":"event","event":{"t":"f","d":"QUJD"}}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a stream is not opened without a ready socket`() {
        assertNull(link.openStream("portico/media_open", MediaCommands.open("gate", "c1"), {}, {}, {}))
        assertFalse(link.sendQuiet("portico/media_frame", MediaCommands.frame("c1", "QUJD")))
    }

    @Test
    fun `a quiet command is sent with a fresh id and nothing is kept for its reply`() {
        val t = ready()
        assertTrue(link.sendQuiet("portico/media_frame", MediaCommands.frame("c1", "QUJD")))
        val frame = Json.parseToJsonElement(t.sent.last()).jsonObject
        assertEquals("portico/media_frame", frame["type"]!!.jsonPrimitive.content)
        assertEquals("c1", frame["call_id"]!!.jsonPrimitive.content)
        // an error answer to it is logged as a failed quiet command, never as an unknown id
        link.onSocketText("""{"id":${lastId(t)},"type":"result","success":false,"error":{"code":"unknown_session","message":"c1"}}""")
        assertTrue(logged.none { "unknown id" in it })
        assertTrue(logged.any { "quiet command" in it && "unknown_session" in it })
    }

    @Test
    fun `the result of a successful quiet command is dropped without a log line`() {
        val t = ready()
        link.sendQuiet("portico/media_frame", MediaCommands.frame("c1", "QUJD"))
        logged.clear()
        link.onSocketText("""{"id":${lastId(t)},"type":"result","success":true,"result":null}""")
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `the quiet id set stays bounded and an evicted id falls back to the unknown-id log`() {
        val t = ready()
        repeat(300) { link.sendQuiet("portico/media_frame", MediaCommands.frame("c1", "QUJD")) }
        val last = lastId(t)
        logged.clear()
        link.onSocketText("""{"id":$last,"type":"result","success":true,"result":null}""")
        assertTrue(logged.isEmpty())
        link.onSocketText("""{"id":${last - 299},"type":"result","success":true,"result":null}""")
        assertTrue(logged.any { "unknown id" in it })
    }

    @Test
    fun `the socket's backlog is readable`() {
        val t = ready()
        t.queued = 4096
        assertEquals(4096L, link.queuedBytes())
    }
}
