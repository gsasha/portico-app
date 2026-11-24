package com.meda.portico.live

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.meda.portico.AppGraph
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.content.StationTab
import com.meda.portico.core.live.ChipLook
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveTileIn
import com.meda.portico.core.live.PrewarmCam
import com.meda.portico.core.live.PrewarmDecision
import com.meda.portico.core.live.PrewarmIn
import com.meda.portico.core.live.PrewarmRun
import com.meda.portico.core.live.PrewarmStop
import com.meda.portico.core.live.PrewarmTracker
import com.meda.portico.core.live.RingPrewarm
import com.meda.portico.core.ring.LinkState
import com.meda.portico.home.currentLinkState

/**
 * Ring pre-warm (the owner, D-20261111-be58): when a door rings and the phone is on Wi-Fi, the live streams of the page the ring opens start
 * AT ONCE, before the screen is up or Answer is pressed, so the key frame (the doorbell's GOP is 5 s) is already in by the time the person
 * looks. All the rules are `:core`'s [RingPrewarm]; this class only reads the phone, applies the decision, and hands over.
 *
 * - Triggered by the ring state changing ([com.meda.portico.ring.AndroidOutcomeHandler.addChangeListener]): the decided ring has been
 *   recorded, so this runs after the ring/Silent decision, never instead of it, and never on the ringing path (it posts to the main thread
 *   and everything here is fenced: a throw is journalled once as a live fault, the ring is untouched).
 * - The streams live in the graph's shared [HomeLiveHost] `LiveHome`. Home acquires the SAME instance and its first plan finds the sessions
 *   running under the same [LiveKey]s ([RingPrewarm.keyFor] is the viewport's AUTO rule), so nothing restarts. [onHomeStarted] is the hand-off:
 *   from then on Home's plan is the only planner, and [protectedCameras] keeps these cameras on screen for [RingPrewarm.PROTECT_MS] while the
 *   tiles are still being laid out.
 * - Closes when the ring ends, or [RingPrewarm.MAX_HOLD_MS] after it began unanswered. One set per ring, no restart: this class asks the live
 *   code to plan about twice a second, and `LiveManager` never recreates a session that is healthy or has failed.
 *
 * UNVERIFIED ON A PHONE.
 */
class RingPrewarmGlue(private val graph: AppGraph) {
    private val main = Handler(Looper.getMainLooper())
    private val faults = LiveFaults(graph.pushJournal.liveLog)
    private val tracker = PrewarmTracker()
    private var run: PrewarmRun? = null
    private var home: LiveHome? = null
    private var attached = false
    private var handoffAt = -1L
    private var handedCameras: Set<String> = emptySet()

    /** Home is between onStart and onStop (set by HomeActivity). */
    @Volatile
    var homeStarted: Boolean = false

    private val tick = object : Runnable {
        override fun run() {
            faults.run("prewarm-tick") { onTick() }
        }
    }

    /** Once, at process start: listen to the ring state. */
    fun attach() {
        if (attached) return
        attached = true
        faults.run("prewarm-attach") { graph.ring.addChangeListener { main.post { faults.run("prewarm-ring") { onRingChanged() } } } }
    }

    /** Home calls this from onStart (before it plans): the screen is up, so it takes the sessions over. */
    fun onHomeStarted() {
        faults.run("prewarm-handoff") {
            homeStarted = true
            val r = run ?: return@run
            if (r.handedOff) return@run
            r.handoff()
            val now = SystemClock.elapsedRealtime()
            val h = home
            for (k in r.keys) {
                val playing = h?.views?.get(k.cameraId)?.ui?.chip == ChipLook.ON
                graph.pushJournal.cameraView(RingPrewarm.handoffLine(k.cameraId, r.age(now), playing))
            }
            handoffAt = now
            handedCameras = r.keys.map { it.cameraId }.toSet()
            main.removeCallbacks(tick)
            graph.homeLive.release(this) // Home holds the instance; the sessions go on
            home = null
        }
    }

    /** Home's onStop: the screen left. */
    fun onHomeStopped() {
        homeStarted = false
        handoffAt = -1L
        handedCameras = emptySet()
    }

    /** Cameras Home's first plans must keep (their tiles may not be laid out yet): the pre-warmed ones, for a short while after the hand-off. */
    fun protectedCameras(): Set<String> {
        if (handoffAt < 0 || SystemClock.elapsedRealtime() - handoffAt > RingPrewarm.PROTECT_MS) return emptySet()
        return handedCameras
    }

    private fun onRingChanged() {
        val rings = graph.ring.activeRings()
        val r = run
        if (r != null && !r.handedOff && rings[r.doorRef] != r.callId) stop(PrewarmStop.ENDED)
        for ((door, call) in rings) {
            if (tracker.has(call)) continue
            tracker.add(call)
            begin(door, call)
        }
    }

    private fun begin(doorRef: String, callId: String) {
        val now = SystemClock.elapsedRealtime()
        val c = graph.content
        val link = graph.currentLinkState()
        val call = StationCall(doorRef, graph.doors.doorIdFor(doorRef), callId, StationPhase.RINGING, graph.doors.nameFor(doorRef), canAnswer = true)
        val pageId = StationTab.pageFor(c.profile, graph.doors.all, call)
        val view = if (pageId == null) null else HomeModel.build(c.profile, link, c.support, c.answered, c.runningIds(), c.pictureIds(), null, pageId, graph.sizes, null, graph.doors.all, call)
        val cams = view?.cameras.orEmpty().map {
            PrewarmCam(
                cameraId = it.id,
                size = it.size,
                hasSmall = it.hasSmall,
                available = it.picture != PictureState.CAMERA_UNAVAILABLE && it.picture != PictureState.OFFLINE,
                wantsLive = graph.liveChoices.effective(it.id, it.live),
                smallOnly = graph.videoVerdict().smallOnly,
            )
        }
        val d = RingPrewarm.decide(
            PrewarmIn(
                liveSwitchOn = graph.liveVideoEnabled(),
                paused = graph.isPaused(),
                roamingBlocked = graph.roamingBlocked(),
                net = graph.roaming.defaultNet(),
                path = graph.linkPath,
                ringing = true,
                alreadyForThisCall = run?.callId == callId,
                cameras = cams,
                screenUp = homeStarted,
            ),
        )
        when (d) {
            is PrewarmDecision.Skip -> {
                graph.pushJournal.cameraView(RingPrewarm.skipLine(doorRef, d.why))
            }
            is PrewarmDecision.Start -> {
                run?.let { if (!it.handedOff) stop(PrewarmStop.CANCELLED) }
                val keys = d.cameras.map(RingPrewarm::keyFor)
                run = PrewarmRun(doorRef, callId, now, keys)
                tickCams = d.cameras
                home = graph.homeLive.acquire(this)
                graph.pushJournal.cameraView(RingPrewarm.startLine(doorRef, keys.size))
                onTick()
            }
        }
    }

    private var tickCams: List<PrewarmCam> = emptyList()

    private fun onTick() {
        val r = run ?: return
        val h = home ?: return
        if (r.handedOff) return
        val now = SystemClock.elapsedRealtime()
        val ringing = graph.ring.activeRings()[r.doorRef] == r.callId
        RingPrewarm.stopWhy(r.startedAtMs, now, ringing)?.let { stop(it); return }
        val global = LiveGlobal(
            switchOn = graph.liveVideoEnabled(),
            shown = true, // the streams are wanted although no screen is up: that is the point
            paused = graph.isPaused(),
            connected = graph.currentLinkState() == LinkState.CONNECTED,
            path = graph.linkPath,
            rtspReady = h.rtspReady,
            roamingBlocked = graph.roamingBlocked(),
        )
        val tiles = tickCams.map { c -> LiveTileIn(cameraId = c.cameraId, size = RingPrewarm.keyFor(c).size, available = true, wantsLive = true, onScreen = true, rtsp = null) }
        h.update(LiveInput(global, tiles, showFps = false, zoomed = emptySet()))
        for (k in r.keys) {
            if (h.views[k.cameraId]?.ui?.chip != ChipLook.ON) continue
            r.firstFrame(k.cameraId, now)?.let { graph.pushJournal.cameraView(RingPrewarm.firstFrameLine(k.cameraId, it)) }
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    private fun stop(why: PrewarmStop) {
        val r = run ?: return
        val h = home
        run = null
        home = null
        main.removeCallbacks(tick)
        if (!r.handedOff) {
            h?.stop() // every stream of this pre-warm closes now, each with its live_close
            graph.homeLive.release(this)
        }
        graph.pushJournal.cameraView(RingPrewarm.stopLine(why.word, SystemClock.elapsedRealtime() - r.startedAtMs))
    }

    private companion object {
        const val TICK_MS = 500L
    }
}
