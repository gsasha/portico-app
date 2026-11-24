package com.meda.portico.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The string form of the call store for the app's prefs-backed `PrefsCallStore`: a JSON array,
 * oldest write first. Holds only opaque ids, states and times (no secret). Decoding never
 * throws: a garbled value is an empty store, and a bad entry is skipped.
 */
object CallRecordCodec {
    fun encode(records: List<CallRecord>): String = buildJsonArray {
        for (r in records) add(buildJsonObject {
            put("door_ref", r.doorRef)
            put("call_id", r.callId)
            put("state", r.state.name)
            put("seq", r.seq)
            put("t", r.tMs)
            put("source", r.source.name)
            put("at", r.updatedAtMs)
        })
    }.toString()

    fun decode(text: String?): List<CallRecord> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el as? JsonObject ?: return@mapNotNull null
                CallRecord(
                    doorRef = o.s("door_ref") ?: return@mapNotNull null,
                    callId = o.s("call_id") ?: return@mapNotNull null,
                    state = CallState.valueOf(o.s("state") ?: return@mapNotNull null),
                    seq = o.s("seq")?.toInt() ?: return@mapNotNull null,
                    tMs = o.s("t")?.toLong() ?: return@mapNotNull null,
                    source = Source.valueOf(o.s("source") ?: return@mapNotNull null),
                    updatedAtMs = o.s("at")?.toLong() ?: return@mapNotNull null,
                )
            } catch (e: IllegalArgumentException) { // unknown enum name, bad number
                null
            }
        }
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
