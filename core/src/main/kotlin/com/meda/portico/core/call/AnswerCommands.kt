package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builders and parsers for the answer path's WebSocket commands, against `portico-ha`
 * `custom_components/portico/signaling.py` (contract 3) and the Lovelace card
 * (`www/portico-card.js`), the reference client:
 *
 * | command                 | body                                       | ok result                        |
 * | `portico/answer`        | `door_id`, `call_id`, `client_kind`        | `{mode: "relay", ...}`           |
 * | `portico/webrtc_offer`  | `call_id`, `door_id`, `sdp`                | `{sdp}` (the answer)             |
 * | `portico/hangup`        | `call_id`, `reason`                        | the transport's result           |
 *
 * Contract 3 names the door by `door_id` everywhere (v2's `device_id` is refused by the schema), and `webrtc_offer`
 * names it too, so the server checks that the door owns the call.
 *
 * `client_kind` is `"browser"`: the phone carries its audio over WebRTC to the transport's SFU,
 * exactly as the card does, and the server computes `mode` from `client_kind` and the driver
 * (`mode: direct` is for `client_kind: app` against a `DirectCapable` driver, where the phone
 * would have to dial the device itself, which this app cannot do yet). Sending `"app"` could be
 * answered `direct` by a driver that this path cannot serve. A reply with any `mode` but `relay`
 * is [AnswerFailure.Kind.UNEXPECTED_MODE], as in the card.
 *
 * The card never sends `portico/webrtc_ice`: the offer carries every candidate (non-trickle,
 * matching `internal/sfu.Session`), so neither does this app.
 */
object AnswerCommands {
    const val ANSWER = "portico/answer"
    const val WEBRTC_OFFER = "portico/webrtc_offer"
    const val HANGUP = "portico/hangup"

    const val CLIENT_KIND = "browser"
    const val MODE_RELAY = "relay"

    /** The card's own reason for a user's hang-up. */
    const val REASON_USER = "user_hangup"

    /** A call the phone ended because something went wrong, or lost it (design §9.5: `hangup {reason: error}`). */
    const val REASON_ERROR = "error"

    /** The five minute cap, and the app stopping under a live call. */
    const val REASON_TIMEOUT = "timeout"

    fun answer(doorId: String, callId: String): JsonObject {
        require(doorId.isNotEmpty()) { "door_id" }
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject {
            put("door_id", doorId)
            put("call_id", callId)
            put("client_kind", CLIENT_KIND)
        }
    }

    fun webrtcOffer(callId: String, doorId: String, sdp: String): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        require(doorId.isNotEmpty()) { "door_id" }
        require(sdp.isNotEmpty()) { "sdp" }
        return buildJsonObject {
            put("call_id", callId)
            put("door_id", doorId)
            put("sdp", sdp)
        }
    }

    fun hangup(callId: String, reason: String): JsonObject {
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject {
            put("call_id", callId)
            put("reason", reason)
        }
    }

    sealed interface Claim {
        /** The server granted the call; [mode] is what it computed (the card accepts only `relay`). */
        data class Granted(val mode: String?) : Claim
        data class Refused(val failure: AnswerFailure) : Claim
    }

    fun parseClaim(r: HaInbound.Result): Claim {
        if (!r.success) return Claim.Refused(failure(r))
        val mode = ((r.result as? JsonObject)?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Claim.Granted(mode)
    }

    sealed interface Offer {
        data class Answer(val sdp: String) : Offer
        data class Refused(val failure: AnswerFailure) : Offer
    }

    fun parseOffer(r: HaInbound.Result): Offer {
        if (!r.success) return Offer.Refused(failure(r))
        val sdp = ((r.result as? JsonObject)?.get("sdp") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (sdp.isNullOrEmpty()) {
            Offer.Refused(AnswerErrors.fromCode("malformed_result"))
        } else {
            Offer.Answer(sdp)
        }
    }

    private fun failure(r: HaInbound.Result): AnswerFailure {
        val e = r.error
        return AnswerErrors.fromCode(e?.code ?: "unknown_error", e?.message)
    }
}
