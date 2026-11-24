package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builders and result parsers for the Portico push-registration commands, against `portico-ha`
 * `custom_components/portico/signaling.py` (contract 3; the handshake and subscriptions are in [Handshake]):
 *
 * | command                  | body                                                                                 | ok result |
 * | `portico/push/register`  | installation_id 1-128, kind fid/token, registration 1-4096, package 1-255, fcm_project_id 1-128, app_version <=64 | `{reg_id, push_enabled}` |
 * | `portico/push/unregister`| installation_id 1-128                                                                | `{removed: true}` |
 * | `portico/push/status`    | none                                                                                 | `{state, registrations: [...]}` |
 *
 * Server checks run in this order: the auth table (`unauthorized`/`not_enrolled`), the app's version
 * (`app_too_old`: contract 3 refuses an `app_version` below `0.5.0`, or none), then configured
 * (`push_not_configured`), enrolled (`unauthorized`), placeholder (`invalid_registration`), project
 * (`project_mismatch`) and package (`package_mismatch`). HA itself adds `invalid_format` (schema) and
 * `unknown_command` (an integration without the command).
 */
object PushCommands {
    const val PACKAGE = "com.meda.portico"

    const val REGISTER = "portico/push/register"
    const val UNREGISTER = "portico/push/unregister"
    const val STATUS = "portico/push/status"

    /** The server's own placeholder list (signaling.py `_PLACEHOLDERS`). */
    private val PLACEHOLDERS = setOf("unknown", "unavailable", "none", "null", "undefined")

    /** True when the server would accept [value] as a registration (never send anything else). */
    fun isUsableRegistration(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim()
        return v.isNotEmpty() && v.length <= 4096 && v.lowercase() !in PLACEHOLDERS
    }

    fun register(
        installationId: String,
        kind: String,
        registration: String,
        fcmProjectId: String,
        appVersion: String,
        packageName: String = PACKAGE,
    ): JsonObject {
        require(kind == "fid" || kind == "token") { "kind must be fid or token" }
        require(isUsableRegistration(registration)) { "blank or placeholder registration" }
        require(installationId.length in 1..128) { "installation_id must be 1-128 chars" }
        require(fcmProjectId.length in 1..128) { "fcm_project_id must be 1-128 chars" }
        return buildJsonObject {
            put("installation_id", installationId)
            put("kind", kind)
            put("registration", registration.trim())
            put("package", packageName)
            put("fcm_project_id", fcmProjectId)
            put("app_version", appVersion.take(64))
        }
    }

    fun unregister(installationId: String): JsonObject = buildJsonObject { put("installation_id", installationId) }

    // ---- results -------------------------------------------------------------------------

    enum class ErrorKind(val wire: String?) {
        UNAUTHORIZED("unauthorized"),
        PROJECT_MISMATCH("project_mismatch"),
        PACKAGE_MISMATCH("package_mismatch"),

        /** Contract 3's refusal of `app_version` below 0.5.0 (or none): the app is the thing to update. */
        APP_TOO_OLD("app_too_old"),
        INVALID_REGISTRATION("invalid_registration"),
        PUSH_NOT_CONFIGURED("push_not_configured"),
        NOT_FOUND("not_found"),
        INVALID_FORMAT("invalid_format"),
        UNKNOWN_COMMAND("unknown_command"),

        /** Any other code (HA's `unknown_error`, `home_assistant_error`, a future server code). */
        OTHER(null),
        ;

        companion object {
            fun of(code: String): ErrorKind = entries.firstOrNull { it.wire == code } ?: OTHER
        }
    }

    sealed interface Outcome {
        data class Registered(val regId: String, val pushEnabled: Boolean) : Outcome
        data class Unregistered(val removed: Boolean) : Outcome
        data class Status(val state: String, val registrations: List<StatusRow>) : Outcome
        data class Failed(val kind: ErrorKind, val code: String, val message: String) : Outcome

        /** The socket closed before the result arrived. */
        data object Lost : Outcome
    }

    data class StatusRow(
        val regId: String,
        val installationId: String?,
        val name: String?,
        val kind: String?,
        val state: String?,
        val pushEnabled: Boolean,
        val lastResult: String?,
        val lastOkAt: String?,
        val lastSeenAt: String?,
        val registrationUpdatedAt: String?,
        val stale: Boolean,
    )

    private fun failed(r: HaInbound.Result): Outcome.Failed {
        val e = r.error ?: HaError("unknown_error", "")
        return Outcome.Failed(ErrorKind.of(e.code), e.code, e.message)
    }

    private fun malformed(what: String) = Outcome.Failed(ErrorKind.OTHER, "malformed_result", what)

    fun parseRegister(r: HaInbound.Result): Outcome {
        if (!r.success) return failed(r)
        val o = r.result.asObject() ?: return malformed("register result is not an object")
        val regId = o.str("reg_id") ?: return malformed("register result without reg_id")
        return Outcome.Registered(regId, o.bool("push_enabled") ?: false)
    }

    /** `not_found` is success: the row is already gone (P2 plan §3.2). */
    fun parseUnregister(r: HaInbound.Result): Outcome {
        if (!r.success) {
            val f = failed(r)
            return if (f.kind == ErrorKind.NOT_FOUND) Outcome.Unregistered(removed = false) else f
        }
        return Outcome.Unregistered(removed = r.result.asObject()?.bool("removed") ?: true)
    }

    fun parseStatus(r: HaInbound.Result): Outcome {
        if (!r.success) return failed(r)
        val o = r.result.asObject() ?: return malformed("status result is not an object")
        val rows = (o["registrations"] as? JsonArray)?.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            StatusRow(
                regId = row.str("reg_id") ?: return@mapNotNull null,
                installationId = row.str("installation_id"),
                name = row.str("name"),
                kind = row.str("kind"),
                state = row.str("state"),
                pushEnabled = row.bool("push_enabled") ?: false,
                lastResult = row.str("last_result"),
                lastOkAt = row.str("last_ok_at"),
                lastSeenAt = row.str("last_seen_at"),
                registrationUpdatedAt = row.str("registration_updated_at"),
                stale = row.bool("stale") ?: false,
            )
        } ?: emptyList()
        return Outcome.Status(o.str("state") ?: "unknown", rows)
    }
}
