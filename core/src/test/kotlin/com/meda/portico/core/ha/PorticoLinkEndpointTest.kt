package com.meda.portico.core.ha

import com.meda.portico.core.ha.WsContract.HA_TOKEN
import com.meda.portico.core.ha.WsContract.ID_DOORS
import com.meda.portico.core.ha.WsContract.ID_VERSION
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 slice 2: a panel names its screen (`endpoint=<slug>`) in the five content commands that take one, and only there. */
class PorticoLinkEndpointTest {
    private var slug: String? = null
    private val refused = mutableListOf<ContentCommands.ErrorKind>()
    private val log = HaLog { _, _ -> }
    private val link = PorticoLink(
        RegistrationMachine(
            RegistrationConfig(WsContract.INSTALLATION, WsContract.PROJECT, WsContract.APP_VERSION),
            InMemoryRegistrationStore(value = RegValue(RegKind.FID, WsContract.REGISTRATION)),
            { 0L },
            log,
        ),
        DoorsCache(InMemoryDoorsCacheStore()),
        { },
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
        object : PorticoLink.Listener {
            override fun onContentSubscribeRefused(kind: ContentCommands.ErrorKind) { refused += kind }
        },
        log,
        endpointOf = { slug },
    )
    private val t = PorticoLinkTest.FakeTransport()

    private fun connect() {
        link.onSocketOpen(t, HA_TOKEN)
        link.onSocketText(WsContract.AUTH_REQUIRED)
        link.onSocketText(WsContract.AUTH_OK)
        link.onSocketText(WsContract.reply("version: phone", ID_VERSION))
        WsContract.frames("doors: enrolled phone", ID_DOORS).forEach(link::onSocketText)
    }

    private fun last(type: String): JsonObject = (0 until t.sent.size).map { t.json(it) }.last { it["type"]!!.jsonPrimitive.content == type }

    private fun endpointOf(type: String): String? = last(type)["endpoint"]?.jsonPrimitive?.content

    @Test
    fun `a phone names no endpoint on any content command`() {
        connect()
        assertNull(endpointOf(Handshake.CONTENT_SUBSCRIBE))
        assertNull(endpointOf(ContentCommands.GET))
        assertTrue(link.runAction("act-1"))
        assertTrue(link.requestSnapshot("cam-1"))
        assertNull(endpointOf(ContentCommands.RUN))
        assertNull(endpointOf(ContentCommands.SNAPSHOT))
    }

    @Test
    fun `a panel names its screen on subscribe, get, run, snapshot and live`() {
        slug = "kitchen-panel"
        connect()
        assertEquals("kitchen-panel", endpointOf(Handshake.CONTENT_SUBSCRIBE))
        assertEquals("kitchen-panel", endpointOf(ContentCommands.GET))
        assertTrue(link.runAction("act-1"))
        assertTrue(link.requestSnapshot("cam-1", 320))
        assertEquals("kitchen-panel", endpointOf(ContentCommands.RUN))
        assertEquals("kitchen-panel", endpointOf(ContentCommands.SNAPSHOT))
        // a placed call is the same `portico/content/run`, sent through the call path
        assertTrue(link.sendCall(ContentCommands.RUN, ContentCommands.run("call-1")) { })
        assertEquals("kitchen-panel", endpointOf(ContentCommands.RUN))
        assertTrue(link.sendCall(LiveCommands.LIVE, LiveCommands.live("cam-1", "v=0 offer", CameraSize.SMALL)) { })
        assertEquals("kitchen-panel", endpointOf(LiveCommands.LIVE))
    }

    @Test
    fun `other commands are never given an endpoint, and a command that names one keeps it`() {
        slug = "kitchen-panel"
        connect()
        assertTrue(link.sendCall(PanelHeartbeatNames.HEARTBEAT, ContentCommands.get()) { })
        assertNull(endpointOf(PanelHeartbeatNames.HEARTBEAT))
        assertNull(endpointOf(Handshake.DOORS))
        assertTrue(link.sendCall(ContentCommands.RUN, ContentCommands.run("act-2", "other-screen")) { })
        assertEquals("other-screen", endpointOf(ContentCommands.RUN))
        assertTrue(link.sendCall(LiveCommands.LIVE_CLOSE, LiveCommands.close("live-1")) { })
        assertNull(endpointOf(LiveCommands.LIVE_CLOSE))
    }

    @Test
    fun `a slug dropped between two commands applies to the next one`() {
        slug = "kitchen-panel"
        connect()
        assertEquals("kitchen-panel", endpointOf(ContentCommands.GET))
        slug = null
        assertTrue(link.requestContent())
        assertNull(endpointOf(ContentCommands.GET))
        assertTrue(link.resubscribeContent())
        assertNull(endpointOf(Handshake.CONTENT_SUBSCRIBE))
    }

    @Test
    fun `a refused subscription is told to the listener with its kind`() {
        slug = "gone"
        connect()
        val id = last(Handshake.CONTENT_SUBSCRIBE)["id"]!!.jsonPrimitive.content.toInt()
        link.onSocketText("""{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized","message":"no"}}""")
        assertEquals(listOf(ContentCommands.ErrorKind.UNAUTHORIZED), refused)
    }

    private object PanelHeartbeatNames {
        const val HEARTBEAT = "portico/wall/heartbeat"
    }
}
