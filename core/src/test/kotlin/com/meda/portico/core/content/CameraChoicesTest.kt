package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveStore
import com.meda.portico.core.live.LiveTileIn
import com.meda.portico.core.live.LivePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.53: the gear menu's rows (resolution names, stills, off) and how they interplay with the LIVE chip and with persistence. */
class CameraChoicesTest {
    private class Store(var text: String? = null) : LiveStore {
        override fun load() = text
        override fun save(text: String): Boolean {
            this.text = text
            return true
        }
    }

    // ---- the labels ----

    @Test
    fun `quality is the exact height plus p, never snapped`() {
        assertEquals("2160p", StreamLabels.quality(2160))
        assertEquals("1520p", StreamLabels.quality(1520)) // a 2688x1520 camera
        assertEquals("1440p", StreamLabels.quality(1440))
        assertEquals("1200p", StreamLabels.quality(1200))
        assertEquals("1072p", StreamLabels.quality(1072))
        assertEquals("720p", StreamLabels.quality(720))
        assertEquals("288p", StreamLabels.quality(288)) // 352x288
        assertEquals("576p", StreamLabels.quality(576))
        assertEquals("368p", StreamLabels.quality(368))
        assertEquals("96p", StreamLabels.quality(96))
        assertNull(StreamLabels.quality(0))
        assertNull(StreamLabels.quality(-4))
    }

    @Test
    fun `heights seen are kept per camera and variant`() {
        val h = StreamHeights()
        assertNull(h.height("cam", CameraSize.BIG))
        assertTrue(h.record("cam", CameraSize.BIG, 1520))
        assertFalse(h.record("cam", CameraSize.BIG, 1520))
        assertFalse(h.record("cam", CameraSize.SMALL, 0))
        assertEquals(1520, h.height("cam", CameraSize.BIG))
        assertNull(h.height("cam", CameraSize.SMALL))
        assertNull(h.height("other", CameraSize.BIG))
    }

    // ---- the chip and the menu ----

    @Test
    fun `live off then on restores the resolution`() {
        val s = CameraState(StreamPick.BIG)
        val stills = CameraModes.tapLive(s)
        assertEquals(StreamPick.STILLS, CameraModes.checked(stills))
        val back = CameraModes.tapLive(stills)
        assertEquals(StreamPick.BIG, CameraModes.checked(back), "not reset to Auto")
        assertEquals(s, back)
    }

    @Test
    fun `menu stills then the chip restores the last live resolution`() {
        val s = CameraModes.choose(CameraState(StreamPick.SMALL), StreamPick.STILLS)
        assertEquals(StreamPick.STILLS, CameraModes.checked(s))
        assertEquals(StreamPick.SMALL, CameraModes.checked(CameraModes.tapLive(s)))
    }

    @Test
    fun `a resolution chosen from stills turns live on and is remembered`() {
        val s = CameraModes.choose(CameraState(StreamPick.AUTO, live = false), StreamPick.BIG)
        assertEquals(CameraState(StreamPick.BIG, live = true, off = false), s)
    }

    @Test
    fun `off keeps the resolution, and any other row turns the view back on`() {
        val off = CameraModes.choose(CameraState(StreamPick.SMALL), StreamPick.OFF)
        assertEquals(StreamPick.OFF, CameraModes.checked(off))
        assertEquals(StreamPick.SMALL, off.pick)
        assertSame(off, CameraModes.tapLive(off), "an off view has no chip: a tap is nothing")
        assertEquals(StreamPick.STILLS, CameraModes.checked(CameraModes.choose(off, StreamPick.STILLS)))
        assertEquals(StreamPick.AUTO, CameraModes.checked(CameraModes.choose(off, StreamPick.AUTO)))
        // off from stills: choosing a resolution afterwards is live again; the chip works after stills
        val offFromStills = CameraModes.choose(CameraState(StreamPick.BIG, live = false), StreamPick.OFF)
        assertEquals(StreamPick.BIG, CameraModes.checked(CameraModes.choose(offFromStills, StreamPick.BIG)))
        assertEquals(StreamPick.BIG, CameraModes.checked(CameraModes.tapLive(CameraModes.choose(offFromStills, StreamPick.STILLS))), "stills then chip: the last live resolution")
    }

    // ---- persistence ----

    @Test
    fun `the last resolution and the off switch survive a restart`() {
        val store = Store()
        val a = CameraChoices(store)
        assertEquals(StreamPick.AUTO, a.pick("gate"))
        assertTrue(a.set("gate", StreamPick.BIG, false))
        assertFalse(a.set("gate", StreamPick.BIG, false))
        a.set("lawn", StreamPick.SMALL, true)
        val b = CameraChoices(store) // a new run
        assertEquals(StreamPick.BIG, b.pick("gate"))
        assertEquals(StreamPick.SMALL, b.pick("lawn"))
        assertEquals(setOf("lawn"), b.offIds())
        assertTrue(b.isOff("lawn"))
        assertFalse(b.isOff("gate"))
    }

    @Test
    fun `defaults are not stored, and stills or off are never a stored resolution`() {
        val store = Store()
        val c = CameraChoices(store)
        c.set("gate", StreamPick.BIG, false)
        c.set("gate", StreamPick.AUTO, false)
        assertEquals(StreamPick.AUTO, CameraChoices(store).pick("gate"))
        assertFalse(store.text!!.contains("gate"))
        c.set("gate", StreamPick.STILLS, false)
        assertEquals(StreamPick.AUTO, c.pick("gate"))
    }

    @Test
    fun `damaged text is no choices`() {
        for (t in listOf("", "{", "[]", """{"v":2,"cams":[]}""", """{"v":1,"cams":5}""")) assertEquals(emptySet<String>(), CameraChoices(Store(t)).offIds(), t)
        assertEquals(StreamPick.AUTO, CameraChoices(Store("""{"v":1,"cams":[{"c":"g","p":"stills","o":false}]}""")).pick("g"))
    }

    // ---- the plan and the model ----

    private val home = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT)

    @Test
    fun `an off tile never starts a session and has no chip`() {
        val p = LivePlan.plan(home, listOf(LiveTileIn("gate", CameraSize.BIG, true, true, true, off = true), LiveTileIn("lawn", CameraSize.BIG, true, true, true)))
        assertEquals(setOf("lawn"), p.wanted.map { it.cameraId }.toSet())
        assertFalse(p.tiles.getValue("gate").chip)
        assertNull(p.tiles.getValue("gate").key)
    }

    @Test
    fun `an off tile is also quiet while roaming is blocked and on the tailnet`() {
        val roaming = LivePlan.plan(home.copy(roamingBlocked = true), listOf(LiveTileIn("gate", CameraSize.BIG, true, true, true, off = true)))
        assertFalse(roaming.tiles.getValue("gate").roaming, "no notice on an off tile")
        val away = LivePlan.plan(home.copy(path = LinkPath.TAILNET), listOf(LiveTileIn("gate", CameraSize.BIG, true, true, true, off = true)))
        assertTrue(away.wanted.isEmpty())
        assertFalse(away.tiles.getValue("gate").notSetUp)
    }

    @Test
    fun `withOff marks only the named cameras and returns the same view when none`() {
        val cam = { id: String -> CameraTile(id, id, null, PictureState.PICTURE) }
        val view = HomeView(Banner.NONE, listOf(BlockView("b", null, listOf(cam("a"), cam("b")), emptyList())), null, emptyList(), 0, "p", null)
        assertSame(view, HomeModel.withOff(view, emptySet()))
        assertSame(view, HomeModel.withOff(view, setOf("zzz")))
        val marked = HomeModel.withOff(view, setOf("b"))
        assertEquals(listOf(false, true), marked.cameras.map { it.off })
    }
}
