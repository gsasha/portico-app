package com.meda.portico.core.enroll

/** The four Firebase client values, by their enrollment-QR key (spec §5.1). */
enum class FirebaseField(val key: String) {
    PROJECT_ID("project_id"),
    SENDER_ID("sender_id"),
    APP_ID("app_id"),
    API_KEY("api_key"),
}

enum class FirebaseProblem(val describe: String) {
    BLANK("is blank"),
    PLACEHOLDER("is a placeholder such as \"unknown\""),
    MALFORMED("is not in the expected format"),
    OTHER_PROJECT("belongs to a different Firebase project than the sender id"),
}

sealed interface FirebaseConfigResult {
    data class Ok(val config: FirebaseConfig) : FirebaseConfigResult

    /** Names the field and the problem, never the value. */
    data class Invalid(val field: FirebaseField, val problem: FirebaseProblem) : FirebaseConfigResult {
        val message: String get() = "Firebase ${this.field.key} ${problem.describe}."
    }
}

/**
 * The four values runtime `FirebaseOptions` need (spec §5.3): `projectId`, `senderId` (the GCM
 * sender id, = `project_number`), `appId` (`mobilesdk_app_id`) and `apiKey`.
 *
 * None of them is a secret (they ship inside every Firebase app), but the api_key is still kept
 * out of [toString] so it never lands in a log line (plan §3.7). Only [create] makes one, so
 * every instance is validated.
 */
class FirebaseConfig private constructor(
    val projectId: String,
    val senderId: String,
    val appId: String,
    val apiKey: String,
) {
    override fun equals(other: Any?): Boolean = other is FirebaseConfig &&
        projectId == other.projectId && senderId == other.senderId && appId == other.appId && apiKey == other.apiKey

    override fun hashCode(): Int = listOf(projectId, senderId, appId, apiKey).hashCode()

    override fun toString(): String = "FirebaseConfig(project_id=$projectId, sender_id=$senderId, app_id=$appId, api_key=<redacted>)"

    companion object {
        /** The server's `_PLACEHOLDERS` (`signaling.py`): what a broken client sends instead of a value. */
        val PLACEHOLDERS = setOf("unknown", "unavailable", "none", "null", "undefined")

        // project_id: the server's _PROJECT_ID_RE (`push/credentials.py`). HA refuses a register whose
        // fcm_project_id differs from its service account's, which passed this same pattern.
        private val PROJECT_ID_RE = Regex("^[a-z][a-z0-9-]{3,62}$")
        private val SENDER_ID_RE = Regex("^[0-9]{1,32}$")
        // mobilesdk_app_id is "<version>:<project_number>:android:<hex>".
        private val APP_ID_RE = Regex("^[0-9]+:([0-9]+):android:[0-9A-Za-z]+$")
        // printable ASCII, no spaces
        private val API_KEY_RE = Regex("^[\\x21-\\x7e]{1,256}$")

        fun create(projectId: String, senderId: String, appId: String, apiKey: String): FirebaseConfigResult {
            val values = linkedMapOf(
                FirebaseField.PROJECT_ID to projectId,
                FirebaseField.SENDER_ID to senderId,
                FirebaseField.APP_ID to appId,
                FirebaseField.API_KEY to apiKey,
            )
            for ((field, v) in values) {
                if (v.isBlank()) return FirebaseConfigResult.Invalid(field, FirebaseProblem.BLANK)
                if (v.trim().lowercase() in PLACEHOLDERS) return FirebaseConfigResult.Invalid(field, FirebaseProblem.PLACEHOLDER)
            }
            if (!PROJECT_ID_RE.matches(projectId)) return FirebaseConfigResult.Invalid(FirebaseField.PROJECT_ID, FirebaseProblem.MALFORMED)
            if (!SENDER_ID_RE.matches(senderId)) return FirebaseConfigResult.Invalid(FirebaseField.SENDER_ID, FirebaseProblem.MALFORMED)
            val app = APP_ID_RE.matchEntire(appId)
                ?: return FirebaseConfigResult.Invalid(FirebaseField.APP_ID, FirebaseProblem.MALFORMED)
            if (app.groupValues[1] != senderId) return FirebaseConfigResult.Invalid(FirebaseField.APP_ID, FirebaseProblem.OTHER_PROJECT)
            if (!API_KEY_RE.matches(apiKey)) return FirebaseConfigResult.Invalid(FirebaseField.API_KEY, FirebaseProblem.MALFORMED)
            return FirebaseConfigResult.Ok(FirebaseConfig(projectId, senderId, appId, apiKey))
        }
    }
}
