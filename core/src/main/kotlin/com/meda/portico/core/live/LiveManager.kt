package com.meda.portico.core.live

/**
 * The live sessions of one Home screen, held OUTSIDE the view tree and keyed by camera and size (DEV-713 S1: a re-layout of the grid
 * rebuilds the views, never the sessions). [reconcile] makes the running set equal to what [LivePlan] wants: a wanted key without a
 * session gets one and starts; a session whose key is no longer wanted is stopped (it always sends its `live_close`) and forgotten.
 * A healthy session is never touched by a reconcile that still wants it; a FAILED one stays (the tile is on its still with the
 * notice) until [restart] (a tap on the chip) or until its tile leaves the wanted set and comes back (a new session).
 *
 * FAILURE ISOLATION (S2, the ring and in-call screens): whatever one session's factory, start or stop throws is caught here, reported to
 * [onFault] (the Journal) and does not reach the caller, nor stop the other sessions from being reconciled. A session that could not be
 * made or started is not kept (the next reconcile tries again), so a broken engine costs one tile its video and nothing else.
 *
 * Single-threaded, like [LiveSession].
 */
class LiveManager(
    private val onFault: (at: String, error: Throwable) -> Unit = { _, _ -> },
    private val create: (LiveKey) -> LiveRun,
) {
    private val sessions = LinkedHashMap<LiveKey, LiveRun>()

    fun reconcile(plan: LivePlanResult) {
        for (k in sessions.keys.toList()) {
            if (k !in plan.wanted) {
                val s = sessions.remove(k)
                fenced("stop") { s?.stop(plan.reasonFor(k)) }
            }
        }
        for (k in plan.wanted) {
            if (k in sessions) continue
            val s = try {
                create(k)
            } catch (e: Throwable) {
                onFault("create", e)
                continue
            }
            try {
                s.start()
            } catch (e: Throwable) {
                onFault("start", e)
                fenced("stop") { s.stop(StopWhy.CLOSED) }
                continue
            }
            sessions[k] = s
        }
    }

    fun session(key: LiveKey): LiveRun? = sessions[key]

    /** A tap on the chip of a tile that should be live but gave up: ask again. False when there is nothing to restart. */
    fun restart(key: LiveKey): Boolean {
        val s = sessions[key] ?: return false
        if (s.state != LiveSession.State.FAILED) return false
        return try {
            s.start()
            true
        } catch (e: Throwable) {
            onFault("restart", e)
            false
        }
    }

    fun keys(): Set<LiveKey> = sessions.keys.toSet()

    fun count(): Int = sessions.size

    /** Closes everything (Home stopped, the switch went off). */
    fun stopAll(why: StopWhy) {
        val all = sessions.values.toList()
        sessions.clear()
        for (s in all) fenced("stop") { s.stop(why) }
    }

    private inline fun fenced(at: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            onFault(at, e)
        }
    }
}
