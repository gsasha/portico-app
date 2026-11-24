package com.meda.portico.core.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Portico door as `portico/doors` (contract 3) names it:
 *
 * - [doorId] is the socket's key: what `portico/answer`, `webrtc_offer`, `media_open` and `open_relay` name
 *   and what `portico/calls/subscribe` events carry. It may be a URL (the gate's is `http://192.168.1.7:8091`).
 * - [doorRef] is the push contract's `door_ref` (`d-<32 hex>`, derived by the server from the endpoint id).
 * - [endpointId] keys the `callers` map of `portico/content/get` (`<provider>:<door id>`). The ring screen of A2
 *   reads it; null only if a server leaves it out.
 *
 * Never any of the door's secrets: contract 3 does not send them ([DoorCodec] keeps six fields, nothing else).
 *
 * [name] is the door's display name for the ring screen; the ring shows a generic "Door" when it is absent.
 */
data class Door(
    val doorId: String,
    val doorRef: String,
    val endpointId: String? = null,
    val driver: String? = null,
    val name: String? = null,
    /** The server's word (`answerable` of `portico/doors`): false for a ring-only door. Absent (an older server) means true. */
    val answerable: Boolean = true,
)

/**
 * Where the doors cache survives a process death (P2 plan §3.3: a push may be the first thing
 * a cold process sees, and an empty cache silences every ring). The Android layer implements it
 * (prefs); [DoorCodec] gives it a string form if that is convenient.
 */
interface DoorsCacheStore {
    fun load(): List<Door>
    fun save(doors: List<Door>)
}

class InMemoryDoorsCacheStore(initial: List<Door> = emptyList()) : DoorsCacheStore {
    var saved: List<Door> = initial
        private set
    var saves = 0
        private set

    override fun load(): List<Door> = saved
    override fun save(doors: List<Door>) {
        saved = doors
        saves++
    }
}

/** `door_id <-> door_ref` (and the endpoint id), refreshed on every connect and when a calls event names a door it does not know. */
class DoorsCache(private val store: DoorsCacheStore) {
    @Volatile
    private var doors: List<Door> = try {
        store.load()
    } catch (e: Exception) {
        emptyList()
    }

    val all: List<Door> get() = doors

    /** Replaces the cache with a fresh `portico/doors` list; persists only when it changed. */
    fun replaceAll(fresh: List<Door>) {
        if (fresh == doors) return
        doors = fresh
        store.save(fresh)
    }

    fun doorRefFor(doorId: String): String? = doors.firstOrNull { it.doorId == doorId }?.doorRef
    fun doorIdFor(doorRef: String): String? = doors.firstOrNull { it.doorRef == doorRef }?.doorId
    fun endpointIdFor(doorRef: String): String? = doors.firstOrNull { it.doorRef == doorRef }?.endpointId

    /** False only when the server said this door cannot be answered from a phone; an unknown door or a silent server is true. */
    fun answerableFor(doorRef: String): Boolean = doors.firstOrNull { it.doorRef == doorRef }?.answerable ?: true

    /** True when [doorId] is in the cache. */
    fun knowsDoorId(doorId: String): Boolean = doors.any { it.doorId == doorId }

    /** The door's display name for [doorRef], if HA sent one. */
    fun nameFor(doorRef: String): String? = doors.firstOrNull { it.doorRef == doorRef }?.name?.takeIf { it.isNotBlank() }

    /** The `door_ref`s a push may legitimately name (the decision function's `knownDoorRefs`). */
    fun knownDoorRefs(): Set<String> = doors.mapTo(LinkedHashSet()) { it.doorRef }
}

/**
 * A stable string form of the cache for a prefs-backed [DoorsCacheStore]. Never throws on decode. A list written
 * by contract 2's app (`device_id`/`entry_id` keys, no `door_ref`) decodes to nothing, so an old cache is dropped,
 * never half-read: its ids are not push v2's `door_ref`s.
 */
object DoorCodec {
    fun encode(doors: List<Door>): String = buildJsonArray {
        for (d in doors) add(buildJsonObject {
            put("door_id", d.doorId)
            put("door_ref", d.doorRef)
            if (d.endpointId != null) put("endpoint_id", d.endpointId)
            if (d.driver != null) put("driver", d.driver)
            if (d.name != null) put("name", d.name)
            if (!d.answerable) put("answerable", false)
        })
    }.toString()

    fun decode(text: String?): List<Door> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Door(
                doorId = o.str("door_id") ?: return@mapNotNull null,
                doorRef = o.str("door_ref") ?: return@mapNotNull null,
                endpointId = o.str("endpoint_id"),
                driver = o.str("driver"),
                name = o.str("name"),
                answerable = (o["answerable"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
    }
}
