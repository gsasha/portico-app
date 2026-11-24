package com.meda.portico.core.connlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** The one command the app sends for the connection log (DEV-705). The link adds the `type` and the `id`. */
object ConnLogCommands {
    const val REPORT = "portico/connlog/report"

    /** The body of `portico/connlog/report` for [reports] (each a compact JSON object text). */
    fun report(reports: List<String>): JsonObject = buildJsonObject {
        put("reports", JsonArray(reports.map { Json.parseToJsonElement(it) }))
    }
}
