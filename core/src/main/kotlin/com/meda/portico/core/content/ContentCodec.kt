package com.meda.portico.core.content

import com.meda.portico.core.ha.CallTarget
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HomeName
import com.meda.portico.core.ha.LiveRtsp
import com.meda.portico.core.ha.RosterEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A stable string form of the last [ContentProfile] for a prefs-backed [ContentStore]: the whole resolved document
 * (pages, callers, roster) as ONE JSON blob, because the phone never edits it. Names, ids and flags only; never
 * anything secret. Decoding never throws: a damaged value, or one of another version, is "nothing cached", and the
 * next `portico/content/get` fills it again.
 *
 * Version 3 (blocks, B5): a page holds its `blocks` (title, door, cameras with `size`, buttons with `color` and
 * `show_label`) when the server sent them, else its flat `cameras` and `actions` (an older server). Version 2 (A2) held
 * flat pages only and version 1 was contract 2's flat form (`phone`, `cameras`, `actions`); neither is half-read: an
 * older blob is dropped on the first read and the next `portico/content/get` fills the cache again.
 */
object ContentCodec {
    private const val VERSION = 3

    fun encode(p: ContentProfile): String = buildJsonObject {
        put("v", VERSION)
        put("rev", p.rev)
        if (p.homeName != null) put("home_name", p.homeName)
        if (p.showFps) put("show_fps", true)
        if (p.endpoint != null) put("endpoint", p.endpoint)
        if (p.kind != null) put("kind", p.kind)
        if (p.phoneEndpoint != null) put("phone_endpoint", p.phoneEndpoint)
        if (p.houseCalls != null) put("house_calls", p.houseCalls)
        if (p.screens.isNotEmpty()) put("screens", buildJsonArray { for (x in p.screens) add(JsonPrimitive(x)) })
        if (p.wake != null) put("wake", buildJsonArray { for (x in p.wake) add(JsonPrimitive(x)) })
        put("pages", buildJsonArray {
            for (page in p.pages) add(buildJsonObject {
                put("id", page.id)
                put("label", page.label)
                if (page.blocks == null) {
                    put("cameras", cameras(page.cameras))
                    put("actions", actions(page.actions))
                } else {
                    put("blocks", buildJsonArray {
                        for (b in page.blocks) add(buildJsonObject {
                            put("id", b.id)
                            if (b.title != null) put("title", b.title)
                            if (b.door != null) put("door", b.door)
                            if (b.doorAvailable != null) put("door_available", b.doorAvailable)
                            put("cameras", cameras(b.cameras))
                            put("actions", actions(b.actions))
                        })
                    })
                }
            })
        })
        put("callers", buildJsonObject {
            for ((key, c) in p.callers) put(key, buildJsonObject {
                if (c.name != null) put("name", c.name)
                if (c.kind != null) put("kind", c.kind)
                put("cameras", cameras(c.cameras))
                put("ring_buttons", actions(c.ringButtons))
                put("call_buttons", actions(c.callButtons))
            })
        })
        put("roster", buildJsonArray {
            for (r in p.roster) add(buildJsonObject {
                put("action_id", r.actionId)
                put("id", r.id)
                put("kind", r.kind)
                if (r.name != null) put("name", r.name)
                put("available", r.available)
                if (r.reason != null) put("reason", r.reason)
                if (r.count != null) put("count", r.count)
                if (r.canDial) put("can_dial", true)
            })
        })
    }.toString()

    private fun cameras(list: List<ContentCamera>): JsonArray = buildJsonArray {
        for (c in list) add(buildJsonObject {
            put("id", c.id)
            put("label", c.label)
            if (c.name != null) put("name", c.name)
            put("available", c.available)
            if (c.size != null) put("size", c.size.wire)
            if (c.live) put("live", true)
            if (c.liveRtsp != null) put("live_rtsp", buildJsonObject {
                put("main", c.liveRtsp.main)
                if (c.liveRtsp.sub != null) put("sub", c.liveRtsp.sub)
            })
            if (c.small) put("small", true)
            if (c.bigHeight != null || c.smallHeight != null) put("streams", buildJsonObject {
                if (c.bigHeight != null) put("big", buildJsonObject { put("height", c.bigHeight) })
                if (c.smallHeight != null) put("small", buildJsonObject { put("height", c.smallHeight) })
            })
        })
    }

    private fun actions(list: List<ContentAction>): JsonArray = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id)
            put("label", a.label)
            put("kind", a.kind)
            if (a.icon != null) put("icon", a.icon)
            put("confirm", a.confirm)
            if (a.name != null) put("name", a.name)
            put("available", a.available)
            if (a.target != null) put("target", buildJsonObject {
                put("type", a.target.type)
                put("id", a.target.id)
            })
            if (a.reason != null) put("reason", a.reason)
            if (a.color != null) put("color", a.color)
            if (!a.showLabel) put("show_label", false)
            if (a.gateCall) put("gate_call", true)
            if (a.door != null) put("door", a.door)
        })
    }

    fun decode(text: String?): ContentProfile? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
            if (o.str("v") != VERSION.toString()) return null
            val pages = (o["pages"] as? JsonArray)?.mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                val id = p.str("id") ?: return@mapNotNull null
                val label = p.str("label") ?: return@mapNotNull null
                val blocks = p["blocks"] as? JsonArray
                if (blocks == null) {
                    ContentPage(id, label, cameras(p["cameras"]), actions(p["actions"]))
                } else {
                    ContentPage.ofBlocks(
                        id, label,
                        blocks.mapNotNull { b ->
                            val o = b as? JsonObject ?: return@mapNotNull null
                            ContentBlock(
                                id = o.str("id") ?: return@mapNotNull null,
                                title = o.str("title"),
                                cameras = cameras(o["cameras"]),
                                actions = actions(o["actions"]),
                                door = o.str("door"),
                                doorAvailable = o.bool("door_available"),
                            )
                        },
                    )
                }
            } ?: emptyList()
            val callers = LinkedHashMap<String, ContentCaller>()
            (o["callers"] as? JsonObject)?.forEach { (key, el) ->
                val c = el as? JsonObject ?: return@forEach
                callers[key] = ContentCaller(c.str("name"), c.str("kind"), cameras(c["cameras"]), actions(c["ring_buttons"]), actions(c["call_buttons"]))
            }
            val roster = (o["roster"] as? JsonArray)?.mapNotNull { el ->
                val r = el as? JsonObject ?: return@mapNotNull null
                RosterEntry(
                    actionId = r.str("action_id") ?: return@mapNotNull null,
                    id = r.str("id") ?: return@mapNotNull null,
                    kind = r.str("kind") ?: return@mapNotNull null,
                    name = r.str("name"),
                    available = r.bool("available") ?: false,
                    reason = r.str("reason"),
                    count = r.int("count"),
                    canDial = r.bool("can_dial") == true,
                )
            } ?: emptyList()
            val first = pages.firstOrNull()
            ContentProfile(
                phone = "",
                rev = o.str("rev") ?: return null,
                cameras = first?.cameras ?: emptyList(),
                actions = first?.actions ?: emptyList(),
                homeName = HomeName.clean((o["home_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content),
                endpoint = o.str("endpoint"),
                kind = o.str("kind"),
                pages = pages,
                callers = callers,
                roster = roster,
                showFps = o.strictBool("show_fps"),
                phoneEndpoint = o.str("phone_endpoint"),
                houseCalls = o.bool("house_calls"),
                screens = (o["screens"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
                wake = (o["wake"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun cameras(el: kotlinx.serialization.json.JsonElement?): List<ContentCamera> = (el as? JsonArray)?.mapNotNull { x ->
        val c = x as? JsonObject ?: return@mapNotNull null
        ContentCamera(c.str("id") ?: return@mapNotNull null, c.str("label") ?: return@mapNotNull null, c.str("name"), c.bool("available") ?: false, CameraSize.of(c.str("size")), c.strictBool("live"), LiveRtsp.parse(c["live_rtsp"]), c.strictBool("small"), streamHeight(c, "big"), streamHeight(c, "small"))
    } ?: emptyList()

    /** `streams.<variant>.height` as a positive JSON integer; anything else (absent, a string, a float, zero, an unknown shape) is null. */
    private fun streamHeight(c: JsonObject, variant: String): Int? =
        (((c["streams"] as? JsonObject)?.get(variant)) as? JsonObject)?.int("height")?.takeIf { it in 1..100_000 }

    private fun actions(el: kotlinx.serialization.json.JsonElement?): List<ContentAction> = (el as? JsonArray)?.mapNotNull { x ->
        val a = x as? JsonObject ?: return@mapNotNull null
        val kind = a.str("kind") ?: return@mapNotNull null
        val call = kind == ContentAction.KIND_CALL
        val target = (a["target"] as? JsonObject)?.let { t ->
            val type = t.str("type")
            val id = t.str("id")
            if (type != null && id != null) CallTarget(type, id) else null
        }
        ContentAction(
            id = a.str("id") ?: return@mapNotNull null,
            label = a.str("label") ?: return@mapNotNull null,
            kind = kind,
            icon = a.str("icon"),
            // An unreadable flag asks first (the safe side); a call or a talk never asks (CB-9).
            confirm = if (call || kind == ContentAction.KIND_TALK) false else a.bool("confirm") ?: true,
            name = a.str("name"),
            available = a.bool("available") ?: false,
            target = if (call) target else null,
            reason = if (call || kind == ContentAction.KIND_TALK) a.str("reason") else null,
            color = a.str("color"),
            showLabel = a.bool("show_label") ?: true,
            gateCall = call && a.bool("gate_call") == true,
            door = if (kind == ContentAction.KIND_TALK) a.str("door")?.takeIf { it.isNotEmpty() } else null,
        )
    } ?: emptyList()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }
    /** `true` only for a JSON boolean true (the new live fields): a string "true", a number or null read as false, like the wire parser. */
    private fun JsonObject.strictBool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
}
