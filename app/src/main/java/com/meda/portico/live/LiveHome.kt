package com.meda.portico.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import com.meda.portico.core.live.LiveClock
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveGuards
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveLog
import com.meda.portico.core.live.LiveManager
import com.meda.portico.core.live.LivePeerFactory
import com.meda.portico.core.live.LivePlan
import com.meda.portico.core.live.LivePlanResult
import com.meda.portico.core.live.LiveSession
import com.meda.portico.core.live.LiveTileIn
import com.meda.portico.core.live.LiveRun
import com.meda.portico.core.live.LiveTileView
import com.meda.portico.core.live.RtspDecoderFactory
import com.meda.portico.core.live.RtspLiveSession
import com.meda.portico.core.live.RtspSourceFactory
import com.meda.portico.core.live.StopWhy
import com.meda.portico.core.live.TileLiveUi
import com.meda.portico.home.LiveSurfaces
import com.meda.portico.signaling.SignalingClient
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** What Home tells the live code on each look at the screen (the main thread builds it, the live thread plans from it). */
data class LiveInput(
    val global: LiveGlobal,
    val tiles: List<LiveTileIn>,
    /** The document's `show_fps`. */
    val showFps: Boolean,
    /** Tiles a pinch has zoomed (they show the still). */
    val zoomed: Set<String>,
)

/**
 * The live video of one Home screen (DEV-713 S1): the streams, held OUTSIDE the view tree, and the state the tiles draw. It owns one
 * "portico-live" thread; every session, the manager and every libwebrtc and websocket callback run there. The main thread only calls
 * [update] / [stop] / [restart], and reads [views] (a volatile snapshot, replaced whole).
 *
 * - [update] (about once a second, and on scroll) plans with `:core`'s [LivePlan] and reconciles the sessions: it opens the tiles
 *   that should stream and closes the others. A re-layout of the grid changes no key, so it opens and closes nothing; the new
 *   tile's renderer is bound to the running stream ([bind]).
 * - [stop] (Home stopped) closes every session (each sends its `live_close`) and then releases the shared libwebrtc engine; the
 *   next [update] builds a new one.
 * - With the Settings switch off the plan is empty, so no session, no engine and no command ever exists.
 * - DEV-713 S2: the phone's own guards ([LiveGuards]: too hot, too few decoders) are read on the live thread before each plan, so a
 *   call from the main thread never waits on a platform query, and the same instance serves Home and the ring and in-call screens
 *   (the latter through `CallerLiveHost`). Whatever a session's factory, start or stop throws is fenced in `LiveManager`, and every task
 *   here runs under [guarded]: nothing reaches a screen.
 */
class LiveHome(
    private val app: Context,
    signaling: () -> SignalingClient,
    private val log: LiveLog,
    private val guards: LiveGuards = LiveGuards.NONE,
    /** Called on the main thread when something a tile draws changed. */
    private val onUi: () -> Unit,
) : LiveSurfaces {
    private val faults = LiveFaults(log)

    private val main = Handler(Looper.getMainLooper())
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "portico-live").apply { isDaemon = true } }.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false) // a timer of a closed session never fires after [destroy]
        removeOnCancelPolicy = true
    }

    private fun post(task: () -> Unit) {
        try {
            exec.execute { guarded(task) }
        } catch (_: RejectedExecutionException) {
            // shut down
        }
    }

    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Throwable) {
            android.util.Log.w("Portico", "live: a task failed: ${e.javaClass.simpleName}")
            faults.report("task", e)
        }
    }

    private val clock = object : LiveClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()

        override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
            val f = try {
                exec.schedule({ guarded(task) }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                return {}
            }
            return { f.cancel(false) }
        }
    }

    private val wire = LinkLiveWire(signaling, ::post)

    @Volatile
    private var engine: LiveVideoEngine? = null

    @Synchronized
    private fun engine(): LiveVideoEngine = engine ?: LiveVideoEngine(app).also { engine = it }

    /** Where each stream's frames go: the renderer of the tile that shows it now. */
    private val slots = ConcurrentHashMap<LiveKey, LiveSlot>()

    private fun slotFor(key: LiveKey): LiveSlot = slots.computeIfAbsent(key) { LiveSlot() }

    /** The Go RTSP client (away from home), or null in an APK built without `gocore.aar`. */
    private val rtspBackend: RtspSourceFactory? = RtspBackends.create(::post)

    /** This build can play the away route (the plan says "not available yet" when it cannot). */
    val rtspReady: Boolean get() = rtspBackend != null

    /** Where each RTSP stream's decoder draws: the plain SurfaceView of the tile that shows it now. */
    private val rtspSlots = ConcurrentHashMap<LiveKey, RtspSlot>()

    private fun rtspSlotFor(key: LiveKey): RtspSlot = rtspSlots.computeIfAbsent(key) { RtspSlot() }

    private val rtspDecoders = RtspDecoderFactory { key, listener -> MediaCodecRtspDecoder.create(rtspSlotFor(key), ::post, listener) }

    private val manager = LiveManager(onFault = faults::report) { key -> create(key) }

    /** The session for [key]: WebRTC at home, RTSP away (the plan's address for it is read here, on the live thread, as the plan was just made). */
    private fun create(key: LiveKey): LiveRun {
        if (key.rtsp) {
            val backend = rtspBackend ?: RtspSourceFactory { _, _ -> throw IllegalStateException("no RTSP client in this build") }
            return RtspLiveSession(key, plan?.urls?.get(key).orEmpty(), backend, rtspDecoders, clock, log, onChange = ::publish)
        }
        val peers = LivePeerFactory { listener -> WebRtcLivePeer(engine(), slotFor(key), ::post, clock::after, listener) }
        return LiveSession(key, peers, wire, clock, log, onChange = ::publish)
    }

    // ---- state of the live thread ----------------------------------------------------------

    private var latest: LiveInput? = null
    private var plan: LivePlanResult? = null

    /** What each tile draws, by camera id. Replaced whole; read on the main thread. */
    @Volatile
    var views: Map<String, LiveTileView> = emptyMap()
        private set

    // ---- the main thread's calls -----------------------------------------------------------

    fun update(input: LiveInput) = post {
        latest = input
        // the phone's own guards, read here on the live thread (the decoder probe may take a moment the first time)
        val global = input.global.copy(thermalHot = faults.value("thermal", false) { guards.thermalHot() }, maxStreams = faults.value("decoder-cap", Int.MAX_VALUE) { guards.maxStreams() })
        val p = LivePlan.plan(global, input.tiles)
        plan = p
        manager.reconcile(p)
        val open = manager.keys()
        diagSessions(input, open)
        for ((k, s) in slots) if (k !in open && s.renderer == null) slots.remove(k)
        for ((k, s) in rtspSlots) if (k !in open && !s.isBound()) rtspSlots.remove(k)
        publish()
    }

    private var lastSessionsSig = ""

    /** DEV-764: one `ui lv-sessions` line whenever the number of live streams, the link path, or the screen's state changes (concurrent video sessions). */
    private fun diagSessions(input: LiveInput, open: Set<LiveKey>) {
        val g = input.global
        val rtsp = open.count { it.rtsp }
        val sig = "${open.size}/$rtsp/${g.path}/${g.shown}/${g.paused}/${g.thermalHot}/${g.connected}"
        if (sig == lastSessionsSig) return
        lastSessionsSig = sig
        log.diag(
            "lv-sessions", null,
            listOf("n" to open.size, "rtsp" to rtsp, "path" to g.path, "shown" to g.shown, "paused" to g.paused, "hot" to g.thermalHot, "conn" to g.connected, "tiles" to input.tiles.count { it.wantsLive }),
        )
    }

    /** A tap on the chip of a tile that should be live but gave up: ask again. */
    fun restart(key: LiveKey) = post {
        if (manager.restart(key)) publish()
    }

    /** Home stopped: every session ends (each with its `live_close`), then the engine goes. Call after the renderers are released. */
    fun stop() {
        post {
            manager.stopAll(StopWhy.BACKGROUND)
            latest = null
            plan = null
            slots.clear()
            rtspSlots.clear()
            views = emptyMap()
            // taken here, on the live thread, after every session of this visit is closed: an update queued before this stop that built
            // an engine is released too, and the next visit builds a new one
            synchronized(this) { engine.also { engine = null } }?.release()
        }
    }

    /** The screen is gone for good: [stop], then the thread ends (the tasks already queued, the stop included, still run). */
    fun destroy() {
        stop()
        try {
            exec.shutdown()
        } catch (_: SecurityException) {
            // not allowed to: the daemon thread idles
        }
    }

    // ---- the tiles' renderers --------------------------------------------------------------

    override fun eglContext(): EglBase.Context? = try {
        engine().eglContext()
    } catch (e: Throwable) {
        faults.report("egl", e)
        null // the tile stays on its still
    }

    override fun bind(key: LiveKey, renderer: SurfaceViewRenderer) {
        slotFor(key).renderer = renderer
    }

    override fun unbind(key: LiveKey, renderer: SurfaceViewRenderer) {
        slots[key]?.let { if (it.renderer === renderer) it.renderer = null }
    }

    override fun bindSurface(key: LiveKey, view: SurfaceView, events: RtspTileEvents) {
        rtspSlotFor(key).attach(view, events)
    }

    override fun unbindSurface(key: LiveKey, view: SurfaceView) {
        rtspSlots[key]?.let { if (it.isAttachedTo(view)) it.detach() }
    }

    // ---- on the live thread ----------------------------------------------------------------

    private fun publish() {
        val input = latest ?: return
        val p = plan ?: return
        val out = HashMap<String, LiveTileView>()
        for (t in input.tiles) {
            val tp = p.tiles[t.cameraId] ?: continue
            val s = tp.key?.let { manager.session(it) }
            val ui = TileLiveUi.of(tp, s?.state, s?.why, s?.fpsLabel ?: "--", input.showFps, t.cameraId in input.zoomed)
            out[t.cameraId] = LiveTileView(ui, tp.key, canRetry = tp.wantsLive && s?.state == LiveSession.State.FAILED)
        }
        if (out != views) {
            views = out
            main.post(onUi)
        }
    }
}
