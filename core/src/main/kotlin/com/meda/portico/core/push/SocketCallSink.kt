package com.meda.portico.core.push

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.CallStateSink
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.OutboundCallIds

/**
 * PR-E: D's socket [CallStateSink] adapted onto B's one [CallStore] (P2 plan §3.3 "the socket
 * path writes the same store"), so a call heard on the socket and by push rings once.
 *
 * Each [CallEvent] becomes the [PushMessage] the push path would have produced ([SocketEvents])
 * and goes through the same [Decision.receive]; the outcome goes to [onOutcome] (the app's
 * outcome dispatcher, the same one the push path uses).
 *
 * - The door is the event's `door_ref`: D's doors-cache translation of the event's `door_id` (the event
 *   carries no ref). A door the cache cannot translate is not a known door: silent,
 *   nothing recorded (the push rule, Q5).
 * - `knownDoorRefs` is the cache plus the event's own door (which the cache just translated).
 * - `active` is treated as `answered` (someone took the call). Ended (incl. an inferred end) is
 *   `ended`. A snapshot saying the door has no live call (`idle`, or `unavailable`) ends the door's
 *   still-ringing call, if any ("HA is authoritative", spec §4.3), through [SocketEvents.idle]
 *   inside the same atomic step.
 *
 * Runs on the link's thread; the store's atomic block makes it safe against the push thread.
 */
class SocketCallSink(
    private val store: CallStore,
    private val doors: DoorsCache,
    private val wallClockMs: () -> Long,
    private val onOutcome: (PushMessage, Outcome) -> Unit,
    private val journal: (String) -> Unit,
    /** Pause: when the last pause ended (0 if never); a ring for a call begun before it is not rung. */
    private val resumedAt: () -> Long = { 0L },
    /** DEV-807 (Panel mode): false for a door this panel's screen does not ring for. Always true off Panel mode. */
    private val doorTaken: (String) -> Boolean = { true },
) : CallStateSink {

    override fun onCallEvent(event: CallEvent) {
        // Second layer (P3-C): a call this house DIALLED never rings, and nothing is recorded for it. The server keeps
        // `out-` events off the stream; if one arrives anyway it is dropped here, before any message is built.
        val callId = event.callIdOrNull()
        if (OutboundCallIds.isOutbound(callId)) {
            journal("portico push: socket event for dialled call $callId -> dropped (never rings)")
            return
        }
        val door = event.doorRef
        if (door == null) {
            journal("portico push: socket event for unknown door ${event.doorId} -> silent (not in the doors cache)")
            return
        }
        val now = wallClockMs()
        val resumed = try {
            resumedAt()
        } catch (_: Exception) {
            0L // fail open: an unreadable resume time withholds nothing
        }
        val known = doors.knownDoorRefs() + door
        val decided = store.atomically { s ->
            val msg: PushMessage? = when (event) {
                is CallEvent.Ringing -> SocketEvents.ringing(door, event.callId, now)
                is CallEvent.Answered -> SocketEvents.answered(door, event.callId, now)
                is CallEvent.Active -> SocketEvents.answered(door, event.callId, now)
                is CallEvent.Ended -> SocketEvents.ended(door, event.callId, now)
                is CallEvent.NoLiveCall -> SocketEvents.idle(s, door, now)
            }
            msg?.let { it to Decision.receive(s, it, now, known, resumed, doorTaken) }
        } ?: return
        journal(PushLog.decision(decided.first, decided.second))
        onOutcome(decided.first, decided.second)
    }
}

private fun CallEvent.callIdOrNull(): String? = when (this) {
    is CallEvent.Ringing -> callId
    is CallEvent.Answered -> callId
    is CallEvent.Active -> callId
    is CallEvent.Ended -> callId
    is CallEvent.NoLiveCall -> null
}
