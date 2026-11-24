package com.meda.portico.core.call

import com.meda.portico.core.ha.CallTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// The outgoing call (call buttons, house calls design 14). It shares the answer path's ports where they
// fit (CommandPort, AudioRoute, MicPermission, Scheduler, MediaListener) and adds only what is different.

/**
 * The CALLER'S audio leg, as a port: the real one does not exist until the backend's house bridge does
 * (house calls design section 8, step 14d). Tests use a fake; the app ships [UnavailablePlaceMedia].
 *
 * CONTRACT (what the session relies on, and what an implementation must keep):
 * - [open] is called **once, only after the far end has answered** (a `house_call` `answered` event), never
 *   while the call is ringing: the microphone stays closed until a person has picked up.
 * - `descriptor` is the `media` object the server returned from `portico/content/run`, passed through
 *   untouched and opaque to the session (it may be null if the server sent none).
 * - [open] throws if audio cannot start; the session then ends the call with "The phone could not start the
 *   call audio" and releases it with the server.
 * - The listener's [MediaListener.onConnected] means audio flows both ways. The session ends the call with
 *   "Could not connect" if it has not come within [PlaceConfig.connectTimeoutMs] (10 s) of the answer.
 *   [MediaListener.onDisconnected] may be followed by `onConnected` (the session allows [PlaceConfig.graceMs]);
 *   [MediaListener.onFailed] is final. Callbacks may come on any thread; the owner serialises them.
 * - [PlaceLeg.close] stops the microphone and the connection. Idempotent, returns at once, never throws.
 * - Opening the leg never sends a command by itself: every send is the session's.
 */
interface PlaceLeg {
    fun setMuted(muted: Boolean)
    fun close()

    /** The session says why it is about to close the leg (DEV-698, for the journal's audio line). The default ignores it. */
    fun endedBecause(reason: EndReason) {}
}

fun interface PlaceMediaPort {
    /** [callId] is the call this leg carries (`hso-...`): a house call's audio is declared to the server BY id (`portico/house/media_open`). */
    fun open(callId: String, descriptor: JsonObject?, listener: MediaListener): PlaceLeg
}

/** What ships until the backend exists: opening the leg fails, so a call that somehow got that far ends with a clear message. */
object UnavailablePlaceMedia : PlaceMediaPort {
    override fun open(callId: String, descriptor: JsonObject?, listener: MediaListener): PlaceLeg =
        throw IllegalStateException("the call audio leg is not built yet")
}

enum class PlacePhase { IDLE, AWAITING_MIC, PLACING, RINGING, CONNECTING, IN_CALL, ENDED }

data class PlaceSnapshot(
    val phase: PlacePhase,
    val endReason: EndReason? = null,
    val failure: PlaceFailure? = null,
    val muted: Boolean = false,
    /** Wall-clock moment the audio first connected, for the on-screen timer. */
    val connectedAtMs: Long? = null,
    /** The server's id for the call (`hso-...`), once `content/run` granted it. */
    val callId: String? = null,
    /** This session ANSWERS a house ring (M7 3b-B) instead of placing a call: the screen says "Answering" where a placed call says "Calling". */
    val answering: Boolean = false,
)

/**
 * Which button was pressed: the action id (all the server is ever told) and the name to show. [answerCallId] is set for a house RING
 * that was answered (M7 3b-B): the session then sends `portico/house/answer {call_id}` instead of `portico/content/run`.
 */
data class PlaceRequest(val actionId: String, val name: String, val target: CallTarget? = null, val answerCallId: String? = null)

/** Hooks the app wires to the foreground service. All optional. */
interface PlaceHooks {
    /** The microphone is allowed and the call is about to be placed: start the foreground service. */
    fun onMicReady() {}

    /** The session reached ENDED (called once). */
    fun onEnded(snapshot: PlaceSnapshot) {}

    companion object {
        val NONE = object : PlaceHooks {}
    }
}

data class PlaceConfig(
    /** The cap on one connected call, from the moment the far end answers (the gate's 300 s cap, applied to every target). */
    val callCapMs: Long = 300_000,
    /** How long `content/run` may wait for HA's reply. */
    val stepTimeoutMs: Long = 10_000,
    /** How long the call may ring: the server's 45 s ring limit plus a margin; its `ended: no_answer` normally comes first. */
    val ringTimeoutMs: Long = 60_000,
    /** From the far end's answer to audio flowing (design 14.6: "Audio never arrives, 10 s"). */
    val connectTimeoutMs: Long = 10_000,
    /** How long a lost media path or lost WebSocket may recover during a connected call. A ringing call does not wait (design 4.1). */
    val graceMs: Long = 8_000,
)

/**
 * One `house_call` event from `portico/calls/subscribe` (portico-ha `house.py` `_event`; design 4, 15.16 row 3a):
 * `{type: "house_call", call_id, state: ringing|answered|active|ended, from, to, reason?, snapshot?}`.
 *
 * [from] and [to] are ENDPOINT ids, opaque: a name is never read from here, it comes from the document's `callers` or its
 * roster, and an id that resolves to nothing is shown as no name at all, never as the raw id. [to] is `everyone` on a
 * screen's own copy of a group call. [snapshot] is true for the state a subscription opens with (HA saying what is live
 * now), false for a change. [reason] is meaningful for `ended`: `cancelled | declined | busy | no_answer | dnd | error |
 * hangup | answered_elsewhere`.
 */
data class HouseCallEvent(
    val callId: String,
    val state: String,
    val reason: String?,
    val from: String? = null,
    val to: String? = null,
    val snapshot: Boolean = false,
) {
    companion object {
        const val TYPE = "house_call"

        fun parse(event: JsonObject): HouseCallEvent? {
            if ((event["type"] as? JsonPrimitive)?.contentOrNull != TYPE) return null
            val callId = (event["call_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val state = (event["state"] as? JsonPrimitive)?.contentOrNull ?: return null
            fun s(key: String) = (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val snapshot = (event["snapshot"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull == "true"
            return HouseCallEvent(callId, state, s("reason"), s("from"), s("to"), snapshot)
        }

        /** `{type: "house_roster_changed", rev}`: the roster (presence, DND, who receives) moved; the page is read again. */
        fun isRosterChanged(event: JsonObject): Boolean = (event["type"] as? JsonPrimitive)?.contentOrNull == "house_roster_changed"
    }
}

/** Where a parsed [HouseCallEvent] goes. Called on the link's single thread. */
fun interface HouseEventSink {
    fun onHouseCall(event: HouseCallEvent)

    companion object {
        val NONE = HouseEventSink { }
    }
}
