package com.meda.portico.core.content

import com.meda.portico.core.connlog.JournalLines
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentCamera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraViewportTest {
    private val B = CameraSize.BIG
    private val S = CameraSize.SMALL

    // ---- the stream a viewport asks for ----

    @Test
    fun `Auto is today's rule - the tile size decides the stream`() {
        assertEquals(B, CameraViewport.stream(StreamPick.AUTO, B, hasSmall = true))
        assertEquals(S, CameraViewport.stream(StreamPick.AUTO, S, hasSmall = true))
        // an older server sends no small flag, and a small tile asked for its sub stream before: unchanged
        assertEquals(S, CameraViewport.stream(StreamPick.AUTO, S, hasSmall = false))
    }

    @Test
    fun `an explicit pick overrides the tile size, either way`() {
        assertEquals(B, CameraViewport.stream(StreamPick.BIG, S, true))
        assertEquals(S, CameraViewport.stream(StreamPick.SMALL, B, true))
    }

    @Test
    fun `a Small pick on a camera with no small stream falls back to Auto`() {
        assertEquals(StreamPick.AUTO, CameraViewport.usable(StreamPick.SMALL, false))
        assertEquals(B, CameraViewport.stream(StreamPick.SMALL, B, false))
        assertEquals(StreamPick.SMALL, CameraViewport.usable(StreamPick.SMALL, true))
    }

    // ---- menus ----

    @Test
    fun `the resize menu lists Big, Small, Fullscreen with the current one checked`() {
        for (cur in ViewLayout.entries - ViewLayout.PARTIAL) { // Partial is only listed when offered (tablet mode): PartialViewTest
            val items = CameraViewport.resizeItems(cur)
            assertEquals(listOf(ViewLayout.BIG, ViewLayout.SMALL, ViewLayout.FULLSCREEN), items.map { it.layout })
            assertEquals(listOf(cur), items.filter { it.checked }.map { it.layout })
        }
    }

    @Test
    fun `resize - Big and Small change the tile and leave fullscreen, Fullscreen again leaves it`() {
        assertEquals(CameraViewport.Resize(S, false), CameraViewport.resize(ViewLayout.BIG, ViewLayout.SMALL, B))
        assertEquals(CameraViewport.Resize(null, false), CameraViewport.resize(ViewLayout.BIG, ViewLayout.BIG, B))
        assertEquals(CameraViewport.Resize(null, true), CameraViewport.resize(ViewLayout.SMALL, ViewLayout.FULLSCREEN, S))
        assertEquals(CameraViewport.Resize(null, false), CameraViewport.resize(ViewLayout.FULLSCREEN, ViewLayout.FULLSCREEN, S))
        assertEquals(CameraViewport.Resize(B, false), CameraViewport.resize(ViewLayout.FULLSCREEN, ViewLayout.BIG, S))
        assertEquals(CameraViewport.Resize(null, false), CameraViewport.resize(ViewLayout.FULLSCREEN, ViewLayout.SMALL, S))
    }

    @Test
    fun `the stream menu lists Auto, the resolutions, stills, off - in that order`() {
        val m = CameraViewport.streamMenu(CameraState(), S, hasSmall = true)
        assertEquals(listOf(StreamPick.AUTO, StreamPick.BIG, StreamPick.SMALL, StreamPick.STILLS, StreamPick.OFF), m.items.map { it.pick })
        assertEquals(S, m.items[0].resolvesTo)
        assertEquals(listOf(StreamPick.AUTO), m.items.filter { it.checked }.map { it.pick })
        val picked = CameraViewport.streamMenu(CameraState(StreamPick.BIG), S, true)
        assertEquals(listOf(StreamPick.BIG), picked.items.filter { it.checked }.map { it.pick })
    }

    @Test
    fun `the rows carry the quality of the stream when its height was seen, null otherwise`() {
        val heights = mapOf(B to 1520, S to 360)
        val m = CameraViewport.streamMenu(CameraState(), B, true) { heights[it] }
        assertEquals("1520p", m.items[1].quality)
        assertEquals("360p", m.items[2].quality)
        assertEquals("1520p", m.items[0].quality,"Auto is the quality it resolves to (a Big tile: the main stream)")
        val unknown = CameraViewport.streamMenu(CameraState(), B, true)
        assertNull(unknown.items[1].quality)
        assertNull(unknown.items[0].quality)
    }

    @Test
    fun `resolutions are listed biggest first by their seen heights`() {
        val m = CameraViewport.streamMenu(CameraState(), B, true) { if (it == B) 360 else 720 }
        assertEquals(listOf(StreamPick.AUTO, StreamPick.SMALL, StreamPick.BIG, StreamPick.STILLS, StreamPick.OFF), m.items.map { it.pick })
    }

    @Test
    fun `the stream menu of a one-stream camera offers only that stream, then stills and off`() {
        val m = CameraViewport.streamMenu(CameraState(StreamPick.SMALL), B, hasSmall = false)
        assertEquals(listOf(StreamItem(StreamPick.BIG, true), StreamItem(StreamPick.STILLS, false), StreamItem(StreamPick.OFF, false)), m.items)
    }

    @Test
    fun `live off checks stills, off checks off, whatever the resolution`() {
        val stills = CameraViewport.streamMenu(CameraState(StreamPick.BIG, live = false), B, true)
        assertEquals(listOf(StreamPick.STILLS), stills.items.filter { it.checked }.map { it.pick })
        val off = CameraViewport.streamMenu(CameraState(StreamPick.BIG, live = false, off = true), B, true)
        assertEquals(listOf(StreamPick.OFF), off.items.filter { it.checked }.map { it.pick })
    }


    @Test
    fun `layout of a viewport`() {
        assertEquals(ViewLayout.BIG, ViewLayout.of(B, false))
        assertEquals(ViewLayout.SMALL, ViewLayout.of(S, false))
        assertEquals(ViewLayout.FULLSCREEN, ViewLayout.of(S, true))
    }

    // ---- memory ----

    private val k1 = ViewportKey("home", "camera.gate")
    private val k2 = ViewportKey("home", "camera.yard")

    @Test
    fun `picks are per viewport, Auto removes the pick`() {
        val m = ViewportMemory()
        assertEquals(StreamPick.AUTO, m.pick(k1))
        assertTrue(m.setPick(k1, StreamPick.SMALL))
        assertFalse(m.setPick(k1, StreamPick.SMALL))
        assertEquals(StreamPick.AUTO, m.pick(k2))
        assertTrue(m.setPick(k1, StreamPick.AUTO))
        assertTrue(m.isEmpty)
    }

    @Test
    fun `retain drops viewports that are no longer open, fullscreen included`() {
        val m = ViewportMemory()
        m.setPick(k1, StreamPick.BIG)
        m.setPick(k2, StreamPick.SMALL)
        m.setFullscreen(k2)
        assertTrue(m.retain(setOf(k1)))
        assertEquals(StreamPick.BIG, m.pick(k1))
        assertEquals(StreamPick.AUTO, m.pick(k2))
        assertNull(m.fullscreen)
        assertFalse(m.retain(setOf(k1)))
        m.retain(emptySet())
        assertTrue(m.isEmpty)
    }

    @Test
    fun `memory survives a rotation through its text, and damaged text is empty`() {
        val m = ViewportMemory()
        m.setPick(k1, StreamPick.SMALL)
        m.setFullscreen(k2)
        val back = ViewportMemory.decode(m.encode())
        assertEquals(StreamPick.SMALL, back.pick(k1))
        assertEquals(k2, back.fullscreen)
        assertTrue(ViewportMemory.decode(null).isEmpty)
        assertTrue(ViewportMemory.decode("garbage\nP\tonly").isEmpty)
        assertTrue(ViewportMemory.decode("P\ta\tb\tnonsense").isEmpty)
    }

    // ---- control strip ----

    @Test
    fun `a tap shows the strip for 3 seconds, then it is gone`() {
        val s = ControlStrip()
        assertNull(s.visibleId(0))
        s.show("c1", 1_000)
        assertEquals("c1", s.visibleId(1_000))
        assertEquals("c1", s.visibleId(3_999))
        assertNull(s.visibleId(4_000))
        assertEquals(4_000L, ControlStrip().also { it.show("c", 1_000) }.nextCheckAt(1_500))
    }

    @Test
    fun `another tap restarts the clock and moves the strip to the new tile`() {
        val s = ControlStrip()
        s.show("c1", 0)
        s.show("c1", 2_000)
        assertEquals("c1", s.visibleId(4_000))
        s.show("c2", 4_500)
        assertEquals("c2", s.visibleId(5_000))
    }

    @Test
    fun `an open menu holds the strip and closing it starts 3 seconds again`() {
        val s = ControlStrip()
        s.show("c1", 0)
        s.hold(true, 1_000)
        assertEquals("c1", s.visibleId(60_000))
        assertNull(s.nextCheckAt(60_000))
        s.hold(false, 60_000)
        assertEquals("c1", s.visibleId(62_999))
        assertNull(s.visibleId(63_000))
    }

    // ---- journal ----

    @Test
    fun `the journal lines are ui lines the reporter keeps`() {
        val pick = CameraViewport.pickLine("camera.gate_intercom", StreamPick.SMALL, S, live = true)
        val resize = CameraViewport.resizeLine("camera.gate_intercom", ViewLayout.BIG, ViewLayout.FULLSCREEN, B)
        assertEquals("ui cam-pick cam=camera…(20) pick=small stream=small live=yes", pick)
        assertEquals("ui cam-resize cam=camera…(20) from=big to=fullscreen stream=big", resize)
        for (l in listOf(pick, resize)) assertTrue(JournalLines.check(l) is JournalLines.Verdict.Keep, l)
    }

    // ---- the camera item ----

    @Test
    fun `hasSmall comes only from a strict true`() {
        fun cam(small: Boolean) = ContentCamera("c", "L", null, true, small = small)
        assertTrue(HomeModel.cameraTile(cam(true), true, emptySet()).hasSmall)
        assertFalse(HomeModel.cameraTile(cam(false), true, emptySet()).hasSmall)
    }
}
