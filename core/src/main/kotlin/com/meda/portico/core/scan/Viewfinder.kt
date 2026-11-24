package com.meda.portico.core.scan

/** A rectangle in view pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The scanner screen's arithmetic, out of the view so it can be tested and rendered to a PNG on
 * the build host (memory note render-layout-math-to-see-it). Ported from the old intercom app's
 * `TransferActivity.Reticle` and `transform`, which were checked on real phones.
 */
object Viewfinder {
    /**
     * The aiming square: 74% of the shorter side, a little above centre (the bottom bar takes the
     * lower strip). An aid, not a crop: the decoder reads the whole frame.
     */
    fun aimingSquare(viewW: Int, viewH: Int): Box {
        val side = minOf(viewW, viewH) * 0.74f
        val l = (viewW - side) / 2f
        val t = (viewH - side) / 2f - viewH * 0.05f
        return Box(l, t, l + side, t + side)
    }

    /**
     * Centre-crop scale for a TextureView showing a [frameW]x[frameH] camera buffer in a
     * portrait-locked view. The back sensor is mounted at 90 degrees, and TextureView already
     * compensates for the sensor orientation, so on screen the frame is [frameH] wide and
     * [frameW] tall. TextureView stretches the buffer to its bounds; this restores the frame's
     * aspect ratio and lets the overflow fall off the edges. Returns (scaleX, scaleY), each >= 1.
     */
    fun centreCropScale(frameW: Int, frameH: Int, viewW: Int, viewH: Int): Pair<Float, Float> {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) return 1f to 1f
        val bufW = frameH.toFloat()
        val bufH = frameW.toFloat()
        val viewAspect = viewW / viewH.toFloat()
        val bufAspect = bufW / bufH
        return if (bufAspect > viewAspect) (bufAspect / viewAspect) to 1f else 1f to (viewAspect / bufAspect)
    }

    /**
     * The camera output size to ask for: the one closest to 1280x720 in pixel count, never the
     * biggest. A QR code filling a third of the frame is resolved many times over at 720p, while
     * a 12-megapixel frame costs ~18 MB a copy and drops frames. [sizes] are (width, height).
     */
    fun chooseFrameSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int> =
        sizes.minByOrNull { (w, h) -> kotlin.math.abs(w.toLong() * h - 1280L * 720L) } ?: (640 to 480)
}
