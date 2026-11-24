package com.meda.portico.live

import android.content.Context
import com.meda.portico.core.live.LiveFaults
import com.meda.portico.core.live.LiveGuards
import com.meda.portico.core.live.LiveLog
import com.meda.portico.core.live.RefHolder
import com.meda.portico.signaling.SignalingClient

/**
 * The ONE [LiveHome] of the Home screen, held by the app graph and shared by Home and the ring pre-warm ([RingPrewarmGlue]). When a door
 * rings with the screen off, the pre-warm acquires it and starts the streams; Home (created or started by the ring's full-screen intent)
 * acquires the SAME instance, so its first plan finds the sessions running (same keys) and adopts them. A holder count, not an owner:
 * the pre-warm letting go at the hand-off never tears down what Home holds. After the last holder lets go the instance is destroyed
 * [GRACE_MS] later (every stream is already closed by then: Home's onStop and the pre-warm's stop close them).
 *
 * Main thread. The `onUi` callbacks (main thread, from [LiveHome]) are fanned out to the holders that gave one.
 */
class HomeLiveHost(
    private val app: Context,
    private val signaling: () -> SignalingClient,
    private val log: LiveLog,
    private val guards: LiveGuards,
) {
    private val sinks = LinkedHashMap<Any, () -> Unit>()
    private val faults = LiveFaults(log)
    private val holder = RefHolder<LiveHome>(
        make = { LiveHome(app, signaling, log, guards) { for (s in sinks.values.toList()) faults.run("ui") { s() } } },
        destroy = { it.destroy() },
        clock = MainLiveClock(),
        graceMs = GRACE_MS,
        onFault = faults::report,
    )

    fun acquire(who: Any, onUi: (() -> Unit)? = null): LiveHome {
        if (onUi != null) sinks[who] = onUi
        return holder.acquire(who)
    }

    fun release(who: Any) {
        sinks.remove(who)
        holder.release(who)
    }

    companion object {
        const val GRACE_MS = 3_000L
    }
}
