package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.LiveRtsp
import com.meda.portico.core.live.LiveSession.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-713 S1b: the link's path picks the route (DIRECT WebRTC, TAILNET RTSP), and the tile's size picks the RTSP stream (the owner, Q8 final). */
class LivePlanRtspTest {
    private val main = "rtsp://192.168.1.6:8554/gate_intercom"
    private val sub = "rtsp://192.168.1.6:8554/gate_intercom_sub"
    private val both = LiveRtsp(main, sub)
    private val mainOnly = LiveRtsp(main)

    private fun global(path: LinkPath?, switchOn: Boolean = true, rtspReady: Boolean = true) =
        LiveGlobal(switchOn = switchOn, shown = true, paused = false, connected = true, path = path, rtspReady = rtspReady)

    private fun tile(size: CameraSize = CameraSize.BIG, rtsp: LiveRtsp? = both, wants: Boolean = true, onScreen: Boolean = true) =
        LiveTileIn("gate", size, available = true, wantsLive = wants, onScreen = onScreen, rtsp = rtsp)

    private data class Row(val path: LinkPath?, val size: CameraSize, val rtsp: LiveRtsp?, val switchOn: Boolean, val key: LiveKey?, val url: String?, val notice: Boolean)

    @Test
    fun `route table - path x address x size x switch`() {
        val rows = listOf(
            // away with addresses: Big = main, Small = sub (main when the server listed no sub)
            Row(LinkPath.TAILNET, CameraSize.BIG, both, true, LiveKey("gate", CameraSize.BIG, "gate_intercom"), main, false),
            Row(LinkPath.TAILNET, CameraSize.SMALL, both, true, LiveKey("gate", CameraSize.SMALL, "gate_intercom_sub"), sub, false),
            Row(LinkPath.TAILNET, CameraSize.BIG, mainOnly, true, LiveKey("gate", CameraSize.BIG, "gate_intercom"), main, false),
            Row(LinkPath.TAILNET, CameraSize.SMALL, mainOnly, true, LiveKey("gate", CameraSize.SMALL, "gate_intercom"), main, false),
            // away without an address: not set up, nothing opened
            Row(LinkPath.TAILNET, CameraSize.BIG, null, true, null, null, true),
            Row(LinkPath.TAILNET, CameraSize.SMALL, null, true, null, null, true),
            // at home: WebRTC whatever the addresses
            Row(LinkPath.DIRECT, CameraSize.BIG, both, true, LiveKey("gate", CameraSize.BIG), null, false),
            Row(LinkPath.DIRECT, CameraSize.SMALL, both, true, LiveKey("gate", CameraSize.SMALL), null, false),
            Row(LinkPath.DIRECT, CameraSize.BIG, null, true, LiveKey("gate", CameraSize.BIG), null, false),
            // path unknown: nothing attempted, nothing said
            Row(null, CameraSize.BIG, both, true, null, null, false),
            Row(null, CameraSize.SMALL, null, true, null, null, false),
            // the switch off: never anything, on any path
            Row(LinkPath.TAILNET, CameraSize.BIG, both, false, null, null, false),
            Row(LinkPath.DIRECT, CameraSize.BIG, both, false, null, null, false),
        )
        for (r in rows) {
            val p = LivePlan.plan(global(r.path, r.switchOn), listOf(tile(r.size, r.rtsp)))
            val label = r.toString()
            assertEquals(listOfNotNull(r.key), p.wanted.toList(), label)
            assertEquals(if (r.key != null && r.url != null) mapOf(r.key to r.url) else emptyMap<LiveKey, String>(), p.urls, label)
            if (r.switchOn) assertEquals(r.notice, p.tiles.getValue("gate").notSetUp, label)
            assertFalse(p.tiles["gate"]?.away ?: false, "$label: the old away notice is only for a build without the client")
        }
    }

    @Test
    fun `the route is part of the key, so a change of path replaces the session`() {
        assertFalse(LiveKey("gate", CameraSize.BIG) == LiveKey("gate", CameraSize.BIG, "gate_intercom"))
        assertFalse(LiveKey("gate", CameraSize.BIG).rtsp)
        assertTrue(LiveKey("gate", CameraSize.BIG, "gate_intercom").rtsp)
        // home -> away: the WebRTC key is no longer wanted (closed), the RTSP key is
        val away = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile()))
        assertFalse(LiveKey("gate", CameraSize.BIG) in away.wanted)
        assertEquals(StopWhy.CLOSED, away.reasonFor(LiveKey("gate", CameraSize.BIG)))
        // away -> home: the other way round
        val home = LivePlan.plan(global(LinkPath.DIRECT), listOf(tile()))
        assertEquals(StopWhy.CLOSED, home.reasonFor(LiveKey("gate", CameraSize.BIG, "gate_intercom")))
    }

    @Test
    fun `a size change away picks the other stream and closes the old one`() {
        val small = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile(CameraSize.SMALL)))
        assertEquals(setOf(LiveKey("gate", CameraSize.SMALL, "gate_intercom_sub")), small.wanted)
        assertEquals(StopWhy.CLOSED, small.reasonFor(LiveKey("gate", CameraSize.BIG, "gate_intercom")))
    }

    @Test
    fun `away, a tile off screen does not stream and is closed as hidden, as at home`() {
        val p = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile(onScreen = false)))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertEquals(StopWhy.HIDDEN, p.reasonFor(LiveKey("gate", CameraSize.BIG, "gate_intercom")))
        assertTrue(p.tiles.getValue("gate").chip)
        assertFalse(p.tiles.getValue("gate").notSetUp)
    }

    @Test
    fun `a tile the viewer set to still does not stream away either`() {
        val p = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile(wants = false)))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertFalse(p.tiles.getValue("gate").notSetUp, "a still has nothing to say")
        assertEquals(StopWhy.SWITCH, p.reasonFor(LiveKey("gate", CameraSize.BIG, "gate_intercom")))
    }

    @Test
    fun `an address that is not a valid stream does not stream (defence in depth after the parser)`() {
        // LiveRtsp.parse never builds one; a hand-built one still opens nothing
        val p = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile(rtsp = LiveRtsp("rtsp://u:p@192.168.1.6:8554/gate"))))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertTrue(p.tiles.getValue("gate").notSetUp)
        assertTrue(p.urls.isEmpty())
    }

    @Test
    fun `without the RTSP client the away tile says so`() {
        val p = LivePlan.plan(global(LinkPath.TAILNET, rtspReady = false), listOf(tile()))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertTrue(p.tiles.getValue("gate").away)
        assertFalse(p.tiles.getValue("gate").notSetUp)
    }

    @Test
    fun `the notices - not set up away, and the old words for a build without the client`() {
        val notSetUp = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile(rtsp = null)))
        val ui = TileLiveUi.of(notSetUp.tiles["gate"], null, null, "--", true, false)
        assertEquals(LiveWhy.NOT_SET_UP, ui.notice)
        assertEquals(ChipLook.PENDING, ui.chip)
        assertFalse(ui.video)
        assertNull(ui.fps)
        val old = TileLiveUi.of(LivePlan.plan(global(LinkPath.TAILNET, rtspReady = false), listOf(tile())).tiles["gate"], null, null, "--", true, false)
        assertEquals(LiveWhy.AWAY, old.notice)
        // a playing RTSP tile draws like a playing WebRTC one
        val playing = LivePlan.plan(global(LinkPath.TAILNET), listOf(tile()))
        val up = TileLiveUi.of(playing.tiles["gate"], State.PLAYING, null, "25 fps", true, false)
        assertEquals(ChipLook.ON, up.chip)
        assertTrue(up.video)
        assertEquals("25 fps", up.fps)
        assertNull(up.notice)
    }

    @Test
    fun `the word of every reason is plain, including the new one`() {
        for (w in LiveWhy.entries) assertTrue(Regex("[a-z]+").matches(w.word), w.name)
        assertEquals("notsetup", LiveWhy.NOT_SET_UP.word)
    }
}
