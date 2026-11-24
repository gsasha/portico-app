package com.meda.portico.core.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanPartsTest {

    // ---- the camera permission ----------------------------------------------------------------

    @Test
    fun `no camera wins over everything`() {
        for (g in listOf(true, false)) for (a in listOf(true, false)) for (r in listOf(true, false)) {
            assertEquals(ScanStart.NO_CAMERA, CameraPermissionPlan.onScanTapped(false, g, a, r))
        }
    }

    @Test
    fun `granted opens the scanner`() {
        assertEquals(ScanStart.OPEN_SCANNER, CameraPermissionPlan.onScanTapped(true, true, false, false))
        assertEquals(ScanStart.OPEN_SCANNER, CameraPermissionPlan.onScanTapped(true, true, true, false))
    }

    @Test
    fun `the first tap explains before Android's prompt - never-asked is not blocked`() {
        assertEquals(ScanStart.EXPLAIN_THEN_ASK, CameraPermissionPlan.onScanTapped(true, false, askedBefore = false, showRationale = false))
    }

    @Test
    fun `refused once - explain again, then ask`() {
        assertEquals(ScanStart.EXPLAIN_THEN_ASK, CameraPermissionPlan.onScanTapped(true, false, askedBefore = true, showRationale = true))
    }

    @Test
    fun `asked before and no rationale - ask anyway, Android decides whether a dialog appears`() {
        assertEquals(ScanStart.ASK, CameraPermissionPlan.onScanTapped(true, false, askedBefore = true, showRationale = false))
    }

    @Test
    fun `the answer - granted, refused, refused for good`() {
        assertEquals(AfterAsk.OPEN_SCANNER, CameraPermissionPlan.afterAsk(granted = true, showRationale = false))
        assertEquals(AfterAsk.DENIED, CameraPermissionPlan.afterAsk(granted = false, showRationale = true))
        assertEquals(AfterAsk.DENIED_PERMANENTLY, CameraPermissionPlan.afterAsk(granted = false, showRationale = false))
    }

    // ---- the viewfinder arithmetic ------------------------------------------------------------

    @Test
    fun `the aiming square is square, inside the view, and above centre`() {
        for ((w, h) in listOf(1080 to 2400, 720 to 1280, 1440 to 3120, 1080 to 1920)) {
            val b = Viewfinder.aimingSquare(w, h)
            assertEquals(b.width, b.height, 0.01f)
            assertTrue(b.left > 0 && b.right < w && b.top > 0 && b.bottom < h, "$b in ${w}x$h")
            assertTrue((b.top + b.bottom) / 2 < h / 2f)
        }
        // The old app's numbers on a 1080x2400 phone (its README): x 140-940, y 680-1480.
        val b = Viewfinder.aimingSquare(1080, 2400)
        assertEquals(140.4f, b.left, 0.5f)
        assertEquals(680.4f, b.top, 0.5f)
        assertEquals(1479.6f, b.bottom, 0.5f)
    }

    @Test
    fun `centre-crop restores the frame's aspect and only ever enlarges`() {
        // 1280x720 sensor frame shows as 720 wide x 1280 tall; a 1080x2400 view is taller (20:9).
        val (sx, sy) = Viewfinder.centreCropScale(1280, 720, 1080, 2400)
        assertTrue(sx >= 1f && sy >= 1f)
        // After scaling, the displayed aspect equals the frame's (720/1280).
        assertEquals(720f / 1280f, (1080f * sx) / (2400f * sy), 0.001f)
        // A wide view: the scale falls on the other axis.
        val (sx2, sy2) = Viewfinder.centreCropScale(1280, 720, 1080, 1000)
        assertEquals(1f, sx2)
        assertEquals(720f / 1280f, (1080f * sx2) / (1000f * sy2), 0.001f)
        assertEquals(1f to 1f, Viewfinder.centreCropScale(0, 0, 1080, 2400))
    }

    @Test
    fun `the frame size is the one nearest 720p, not the biggest`() {
        val sizes = listOf(4000 to 3000, 1920 to 1080, 1280 to 960, 1280 to 720, 640 to 480)
        assertEquals(1280 to 720, Viewfinder.chooseFrameSize(sizes))
        assertEquals(1280 to 960, Viewfinder.chooseFrameSize(listOf(4000 to 3000, 1280 to 960, 320 to 240)))
        assertEquals(640 to 480, Viewfinder.chooseFrameSize(emptyList()))
    }

    // ---- the hand-off -------------------------------------------------------------------------

    @Test
    fun `the hand-off gives the code once`() {
        ScanHandoff.clear()
        ScanHandoff.put("x")
        assertEquals("x", ScanHandoff.take())
        assertEquals(null, ScanHandoff.take())
        ScanHandoff.put("y")
        ScanHandoff.clear()
        assertFalse(ScanHandoff.toString().contains("true"))
    }
}
