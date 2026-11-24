package com.meda.portico.core.call

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FakePlaceLeg(val descriptor: JsonObject?, val listener: MediaListener) : PlaceLeg {
    var closes = 0
    var micOff = false
    override fun setMuted(muted: Boolean) {
        micOff = muted
    }

    override fun close() {
        closes++
    }
}

class FakePlaceMedia : PlaceMediaPort {
    val legs = mutableListOf<FakePlaceLeg>()
    var failOpen = false
    val callIds = mutableListOf<String>()
    override fun open(callId: String, descriptor: JsonObject?, listener: MediaListener): PlaceLeg {
        callIds += callId
        if (failOpen) throw IllegalStateException("no audio")
        return FakePlaceLeg(descriptor, listener).also { legs += it }
    }
}

/** The result frame of a granted `content/run` for a call button: `{ran, call_id, media}`. */
fun granted(callId: String = "hso-1-a", media: JsonObject? = buildJsonObject { put("kind", "fake") }): JsonObject = buildJsonObject {
    put("ran", true)
    put("call_id", callId)
    if (media != null) put("media", media)
}

val FAKE_MEDIA: JsonObject = buildJsonObject { put("kind", "fake") }

