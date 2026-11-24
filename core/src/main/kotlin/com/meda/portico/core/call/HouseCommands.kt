package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The house-call WebSocket commands (portico-ha `house_commands.py`; house calls design 4, 15.4 rule 3, 15.16 row 3a):
 *
 * | command                      | body                       | ok result                                    |
 * | `portico/house/answer`       | `call_id`, `client_kind`   | `{call_id, media}` (`media` is null today)   |
 * | `portico/house/decline`      | `call_id`                  | `{}`                                         |
 * | `portico/house/hangup`       | `call_id`                  | `{}`                                         |
 * | `portico/house/presence`     | `dnd`                      | `{}`                                         |
 * | `portico/house/media_open`   | `call_id`, `codec` or `sdp`| `{kind: "frames", ...}` or `{kind: "webrtc"}` |
 *
 * Placing a house call is NOT one of them: it goes through `portico/content/run` with the roster row's `r-...` action id (CB-1), so
 * the phone never sends an endpoint id. All five answer `not_available` while the server's house calls are off, which is the
 * state this ships in: nothing here is sent unless the roster says house calls are on ([com.meda.portico.core.content.HouseGate]).
 *
 * A house call's id starts with `hso-`; a door's with `gso-`; a dialled call's with `out-`. The id is what picks the hang-up
 * command: `portico/hangup` is the door's, and a house call id sent to it is `unknown_call`, so the call would ring on.
 */
object HouseCommands {
    const val ANSWER = "portico/house/answer"
    const val DECLINE = "portico/house/decline"
    const val HANGUP = "portico/house/hangup"
    const val PRESENCE = "portico/house/presence"
    const val MEDIA_OPEN = "portico/house/media_open"

    /** The prefix of a house call id (server `HOUSE_PREFIX`). */
    const val ID_PREFIX = "hso-"

    /** The only `client_kind` the app sends: its audio rides the house bridge's frame or WebRTC legs, never the door's relay. */
    const val CLIENT_KIND = "app"

    fun isHouseCall(callId: String?): Boolean = callId != null && callId.startsWith(ID_PREFIX)

    fun answer(callId: String): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject {
            put("call_id", callId)
            put("client_kind", CLIENT_KIND)
        }
    }

    fun decline(callId: String): JsonObject = idOnly(callId)

    fun hangup(callId: String): JsonObject = idOnly(callId)

    fun presence(dnd: Boolean): JsonObject = buildJsonObject { put("dnd", dnd) }

    /** A frame channel (`codec`, default pcmu) when [sdp] is null, a WebRTC offer otherwise. The server refuses a body with other keys. */
    fun mediaOpen(callId: String, codec: String = AudioProfile.PCMU.codec, sdp: String? = null): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject {
            put("call_id", callId)
            if (sdp != null) put("sdp", sdp) else put("codec", codec)
        }
    }

    private fun idOnly(callId: String): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject { put("call_id", callId) }
    }

    /** The command that ends call [callId]: the house's for an `hso-` id, the door's `portico/hangup` (with [reason]) for any other. */
    fun hangupFor(callId: String, reason: String): Pair<String, JsonObject> =
        if (isHouseCall(callId)) HANGUP to hangup(callId) else AnswerCommands.HANGUP to PlaceCommands.hangup(callId, reason)

    sealed interface Answered {
        /** The server gave this phone the call. [media] is the leg descriptor (null while the server has none); opaque to the app. */
        data class Granted(val callId: String, val media: JsonObject?) : Answered
        data class Refused(val failure: PlaceFailure) : Answered
    }

    fun parseAnswer(r: HaInbound.Result): Answered {
        if (!r.success) return Answered.Refused(PlaceErrors.fromCode(r.error?.code ?: "unknown_error"))
        val o = r.result as? JsonObject
        val callId = (o?.get("call_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: return Answered.Refused(PlaceErrors.fromCode("malformed_result"))
        return Answered.Granted(callId, o["media"] as? JsonObject)
    }

    sealed interface Plain {
        data object Ok : Plain
        data class Refused(val failure: PlaceFailure) : Plain
    }

    /** The reply of decline, hangup and presence: success is `{}`, anything else is a refusal with the server's code. */
    fun parsePlain(r: HaInbound.Result): Plain =
        if (r.success) Plain.Ok else Plain.Refused(PlaceErrors.fromCode(r.error?.code ?: "unknown_error"))

    sealed interface Opened {
        data class Frames(val codec: String, val frameBytes: Int, val frameMs: Int) : Opened
        data object WebRtc : Opened
        data class Refused(val failure: PlaceFailure) : Opened
    }

    fun parseMediaOpen(r: HaInbound.Result): Opened {
        if (!r.success) return Opened.Refused(PlaceErrors.fromCode(r.error?.code ?: "unknown_error"))
        val o = r.result as? JsonObject ?: return Opened.Refused(PlaceErrors.fromCode("malformed_result"))
        return when ((o["kind"] as? JsonPrimitive)?.contentOrNull) {
            "webrtc" -> Opened.WebRtc
            "frames" -> {
                val codec = (o["codec"] as? JsonPrimitive)?.contentOrNull
                val bytes = (o["frame_bytes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                val ms = (o["frame_ms"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                if (codec == null || bytes == null || ms == null) Opened.Refused(PlaceErrors.fromCode("malformed_result")) else Opened.Frames(codec, bytes, ms)
            }
            else -> Opened.Refused(PlaceErrors.fromCode("malformed_result"))
        }
    }
}
