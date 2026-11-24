package com.meda.portico.live

import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.meda.portico.AppGraph
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.live.CallerLive
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveTileView
import com.meda.portico.core.ring.LinkState
import com.meda.portico.home.LiveSurfaces
import com.meda.portico.home.StillTiles
import com.meda.portico.home.currentLinkState
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/**
 * The live video of the caller's cameras on ONE call screen (DEV-713 S2): the ring screen or the in-call screen. A screen owns one of these
 * and calls [resumed] / [paused] from `onResume` / `onPause`; everything else is on its own timer. It is the only live code those screens
 * contain, and it is built so that none of it can hurt the call:
 *
 * - **Never before the screen is up.** Nothing is claimed, planned or opened until [CallerLiveTiming.ARM_DELAY_MS] after [resumed]; a
 *   tile must have its still drawn and be on screen ([CallerLive.tiles]). The screen's own ticks (stills, Answer, Decline, hang-up) do not
 *   wait for, call or share a thread with anything here: the streams run on `LiveHome`'s own thread.
 * - **Never throws into the screen.** Every entry point runs under [LiveFaults.run]: a throw is journalled once (`live fault ...`) and the
 *   tile simply stays on its still. `LiveManager` additionally fences each session.
 * - **One session per camera and size across the ring -> in-call change.** Both screens use the same `LiveHome` through `CallerLiveHost`'s lease
 *   (see there); this controller only claims on resume and lets go on pause.
 * - **Independent of the call.** It never reads or writes the call coordinator, its audio, the microphone or the audio mode. The screen's
 *   [allowed] rule (ringing / call live) closes the streams when the call is over, whatever the screen is still showing.
 *
 * It is also the tiles' [LiveSurfaces] (the hand-off between a tile's renderer and the stream) and forwards to the current `LiveHome`.
 */
class CallerLiveController(
    private val graph: AppGraph,
    private val tiles: StillTiles,
    /** The caller view the screen has drawn (null: none). */
    private val view: () -> CallerView?,
    /** The screen's own rule: this call is still on (ringing / live). False closes every stream. */
    private val allowed: () -> Boolean,
    /** Asks the screen to draw its caller tiles again (main thread). */
    private val redraw: () -> Unit,
) : LiveSurfaces {
    private val main = Handler(Looper.getMainLooper())
    private val clock = MainLiveClock(main)
    private val arm = CallerLiveTiming.newArm()
    private val faults = LiveFaults(graph.pushJournal.liveLog)
    private var home: LiveHome? = null
    private var active = false

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            sync()
            main.postDelayed(this, TICK_MS)
        }
    }

    init {
        tiles.live = this
        tiles.liveViews = { id -> home?.views?.get(id) }
        tiles.listener = object : StillTiles.Listener {
            override fun onWidthChanged(cameraId: String) {}
            override fun onLiveChip(tile: CameraTile) = chip(tile)
        }
    }

    /** `onResume`: take the shared live code and start the arming delay. Nothing is opened yet. */
    fun resumed() {
        faults.run("homegrace") { graph.homeLiveGrace.endNow() } // 0.6.73: Home's background grace never overlaps a call's own sessions
        faults.run("resume") {
            active = true
            arm.arm(clock.nowMs())
            if (graph.liveVideoEnabled()) home = graph.callerLive.claim(this) { onUi() }
            main.removeCallbacks(tick)
            main.postDelayed(tick, CallerLiveTiming.ARM_DELAY_MS)
        }
    }

    /** `onPause` (the call ended, the screen went off, another screen came): the renderers go first, then the lease; the streams close within its grace. */
    fun paused() {
        faults.run("pause") {
            active = false
            main.removeCallbacks(tick)
            arm.disarm()
            tiles.releaseRenderers()
        }
        faults.run("release") {
            graph.callerLive.release(this)
            home = null
        }
    }

    /** True while this tile's video is playing: the screen then asks for no stills for it (as Home does). */
    fun playing(cameraId: String): Boolean = home?.views?.get(cameraId)?.ui?.video == true

    private fun onUi() {
        if (!active) return
        faults.run("ui") { redraw() }
    }

    private fun sync() {
        faults.run("sync") {
            if (arm.ready(clock.nowMs())) plan()
            redraw() // paints the chip, the notice and the renderers from the state the live thread published
        }
    }

    private fun plan() {
        val h = home ?: (if (graph.liveVideoEnabled() || graph.roamingBlocked()) graph.callerLive.claim(this) { onUi() }.also { home = it } else null) ?: return
        val v = view()
        val global = LiveGlobal(
            switchOn = graph.liveVideoEnabled(),
            shown = allowed() && v != null,
            paused = graph.isPaused(),
            connected = graph.currentLinkState() == LinkState.CONNECTED,
            path = graph.linkPath,
            rtspReady = h.rtspReady,
            roamingBlocked = graph.roamingBlocked(),
        )
        val ins = CallerLive.tiles(v?.cameras.orEmpty(), { graph.liveChoices.effective(it.id, it.live) }, tiles.visibleIds(), graph.videoVerdict().smallOnly)
        h.update(LiveInput(global, ins, graph.content.profile.showFps, emptySet()))
    }

    /** A tap on a tile's LIVE chip: live <-> still, remembered only when it differs from the default (the first camera is live); a tile that gave up asks again. */
    private fun chip(tile: CameraTile) {
        faults.run("chip") {
            val h = home
            if (h?.views?.get(tile.id)?.ui?.notice == com.meda.portico.core.live.LiveWhy.ROAMING) {
                graph.allowRoamingTrip() // A-10: lifts the roaming block for this trip
                plan()
                redraw()
                return@run
            }
            val wants = graph.liveChoices.effective(tile.id, tile.live)
            val shown = h?.views?.get(tile.id)
            val key = shown?.key
            if (h != null && wants && shown?.canRetry == true && key != null) h.restart(key) else graph.liveChoices.toggle(tile.id, tile.live)
            plan()
            redraw()
        }
    }

    // ---- the tiles' renderers: forwarded to the shared LiveHome, or nothing (the tile stays on its still) ----

    override fun eglContext(): EglBase.Context? = home?.eglContext()

    override fun bind(key: LiveKey, renderer: SurfaceViewRenderer) {
        home?.bind(key, renderer)
    }

    override fun unbind(key: LiveKey, renderer: SurfaceViewRenderer) {
        home?.unbind(key, renderer)
    }

    override fun bindSurface(key: LiveKey, view: SurfaceView, events: RtspTileEvents) {
        home?.bindSurface(key, view, events)
    }

    override fun unbindSurface(key: LiveKey, view: SurfaceView) {
        home?.unbindSurface(key, view)
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
