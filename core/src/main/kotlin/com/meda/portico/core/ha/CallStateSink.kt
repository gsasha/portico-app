package com.meda.portico.core.ha

/**
 * Where `portico/calls/subscribe` delivers call state. PR-D's own small port: PR-B defines the real
 * call store / decision core, and PR-E adapts it to this interface (one store for the socket and
 * the push path, P2 plan §3.3). Called on the link's single thread.
 */
fun interface CallStateSink {
    fun onCallEvent(event: CallEvent)
}

/**
 * One call-state fact from the socket. [doorId] is the event's `door_id`; [doorRef] is the push contract's
 * `door_ref`, translated through the doors cache (the event carries none); null when the door is not in the cache
 * (the sink should then stay silent, as the push path does for an unknown door_ref).
 */
sealed interface CallEvent {
    val doorId: String
    val doorRef: String?

    /** [snapshot] = this came from the per-door snapshot right after subscribing (or when a door arrived), not a live change. */
    data class Ringing(override val doorId: String, override val doorRef: String?, val callId: String, val snapshot: Boolean = false) : CallEvent
    data class Answered(override val doorId: String, override val doorRef: String?, val callId: String, val snapshot: Boolean = false) : CallEvent
    data class Active(override val doorId: String, override val doorRef: String?, val callId: String, val snapshot: Boolean = false) : CallEvent

    /**
     * The call [callId] is over. [inferred] = the server did not name the call: a new call replacing the
     * followed one, or a snapshot that no longer shows it.
     */
    data class Ended(override val doorId: String, override val doorRef: String?, val callId: String, val inferred: Boolean) : CallEvent

    /** Snapshot (`idle`, or `unavailable` for a door that left): this door has no live call right now ("HA is authoritative", plan §3.3). */
    data class NoLiveCall(override val doorId: String, override val doorRef: String?) : CallEvent
}
