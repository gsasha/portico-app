package com.meda.portico.fcm

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.Redact
import com.meda.portico.core.push.RingGate
import com.meda.portico.core.push.Source

/**
 * What the app does with a decided [Outcome], from either delivery path (the FCM service's
 * worker thread, or the socket's link thread). **PR-F implements the real one** (ring UI and
 * notifications); PR-E ships [LoggingOutcomeHandler]. The store write has already happened, and
 * the local "ring for door calls on this phone" switch has already been applied
 * ([OutcomeDispatcher]): an implementation never re-derives either.
 *
 * Contract for implementations: never throw (the dispatcher catches, but a throw loses the
 * alert); return quickly and never block on the network (the socket thread and FCM's ~10-20 s
 * `onMessageReceived` budget are shared); be thread-safe (both paths call in).
 */
interface OutcomeHandler {
    /**
     * **PR-F:** post the CallStyle incoming-call notification on `portico_ring_v2` with a
     * full-screen intent to `RingActivity` (no foreground service), `setTimeoutAfter(ringUntilMs
     * - now)` plus an AlarmManager cancel at [Outcome.Ring.ringUntilMs] (a backstop that needs no
     * later message). The socket wake for HA's confirmation is NOT the handler's job: the
     * dispatcher has already called [SocketWaker] for a push ring.
     */
    fun onRing(outcome: Outcome.Ring)

    /** **PR-F:** stop the ring for the call, replace it with "Answered elsewhere" on `portico_status_v1`, `setTimeoutAfter(~10 s)`. */
    fun onDismiss(outcome: Outcome.Dismiss)

    /** **PR-F:** stop the ring if one is up; post a quiet "Missed call" on `portico_status_v1` (late ring, or ended unanswered). */
    fun onMissed(outcome: Outcome.Missed)

    /**
     * **PR-F:** nothing new. For `AFTER_TERMINAL` it may re-post the existing status notification
     * with `setOnlyAlertOnce(true)` (P2 plan §3.4), still no alert.
     */
    fun onSilent(outcome: Outcome.Silent)

    /** **PR-F:** `kind: test` (Q6): one quiet "Portico push ring notification works" notification; never a ring or full-screen intent. */
    fun onQuiet(outcome: Outcome.Quiet)
}

/**
 * Brings the HA socket up now so HA can confirm a pushed ring (spec §4.3: "the push is a wake
 * hint, HA is authoritative"). The app's is `SignalingClient.wake()`; never drops a live socket.
 */
fun interface SocketWaker {
    fun wakeForConfirmation()
}

/**
 * PR-E's default handler: logs the outcome class and a masked call id, nothing else. No
 * notification, no vibration, no UI (PR-F).
 */
class LoggingOutcomeHandler(private val log: HaLog) : OutcomeHandler {
    private fun line(kind: String, callId: String?, extra: String = "") =
        log.log(HaLog.Level.INFO, "portico outcome: $kind${callId?.let { " call=${Redact.prefix(it)}" } ?: ""}$extra (PR-E: no UI yet)")

    override fun onRing(outcome: Outcome.Ring) = line("Ring", outcome.callId)
    override fun onDismiss(outcome: Outcome.Dismiss) = line("Dismiss", outcome.callId)
    override fun onMissed(outcome: Outcome.Missed) = line("Missed", outcome.callId, " reason=${outcome.reason.name.lowercase()}")
    override fun onSilent(outcome: Outcome.Silent) = line("Silent", outcome.update?.callId, " reason=${outcome.reason.name.lowercase()}")
    override fun onQuiet(outcome: Outcome.Quiet) = line("Quiet", null)
}

/**
 * The one boundary between a decided [Outcome] and the [OutcomeHandler], shared by the push and
 * socket paths:
 * 1. the ring switch ([RingGate], plan §3.6): off -> no door-call alert reaches the handler;
 * 2. a PUSH ring wakes the socket ([waker]) before the handler runs (a socket ring already has
 *    one); a waker or handler failure is logged, never thrown (the service must not crash);
 * 3. a ring that gets this far (decided [Outcome.Ring], ring switch on) calls [onDoorRing] before the
 *    handler, so "a door call outranks an outgoing call" follows what this phone will actually ring for,
 *    never a raw socket event (a duplicate, a snapshot, an unknown door or a paused phone rings nothing).
 */
class OutcomeDispatcher(
    private val handler: OutcomeHandler,
    private val waker: SocketWaker,
    private val ringOnThisPhone: () -> Boolean,
    private val log: HaLog,
    private val onDoorRing: () -> Unit = {},
    /** The push and ring journal (0.5.12-alpha): write-only observation of what was held back or threw. */
    private val journal: PushJournal? = null,
) {
    fun dispatch(outcome: Outcome) {
        val ringOn = try {
            ringOnThisPhone()
        } catch (e: Exception) {
            true // the default (Q2); a settings read must not cost a ring
        }
        if (!RingGate.shouldHandle(outcome, ringOn)) {
            log.log(HaLog.Level.INFO, "portico outcome: ${outcome.contractName} not alerted: ring for door calls on this phone is off")
            try {
                journal?.gated(outcome, "ring switch off")
            } catch (e: Throwable) {
                // write-only observation
            }
            return
        }
        if (outcome is Outcome.Ring && outcome.update.source == Source.FCM) {
            guarded("socket wake") { waker.wakeForConfirmation() }
        }
        if (outcome is Outcome.Ring) guarded("door outranks call") { onDoorRing() }
        guarded("handler") {
            when (outcome) {
                is Outcome.Ring -> handler.onRing(outcome)
                is Outcome.Dismiss -> handler.onDismiss(outcome)
                is Outcome.Missed -> handler.onMissed(outcome)
                is Outcome.Silent -> handler.onSilent(outcome)
                is Outcome.Quiet -> handler.onQuiet(outcome)
            }
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico outcome: $what threw ${e.javaClass.simpleName}")
            try {
                journal?.failed(what, e)
            } catch (t: Throwable) {
                // write-only observation
            }
        }
    }
}
