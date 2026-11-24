package com.meda.portico.core.stay

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.OneShotTimer
import com.meda.portico.core.stay.StayConnectedPolicy.Connection
import com.meda.portico.core.stay.StayConnectedPolicy.Service

/** What [StayConnected] acts through. Every method may be called from any thread and must not throw out of the call. */
interface StayPorts {
    /** Ask Android to start the service. False: refused or not allowed now (this is "not now", not "never"). */
    fun startService(): Boolean
    fun stopService()

    /** Open the socket if it is not open ([com.meda.portico.signaling.SignalingClient.start]; idempotent; refused while paused). */
    fun connect()

    /** Close the socket and stop retrying, and let the tailnet node sleep. A push or the app opening brings it back. */
    fun release()

    /** Redraw the service's notification. */
    fun updateNote(text: StayConnectedPolicy.Text)
}

/**
 * Applies [StayConnectedPolicy] to the app. The inputs are read fresh at every call (they live in
 * other objects), so this holds no copy that can go stale.
 *
 * Evaluate with `allowStart = true` ONLY from a context Android lets start a foreground service
 * (app opening, boot or update, a push, a tap on our notification). A network callback passes
 * false: it may stop or retune a running service, never start one.
 */
class StayConnected(
    private val ports: StayPorts,
    private val inputs: () -> StayConnectedPolicy.Inputs,
    private val timer: OneShotTimer,
    private val clockMs: () -> Long,
    private val log: HaLog = HaLog.NONE,
    private val journal: StayJournalSink = StayJournalSink.NONE,
    /** How many door rings are up now (the ring state's own count), for the Journal only. */
    private val ringCount: () -> Int = { 0 },
) {
    private val lock = Any()
    private var graceUntil = 0L
    private var cancelTimer: () -> Unit = {}
    private var lastConnection: Connection? = null
    private var ringSince = -1L

    /** True while a push's grace period runs. */
    fun inGrace(): Boolean = synchronized(lock) { clockMs() < graceUntil }

    /** A door ring is up and younger than [RING_CAP_MS]: a lost "ended" can not pin the socket open. */
    fun ringLive(): Boolean = synchronized(lock) { ringSince >= 0 && clockMs() - ringSince < RING_CAP_MS }

    /** Seconds since the ring that holds the socket went up; null when none. Journal only. */
    private fun ringAgeS(): Int? = synchronized(lock) { if (ringSince < 0) null else ((clockMs() - ringSince) / 1000).coerceIn(0, 999).toInt() }

    private fun note(event: String, vararg fields: Pair<String, Any?>) {
        runCatching { journal.event(event, fields.toList()) }
    }

    /** The inputs as the policy sees them now (for the Journal snapshot); null when unreadable. */
    fun currentInputs(): StayConnectedPolicy.Inputs? = try { inputs().copy(inGrace = inGrace(), ringLive = ringLive()) } catch (_: Exception) { null }

    /**
     * A ring went up or came down (every door counted together; the caller reports only the edges). Up: the socket is busy for at most
     * [RING_CAP_MS]. Down: the ring counts as a wake, so the socket is released after the normal grace, not at once.
     */
    fun onRingChanged(up: Boolean) {
        synchronized(lock) {
            if (up) {
                if (ringSince < 0) ringSince = clockMs()
            } else {
                if (ringSince < 0) return
                ringSince = -1L
                graceUntil = clockMs() + StayConnectedPolicy.GRACE_MS
            }
        }
        note("stay-ring", "act" to (if (up) "up" else "down"), "rings" to ringCount().coerceIn(0, 9), "rage" to ringAgeS())
        armLookAgain()
        evaluate(false, if (up) "ring started" else "ring ended")
    }

    fun evaluate(allowStart: Boolean, why: String) {
        val i = try {
            inputs().copy(inGrace = inGrace(), ringLive = ringLive())
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "stay: inputs unreadable (${e.javaClass.simpleName}); leaving things as they are")
            return
        }
        val d = StayConnectedPolicy.decide(i)
        if (d.connection == Connection.DEFER || d.connection == Connection.RELEASE) {
            // every deferring or releasing evaluation, changed or not, with what held or freed the socket, so a later release traces to its cause
            note(
                "stay-eval", "why" to StayWhy.word(why), "dec" to d.connection.name.lowercase(), "ring" to i.ringLive, "rings" to ringCount().coerceIn(0, 9),
                "rage" to ringAgeS(), "grace" to i.inGrace, "call" to i.callLive, "ui" to i.uiVisible,
            )
        }
        if (lastConnection != d.connection) {
            log.log(HaLog.Level.INFO, "stay: ${d.connection} / ${d.service} ($why; net ${i.net})")
            lastConnection = d.connection
            runCatching { journal.event(StayWhy.event(d.connection), listOf("why" to StayWhy.word(why))) }
        }
        when (d.service) {
            Service.START -> if (allowStart) runCatching { ports.startService() }
            Service.STOP -> runCatching { ports.stopService() }
            Service.KEEP, Service.NONE -> Unit
        }
        when (d.connection) {
            Connection.HOLD, Connection.IDLE -> runCatching { ports.connect() }
            Connection.RELEASE -> runCatching { ports.release() }
            Connection.DEFER -> armLookAgain()
        }
        if (d.service != Service.STOP && d.service != Service.NONE) runCatching { ports.updateNote(d.text) }
    }

    /** A push woke the socket: on mobile data it is left alone for [StayConnectedPolicy.GRACE_MS], then released again. */
    fun onWoken() {
        synchronized(lock) { graceUntil = clockMs() + StayConnectedPolicy.GRACE_MS }
        armLookAgain()
    }

    /** The notification text only (the link came up or went down). */
    fun refreshNote() {
        val i = currentInputs() ?: return
        val d = StayConnectedPolicy.decide(i)
        if (d.service == Service.KEEP) runCatching { ports.updateNote(d.text) }
    }

    private fun armLookAgain() {
        synchronized(lock) {
            cancelTimer()
            val now = clockMs()
            val left = graceUntil - now
            val ringLeft = if (ringSince >= 0) ringSince + RING_CAP_MS - now else 0L
            cancelTimer = when {
                // the later of the two: while the ring is inside its cap the socket stays; the cap's own end evaluates again
                ringLeft > 0 && ringLeft >= left -> {
                    note("stay-timer", "act" to "armed", "kind" to "cap", "s" to (ringLeft / 1000).toInt())
                    timer.schedule(ringLeft + 1) { note("stay-timer", "act" to "fired", "kind" to "cap"); evaluate(false, "ring cap") }
                }
                left > 0 -> {
                    note("stay-timer", "act" to "armed", "kind" to "grace", "s" to (left / 1000).toInt())
                    timer.schedule(left + 1) { note("stay-timer", "act" to "fired", "kind" to "grace"); evaluate(false, "grace ended") }
                }
                else -> ({})
            }
        }
    }

    companion object {
        /**
         * The longest a ring may keep the socket busy. A ring window is 45 s ([com.meda.portico.core.push.Contract.DEFAULT_RING_WINDOW_S]) plus
         * skew; 60 s is the same cap the link watchdog uses for a ring ([com.meda.portico.core.ha.LinkWatchdog.RING_CAP_MS]), so a lost
         * "ended" costs at most that much extra radio time, once.
         */
        const val RING_CAP_MS = 60_000L
    }
}
