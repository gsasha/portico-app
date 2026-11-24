package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonObject

/**
 * The id grammar of a call the house DIALLED (blocks design 7, house design 15.15 (d)): the hub mints `out-<16 hex>`.
 * A dialled call's own `ringing` is never a visitor, so nothing in the app may ring for it. The server keeps those
 * events away from every phone; this is the app's second layer (P3-C).
 */
object OutboundCallIds {
    const val PREFIX = "out-"

    fun isOutbound(callId: String?): Boolean = callId != null && callId.startsWith(PREFIX)
}

/** The states a `talk_state` event carries (portico-ha P3-B). */
enum class TalkWireState(val wire: String) {
    DIALING("dialing"),
    IN_CALL("in_call"),
    ENDED("ended"),
    ;

    companion object {
        fun of(wire: String?): TalkWireState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `{type: "talk_state", door_id, call_id, state: dialing | in_call | ended, reason?}` on `portico/calls/subscribe`. The
 * server sends it ONLY to the endpoint that placed the Talk call; it is not a `call_state_changed` and never rings.
 * [reason] is meaningful for `ended` (and may be absent). [callId] is always an `out-` id.
 */
data class TalkStateEvent(val doorId: String, val callId: String, val state: TalkWireState, val reason: String?)

/** Where a parsed [TalkStateEvent] goes. Called on the link's single thread. */
fun interface TalkStateSink {
    fun onTalkState(event: TalkStateEvent)

    companion object {
        val NONE = TalkStateSink { }
    }
}

/** Reads the `talk_state` event; anything that is not exactly that (wrong type, no door, no `out-` id, unknown state) is null. */
internal fun parseTalkState(event: JsonObject): TalkStateEvent? {
    if (event.str("type") != "talk_state") return null
    val doorId = event.str("door_id") ?: return null
    val callId = event.str("call_id")?.takeIf { OutboundCallIds.isOutbound(it) } ?: return null
    val state = TalkWireState.of(event.str("state")) ?: return null
    return TalkStateEvent(doorId, callId, state, event.str("reason"))
}
