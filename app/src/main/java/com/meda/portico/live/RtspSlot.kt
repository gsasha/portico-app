package com.meda.portico.live

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** What a tile's SurfaceView wants to hear about its stream. Called on any thread; the tile posts to its own. */
interface RtspTileEvents {
    /** A frame has been drawn on the surface (the first one after the decoder started on it). */
    fun onRendered()

    /** The picture size the decoder reported, so the tile can fit the view to it. */
    fun onVideoSize(width: Int, height: Int)
}

/**
 * Where one RTSP stream's decoder draws: the [Surface] of the plain `SurfaceView` of the tile that shows the stream now (DEV-713 S1b).
 * The stream outlives its view (a re-layout of the grid rebuilds the tiles), so the slot is held by [LiveHome], keyed like the
 * stream, and the decoder asks it for the surface it should draw on.
 *
 * A surface is only good between `surfaceCreated` and `surfaceDestroyed`; after `surfaceDestroyed` returns, nothing may draw on it.
 * So the decoder draws under [lock] and only when [surface] is still the one it was configured with, and `surfaceDestroyed` clears
 * [surface] under the same lock.
 */
class RtspSlot {
    val lock = Any()
    private var surface: Surface? = null

    @Volatile
    var events: RtspTileEvents? = null

    @Volatile
    var videoWidth = 0

    @Volatile
    var videoHeight = 0

    fun surface(): Surface? = synchronized(lock) { surface }

    fun setSurface(s: Surface?) {
        synchronized(lock) { surface = s }
    }

    fun videoSize(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        events?.onVideoSize(w, h)
    }

    fun rendered() {
        events?.onRendered()
    }

    private var attachedTo: SurfaceView? = null
    private var callback: SurfaceHolder.Callback? = null

    /** Follows [view]'s surface. The view may already have one. Main thread. */
    fun attach(view: SurfaceView, e: RtspTileEvents) {
        detach()
        events = e
        val cb = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = setSurface(holder.surface.takeIf { it.isValid })
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = setSurface(holder.surface.takeIf { it.isValid })
            override fun surfaceDestroyed(holder: SurfaceHolder) = setSurface(null)
        }
        callback = cb
        attachedTo = view
        view.holder.addCallback(cb)
        if (view.holder.surface.isValid) setSurface(view.holder.surface)
        if (videoWidth > 0 && videoHeight > 0) e.onVideoSize(videoWidth, videoHeight) // a rebuilt tile fits at once
    }

    /** Lets go of the view: nothing draws on its surface after this returns. Main thread. */
    fun detach() {
        setSurface(null)
        val v = attachedTo
        val cb = callback
        if (v != null && cb != null) v.holder.removeCallback(cb)
        attachedTo = null
        callback = null
        events = null
    }

    fun isAttachedTo(view: SurfaceView): Boolean = attachedTo === view

    fun isBound(): Boolean = attachedTo != null
}
