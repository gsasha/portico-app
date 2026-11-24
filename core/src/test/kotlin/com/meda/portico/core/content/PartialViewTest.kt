package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tablet mode's partial camera layout (D-20261122-da34): the big camera with up to two small ones of its block at its right. */
class PartialViewTest {
    private val B = CameraSize.BIG
    private val S = CameraSize.SMALL

    private fun tile(id: String, size: CameraSize) = CameraTile(id, id, null, PictureState.PICTURE, size)
    private fun block(id: String, vararg t: CameraTile) = BlockView(id, null, t.toList(), emptyList())
    private fun view(vararg b: BlockView) = HomeView(Banner.NONE, b.toList(), selectedId = "p")

    @Test
    fun `the menu offers Partial only when asked, between Big and Small`() {
        assertEquals(listOf(ViewLayout.BIG, ViewLayout.SMALL, ViewLayout.FULLSCREEN), CameraViewport.resizeItems(ViewLayout.BIG).map { it.layout })
        val items = CameraViewport.resizeItems(ViewLayout.PARTIAL, partialOffered = true)
        assertEquals(listOf(ViewLayout.BIG, ViewLayout.PARTIAL, ViewLayout.SMALL, ViewLayout.FULLSCREEN), items.map { it.layout })
        assertEquals(listOf(ViewLayout.PARTIAL), items.filter { it.checked }.map { it.layout })
    }

    @Test
    fun `choosing Partial saves the tile Big, the checked item again goes back, other choices keep their rules`() {
        assertEquals(CameraViewport.Resize(B, false, partial = true), CameraViewport.resize(ViewLayout.SMALL, ViewLayout.PARTIAL, S))
        assertEquals(CameraViewport.Resize(null, false, partial = true), CameraViewport.resize(ViewLayout.BIG, ViewLayout.PARTIAL, B))
        assertEquals(CameraViewport.Resize(null, false), CameraViewport.resize(ViewLayout.PARTIAL, ViewLayout.PARTIAL, B))
        assertEquals(CameraViewport.Resize(null, false), CameraViewport.resize(ViewLayout.PARTIAL, ViewLayout.BIG, B))
        assertEquals(CameraViewport.Resize(S, false), CameraViewport.resize(ViewLayout.PARTIAL, ViewLayout.SMALL, B))
        assertEquals(CameraViewport.Resize(null, true), CameraViewport.resize(ViewLayout.PARTIAL, ViewLayout.FULLSCREEN, B))
    }

    @Test
    fun `layout of a viewport - fullscreen outranks partial`() {
        assertEquals(ViewLayout.PARTIAL, ViewLayout.of(B, fullscreen = false, partial = true))
        assertEquals(ViewLayout.FULLSCREEN, ViewLayout.of(B, fullscreen = true, partial = true))
        assertEquals(ViewLayout.BIG, ViewLayout.of(B, false))
    }

    @Test
    fun `the side cameras are the first two others in block order, the rest flow below`() {
        val p = CameraGrid.partial(5, big = 1)
        assertEquals(1, p.big)
        assertEquals(listOf(0, 2), p.side)
        val rows = CameraGrid.partialRows(listOf(B, B, S, S, B), big = 1)
        assertEquals(listOf(0, 2), rows.side)
        assertEquals(listOf(GridRow(listOf(3), false), GridRow(listOf(4), true)), rows.rest)
    }

    @Test
    fun `one or two side cameras, or none`() {
        assertEquals(emptyList<Int>(), CameraGrid.partial(1, 0).side)
        assertEquals(listOf(1), CameraGrid.partial(2, 0).side)
        assertEquals(listOf(0, 1), CameraGrid.partial(3, 2).side)
        assertEquals(emptyList<GridRow>(), CameraGrid.partialRows(listOf(B, S, S), 0).rest)
    }

    @Test
    fun `withPartial forces the draw sizes in that block only and marks it`() {
        val v = view(block("b1", tile("a", S), tile("b", B), tile("c", B), tile("d", B)), block("b2", tile("x", B), tile("y", B)))
        val out = CameraViewport.withPartial(v, "b")
        val b1 = out.blocks[0]
        assertEquals("b", b1.partialBig)
        assertEquals(listOf(S, B, S, B), b1.cameras.map { it.size }) // b big, a and c (the first two others) small, d untouched
        assertNull(out.blocks[1].partialBig)
        assertEquals(listOf(B, B), out.blocks[1].cameras.map { it.size })
        val plan = b1.partialPlan!!
        assertEquals(1, plan.big)
        assertEquals(listOf(0, 2), plan.side)
        assertEquals(listOf(GridRow(listOf(3), true)), plan.rest)
    }

    @Test
    fun `withPartial is the same view for no camera or an unknown one, and a block without one has no plan`() {
        val v = view(block("b1", tile("a", B), tile("b", B)))
        assertSame(v, CameraViewport.withPartial(v, null))
        assertSame(v, CameraViewport.withPartial(v, "zzz"))
        assertNull(v.blocks[0].partialPlan)
    }

    @Test
    fun `the memory keeps partial like fullscreen - encoded, retained by page, cleared`() {
        val k = ViewportKey("p", "a")
        val m = ViewportMemory()
        m.setPartial(k)
        assertTrue(!m.isEmpty)
        assertEquals(k, ViewportMemory.decode(m.encode()).partial)
        assertTrue(!m.retain(setOf(k)))
        assertTrue(m.retain(setOf(ViewportKey("p", "other"))))
        assertNull(m.partial)
        m.setPartial(k)
        m.clear()
        assertTrue(m.isEmpty)
    }
}
