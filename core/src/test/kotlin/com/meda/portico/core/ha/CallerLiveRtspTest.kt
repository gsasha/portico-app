package com.meda.portico.core.ha

import com.meda.portico.core.content.CallScreen
import com.meda.portico.core.content.CallerModel
import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LivePlan
import com.meda.portico.core.live.CallerLive
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-713 S2: the CALLER's cameras (`callers.<id>.cameras[]`, ids `c-<12 hex>`) carry the same additive `live_rtsp` as a page camera and are
 * read as strictly (the address goes to the tailnet dialer); they never carry `live`; the cache keeps it and an older cache decodes without it.
 */
class CallerLiveRtspTest {
    private val gate = "rtsp://192.168.1.6:8554/gate_intercom"
    private val gateSub = "rtsp://192.168.1.6:8554/gate_intercom_sub"

    private fun frame(callerCamera: String): String =
        """{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","cameras":[],"actions":[]}],""" +
            """"callers":{"ep-x":{"name":"X","kind":"door","cameras":[{"id":"c-30102768d913","label":"Gate","available":true$callerCamera}],"ring_buttons":[],"call_buttons":[]}}}}"""

    private fun profile(text: String) =
        (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    private fun camera(callerCamera: String) = profile(frame(callerCamera)).callers.getValue("ep-x").cameras.single()

    @Test
    fun `a caller camera carries live_rtsp, main and sub, or main only`() {
        assertEquals(LiveRtsp(gate, gateSub), camera(""","live_rtsp":{"main":"$gate","sub":"$gateSub"}""").liveRtsp)
        assertEquals(LiveRtsp(gate), camera(""","live_rtsp":{"main":"$gate"}""").liveRtsp)
        assertNull(camera("").liveRtsp, "none when the admin set no address, or the camera is not a Frigate camera")
    }

    @Test
    fun `a caller camera has no live key, and a live key a later server adds changes nothing here`() {
        assertFalse(camera("").live)
        assertFalse(camera(""","live_rtsp":{"main":"$gate"}""").live, "the hint is not the flag")
    }

    @Test
    fun `a hostile live_rtsp on a caller camera reads as absent and keeps the camera`() {
        for (bad in listOf(
            ""","live_rtsp":"$gate"""", ""","live_rtsp":null""", ""","live_rtsp":[]""", ""","live_rtsp":{"main":"rtsp://admin:pw@192.168.1.6:8554/gate"}""",
            ""","live_rtsp":{"main":"http://192.168.1.6/gate"}""", ""","live_rtsp":{"main":"$gate","sub":"rtsp://evil/x/y"}""", ""","live_rtsp":{"main":"$gate","sub":"rtsp://10.9.9.9:8554/s"}""",
            ""","live_rtsp":{"main":7}""", ""","live_rtsp":{"main":"rtsp://[::1]/gate"}""", ""","live_rtsp":{"main":"$gate?x=1"}""",
        )) {
            val p = profile(frame(bad))
            val cam = p.callers.getValue("ep-x").cameras.single()
            assertNull(cam.liveRtsp, bad)
            assertEquals("Gate", cam.label, bad)
            assertTrue(cam.available, bad)
            assertEquals(1, p.callers.size, "the caller is kept")
            // and the plan treats it as "not set up" away, never dials it
            val tile = CallerModel.build(p, "ep-x", CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), setOf(cam.id))!!.cameras.single()
            val plan = LivePlan.plan(
                LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.TAILNET),
                CallerLive.tiles(listOf(tile), { it.live }, setOf(cam.id)),
            )
            assertEquals(emptySet<LiveKey>(), plan.wanted, bad)
            assertTrue(plan.tiles.getValue(cam.id).notSetUp, bad)
        }
    }

    @Test
    fun `the cache keeps a caller's live_rtsp, writes it only when present, and an older cache decodes without it`() {
        val on = profile(frame(""","live_rtsp":{"main":"$gate","sub":"$gateSub"}"""))
        val back = ContentCodec.decode(ContentCodec.encode(on))!!
        assertEquals(LiveRtsp(gate, gateSub), back.callers.getValue("ep-x").cameras.single().liveRtsp)
        assertEquals(on, back.copy(phone = on.phone))
        val text = ContentCodec.encode(profile(frame("")))
        assertFalse(text.contains("live_rtsp"), text)
        assertTrue(text.contains("\"v\":3"), "the codec version is unchanged: $text")
        // a cache written before S1b/S2 (no live_rtsp, no live, no show_fps anywhere)
        val old = """{"v":3,"rev":"r9","home_name":"Home","pages":[],"callers":{"ep-x":{"name":"X","kind":"door","cameras":[{"id":"c-30102768d913","label":"Gate","available":true}],"ring_buttons":[],"call_buttons":[]}},"roster":[]}"""
        val decoded = ContentCodec.decode(old)!!
        val cam = decoded.callers.getValue("ep-x").cameras.single()
        assertNull(cam.liveRtsp)
        assertFalse(cam.live)
        assertFalse(decoded.showFps)
    }

    @Test
    fun `a cached hostile address is refused again when the cache is read`() {
        val poisoned = """{"v":3,"rev":"r9","pages":[],"callers":{"ep-x":{"name":"X","kind":"door","cameras":[{"id":"c-1","label":"G","available":true,"live_rtsp":{"main":"rtsp://u:p@192.168.1.6:8554/gate"}}],"ring_buttons":[],"call_buttons":[]}},"roster":[]}"""
        assertNull(ContentCodec.decode(poisoned)!!.callers.getValue("ep-x").cameras.single().liveRtsp)
    }
}
