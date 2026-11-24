package com.meda.portico.core.ha

import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.HomeModel
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-713 S1b: the camera tile's additive `live_rtsp` object. It is handed to the tailnet dialer, so it is read STRICTLY (only
 * `rtsp://host[:port]/name`) and anything else reads as absent without ever dropping the page; the cache keeps it only when present.
 */
class LiveRtspTest {
    private val gate = "rtsp://192.168.1.6:8554/gate_intercom"
    private val gateSub = "rtsp://192.168.1.6:8554/gate_intercom_sub"

    @Test
    fun `the addresses the server sends are accepted`() {
        for (ok in listOf(
            gate, gateSub, "rtsp://192.168.1.6/gate", "rtsp://go2rtc/gate", "rtsp://go2rtc.lan:8554/Gate-1_x", "rtsp://a-b.c-d.example.com:1/x",
            "rtsp://100.64.1.2:65535/x", "rtsp://192.168.1.5/x", "rtsp://172.16.0.9/x", "rtsp://host-1/" + "a".repeat(64),
        )) assertTrue(LiveRtsp.validUrl(ok), ok)
    }

    @Test
    fun `everything else is refused`() {
        val refused = listOf(
            "", "rtsp://", "rtsp:///x", "rtsp://192.168.1.6", "rtsp://192.168.1.6/", "rtsp://192.168.1.6:8554", "rtsp://192.168.1.6:8554/",
            // credentials
            "rtsp://admin:pw@192.168.1.6:8554/gate", "rtsp://admin@192.168.1.6/gate", "rtsp://@192.168.1.6/gate", "rtsp://192.168.1.6:8554@evil/gate", "rtsp://192.168.1.6/gate@x",
            // schemes
            "RTSP://192.168.1.6:8554/gate", "rtsps://192.168.1.6:8554/gate", "rtspu://192.168.1.6/gate", "http://192.168.1.6:8554/gate", "//192.168.1.6/gate", "192.168.1.6/gate", " rtsp://192.168.1.6/gate",
            // hosts
            "rtsp://[::1]:8554/gate", "rtsp://[fe80::1]/gate", "rtsp://::1/gate", "rtsp://10.2.0/gate", "rtsp://192.168.1.6.7/gate", "rtsp://256.1.1.1/gate", "rtsp://1.2.3.4444/gate",
            // SEC-05: a numeric host is a canonical private/tailnet quad: no leading zeros, no public, unspecified or broadcast address
            "rtsp://010.0.0.1/gate", "rtsp://10.02.0.6/gate", "rtsp://10.0.0.06/gate", "rtsp://8.8.8.8/gate", "rtsp://1.2.3.4:554/gate", "rtsp://0.0.0.0/gate", "rtsp://255.255.255.255:65535/gate",
            "rtsp://10/gate", "rtsp://-a/gate", "rtsp://a-/gate", "rtsp://a..b/gate", "rtsp://.a/gate", "rtsp://a./gate", "rtsp://a b/gate", "rtsp://a_b/gate", "rtsp://host.123/gate",
            "rtsp://" + "a".repeat(64) + "/gate", "rtsp://ünï/gate",
            // ports
            "rtsp://192.168.1.6:/gate", "rtsp://192.168.1.6:0/gate", "rtsp://192.168.1.6:65536/gate", "rtsp://192.168.1.6:123456/gate", "rtsp://192.168.1.6:80a/gate", "rtsp://192.168.1.6:-1/gate", "rtsp://192.168.1.6:8554:1/gate",
            // paths
            "rtsp://192.168.1.6:8554/gate/", "rtsp://192.168.1.6:8554/a/b", "rtsp://192.168.1.6:8554//gate", "rtsp://192.168.1.6:8554/..", "rtsp://192.168.1.6:8554/.", "rtsp://192.168.1.6:8554/../gate",
            "rtsp://192.168.1.6:8554/gate?x=1", "rtsp://192.168.1.6:8554/gate#f", "rtsp://192.168.1.6:8554/gate%2f", "rtsp://192.168.1.6:8554/ga te", "rtsp://192.168.1.6:8554/gate\n",
            "rtsp://192.168.1.6:8554/gate;x", "rtsp://192.168.1.6:8554/gä", "rtsp://192.168.1.6:8554/" + "a".repeat(65), "rtsp://192.168.1.6:8554/gate.mp4", "rtsp://192.168.1.6:8554/gate\\x",
            // length
            "rtsp://" + "a".repeat(60) + "." + "b".repeat(60) + "." + "c".repeat(60) + "." + "d".repeat(60) + "/gate",
        )
        for (bad in refused) assertFalse(LiveRtsp.validUrl(bad), bad)
        assertFalse(LiveRtsp.validUrl("rtsp://" + "a.".repeat(100) + "com/x"), "over the length cap")
    }

    @Test
    fun `the stream name is the last segment, and only of a valid address`() {
        assertEquals("gate_intercom_sub", LiveRtsp.nameOf(gateSub))
        assertEquals("gate_intercom", LiveRtsp.nameOf(gate))
        assertNull(LiveRtsp.nameOf("rtsp://u:p@192.168.1.6/gate"))
        assertNull(LiveRtsp.nameOf("rtsp://192.168.1.6/gate?x"))
    }

    @Test
    fun `a Big tile pulls main and a Small tile pulls sub, or main when there is no sub`() {
        val both = LiveRtsp(gate, gateSub)
        assertEquals(gate, both.forSize(CameraSize.BIG))
        assertEquals(gateSub, both.forSize(CameraSize.SMALL))
        val mainOnly = LiveRtsp(gate)
        assertEquals(gate, mainOnly.forSize(CameraSize.BIG))
        assertEquals(gate, mainOnly.forSize(CameraSize.SMALL))
    }

    private fun obj(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `parse reads the object strictly and anything unacceptable makes the whole object absent`() {
        assertEquals(LiveRtsp(gate, gateSub), LiveRtsp.parse(obj("""{"main":"$gate","sub":"$gateSub"}""")))
        assertEquals(LiveRtsp(gate), LiveRtsp.parse(obj("""{"main":"$gate"}""")))
        assertEquals(LiveRtsp(gate), LiveRtsp.parse(obj("""{"main":"$gate","extra":1}""")), "an unknown key is ignored")
        val absent = listOf(
            null, """null""", """"$gate"""", """[]""", """{}""", """{"sub":"$gateSub"}""", """{"main":1}""", """{"main":null}""", """{"main":["$gate"]}""", """{"main":""}""",
            """{"main":"rtsp://u:p@192.168.1.6:8554/gate"}""", """{"main":"$gate","sub":"rtsp://u:p@192.168.1.6:8554/gate_sub"}""",
            """{"main":"$gate","sub":1}""", """{"main":"$gate","sub":null}""", """{"main":"$gate","sub":""}""", """{"main":"$gate","sub":"rtsp://192.168.1.6:8554/a/b"}""",
            """{"main":"$gate","sub":"rtsp://192.168.1.99:8554/gate_sub"}""", """{"main":"$gate","sub":"rtsp://192.168.1.6:8555/gate_sub"}""",
            """{"main":"rtsp://[::1]/gate"}""", """{"main":"rtsp://192.168.1.6:8554/gate?x=1"}""", """{"main":"rtsp://192.168.1.6:8554/../gate"}""",
        )
        for (a in absent) assertNull(LiveRtsp.parse(a?.let { obj(it) }), a.toString())
    }

    // ---- through the document parser and the cache ---------------------------------------------

    private fun frame(camera: String, flat: Boolean = false, caller: String? = null): String {
        val page = if (flat) {
            """{"id":"p","label":"P","cameras":[{"id":"gate","label":"Gate","available":true,"live":true$camera}],"actions":[]}"""
        } else {
            """{"id":"p","label":"P","blocks":[{"id":"b","title":"B","cameras":[{"id":"gate","label":"Gate","available":true,"live":true$camera},{"id":"yard","label":"Yard","available":true}],"actions":[]}]}"""
        }
        val callers = caller?.let { ""","callers":{"ep-x":{"name":"X","kind":"phone","cameras":[{"id":"c-1","label":"Door","available":true$it}],"ring_buttons":[],"call_buttons":[]}}""" } ?: ""
        return """{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[$page]$callers}}"""
    }

    private fun profile(text: String) =
        (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    private val full = ""","live_rtsp":{"main":"$gate","sub":"$gateSub"}"""

    @Test
    fun `a camera of a block, a flat page and a caller carries live_rtsp`() {
        val block = profile(frame(full)).pageList.single().cameras
        assertEquals(LiveRtsp(gate, gateSub), block.first { it.id == "gate" }.liveRtsp)
        assertNull(block.first { it.id == "yard" }.liveRtsp)
        assertEquals(LiveRtsp(gate, gateSub), profile(frame(full, flat = true)).pageList.single().cameras.single().liveRtsp)
        assertEquals(LiveRtsp(gate, gateSub), profile(frame("", caller = full)).callers.getValue("ep-x").cameras.single().liveRtsp)
    }

    @Test
    fun `a hostile live_rtsp reads as absent and the page and the camera's other fields are kept`() {
        for (bad in listOf(
            ""","live_rtsp":"$gate"""", ""","live_rtsp":null""", ""","live_rtsp":[]""", ""","live_rtsp":{"main":"rtsp://admin:pw@192.168.1.6:8554/gate"}""",
            ""","live_rtsp":{"main":"http://192.168.1.6/gate"}""", ""","live_rtsp":{"main":"$gate","sub":"rtsp://evil/x/y"}""", ""","live_rtsp":{"main":7}""",
        )) {
            val p = profile(frame("""$bad,"size":"big""""))
            val cam = p.pageList.single().cameras.first { it.id == "gate" }
            assertNull(cam.liveRtsp, bad)
            assertEquals("Gate", cam.label)
            assertEquals(CameraSize.BIG, cam.size)
            assertTrue(cam.live && cam.available, bad)
            assertEquals(2, p.pageList.single().cameras.size, "the page keeps its other camera")
        }
    }

    @Test
    fun `the cache keeps live_rtsp through encode and decode, and writes it only when present`() {
        val on = profile(frame(full, caller = full))
        val back = ContentCodec.decode(ContentCodec.encode(on))!!
        assertEquals(on, back.copy(phone = on.phone), "the whole document survives the cache")
        assertEquals(LiveRtsp(gate, gateSub), back.pageList.single().cameras.first { it.id == "gate" }.liveRtsp)
        assertEquals(LiveRtsp(gate, gateSub), back.callers.getValue("ep-x").cameras.single().liveRtsp)
        val mainOnly = ContentCodec.decode(ContentCodec.encode(profile(frame(""","live_rtsp":{"main":"$gate"}"""))))!!
        assertEquals(LiveRtsp(gate), mainOnly.pageList.single().cameras.first { it.id == "gate" }.liveRtsp)
        val off = ContentCodec.encode(profile(frame("")))
        assertFalse(off.contains("live_rtsp"), off)
        assertTrue(ContentCodec.encode(on).contains("live_rtsp"))
    }

    @Test
    fun `the codec version is unchanged and a cache from the build before still decodes without it`() {
        val old = """{"v":3,"rev":"r9","pages":[{"id":"p","label":"P","blocks":[{"id":"b","cameras":[{"id":"gate","label":"Gate","available":true,"size":"small","live":true}],"actions":[]}]}],"callers":{},"roster":[]}"""
        val cam = ContentCodec.decode(old)!!.pageList.single().cameras.single()
        assertTrue(cam.live)
        assertNull(cam.liveRtsp)
        assertTrue(ContentCodec.encode(profile(frame(""))).startsWith("""{"v":3"""), "version 3 still")
    }

    @Test
    fun `a damaged cached live_rtsp reads as absent and never throws`() {
        for (hostile in listOf("0", "\"x\"", "null", "[]", """{"main":"rtsp://a:b@h/x"}""", """{"main":""}""")) {
            val text = """{"v":3,"rev":"r9","pages":[{"id":"p","label":"P","cameras":[{"id":"gate","label":"Gate","available":true,"live":true,"live_rtsp":$hostile}],"actions":[]}],"callers":{},"roster":[]}"""
            assertNull(ContentCodec.decode(text)!!.pageList.single().cameras.single().liveRtsp, hostile)
        }
    }

    @Test
    fun `the home tile carries the addresses`() {
        val c = ContentCamera("gate", "Gate", null, true, CameraSize.BIG, true, LiveRtsp(gate, gateSub))
        assertEquals(LiveRtsp(gate, gateSub), HomeModel.cameraTile(c, usable = true, pictures = emptySet()).liveRtsp)
        assertNull(HomeModel.cameraTile(c.copy(liveRtsp = null), usable = true, pictures = emptySet()).liveRtsp)
    }
}
