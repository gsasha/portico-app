package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blocks B6 (design 6.3): the width asked for, and how much a still is shrunk while decoded. Pure. */
class StillWidthTest {

    private val big = CameraSize.BIG
    private val small = CameraSize.SMALL

    @Test
    fun `the table - small 1x server default, big 960, zoom 1,5x 1280, zoom 2,5x 1920`() {
        assertNull(StillWidth.forTile(small, 1f), "none: the server's 640")
        assertEquals(960, StillWidth.forTile(big, 1f))
        assertEquals(1280, StillWidth.forTile(small, 1.5f))
        assertEquals(1280, StillWidth.forTile(big, 2.49f))
        assertEquals(1920, StillWidth.forTile(small, 2.5f))
        assertEquals(1920, StillWidth.forTile(big, 4f))
    }

    @Test
    fun `just under a threshold stays on the lower width`() {
        assertNull(StillWidth.forTile(small, 1.49f))
        assertEquals(960, StillWidth.forTile(big, 1.49f))
        assertEquals(1280, StillWidth.forTile(small, 2.49f))
    }

    @Test
    fun `every width the app can ask for is one the guard accepts`() {
        for (size in CameraSize.entries) for (z in listOf(1f, 1.05f, 1.5f, 2f, 2.5f, 3f, 4f)) {
            val w = StillWidth.forTile(size, z)
            if (w != null) ContentCommands.snapshot("gate", w)
        }
        assertEquals(ContentCommands.SNAPSHOT_WIDTH_MAX, StillWidth.ZOOMED_MORE)
    }

    @Test
    fun `a zoom change matters only when it crosses a width step`() {
        assertFalse(StillWidth.changes(big, 1f, 1.4f))
        assertTrue(StillWidth.changes(big, 1f, 1.5f))
        assertFalse(StillWidth.changes(small, 1.5f, 2.4f))
        assertTrue(StillWidth.changes(small, 2.4f, 2.5f))
        assertTrue(StillWidth.changes(small, 2f, 1f), "leaving the zoom drops back to the lighter still")
        assertTrue(StillWidth.changes(small, 1f, 1.5f), "a small tile has none at 1x, so zooming changes it")
    }

    // ---- inSampleSize --------------------------------------------------------------------

    @Test
    fun `a 1x tile decodes to about its own pixels, not the camera's`() {
        // 1920x1080 camera, a 1080x608 big tile: 1920/2 = 960 < 1080, so no shrinking
        assertEquals(1, ImageSampling.sampleSize(1920, 1080, 1080, 608, 1f))
        // the same picture in a 540x304 small tile: 960 >= 540 and 540 >= 304, then 480 < 540: sample 2
        assertEquals(2, ImageSampling.sampleSize(1920, 1080, 540, 304, 1f))
        // a 640-wide still in a small tile: nothing to shrink
        assertEquals(1, ImageSampling.sampleSize(640, 360, 540, 304, 1f))
    }

    @Test
    fun `zoom asks for more pixels - the decode keeps what the zoomed viewport needs`() {
        assertEquals(2, ImageSampling.sampleSize(1920, 1080, 540, 304, 1f))
        assertEquals(1, ImageSampling.sampleSize(1920, 1080, 540, 304, 2f), "1080 wide wanted: 960 would be short")
        assertEquals(1, ImageSampling.sampleSize(1920, 1080, 540, 304, 4f))
    }

    @Test
    fun `no decoded still is over the pixel cap, whatever the viewport says`() {
        val s = ImageSampling.sampleSize(4000, 3000, 4000, 3000, 4f)
        assertTrue(4000L / s * (3000L / s) <= ImageSampling.MAX_PIXELS)
        assertEquals(4, s, "12 MP -> 3 MP at 2 is still over 2,5 MP, 0,75 MP at 4")
        assertEquals(1, ImageSampling.sampleSize(1920, 1080, 1920, 1080, 1f), "a 1920x1080 fits the cap whole")
        assertTrue(1920 * 1080 <= ImageSampling.MAX_PIXELS)
    }

    @Test
    fun `the sample is a power of two and an unreadable or zero-sized input is 1`() {
        for (src in listOf(320, 641, 1280, 3000, 8000)) for (view in listOf(0, 100, 540, 1080)) {
            val s = ImageSampling.sampleSize(src, src * 9 / 16, view, view * 9 / 16, 1f)
            assertEquals(0, s and (s - 1), "$src/$view -> $s")
            assertTrue(s >= 1)
        }
        assertEquals(1, ImageSampling.sampleSize(0, 0, 540, 304, 1f))
        assertEquals(1, ImageSampling.sampleSize(-1, 100, 540, 304, 1f))
        assertEquals(1, ImageSampling.sampleSize(1920, 1080, 0, 0, 1f), "an unmeasured tile decodes whole")
    }

    @Test
    fun `forCamera - a small page tile means the server default, never a fall through to the call panel`() {
        assertNull(StillWidth.forCamera(small, 1f, null, 1f), "the 0.6.37 crash: small page tile, not in the call panel")
        assertNull(StillWidth.forCamera(small, 1f, big, 1f), "the page tile wins even when its width is none")
        assertEquals(960, StillWidth.forCamera(big, 1f, null, 1f))
        assertEquals(960, StillWidth.forCamera(null, 1f, big, 1f), "a call-panel-only camera")
        assertNull(StillWidth.forCamera(null, 1f, null, 1f))
    }

    @Test
    fun `a picture smaller than the box is never shrunk`() {
        assertEquals(1, ImageSampling.sampleSize(320, 180, 1080, 608, 1f))
    }
}
