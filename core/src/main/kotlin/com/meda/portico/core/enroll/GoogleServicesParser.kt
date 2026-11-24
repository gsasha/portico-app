package com.meda.portico.core.enroll

/** The server's three codes (`push/credentials.py` `GoogleServicesError`), plus two of the app's own. */
enum class GoogleServicesError(val code: String, val message: String) {
    INVALID_JSON("invalid_json", "That is not a google-services.json (not a JSON object)."),
    MISSING_FIELDS("missing_fields", "The google-services.json is missing project_info, the app id or the API key."),
    PACKAGE_NOT_FOUND(
        "package_not_found",
        "The google-services.json has no Android app for package ${GoogleServicesParser.PORTICO_PACKAGE}. " +
            "Add that app in the Firebase console and download the file again.",
    ),

    /** App-only: the server returns these values unchecked; the app refuses to store unusable ones. */
    INVALID_VALUES("invalid_values", "The google-services.json has an unusable value."),

    /** App-only: a size cap on the paste. */
    TOO_LARGE("too_large", "That text is far too large for a google-services.json."),
}

sealed interface GoogleServicesResult {
    data class Ok(val config: FirebaseConfig) : GoogleServicesResult

    data class Error(val code: GoogleServicesError, val firebase: FirebaseConfigResult.Invalid? = null) : GoogleServicesResult {
        val message: String get() = if (firebase != null) "${code.message} ${firebase.message}" else code.message
    }
}

/** The server's return value, unvalidated: the four strings exactly as `parse_google_services` returns them. */
sealed interface RawExtract {
    class Values(val projectId: String, val senderId: String, val appId: String, val apiKey: String) : RawExtract {
        override fun toString() = "RawExtract.Values(project_id=$projectId, sender_id=$senderId, app_id=$appId, api_key=<redacted>)"
    }

    data class Failed(val code: GoogleServicesError) : RawExtract
}

/**
 * Extracts the four Firebase client values for one Android package from a pasted
 * `google-services.json` (spec §5.2). [extractValues] mirrors `portico-ha`
 * `push/credentials.py` `parse_google_services` step for step:
 *
 * 1. not a JSON object -> `invalid_json`;
 * 2. `project_info` not an object, or `project_id` / `project_number` falsy -> `missing_fields`;
 * 3. walk `client[]` in order. For each entry, `client_info.android_client_info.package_name` must
 *    exist (else `missing_fields`, even for an entry that is not Portico's: the server's try/except
 *    covers the whole entry). A different package is skipped. The FIRST match returns
 *    `client_info.mobilesdk_app_id` and `api_key[0].current_key`; either missing -> `missing_fields`;
 * 4. no match -> `package_not_found`.
 *
 * Deliberate differences, all toward refusing: a value that is not a string or an integer
 * (where Python would `str()` it into `"None"` or `"True"`) is `missing_fields`; a truthy
 * non-list `client` is `missing_fields` (Python fails inside or outside its try, depending on
 * the type); input over [MAX_CHARS] is `too_large`. One loosening: a leading byte-order mark
 * is tolerated (Python's `json.loads` refuses it), since a phone's clipboard may carry one.
 *
 * [extract] then validates the values ([FirebaseConfig.create]); the server does not, but the
 * app must not store a value that can never register. Only the four values are kept: the pasted
 * document itself is dropped with the call.
 */
object GoogleServicesParser {
    /** `portico-ha` `push/const.py` `PORTICO_PACKAGE`; also this app's applicationId (not its code namespace). */
    const val PORTICO_PACKAGE = "com.meda.portico"

    /** A real file is 1-5 KB per app; 256 KB leaves room for large multi-app projects. */
    const val MAX_CHARS = 256 * 1024

    fun extract(text: String, packageName: String = PORTICO_PACKAGE): GoogleServicesResult =
        when (val raw = extractValues(text, packageName)) {
            is RawExtract.Failed -> GoogleServicesResult.Error(raw.code)
            is RawExtract.Values -> when (val c = FirebaseConfig.create(raw.projectId, raw.senderId, raw.appId, raw.apiKey)) {
                is FirebaseConfigResult.Ok -> GoogleServicesResult.Ok(c.config)
                is FirebaseConfigResult.Invalid -> GoogleServicesResult.Error(GoogleServicesError.INVALID_VALUES, c)
            }
        }

    fun extractValues(text: String, packageName: String): RawExtract {
        if (text.length > MAX_CHARS) return RawExtract.Failed(GoogleServicesError.TOO_LARGE)
        val data = MiniJson.parse(text.trim().removePrefix("﻿").trim()) as? Json.Obj
            ?: return RawExtract.Failed(GoogleServicesError.INVALID_JSON)
        val missing = RawExtract.Failed(GoogleServicesError.MISSING_FIELDS)

        val info = data["project_info"] as? Json.Obj ?: return missing
        if (!info["project_id"].pyTruthy() || !info["project_number"].pyTruthy()) return missing
        val projectId = info["project_id"].pyStrOrNull() ?: return missing
        val senderId = info["project_number"].pyStrOrNull() ?: return missing

        val clientsJson = data["client"]
        val clients: List<Json> = when {
            !clientsJson.pyTruthy() -> emptyList()
            clientsJson is Json.Arr -> clientsJson.items
            else -> return missing
        }
        for (client in clients) {
            val entry = client as? Json.Obj ?: return missing
            val clientInfo = entry["client_info"] as? Json.Obj ?: return missing
            val android = clientInfo["android_client_info"] as? Json.Obj ?: return missing
            if (!android.has("package_name")) return missing
            val pkg = android["package_name"]
            if (!(pkg is Json.Str && pkg.value == packageName)) continue

            if (!clientInfo.has("mobilesdk_app_id")) return missing
            val appId = clientInfo["mobilesdk_app_id"].pyStrOrNull() ?: return missing
            val keys = entry["api_key"] as? Json.Arr ?: return missing
            val first = keys.items.firstOrNull() as? Json.Obj ?: return missing
            if (!first.has("current_key")) return missing
            val apiKey = first["current_key"].pyStrOrNull() ?: return missing
            return RawExtract.Values(projectId, senderId, appId, apiKey)
        }
        return RawExtract.Failed(GoogleServicesError.PACKAGE_NOT_FOUND)
    }
}
