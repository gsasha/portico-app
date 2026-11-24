package com.meda.portico.core.call

import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The outgoing call's WebSocket commands (portico-ha README "Call buttons", house calls design 14.2):
 *
 * | command                     | body       | ok result                                  |
 * | `portico/content/run`       | `action_id`| `{ran: true, call_id, media}`              |
 * | `portico/hangup`            | `call_id`, `reason` | the server's result (any reply settles it) |
 *
 * The phone sends the button's **action id** and nothing else: never an endpoint id, a phone name or a
 * target. Ending a call is the same `portico/hangup` the answer path uses (contract 3 has no `portico/house/hangup`;
 * M7 adds the house commands as additive cases). The `reason` says what the user did: [REASON_CANCELLED] while the
 * far end was still ringing, [REASON_HANGUP] once it was answered.
 */
object PlaceCommands {
    const val RUN = ContentCommands.RUN
    const val HANGUP = "portico/hangup"

    /** Ended while still ringing: the caller gave up before anyone answered. */
    const val REASON_CANCELLED = "cancelled"

    /** Ended after the far end answered. */
    const val REASON_HANGUP = "hangup"

    fun place(actionId: String): JsonObject = ContentCommands.run(actionId)

    fun hangup(callId: String, reason: String): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        require(reason.isNotEmpty()) { "reason" }
        return buildJsonObject {
            put("call_id", callId)
            put("reason", reason)
        }
    }

    sealed interface Placed {
        /** The server rang them. [media] is the caller's leg descriptor, opaque to the app. */
        data class Granted(val callId: String, val media: JsonObject?) : Placed
        data class Refused(val failure: PlaceFailure) : Placed
    }

    fun parsePlaced(r: HaInbound.Result, name: String?): Placed {
        if (!r.success) {
            val e = r.error
            return Placed.Refused(PlaceErrors.fromCode(e?.code ?: "unknown_error", name))
        }
        val o = r.result as? JsonObject
        val callId = (o?.get("call_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val ran = (o?.get("ran") as? JsonPrimitive)?.booleanOrNull == true
        // `ran` without a call id is what a server without call buttons would say for any other kind: no call exists.
        if (!ran || callId == null) return Placed.Refused(PlaceErrors.fromCode("malformed_result", name))
        return Placed.Granted(callId, o["media"] as? JsonObject)
    }
}
