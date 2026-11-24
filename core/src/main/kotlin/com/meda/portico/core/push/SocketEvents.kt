package com.meda.portico.core.push

/**
 * The WebSocket hook for PR-D: turns a `portico/calls/subscribe` `call_state_changed` event into the
 * same [PushMessage] the push path produces, so both go through [Decision.receive] and the one
 * [CallStore] (spec §3.4, P2 plan §3.3 "the socket path writes the same store").
 *
 * PR-D translates the event's `door_id` to the push `door_ref` through its `portico/doors` cache
 * before calling these; an untranslatable door is not a known door
 * and stays silent like any unknown `door_ref`.
 *
 * A socket event carries no `seq`, so each kind uses the contract's fixed per-kind `seq`
 * ([Contract.SEQ_RING] ...), which is exactly what the server sends by push. A socket ring has no
 * `exp`: it is live by definition, so it is never "late".
 */
object SocketEvents {
    fun ringing(doorRef: String, callId: String, nowMs: Long): PushMessage.Ring =
        PushMessage.Ring(doorRef, callId, Contract.SEQ_RING, nowMs, expMs = null, source = Source.WS)

    fun answered(doorRef: String, callId: String, nowMs: Long): PushMessage.Answered =
        PushMessage.Answered(doorRef, callId, Contract.SEQ_ANSWERED, nowMs, by = null, source = Source.WS)

    fun ended(doorRef: String, callId: String, nowMs: Long): PushMessage.Ended =
        PushMessage.Ended(doorRef, callId, Contract.SEQ_ENDED, nowMs, source = Source.WS)

    /**
     * A snapshot saying the door has no live call (`idle`, or `unavailable` for a door that left; the server is
     * authoritative, plan §3.3, §4 G1). This maps it to [PushMessage.Ended] for the door's most recent **still ringing** call, or null when the
     * store holds no ringing call for the door (nothing to end). Call it inside
     * [CallStore.atomically] together with [Decision.receive] if the socket and push threads can
     * race; a stale lookup is harmless anyway, because `ended` on a terminal call is silent.
     */
    fun idle(store: CallStore, doorRef: String, nowMs: Long): PushMessage.Ended? {
        val current = store.latestFor(doorRef) ?: return null
        if (current.state != CallState.RINGING) return null
        return ended(doorRef, current.callId, nowMs)
    }
}
