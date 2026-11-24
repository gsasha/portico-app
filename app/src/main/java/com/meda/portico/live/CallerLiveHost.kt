package com.meda.portico.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.meda.portico.core.live.LiveArm
import com.meda.portico.core.live.LiveClock
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.LiveGuards
import com.meda.portico.core.live.LiveLease
import com.meda.portico.core.live.LiveLog
import com.meda.portico.signaling.SignalingClient

/** [LiveClock] on the main thread (the lease's teardown timer and the arming delay). */
class MainLiveClock(private val main: Handler = Handler(Looper.getMainLooper())) : LiveClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
        val r = Runnable(task)
        main.postDelayed(r, delayMs)
        return { main.removeCallbacks(r) }
    }
}

/**
 * The ONE [LiveHome] the ring screen and the in-call screen share (DEV-713 S2), held by the app graph so it outlives either activity.
 *
 * Why shared: when the person answers, `RingActivity` finishes and `InCallActivity` starts, and the Android order is the new screen's
 * `onResume` before the old one's `onStop`. Two separate `LiveHome`s would have two sessions of one camera and size for a moment (and
 * two sets of decoders). One `LiveHome` behind a [LiveLease] has one session: the in-call screen's tile binds its own renderer to the stream
 * the ring screen started, and the picture goes on.
 *
 * - [claim] on a screen's `onResume`; [release] on its `onPause`. The streams are closed [GRACE_MS] after the last release (screen off,
 *   Home pressed, the call over), unless another of the two screens claimed in between.
 * - The call's AUDIO is not here and never touches it: it is `AnswerCoordinator`'s own audio-only peer connection. This class
 *   shares nothing with it.
 * - Home has its own `LiveHome` (it stops in `onStop`, before a ring or call screen is armed): see `CallerLiveController.ARM_DELAY_MS`.
 */
class CallerLiveHost(
    private val app: Context,
    private val signaling: () -> SignalingClient,
    private val log: LiveLog,
    private val guards: LiveGuards,
) {
    private var ui: (() -> Unit)? = null
    private val faults = LiveFaults(log)
    private val lease = LiveLease<LiveHome>(
        make = { LiveHome(app, signaling, log, guards) { ui?.invoke() } },
        destroy = { it.destroy() },
        clock = MainLiveClock(),
        graceMs = GRACE_MS,
        onFault = faults::report,
    )

    /** Main thread. [onUi] is called (main thread) when a tile's live state changed. */
    fun claim(who: Any, onUi: () -> Unit): LiveHome {
        ui = onUi
        return lease.claim(who)
    }

    fun release(who: Any) {
        if (lease.isOwner(who)) ui = null
        lease.release(who)
    }

    fun isOwner(who: Any): Boolean = lease.isOwner(who)

    companion object {
        /** Long enough for the next screen to resume and claim (the new activity resumes within a second even on a slow phone), short enough that a screen-off closes the streams at once. */
        const val GRACE_MS = 3_000L
    }
}

/** The arming delay of a ring or in-call screen: nothing live starts before this long after the screen is resumed. */
object CallerLiveTiming {
    /** Ringing, Answer, Decline and the lock-screen layout all come first; the video starts after (DEV-713 S2 hard rule). */
    const val ARM_DELAY_MS = 800L

    fun newArm(): LiveArm = LiveArm(ARM_DELAY_MS)
}
