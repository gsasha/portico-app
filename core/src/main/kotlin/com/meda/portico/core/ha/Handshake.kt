package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The first things a connection says (contract 3; design `portico_editor_design.md` 3.3, 3.4):
 *
 * | command                       | body        | ok result |
 * | `portico/version`             | none        | `{contract: 3, ui_version, providers}` |
 * | `portico/doors`               | none        | `{doors: [{door_id, door_ref, endpoint_id, name, driver, capabilities, camera_entity_id, available, away_media}]}` |
 * | `portico/calls/subscribe`     | none        | `null`, then one snapshot event per door, then `call_state_changed` events |
 * | `portico/content/subscribe`   | `endpoint?` | `null`, then `{type: "content_changed", endpoint, rev}` events |
 *
 * A connection sends `version` first, after `auth_ok`. Only when it answers contract [CONTRACT] does the link go on to
 * `doors`, `portico/push/register` and the two subscriptions; otherwise it registers nothing (see [ContractMismatch]).
 * The old `portico/devices` and `portico/subscribe` no longer exist on a contract-3 server (`unknown_command`).
 */
object Handshake {
    /** The one contract version this app speaks. */
    const val CONTRACT = 3

    const val VERSION = "portico/version"
    const val DOORS = "portico/doors"
    const val CALLS_SUBSCRIBE = "portico/calls/subscribe"
    const val CONTENT_SUBSCRIBE = "portico/content/subscribe"

    sealed interface Version {
        /** The server speaks [CONTRACT]. [uiVersion] is the editor's, for display only. */
        data class Ok(val uiVersion: Int?) : Version

        data class Mismatch(val mismatch: ContractMismatch) : Version
    }

    /**
     * `portico/version` -> [Version.Ok] only for `contract == 3`. A different number, `unknown_command` (a contract-2
     * server that has no `portico/version` either, or a future one that dropped it), any other error and a malformed
     * result are all a [Version.Mismatch]: the app cannot confirm the server speaks its contract, so it registers nothing.
     */
    fun parseVersion(r: HaInbound.Result): Version {
        if (!r.success) return Version.Mismatch(ContractMismatch(null, r.error?.code ?: "unknown_error"))
        val o = r.result.asObject() ?: return Version.Mismatch(ContractMismatch(null, "malformed_result"))
        val contract = o.int("contract") ?: return Version.Mismatch(ContractMismatch(null, "malformed_result"))
        if (contract != CONTRACT) return Version.Mismatch(ContractMismatch(contract, "contract"))
        return Version.Ok(o.int("ui_version"))
    }

    /**
     * `portico/doors` -> the ids and the name only. Contract 3 carries no `secrets` and no `entry_id`; everything
     * else a door has (`capabilities`, `camera_entity_id`, `available`, `away_media`, anything added later) is
     * tolerated and not kept; `answerable` is the one extra fact kept (the server's word for a ring-only door). A door without `door_id` or `door_ref` is skipped: it could neither be answered nor
     * match a push. Null when the result is not a doors list.
     */
    fun parseDoors(r: HaInbound.Result): List<Door>? {
        if (!r.success) return null
        val list = r.result.asObject()?.get("doors") as? JsonArray ?: return null
        return list.mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            Door(
                doorId = d.str("door_id") ?: return@mapNotNull null,
                doorRef = d.str("door_ref") ?: return@mapNotNull null,
                endpointId = d.str("endpoint_id"),
                driver = d.str("driver"),
                name = d.str("name"),
                answerable = d.bool("answerable") ?: true,
            )
        }
    }
}

/**
 * The server did not answer `portico/version` with contract 3 (R7-05). It is a **per-connection, retrying** state:
 * the link closes the socket, the client stays in its backoff loop (`WAITING_TO_RETRY`) and the next connect asks
 * again, so an app installed before the server restart heals itself. It is never modelled on `AUTH_REJECTED` (the one
 * terminal state, cleared only by a Setup save).
 *
 * [serverContract] is the number the server named, null when it named none (`unknown_command`, an error, a malformed
 * result). [code] is `contract` for a different number, else the server's error code. Neither holds a URL or a token.
 */
data class ContractMismatch(val serverContract: Int?, val code: String) {
    /** The words Settings shows for it. */
    val userText: String
        get() {
            val server = serverContract?.let { "server contract $it" } ?: "server contract unknown"
            return "This Home Assistant runs a different Portico version ($server, app needs ${Handshake.CONTRACT}); retrying."
        }
}
