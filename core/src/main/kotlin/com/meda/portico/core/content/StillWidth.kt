package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize

/**
 * Blocks B6 (design 6.3): the width Home asks `portico/content/snapshot` for, from what is on screen. Pure.
 *
 * | tile | width |
 * | small, 1x | none (the server's 640) |
 * | big, 1x | 960 |
 * | zoom 1.5x or more | 1280 |
 * | zoom 2.5x or more | 1920 (the server's cap since question 7) |
 *
 * The request still goes through the one-in-flight-per-camera guard and the 4 s cadence: a change of width takes effect
 * at the next request and never adds one.
 */
object StillWidth {
    const val BIG = 960
    const val ZOOMED = 1280
    const val ZOOMED_MORE = 1920
    const val ZOOM_AT = 1.5f
    const val ZOOM_MORE_AT = 2.5f

    fun forTile(size: CameraSize, zoom: Float): Int? = when {
        zoom >= ZOOM_MORE_AT -> ZOOMED_MORE
        zoom >= ZOOM_AT -> ZOOMED
        size == CameraSize.BIG -> BIG
        else -> null
    }

    /**
     * The width for a camera that may be on the page, in the station call's panel, or both: the page tile wins, else the
     * panel tile, else none. A null result means "the server's 640" and must never be read as "no size known"
     * (0.6.37 fell through to a map lookup on a small tile's null and crashed the app in a loop).
     */
    fun forCamera(pageSize: CameraSize?, pageZoom: Float, callSize: CameraSize?, callZoom: Float): Int? = when {
        pageSize != null -> forTile(pageSize, pageZoom)
        callSize != null -> forTile(callSize, callZoom)
        else -> null
    }

    /** Whether going from zoom [from] to [to] changes the width wanted (the sharper, or the lighter, still is due now). */
    fun changes(size: CameraSize, from: Float, to: Float): Boolean = forTile(size, from) != forTile(size, to)
}

/** Blocks B6 (design 6.3, "Memory and data"): how much to shrink a still while decoding it. Pure. */
object ImageSampling {
    /** No decoded still is allowed more than this many pixels (about 10 MB as ARGB_8888): a 1920x1080 one fits. */
    const val MAX_PIXELS = 2_500_000

    /**
     * The `inSampleSize` (a power of two) for a picture [srcW]x[srcH] shown in a viewport [viewW]x[viewH] at [zoom]: the
     * largest that still leaves the decoded picture at least as big as what the viewport shows (viewport times zoom), and
     * never above [MAX_PIXELS]. A 1x tile therefore decodes to its viewport, not to the camera's resolution.
     */
    fun sampleSize(srcW: Int, srcH: Int, viewW: Int, viewH: Int, zoom: Float): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        val wantW = Math.ceil((viewW * maxOf(1f, zoom)).toDouble()).toInt()
        val wantH = Math.ceil((viewH * maxOf(1f, zoom)).toDouble()).toInt()
        var s = 1
        if (wantW > 0 && wantH > 0) {
            while (srcW / (s * 2) >= wantW && srcH / (s * 2) >= wantH) s *= 2
        }
        while (srcW.toLong() / s * (srcH.toLong() / s) > MAX_PIXELS) s *= 2
        return s
    }
}
