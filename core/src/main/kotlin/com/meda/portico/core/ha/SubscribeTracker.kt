package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonObject

/**
 * Turns `portico/calls/subscribe` event payloads into [CallEvent]s (contract 3; design 3.4). The server sends:
 *
 * - right after the subscribe result, **one snapshot per door**: `{type: "call_state_changed", snapshot: true, door_id,
 *   call_id, state}`. `state` is `idle` (`call_id` null), `ringing`, `answered`/`active`, or `unavailable` when the
 *   door left (the integration removed it); a door set up after the subscription gets its snapshot when it arrives;
 * - then **one event per change**: `{type: "call_state_changed", door_id, call_id, state, prev_state}`. A call that
 *   ends is `state: "ended"` with its own `call_id`; there is **no `idle` echo** after it (v1's `{state: "idle",
 *   call_id: null}` is gone), so nothing here infers an end from an idle.
 *
 * A snapshot is HA being authoritative about the door right now: `idle` or `unavailable` means no live call, which
 * ends the call this tracker was following on that door (an end it never saw) and yields [CallEvent.NoLiveCall].
 *
 * `talk_state` events (portico-ha P3-B, sent only to the endpoint that placed a Talk call) are NOT call state: this
 * tracker ignores them, [parseTalkState] reads them, and the link hands them to the Talk session only.
 *
 * The event carries no `door_ref`: it comes from the [DoorsCache] by `door_id`, null for a door the cache does not
 * know (the sinks stay silent for it, and the link asks `portico/doors` again).
 *
 * Idempotent: an end already reported is not reported again. Owned by the link; the followed-call map survives
 * reconnects. The `devices_changed` event of contract 2 was never emitted by the server and is not read.
 */
class SubscribeTracker(private val doors: DoorsCache) {

    private val followed = HashMap<String, String>()
    private val recentlyEnded = ArrayDeque<String>()
    private val lastState = HashMap<String, String>()

    /**
     * DEV-811: true when [event] would say what this tracker already reported: the call it follows on that door, in the state it was
     * last reported in. A link race replays the candidate connection's events after the swap, and the old connection delivered the
     * same ring first. Read only; a repeat is dropped by the caller, never by [onEvent] (a reconnect snapshot is still delivered).
     */
    fun isRepeat(event: JsonObject): Boolean {
        if (event.str("type") != "call_state_changed") return false
        val doorId = event.str("door_id") ?: return false
        val state = event.str("state") ?: return false
        val callId = event.str("call_id") ?: return false
        if (state != "ringing" && state != "answered" && state != "active") return false
        return followed[doorId] == callId && lastState[doorId] == state
    }

    /** The call currently followed on [doorId], if any. */
    fun followedCall(doorId: String): String? = followed[doorId]

    fun onEvent(event: JsonObject): List<CallEvent> {
        if (event.str("type") != "call_state_changed") return emptyList()
        val snapshot = isSnapshot(event)
        val doorId = event.str("door_id") ?: return emptyList()
        val state = event.str("state") ?: return emptyList()
        val callId = event.str("call_id")
        // A dialled call (`out-`) is never a visitor: the server keeps it off this stream, and if one arrives it is dropped
        // before it can be followed (it must not end a real followed call as "replaced") or reach any sink.
        if (OutboundCallIds.isOutbound(callId)) return emptyList()
        val doorRef = doors.doorRefFor(doorId)
        val out = ArrayList<CallEvent>(2)

        when (state) {
            "ringing", "answered", "active" -> {
                if (callId == null) return emptyList()
                if (callId in recentlyEnded) return emptyList() // a late event for an ended call
                val previous = followed[doorId]
                if (previous != null && previous != callId) end(doorId, doorRef, previous, inferred = true, out)
                followed[doorId] = callId
                lastState[doorId] = state
                out += when (state) {
                    "ringing" -> CallEvent.Ringing(doorId, doorRef, callId, snapshot)
                    "answered" -> CallEvent.Answered(doorId, doorRef, callId, snapshot)
                    else -> CallEvent.Active(doorId, doorRef, callId, snapshot)
                }
            }
            "ended" -> {
                // An end names its call. One without a call id is not in the contract; it ends what is followed.
                val ended = callId ?: followed[doorId] ?: return emptyList()
                end(doorId, doorRef, ended, inferred = callId == null, out)
            }
            "idle", "unavailable" -> {
                // Only a snapshot says "nothing is live here"; a live `idle` is not sent in contract 3 and means nothing.
                if (!snapshot) return emptyList()
                followed[doorId]?.let { end(doorId, doorRef, it, inferred = true, out) }
                out += CallEvent.NoLiveCall(doorId, doorRef)
            }
            else -> return emptyList()
        }
        return out
    }

    private fun end(doorId: String, doorRef: String?, callId: String, inferred: Boolean, out: MutableList<CallEvent>) {
        if (followed[doorId] == callId) {
            followed.remove(doorId)
            lastState.remove(doorId)
        }
        if (callId in recentlyEnded) return
        recentlyEnded.addLast(callId)
        while (recentlyEnded.size > RECENT_ENDS) recentlyEnded.removeFirst()
        out += CallEvent.Ended(doorId, doorRef, callId, inferred)
    }

    companion object {
        const val RECENT_ENDS = 64

        internal fun isSnapshot(event: JsonObject): Boolean = event.bool("snapshot") == true

        /** The `door_id` of a snapshot saying the door left (`state: "unavailable"`): the door set changed. */
        internal fun doorLeft(event: JsonObject): String? =
            if (event.str("type") == "call_state_changed" && isSnapshot(event) && event.str("state") == "unavailable") event.str("door_id") else null

        /** The `door_id` of a `call_state_changed` event, whatever its state. */
        internal fun doorOf(event: JsonObject): String? =
            if (event.str("type") == "call_state_changed") event.str("door_id") else null
    }
}
