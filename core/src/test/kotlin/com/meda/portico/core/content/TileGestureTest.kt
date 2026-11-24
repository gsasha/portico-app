package com.meda.portico.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blocks B6 (design 6.3): the tile's touch state machine, the zoom arithmetic and the page-swipe guard. Pure. */
class TileGestureTest {

    private val slop = 8f
    private fun gesture(w: Int = 540, h: Int = 304): TileGesture = TileGesture(slop).also { it.setViewport(w, h) }

    /** Zooms the tile with two fingers: second finger down, scale steps about the centre, one finger lifts, then the last. */
    private fun pinchTo(g: TileGesture, factor: Float, fx: Float = 270f, fy: Float = 152f) {
        g.down(100f, 100f, 0)
        g.pointerDown()
        g.scaleBegin(fx, fy)
        g.scale(factor, fx, fy)
        g.scaleEnd()
        g.pointerUp(1)
        g.up(50)
    }

    // ---- tap -----------------------------------------------------------------------------

    @Test
    fun `a quick tap at 1x toggles the size`() {
        val g = gesture()
        g.down(100f, 100f, 1_000)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(1_120))
        assertEquals(1f, g.scale)
    }

    @Test
    fun `a tap with a little jitter inside the slop is still a tap`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.move(104f, 103f)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(100))
    }

    @Test
    fun `a press that lasts too long is not a tap`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        assertEquals(TileAction.NONE, g.up(TileGesture.TAP_MAX_MS + 1))
        g.down(100f, 100f, 0)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(TileGesture.TAP_MAX_MS))
    }

    @Test
    fun `a drag past the slop at 1x is a scroll of the page, never a tap, and never a pan`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.move(100f, 140f)
        assertFalse(g.claiming, "the page's scroll view may take it")
        assertEquals(TileAction.NONE, g.up(100))
        assertEquals(0f, g.tx)
        assertEquals(0f, g.ty)
    }

    @Test
    fun `a tap while zoomed resets the zoom and does NOT toggle the size`() {
        val g = gesture()
        pinchTo(g, 2f)
        assertTrue(g.zoomed)
        g.down(100f, 100f, 1_000)
        assertEquals(TileAction.RESET_ZOOM, g.up(1_100))
        assertEquals(1f, g.scale)
        assertEquals(0f, g.tx)
        // and the next tap toggles
        g.down(100f, 100f, 2_000)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(2_100))
    }

    @Test
    fun `there is no double tap - two taps in a row are two single taps`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(50))
        g.down(100f, 100f, 100)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(150))
        assertEquals(1f, g.scale)
    }

    @Test
    fun `a cancelled touch is never a tap`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.cancel()
        assertEquals(TileAction.NONE, g.up(10), "an up with no down in progress does nothing")
    }

    // ---- pinch ---------------------------------------------------------------------------

    @Test
    fun `a pinch zooms, claims the touch, and is never a tap even when the fingers lift fast`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.pointerDown()
        assertTrue(g.claiming)
        g.scaleBegin(270f, 152f)
        g.scale(1.5f, 270f, 152f)
        assertEquals(1.5f, g.scale)
        g.scaleEnd()
        g.pointerUp(1)
        assertEquals(TileAction.NONE, g.up(60), "a pinch released quickly is not a tap")
        assertEquals(1.5f, g.scale, "and the zoom stays")
    }

    @Test
    fun `the finger left after a pinch does not pan and does not tap`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.pointerDown()
        g.scaleBegin(270f, 152f)
        g.scale(2f, 270f, 152f)
        g.scaleEnd()
        g.pointerUp(1)
        val tx = g.tx
        g.move(200f, 220f) // the remaining finger drags
        assertEquals(tx, g.tx, "no jump")
        assertFalse(g.claiming)
        assertEquals(TileAction.NONE, g.up(100))
    }

    @Test
    fun `a second finger after the first has started to scroll is still a pinch`() {
        val g = gesture()
        g.down(100f, 100f, 0)
        g.move(100f, 150f)
        g.pointerDown()
        assertTrue(g.claiming)
    }

    @Test
    fun `the scale is held between 1x and 4x`() {
        val g = gesture()
        g.down(0f, 0f, 0)
        g.pointerDown()
        g.scaleBegin(270f, 152f)
        g.scale(10f, 270f, 152f)
        assertEquals(4f, g.scale)
        g.scale(0.01f, 270f, 152f)
        assertEquals(1f, g.scale)
        assertEquals(0f, g.tx)
        assertEquals(0f, g.ty)
    }

    @Test
    fun `below 1,05x the fingers' lift snaps to 1x and drops the pan`() {
        val g = gesture()
        g.down(0f, 0f, 0)
        g.pointerDown()
        g.scaleBegin(400f, 200f)
        g.scale(1.04f, 400f, 200f)
        assertTrue(g.scale > 1f, "live, a small zoom is shown")
        g.scaleEnd()
        assertEquals(1f, g.scale)
        assertEquals(0f, g.tx)
        assertEquals(0f, g.ty)
        g.down(0f, 0f, 0)
        g.pointerDown()
        g.scaleBegin(400f, 200f)
        g.scale(1.06f, 400f, 200f)
        g.scaleEnd()
        assertEquals(1.06f, g.scale, 1e-4f, "1.06 stays")
    }

    @Test
    fun `zooming keeps the point under the fingers where it is`() {
        val g = gesture(540, 300)
        g.down(0f, 0f, 0)
        g.pointerDown()
        g.scaleBegin(400f, 200f)
        g.scale(2f, 400f, 200f)
        // the picture point that was at (400,200) is still there: p' = p * s + t
        assertEquals(2f, g.scale)
        assertEquals(400f * 2f + g.tx, 400f, 1e-3f)
        assertEquals(200f * 2f + g.ty, 200f, 1e-3f)
    }

    @Test
    fun `the two fingers' midpoint moving pans while they zoom`() {
        val g = gesture(540, 300)
        g.down(0f, 0f, 0)
        g.pointerDown()
        g.scaleBegin(270f, 150f)
        g.scale(2f, 270f, 150f)
        val before = g.tx
        g.scale(1f, 250f, 150f) // midpoint moved 20 px left
        assertEquals(before - 20f, g.tx, 1e-3f)
    }

    // ---- pan -----------------------------------------------------------------------------

    @Test
    fun `one finger pans a zoomed picture, claims the touch, and the pan is bounded to keep the picture covering the tile`() {
        val g = gesture(540, 300)
        pinchTo(g, 2f)
        g.down(300f, 150f, 1_000)
        g.move(310f, 150f) // past the slop: panning starts here, no jump
        assertTrue(g.claiming)
        val start = g.tx
        g.move(330f, 170f)
        assertEquals(start + 20f, g.tx, 1e-3f)
        g.move(-5000f, -5000f)
        assertEquals(540f - 540f * 2f, g.tx, 1e-3f, "bounded at the far edge")
        assertEquals(300f - 300f * 2f, g.ty, 1e-3f)
        g.move(9000f, 9000f)
        assertEquals(0f, g.tx, 1e-3f, "and the near edge")
        assertEquals(0f, g.ty, 1e-3f)
        assertEquals(TileAction.NONE, g.up(1_300), "a pan is not a tap")
    }

    @Test
    fun `a pan within the slop while zoomed is a tap that resets`() {
        val g = gesture()
        pinchTo(g, 2f)
        g.down(100f, 100f, 0)
        g.move(103f, 104f)
        assertEquals(TileAction.RESET_ZOOM, g.up(80))
    }

    @Test
    fun `the viewport shrinking re-bounds a zoomed pan`() {
        val g = gesture(540, 300)
        pinchTo(g, 4f, 540f, 300f)
        assertTrue(g.tx < -1000f)
        g.setViewport(270, 150)
        assertEquals(270f - 270f * 4f, g.tx, 1e-3f)
    }

    // ---- zoom off, steps, reset ----------------------------------------------------------

    @Test
    fun `with zoom off (a tile with no picture) a pinch does nothing but the tap still toggles`() {
        val g = gesture()
        g.zoomEnabled = false
        g.down(100f, 100f, 0)
        g.pointerDown()
        g.scaleBegin(270f, 152f)
        g.scale(3f, 270f, 152f)
        assertEquals(1f, g.scale)
        g.scaleEnd()
        g.pointerUp(1)
        g.up(10)
        assertFalse(g.zoomIn())
        g.down(100f, 100f, 0)
        assertEquals(TileAction.SHOW_CONTROLS, g.up(50))
    }

    @Test
    fun `turning zoom off while zoomed resets it (the picture went away)`() {
        val g = gesture()
        pinchTo(g, 3f)
        g.zoomEnabled = false
        assertEquals(1f, g.scale)
        assertEquals(0f, g.tx)
    }

    @Test
    fun `TalkBack steps - zoom in goes 1, 2, 4 and stops, zoom out goes back to 1`() {
        val g = gesture()
        assertTrue(g.zoomIn()); assertEquals(2f, g.scale)
        assertTrue(g.zoomIn()); assertEquals(4f, g.scale)
        assertFalse(g.zoomIn(), "nothing above 4x"); assertEquals(4f, g.scale)
        assertTrue(g.zoomOut()); assertEquals(2f, g.scale)
        assertTrue(g.zoomOut()); assertEquals(1f, g.scale)
        assertFalse(g.zoomOut(), "nothing below 1x")
        assertEquals(0f, g.tx)
    }

    @Test
    fun `a step from an in-between zoom goes to the next step, not past it`() {
        val g = gesture()
        pinchTo(g, 1.5f)
        assertTrue(g.zoomIn()); assertEquals(2f, g.scale)
        pinchTo(g, 1.2f) // 2.4
        assertTrue(g.zoomOut()); assertEquals(2f, g.scale)
    }

    @Test
    fun `steps zoom about the centre and stay inside the bounds`() {
        val g = gesture(540, 300)
        g.zoomIn()
        assertEquals(270f * 2f + g.tx, 270f, 1e-3f)
        assertEquals(150f * 2f + g.ty, 150f, 1e-3f)
    }

    @Test
    fun `TalkBack's click is the tap - it resets a zoom, else toggles`() {
        val g = gesture()
        assertEquals(TileAction.SHOW_CONTROLS, g.activate())
        g.zoomIn()
        assertEquals(TileAction.RESET_ZOOM, g.activate())
        assertEquals(1f, g.scale)
    }

    @Test
    fun `every change is announced to the view`() {
        val g = gesture()
        var n = 0
        g.onChange = { n++ }
        g.zoomIn()
        assertEquals(1, n)
        g.resetZoom()
        assertEquals(2, n)
        g.resetZoom()
        assertEquals(2, n, "no change, no call")
    }

    // ---- the zoom maths ------------------------------------------------------------------

    @Test
    fun `the pan bounds keep the picture over the viewport at every scale`() {
        for (s in listOf(1f, 1.5f, 2f, 4f)) {
            assertEquals(0f, ZoomMath.clampPan(50f, 540f, s))
            assertEquals(540f - 540f * s, ZoomMath.clampPan(-9999f, 540f, s))
        }
        assertEquals(0f, ZoomMath.clampPan(-30f, 540f, 1f), "no pan at 1x")
    }

    @Test
    fun `placement at 1x is exactly centre-crop`() {
        // 1920x1080 in a 540x304 box (16:9, a hair taller): the height decides, the width is cropped at both sides
        val p = ZoomMath.placement(1920, 1080, 540, 304, 1f, 0f, 0f)
        val crop = maxOf(540f / 1920, 304f / 1080)
        assertEquals(crop, p.scale, 1e-6f)
        assertEquals((540f - 1920 * crop) / 2f, p.dx, 1e-3f)
        assertEquals((304f - 1080 * crop) / 2f, p.dy, 1e-3f)
        // a picture of another aspect is cropped the other way
        val q = ZoomMath.placement(640, 640, 540, 304, 1f, 0f, 0f)
        assertEquals(540f / 640f, q.scale, 1e-6f)
        assertEquals(0f, q.dx, 1e-3f)
    }

    @Test
    fun `a new still of another resolution lands in the same place - no jump`() {
        val a = ZoomMath.placement(960, 540, 540, 304, 2f, -100f, -40f)
        val b = ZoomMath.placement(1920, 1080, 540, 304, 2f, -100f, -40f)
        // the picture corner (0,0) and the far corner map to the same viewport points
        assertEquals(a.dx, b.dx, 0.6f)
        assertEquals(a.dy, b.dy, 0.6f)
        assertEquals(960 * a.scale, 1920 * b.scale, 1.5f)
    }

    @Test
    fun `at every zoom and legal pan the placed picture covers the viewport`() {
        val w = 540
        val h = 304
        for (s in listOf(1f, 1.7f, 2f, 3.3f, 4f)) for (fx in listOf(0f, 0.5f, 1f)) for (fy in listOf(0f, 0.5f, 1f)) {
            val tx = ZoomMath.clampPan((w - w * s) * fx, w.toFloat(), s)
            val ty = ZoomMath.clampPan((h - h * s) * fy, h.toFloat(), s)
            val p = ZoomMath.placement(1920, 1080, w, h, s, tx, ty)
            assertTrue(p.dx <= 0.001f && p.dy <= 0.001f, "left/top edge in $s")
            assertTrue(p.dx + 1920 * p.scale >= w - 0.01f && p.dy + 1080 * p.scale >= h - 0.01f, "right/bottom edge in $s")
        }
    }

    // ---- the page swipe guard ------------------------------------------------------------

    @Test
    fun `a plain swipe from outside the tab strip may turn the page`() {
        val g = SwipeGuard()
        g.down(onTabStrip = false, anyTileZoomed = false)
        assertTrue(g.up())
    }

    @Test
    fun `a touch that starts on the tab strip never turns the page`() {
        val g = SwipeGuard()
        g.down(onTabStrip = true, anyTileZoomed = false)
        assertFalse(g.up())
    }

    @Test
    fun `while a picture is zoomed no swipe turns the page`() {
        val g = SwipeGuard()
        g.down(onTabStrip = false, anyTileZoomed = true)
        assertFalse(g.up())
    }

    @Test
    fun `a second finger during the touch (a pinch) cancels the swipe, even if only one finger is down at the end`() {
        val g = SwipeGuard()
        g.down(onTabStrip = false, anyTileZoomed = false)
        g.pointerDown()
        assertFalse(g.up())
    }

    @Test
    fun `the guard is per touch - after a pinch the next plain swipe works again`() {
        val g = SwipeGuard()
        g.down(false, false)
        g.pointerDown()
        g.up()
        g.down(false, false)
        assertTrue(g.up())
    }

    @Test
    fun `a cancelled touch cannot turn the page, and up without a down is nothing`() {
        val g = SwipeGuard()
        g.down(false, false)
        g.cancel()
        assertFalse(g.up())
        assertFalse(g.up())
    }
}
