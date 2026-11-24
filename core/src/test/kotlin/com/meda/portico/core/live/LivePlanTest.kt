package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.live.LiveSession.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which tiles stream, which close, and what each tile draws (DEV-713 S1, the owner's decisions 1, 3, 4, 7 and 9). */
class LivePlanTest {
    private val home = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT)
    private fun tile(id: String, size: CameraSize = CameraSize.BIG, wants: Boolean = true, onScreen: Boolean = true, available: Boolean = true) =
        LiveTileIn(id, size, available, wants, onScreen)

    @Test
    fun `at home a visible tile that wants live streams at its own size`() {
        val p = LivePlan.plan(home, listOf(tile("gate"), tile("lawn", CameraSize.SMALL)))
        assertEquals(setOf(LiveKey("gate", CameraSize.BIG), LiveKey("lawn", CameraSize.SMALL)), p.wanted, "a Big tile asks for big (main), a Small one for small (sub)")
    }

    @Test
    fun `a tile completely off screen does not stream`() {
        val p = LivePlan.plan(home, listOf(tile("gate", onScreen = false), tile("lawn")))
        assertEquals(setOf(LiveKey("lawn", CameraSize.BIG)), p.wanted)
        assertEquals(StopWhy.HIDDEN, p.reasonFor(LiveKey("gate", CameraSize.BIG)), "and a session it had is closed as hidden")
        assertTrue(p.tiles.getValue("gate").chip, "the chip is still there")
    }

    @Test
    fun `the background, the pause, a dropped link and the switch each close everything`() {
        val tiles = listOf(tile("gate"))
        for ((g, why) in listOf(
            home.copy(shown = false) to StopWhy.BACKGROUND,
            home.copy(paused = true) to StopWhy.PAUSED,
            home.copy(connected = false) to StopWhy.CLOSED,
            home.copy(switchOn = false) to StopWhy.SWITCH,
        )) {
            val p = LivePlan.plan(g, tiles)
            assertEquals(emptySet<LiveKey>(), p.wanted, "$why")
            assertEquals(emptyMap<String, TilePlan>(), p.tiles, "$why: no chip either")
            assertEquals(why, p.reasonFor(LiveKey("gate", CameraSize.BIG)))
        }
    }

    @Test
    fun `the switch beats everything else`() {
        val p = LivePlan.plan(home.copy(switchOn = false, shown = false, paused = true), listOf(tile("gate")))
        assertEquals(StopWhy.SWITCH, p.idle)
    }

    @Test
    fun `on the tailnet in a build without the RTSP client nothing streams and the tile says so`() {
        val p = LivePlan.plan(home.copy(path = LinkPath.TAILNET, rtspReady = false), listOf(tile("gate"), tile("lawn", wants = false)))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertTrue(p.tiles.getValue("gate").away)
        assertFalse(p.tiles.getValue("lawn").away, "a tile set to still has nothing to say")
        assertEquals(StopWhy.CLOSED, p.reasonFor(LiveKey("gate", CameraSize.BIG)))
    }

    @Test
    fun `before the path is known nothing is attempted and nothing is said`() {
        val p = LivePlan.plan(home.copy(path = null), listOf(tile("gate")))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertFalse(p.tiles.getValue("gate").away)
    }

    @Test
    fun `a tile the viewer set to still keeps its chip and does not stream`() {
        val p = LivePlan.plan(home, listOf(tile("gate", wants = false)))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertEquals(StopWhy.SWITCH, p.reasonFor(LiveKey("gate", CameraSize.BIG)))
        assertEquals(ChipLook.OFFER, TileLiveUi.of(p.tiles["gate"], null, null, "--", false, false).chip)
    }

    @Test
    fun `an unavailable camera has no chip and no stream`() {
        val p = LivePlan.plan(home, listOf(tile("gate", available = false)))
        assertEquals(emptySet<LiveKey>(), p.wanted)
        assertFalse(p.tiles.getValue("gate").chip)
        assertEquals(TileLiveUi.NONE, TileLiveUi.of(p.tiles["gate"], null, null, "--", true, false))
    }

    @Test
    fun `a size change is a new key and the old one closes`() {
        val p = LivePlan.plan(home, listOf(tile("gate", CameraSize.SMALL)))
        assertEquals(setOf(LiveKey("gate", CameraSize.SMALL)), p.wanted)
        assertEquals(StopWhy.CLOSED, p.reasonFor(LiveKey("gate", CameraSize.BIG)))
    }

    // ---- what a tile draws ----

    private fun ui(state: State?, why: LiveWhy? = null, wants: Boolean = true, away: Boolean = false, showFps: Boolean = true, zoomed: Boolean = false, fps: String = "24 fps") =
        TileLiveUi.of(TilePlan(chip = true, wantsLive = wants, key = if (away) null else LiveKey("gate", CameraSize.BIG), away = away), state, why, fps, showFps, zoomed)

    @Test
    fun `the LIVE chip shows only while real video renders`() {
        assertEquals(ChipLook.ON, ui(State.PLAYING).chip)
        assertTrue(ui(State.PLAYING).video)
        for (s in listOf(State.IDLE, State.STARTING, State.RETRYING, State.FAILED, null)) {
            assertEquals(ChipLook.PENDING, ui(s).chip, "$s")
            assertFalse(ui(s).video, "$s")
        }
    }

    @Test
    fun `the fps label is drawn only on a playing tile and only when the document asks`() {
        assertEquals("24 fps", ui(State.PLAYING).fps)
        assertNull(ui(State.PLAYING, showFps = false).fps)
        assertNull(ui(State.STARTING).fps)
        assertNull(ui(State.FAILED).fps)
        assertEquals("--", ui(State.PLAYING, fps = "--").fps)
    }

    @Test
    fun `the notice names the class while live is not running, never while it plays`() {
        assertNull(ui(State.PLAYING).notice)
        assertEquals(LiveWhy.CONNECT, ui(State.RETRYING, LiveWhy.CONNECT).notice)
        assertEquals(LiveWhy.TOO_MANY, ui(State.FAILED, LiveWhy.TOO_MANY).notice)
        assertEquals(LiveWhy.DECODER, ui(State.STARTING, LiveWhy.DECODER).notice, "a retry in progress keeps the notice")
        assertNull(ui(State.STARTING, null).notice, "a first attempt has none yet")
        assertEquals(LiveWhy.AWAY, ui(null, away = true).notice)
        assertEquals(ChipLook.PENDING, ui(null, away = true).chip)
        assertNull(ui(null, wants = false).notice, "a tile set to still says nothing")
    }

    @Test
    fun `a pinch shows the still while the stream keeps running`() {
        val z = ui(State.PLAYING, zoomed = true)
        assertFalse(z.video)
        assertEquals(ChipLook.ON, z.chip)
    }

    @Test
    fun `connected with an unknown path opens nothing and the diagnostic line says why`() {
        val g = home.copy(path = null)
        val r = LivePlan.plan(g, listOf(LiveTileIn("cam", CameraSize.BIG, available = true, wantsLive = true, onScreen = true)))
        assertTrue(r.wanted.isEmpty())
        assertEquals("live idle why=path-unknown", LivePlan.pathUnknownLine(g, true))
        assertNull(LivePlan.pathUnknownLine(g, false))
        assertNull(LivePlan.pathUnknownLine(home, true))
        assertNull(LivePlan.pathUnknownLine(g.copy(connected = false), true))
    }
}
