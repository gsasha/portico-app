package com.meda.portico.core.connlog

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** HA's answer to `portico/connlog/report` as the sender's [ConnLogReply]. Pure and total. */
object ConnLogReplies {
    fun of(frame: HaInbound.Result?): ConnLogReply {
        if (frame == null) return ConnLogReply.Lost
        if (!frame.success) return ConnLogReply.Error(frame.error?.code?.takeIf { it.isNotBlank() } ?: "error")
        val o = try {
            frame.result as? JsonObject ?: frame.result?.jsonObject
        } catch (_: Exception) {
            null
        }
        fun count(from: JsonObject?, k: String) = from?.get(k)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
        fun n(k: String) = count(o, k)
        val journal = o?.get("journal") as? JsonObject
        return ConnLogReply.Ok(n("stored"), n("duplicates"), n("rejected"), count(journal, "lines"), count(journal, "dropped"))
    }
}
