package com.meda.portico.core.live

import com.meda.portico.core.ha.ContentProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Where the phone's live/still choices live between launches (a prefs string in the app, a variable in the tests). */
interface LiveStore {
    /** The saved text, or null for none. May throw: [LiveMemory] then starts empty. */
    fun load(): String?

    /** False when it could not be saved (the choices are then kept for this run only). */
    fun save(text: String): Boolean
}

/** The phone's choice for one camera (true: live, false: still) and the document's `live` at the moment it was made. */
data class LiveChoice(val live: Boolean, val documentLiveAtSet: Boolean)

/** The stored text. Decoding never throws: damaged or foreign text is "no choices"; a choice equal to its document default is not one. */
object LiveCodec {
    private const val VERSION = 1

    fun encode(all: Map<String, LiveChoice>): String = buildJsonObject {
        put("v", VERSION)
        put(
            "live",
            buildJsonArray {
                for ((camera, c) in all) add(
                    buildJsonObject {
                        put("c", camera)
                        put("l", c.live)
                        put("d", c.documentLiveAtSet)
                    },
                )
            },
        )
    }.toString()

    fun decode(text: String?): LinkedHashMap<String, LiveChoice> {
        val out = LinkedHashMap<String, LiveChoice>()
        if (text.isNullOrBlank()) return out
        try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return out
            if ((root["v"] as? JsonPrimitive)?.contentOrNull != VERSION.toString()) return out
            val list = root["live"] as? JsonArray ?: return out
            for (e in list) {
                val o = e as? JsonObject ?: continue
                val camera = (o["c"] as? JsonPrimitive)?.contentOrNull ?: continue
                val live = (o["l"] as? JsonPrimitive)?.booleanOrNull ?: continue
                val doc = (o["d"] as? JsonPrimitive)?.booleanOrNull ?: continue
                if (live != doc) out[camera] = LiveChoice(live, doc)
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }
}

/**
 * the owner's decision 1 (DEV-713): the LIVE chip on a tile switches it between live and still, and the choice is remembered on this
 * phone, per camera, **only while it differs from the document's `live`** (the card's rule, `portico-card.js` `_toggleLive`). So:
 *
 * - [effective]: the choice if there is one AND the document's `live` is still what it was when the choice was made, else the
 *   document's. An admin who changes `live` in the editor therefore reaches a phone whose owner chose against the old value.
 * - [toggle]: flips what is shown now; a flip back to the document's value deletes the choice. Saved at once.
 * - [reconcile]: when a real document arrives, forgets a choice for a camera that left it or whose `live` changed. Never from an
 *   empty document, so a bad fetch cannot wipe the memory. A caller's camera (the ring and in-call screens, S2) counts, with its
 *   default (first available camera of that caller or not) as the "document's value".
 *
 * Thread-safe. Nothing here is sent to Home Assistant.
 */
class LiveMemory(private val store: LiveStore) {
    private val entries: LinkedHashMap<String, LiveChoice> = try {
        LiveCodec.decode(store.load())
    } catch (_: Exception) {
        LinkedHashMap()
    }

    @Synchronized
    fun effective(cameraId: String, documentLive: Boolean): Boolean {
        val c = entries[cameraId] ?: return documentLive
        return if (c.documentLiveAtSet == documentLive) c.live else documentLive
    }

    /** The mode now shown for the camera after the flip. */
    @Synchronized
    fun toggle(cameraId: String, documentLive: Boolean): Boolean {
        val next = !effective(cameraId, documentLive)
        if (next == documentLive) entries.remove(cameraId) else entries[cameraId] = LiveChoice(next, documentLive)
        persist()
        return next
    }

    /** Makes the camera live (true) or a still (false) as the viewer's choice (0.6.53: the LIVE chip's state as the video-size menu sets it); false when it already was. */
    @Synchronized
    fun choose(cameraId: String, documentLive: Boolean, live: Boolean): Boolean {
        if (effective(cameraId, documentLive) == live) return false
        toggle(cameraId, documentLive)
        return true
    }

    /** True if something was forgotten. An empty document forgets nothing. */
    @Synchronized
    fun reconcile(profile: ContentProfile): Boolean {
        if (profile.isEmpty || entries.isEmpty()) return false
        // the defaults a camera id has in this document: a page tile's `live`, and (S2) for a caller's camera whether it is that caller's first available one
        val docLive = HashMap<String, MutableSet<Boolean>>()
        for (page in profile.pageList) for (block in page.blockList) for (c in block.cameras) docLive.getOrPut(c.id) { HashSet() }.add(c.live)
        for (caller in profile.callers.values) {
            val first = CallerLive.firstAvailableId(caller.cameras)
            for (c in caller.cameras) docLive.getOrPut(c.id) { HashSet() }.add(c.id == first)
        }
        val before = entries.size
        entries.entries.removeAll { (id, c) -> docLive[id]?.contains(c.documentLiveAtSet) != true }
        if (entries.size == before) return false
        persist()
        return true
    }

    @Synchronized
    fun count(): Int = entries.size

    private fun persist() {
        try {
            store.save(LiveCodec.encode(entries))
        } catch (_: Exception) {
            // kept for this run
        }
    }
}
