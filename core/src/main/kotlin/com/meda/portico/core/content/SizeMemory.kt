package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.CameraSizes
import com.meda.portico.core.ha.ContentProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where the phone's size choices live between launches (a prefs string in the app, a variable in the tests). */
interface SizeStore {
    /** The saved text, or null for none. May throw: [SizeMemory] then starts empty. */
    fun load(): String?

    /** False when it could not be saved (the choices are then kept for this run only). */
    fun save(text: String): Boolean
}

/** A camera in a page: the key of one choice. Two fields, never a joined string, so an id with a slash cannot collide. */
data class SizeKey(val pageId: String, val cameraId: String)

/** The phone's choice for one camera, and the server's default at the moment it was made (blocks design 6.2). */
data class SizeChoice(val size: CameraSize, val defaultAtSet: CameraSize)

/** The stored text. Decoding never throws: damaged or foreign text is "no choices"; a choice equal to its default is not one. */
object SizeCodec {
    private const val VERSION = 1

    fun encode(all: Map<SizeKey, SizeChoice>): String = buildJsonObject {
        put("v", VERSION)
        put("sizes", buildJsonArray {
            for ((k, c) in all) add(buildJsonObject {
                put("p", k.pageId)
                put("c", k.cameraId)
                put("s", c.size.wire)
                put("d", c.defaultAtSet.wire)
            })
        })
    }.toString()

    fun decode(text: String?): LinkedHashMap<SizeKey, SizeChoice> {
        val out = LinkedHashMap<SizeKey, SizeChoice>()
        if (text.isNullOrBlank()) return out
        try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return out
            if (root["v"]?.jsonPrimitive?.contentOrNull != VERSION.toString()) return out
            val list = root["sizes"] as? JsonArray ?: return out
            for (e in list) {
                val o = e as? JsonObject ?: continue
                val p = o["p"]?.jsonPrimitive?.contentOrNull ?: continue
                val c = o["c"]?.jsonPrimitive?.contentOrNull ?: continue
                val s = CameraSize.of(o["s"]?.jsonPrimitive?.contentOrNull) ?: continue
                val d = CameraSize.of(o["d"]?.jsonPrimitive?.contentOrNull) ?: continue
                if (s != d) out[SizeKey(p, c)] = SizeChoice(s, d)
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }
}

/**
 * Blocks B6 (design 6.2, decisions Q4): the camera sizes this phone chose by tapping a tile, per page and camera id.
 * The server's `size` (or the auto rule) is the DEFAULT; a choice only exists while it differs from it.
 *
 * - [effective]: the choice if there is one AND the default is still the one it was made against, else the default.
 *   Pure of side effects, so Home can ask it every second.
 * - [toggle]: flips big/small from what is shown now; a flip back to the default deletes the choice. Saved at once.
 * - [reconcile]: when a real document arrives, forgets a choice whose camera is gone or whose default HA changed (an
 *   admin who changes a size on purpose reaches this phone). Never from an empty document, so a bad fetch cannot wipe
 *   the memory.
 *
 * Thread-safe. Nothing here is sent to Home Assistant.
 */
class SizeMemory(private val store: SizeStore) {
    private val entries: LinkedHashMap<SizeKey, SizeChoice> = try {
        SizeCodec.decode(store.load())
    } catch (_: Exception) {
        LinkedHashMap()
    }

    @Synchronized
    fun effective(pageId: String, cameraId: String, default: CameraSize): CameraSize {
        val c = entries[SizeKey(pageId, cameraId)] ?: return default
        return if (c.defaultAtSet == default) c.size else default
    }

    /** The size now shown for the camera after the flip. [default] is the server's default for it. */
    @Synchronized
    fun toggle(pageId: String, cameraId: String, default: CameraSize): CameraSize {
        val next = opposite(effective(pageId, cameraId, default))
        val key = SizeKey(pageId, cameraId)
        if (next == default) entries.remove(key) else entries[key] = SizeChoice(next, default)
        persist()
        return next
    }

    /** DEV-768 P3: the resize menu's Big / Small. Sets the size shown to [size] (a choice equal to the default is none). True if the shown size changed. */
    @Synchronized
    fun set(pageId: String, cameraId: String, default: CameraSize, size: CameraSize): Boolean {
        if (effective(pageId, cameraId, default) == size) return false
        val key = SizeKey(pageId, cameraId)
        if (size == default) entries.remove(key) else entries[key] = SizeChoice(size, default)
        persist()
        return true
    }

    /** True if something was forgotten. An empty document forgets nothing. */
    @Synchronized
    fun reconcile(profile: ContentProfile): Boolean {
        if (profile.isEmpty || entries.isEmpty()) return false
        val defaults = HashMap<SizeKey, CameraSize>()
        for (page in profile.pageList) for (block in page.blockList) {
            val sizes = CameraSizes.effective(block.cameras)
            block.cameras.forEachIndexed { i, c -> defaults[SizeKey(page.id, c.id)] = sizes[i] }
        }
        val before = entries.size
        entries.entries.removeAll { (k, c) -> defaults[k] == null || defaults[k] != c.defaultAtSet }
        if (entries.size == before) return false
        persist()
        return true
    }

    @Synchronized
    fun count(): Int = entries.size

    private fun persist() {
        try {
            store.save(SizeCodec.encode(entries))
        } catch (_: Exception) {
            // kept for this run
        }
    }

    companion object {
        fun opposite(s: CameraSize): CameraSize = if (s == CameraSize.BIG) CameraSize.SMALL else CameraSize.BIG
    }
}
