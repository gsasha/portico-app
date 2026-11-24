package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Home Assistant's WebSocket protocol for ONE connection: the auth handshake
 * (`auth_required` -> `{"type":"auth","access_token"}` -> `auth_ok` | `auth_invalid`), then
 * numbered commands whose integer `id` increases for the life of the connection.
 *
 * Pure and single-threaded: frames in, actions out. A new connection gets a new instance (the
 * id counter restarts, as HA expects). Never throws on a malformed frame.
 */
class HaProtocol(private val accessToken: String) {

    enum class Phase { AWAITING_AUTH_REQUIRED, AUTHENTICATING, READY, AUTH_FAILED }

    sealed interface Action {
        /** Write [text] to the socket. [toString] never shows it: the auth frame holds the token. */
        class Send(val text: String) : Action {
            override fun toString(): String = "Send(${text.length} chars)"
        }

        data class Authenticated(val haVersion: String?) : Action
        data class AuthRejected(val message: String?) : Action

        /** A result or event, delivered only once the connection is READY. */
        data class Deliver(val frame: HaInbound) : Action
        data class Ignored(val reason: String) : Action
    }

    var phase: Phase = Phase.AWAITING_AUTH_REQUIRED
        private set

    private var lastId = 0

    /** Every frame in one text message (HA may coalesce several into a JSON array). */
    fun onText(text: String): List<Action> = parse(text).map(::onFrame)

    /** The next command, or null when the connection is not READY (nothing is sent before auth_ok). */
    fun command(type: String, body: JsonObject = JsonObject(emptyMap())): Pair<Int, String>? {
        if (phase != Phase.READY) return null
        val id = ++lastId
        val frame = buildJsonObject {
            put("id", id)
            put("type", type)
            for ((k, v) in body) if (k != "id" && k != "type") put(k, v)
        }
        return id to frame.toString()
    }

    private fun onFrame(frame: HaInbound): Action = when (frame) {
        is HaInbound.AuthRequired ->
            if (phase == Phase.AWAITING_AUTH_REQUIRED) {
                phase = Phase.AUTHENTICATING
                Action.Send(buildJsonObject { put("type", "auth"); put("access_token", accessToken) }.toString())
            } else {
                Action.Ignored("auth_required in phase $phase")
            }
        is HaInbound.AuthOk ->
            if (phase == Phase.AUTHENTICATING) {
                phase = Phase.READY
                Action.Authenticated(frame.haVersion)
            } else {
                Action.Ignored("auth_ok in phase $phase")
            }
        is HaInbound.AuthInvalid -> {
            phase = Phase.AUTH_FAILED
            Action.AuthRejected(frame.message)
        }
        is HaInbound.Result, is HaInbound.Event, is HaInbound.Pong ->
            if (phase == Phase.READY) Action.Deliver(frame) else Action.Ignored("$frame before auth_ok")
        is HaInbound.Other -> Action.Ignored("unknown frame type ${frame.type}")
        is HaInbound.Malformed -> Action.Ignored("malformed frame: ${frame.reason}")
    }

    override fun toString(): String = "HaProtocol(phase=$phase, lastId=$lastId, token=${Mask.short(accessToken)})"

    companion object {
        /** Parses one text message into frames. Never throws. */
        fun parse(text: String): List<HaInbound> {
            val root = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return listOf(HaInbound.Malformed("not JSON"))
            }
            return when (root) {
                is JsonObject -> listOf(parseObject(root))
                is JsonArray -> if (root.isEmpty()) listOf(HaInbound.Malformed("empty array")) else root.map {
                    if (it is JsonObject) parseObject(it) else HaInbound.Malformed("array element is not an object")
                }
                else -> listOf(HaInbound.Malformed("not an object"))
            }
        }

        private fun parseObject(o: JsonObject): HaInbound {
            val type = o.str("type") ?: return HaInbound.Malformed("no type")
            return when (type) {
                "auth_required" -> HaInbound.AuthRequired(o.str("ha_version"))
                "auth_ok" -> HaInbound.AuthOk(o.str("ha_version"))
                "auth_invalid" -> HaInbound.AuthInvalid(o.str("message"))
                "result" -> {
                    val id = o.int("id") ?: return HaInbound.Malformed("result without an integer id")
                    val success = (o["success"] as? JsonPrimitive)?.let {
                        if (it.isString) null else it.content.toBooleanStrictOrNull()
                    } ?: return HaInbound.Malformed("result without a boolean success")
                    if (success) {
                        HaInbound.Result(id, true, o["result"], null)
                    } else {
                        val err = o["error"] as? JsonObject
                        val code = err?.str("code") ?: "unknown_error"
                        HaInbound.Result(id, false, null, HaError(code, err?.str("message") ?: ""))
                    }
                }
                "event" -> {
                    val id = o.int("id") ?: return HaInbound.Malformed("event without an integer id")
                    val event = o["event"] as? JsonObject ?: return HaInbound.Malformed("event without an object")
                    HaInbound.Event(id, event)
                }
                "pong" -> o.int("id")?.let { HaInbound.Pong(it) } ?: HaInbound.Malformed("pong without an id")
                else -> HaInbound.Other(type)
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
