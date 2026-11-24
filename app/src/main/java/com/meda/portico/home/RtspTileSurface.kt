package com.meda.portico.home

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The plain `SurfaceView` of one RTSP (away from home) live tile (DEV-713 S1b): the view the phone's `MediaCodec` draws on. It sits in
 * the tile's frame ABOVE the still and BELOW the words, like the WebRTC renderer, with these rules:
 *
 * - **Never INVISIBLE or GONE.** A SurfaceView that is not VISIBLE has no surface, so the decoder could never draw its first frame.
 *   "Not shown" is therefore a 1x1 view in the corner (a one-pixel hole) and the still visible; "shown" is the video fitted inside the
 *   frame, keeping its aspect (the frame is black, so a 4:3 picture gets bars) with the still hidden behind it.
 * - **No background, no z-order tricks.** Nothing paints on it; it stays below the window like any SurfaceView and the words over it
 *   are later siblings.
 * - It is a view, so it dies with the grid ([release]); the stream does not (`LiveHome`).
 */
class RtspTileSurface(context: Context, private val frame: FrameLayout, private val image: View) {
    val view = SurfaceView(context)
    private var videoW = 0
    private var videoH = 0

    init {
        frame.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) // 0.6.49: BELOW the still, full size from the start
    }

        fun setVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
    }

    /**
     * Fits the video inside the frame (keeping its aspect). 0.6.49: the view keeps its full size ALWAYS (no 1x1 trick, so there is no
     * resize and no blank frame at the switch); the still sits ABOVE it and [StillTiles] fades the still out once a frame is drawn.
     * [on] does not change the size. Main thread.
     */
    fun show(on: Boolean) {
        val fw = frame.width
        val fh = frame.height
        val lp: FrameLayout.LayoutParams = when {
            fw <= 0 || fh <= 0 || videoW <= 0 || videoH <= 0 -> FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            else -> {
                val scale = minOf(fw.toFloat() / videoW, fh.toFloat() / videoH)
                FrameLayout.LayoutParams((videoW * scale).toInt().coerceAtLeast(1), (videoH * scale).toInt().coerceAtLeast(1), Gravity.CENTER)
            }
        }
        val old = view.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != lp.width || old.height != lp.height || old.gravity != lp.gravity) view.layoutParams = lp
    }

    fun release() {
        frame.removeView(view)
        image.animate().cancel()
        image.alpha = 1f
    }
}
