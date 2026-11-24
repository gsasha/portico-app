package com.meda.portico.live

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.meda.portico.AppGraph
import com.meda.portico.core.live.BackgroundGrace
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.OffscreenGrace

/**
 * 0.6.73: Home's live streams outlive Home going to the background for [OffscreenGrace.GRACE_MS] (30 s), so closing the app and
 * reopening it quickly finds the sessions running. The rule is `:core`'s [BackgroundGrace]; this class owns the timer and the lease.
 *
 * It lives in the graph, NOT in the Activity: Back finishes Home (MainActivity is the task root and has finished), so the Activity is
 * destroyed while the streams are still within their grace, and the next Home instance must find them. While the countdown runs this
 * class holds a lease on the graph's [HomeLiveHost] (the same `LiveHome` Home acquires), so the activity's release in `onDestroy`
 * does not tear the instance down. What it never touches: the ring and in-call screens (their own `CallerLiveHost` instance).
 *
 * Main thread. Every entry point runs under [LiveFaults.run].
 */
class HomeLiveGrace(private val graph: AppGraph) {
    private val main = Handler(Looper.getMainLooper())
    private val grace = BackgroundGrace()
    private val faults = LiveFaults(graph.pushJournal.liveLog)
    private var home: LiveHome? = null
    private val expiry = Runnable { faults.run("grace-expire") { expireNow() } }

    private fun now() = SystemClock.elapsedRealtime()

    private fun journal(step: BackgroundGrace.Step, awayMs: Long = 0L) = graph.pushJournal.cameraView(BackgroundGrace.line(step, awayMs))

    /**
     * Home's onStop (not a configuration change): leave the sessions running for the grace. Call AFTER the renderers are released.
     * With no stream wanted ([hasStreams] false) there is nothing to hold: the session code stops at once, as before 0.6.73.
     */
    fun leave(live: LiveHome, hasStreams: Boolean) {
        faults.run("grace-leave") {
            if (!hasStreams) {
                live.stop()
                return@run
            }
            if (!grace.leave(now())) return@run
            home = graph.homeLive.acquire(this)
            main.removeCallbacks(expiry)
            main.postDelayed(expiry, OffscreenGrace.GRACE_MS)
            journal(BackgroundGrace.Step.START)
        }
    }

    /** Home's onStart: within the grace, cancel the stop (the sessions go on); after it, make sure they are stopped (Doze can delay the timer). */
    fun back() {
        faults.run("grace-back") {
            val at = now()
            val away = grace.awayMs(at)
            when (grace.back(at)) {
                BackgroundGrace.Back.NONE -> Unit
                BackgroundGrace.Back.CANCELLED -> {
                    main.removeCallbacks(expiry)
                    journal(BackgroundGrace.Step.CANCEL, away)
                    graph.homeLive.release(this) // the new Home instance holds it (it acquired in onCreate)
                    home = null
                }
                BackgroundGrace.Back.EXPIRED -> {
                    main.removeCallbacks(expiry)
                    stopAndRelease()
                }
            }
        }
    }

    /** Another live screen needs the cameras (the ring or in-call screen came up): end the grace at once, so two sets of sessions never overlap. */
    fun endNow() {
        faults.run("grace-end") { if (grace.pending()) expireNow() }
    }

    private fun expireNow() {
        main.removeCallbacks(expiry)
        if (grace.expire()) stopAndRelease()
    }

    private fun stopAndRelease() {
        home?.stop()
        home = null
        journal(BackgroundGrace.Step.EXPIRE)
        graph.homeLive.release(this)
    }
}
