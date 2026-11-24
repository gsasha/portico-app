package com.meda.portico.core.content

import com.meda.portico.core.live.LiveStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 0.6.53, what one camera view is set to, as the video-size (gear) menu and the LIVE chip see it:
 * - [live]: the LIVE chip's state (video, or stills); the owner of that is `LiveMemory`, the value is read from it.
 * - [pick]: the LAST LIVE RESOLUTION, [StreamPick.AUTO] / [StreamPick.BIG] / [StreamPick.SMALL]. It is kept while the view is on stills or off,
 *   so turning live back on restores it.
 * - [off]: the view is turned off (no video, no stills).
 */
data class CameraState(val pick: StreamPick = StreamPick.AUTO, val live: Boolean = true, val off: Boolean = false)

/** The pure rules that connect the gear menu and the LIVE chip (0.6.53). */
object CameraModes {
    /** The menu row that is checked: OFF, else STILLS when live is off, else the resolution. */
    fun checked(s: CameraState): StreamPick = when {
        s.off -> StreamPick.OFF
        !s.live -> StreamPick.STILLS
        else -> s.pick
    }

    /**
     * Choosing [row] in the menu. A resolution turns the view on, live, and becomes the last live resolution. "stills" turns the view on as
     * stills and leaves the last live resolution alone. "off" turns it off and leaves both the chip state and the resolution alone, so the
     * tile's earlier state is what the next choice builds on.
     */
    fun choose(s: CameraState, row: StreamPick): CameraState = when (row) {
        StreamPick.AUTO, StreamPick.BIG, StreamPick.SMALL -> CameraState(row, live = true, off = false)
        StreamPick.STILLS -> s.copy(live = false, off = false)
        StreamPick.OFF -> s.copy(off = true)
    }

    /**
     * A tap on the LIVE chip: video <-> stills; the last live resolution is untouched, so stills -> live restores it (also after a menu "stills").
     * An off view draws no chip, so the tap is nothing.
     */
    fun tapLive(s: CameraState): CameraState = if (s.off) s else s.copy(live = !s.live)
}

/**
 * The phone's last live resolution and off switch per camera, kept on the phone ([LiveStore] text) so they survive a restart. Only values
 * that differ from the defaults (Auto, not off) are kept. Decoding never throws; damaged text is "no choices". Thread-safe.
 */
class CameraChoices(private val store: LiveStore) {
    private class Entry(val pick: StreamPick, val off: Boolean)

    private val entries: LinkedHashMap<String, Entry> = try {
        decode(store.load())
    } catch (_: Exception) {
        LinkedHashMap()
    }

    @Synchronized
    fun pick(cameraId: String): StreamPick = entries[cameraId]?.pick ?: StreamPick.AUTO

    @Synchronized
    fun isOff(cameraId: String): Boolean = entries[cameraId]?.off == true

    @Synchronized
    fun offIds(): Set<String> = entries.filterValues { it.off }.keys.toSet()

    /** Stores [pick] (only AUTO / BIG / SMALL count; anything else is Auto) and [off] for the camera. True if something changed. */
    @Synchronized
    fun set(cameraId: String, pick: StreamPick, off: Boolean): Boolean {
        val p = if (pick == StreamPick.BIG || pick == StreamPick.SMALL) pick else StreamPick.AUTO
        val before = entries[cameraId]
        if ((before?.pick ?: StreamPick.AUTO) == p && (before?.off ?: false) == off) return false
        if (p == StreamPick.AUTO && !off) entries.remove(cameraId) else entries[cameraId] = Entry(p, off)
        persist()
        return true
    }

    private fun persist() {
        try {
            store.save(encode(entries))
        } catch (_: Exception) {
            // kept for this run
        }
    }

    private companion object {
        const val VERSION = 1

        fun encode(all: Map<String, Entry>): String = buildJsonObject {
            put("v", VERSION)
            put(
                "cams",
                buildJsonArray {
                    for ((id, e) in all) add(
                        buildJsonObject {
                            put("c", id)
                            put("p", e.pick.word)
                            put("o", e.off)
                        },
                    )
                },
            )
        }.toString()

        fun decode(text: String?): LinkedHashMap<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            if (text.isNullOrBlank()) return out
            try {
                val root = Json.parseToJsonElement(text) as? JsonObject ?: return out
                if ((root["v"] as? JsonPrimitive)?.contentOrNull != VERSION.toString()) return out
                val list = root["cams"] as? JsonArray ?: return out
                for (e in list) {
                    val o = e as? JsonObject ?: continue
                    val id = (o["c"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val pick = StreamPick.of((o["p"] as? JsonPrimitive)?.contentOrNull)?.takeIf { it == StreamPick.BIG || it == StreamPick.SMALL } ?: StreamPick.AUTO
                    val off = (o["o"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (pick != StreamPick.AUTO || off) out[id] = Entry(pick, off)
                }
            } catch (_: Exception) {
                out.clear()
            }
            return out
        }
    }
}
