package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A talk's WebSocket commands. There is NO command of its own (blocks design 7.6 item 4: `app_commands_v3.txt` is
 * unchanged): a start is `portico/content/run` with the Talk button's action id and nothing else; ending is the same
 * `portico/hangup {call_id, reason}` every other call uses (by the call id the start returned); the audio is the answer
 * path's `portico/webrtc_offer` ([AnswerCommands.webrtcOffer]).
 *
 * | command                | body                    | ok result |
 * | `portico/content/run`  | `action_id`             | `{ran: true, call_id, media: null}`; a press while this phone's own call is going: `{ran, call_id, hung_up: true}` |
 * | `portico/hangup`       | `call_id`, `reason`     | the server's result (any reply settles it) |
 *
 * The phone sends the action id only: never a door, a service or an entity.
 */
object TalkCommands {
    const val RUN = PlaceCommands.RUN
    const val HANGUP = PlaceCommands.HANGUP

    fun start(actionId: String): JsonObject = PlaceCommands.place(actionId)

    fun hangup(callId: String, reason: String): JsonObject = PlaceCommands.hangup(callId, reason)

    sealed interface Started {
        data class Granted(val callId: String) : Started
        data class Refused(val failure: TalkFailure) : Started
    }

    fun parseStarted(r: HaInbound.Result): Started {
        if (!r.success) return Started.Refused(TalkErrors.fromCode(r.error?.code ?: "unknown_error"))
        val o = r.result as? JsonObject
        val ran = (o?.get("ran") as? JsonPrimitive)?.booleanOrNull == true
        val callId = (o?.get("call_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        // The server hung up this phone's own earlier call instead of starting one: nothing new is running.
        if ((o?.get("hung_up") as? JsonPrimitive)?.booleanOrNull == true) return Started.Refused(TalkErrors.previousEnded())
        // `ran` without a call id is what a server without Talk would say for any other kind: no call exists.
        if (!ran || callId == null) return Started.Refused(TalkErrors.fromCode("malformed_result"))
        return Started.Granted(callId)
    }
}
