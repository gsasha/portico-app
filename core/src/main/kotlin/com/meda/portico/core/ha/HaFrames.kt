package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One `{code, message}` error from a failed `result` frame. */
data class HaError(val code: String, val message: String)

/**
 * A frame from Home Assistant's WebSocket API, parsed. Parsing never throws: anything that is
 * not one of these shapes becomes [Malformed] or [Other].
 */
sealed interface HaInbound {
    data class AuthRequired(val haVersion: String?) : HaInbound
    data class AuthOk(val haVersion: String?) : HaInbound
    data class AuthInvalid(val message: String?) : HaInbound

    /**
     * `{"id", "type": "result", "success", "result" | "error"}`. [toString] leaves the result body
     * out: a result is never printed whole, whatever a future command puts in it.
     */
    class Result(val id: Int, val success: Boolean, val result: JsonElement?, val error: HaError?) : HaInbound {
        override fun toString(): String =
            "Result(id=$id, success=$success" + (if (error != null) ", error=${error.code}" else "") + ")"
    }

    /** `{"id", "type": "event", "event": {...}}`: a subscription's delivery. */
    class Event(val id: Int, val event: JsonObject) : HaInbound {
        override fun toString(): String = "Event(id=$id)"
    }

    data class Pong(val id: Int) : HaInbound
    data class Other(val type: String) : HaInbound
    data class Malformed(val reason: String) : HaInbound
}
