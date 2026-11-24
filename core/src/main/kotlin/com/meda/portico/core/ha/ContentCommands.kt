package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * What the phone shows besides a call: its pages, cameras and buttons, built by the Portico integration
 * (portico-ha content design §2) and fetched with `portico/content/get`. Contract 3's per-endpoint document.
 *
 * | command                      | body                                | ok result |
 * | `portico/content/get`        | `endpoint?` (the phone sends none)  | `{endpoint, kind, rev, home_name?, show_fps?, pages: [{id, label, blocks, cameras, actions}], callers: {...}, roster: [...]}` |
 * | `portico/content/run`        | `action_id`, `endpoint?`            | `{ran: true}`; for a `call` button also `call_id` and `media` (see [PlaceCommands]) |
 * | `portico/content/snapshot`   | `camera_id`, `width?`, `endpoint?`  | `{content_type, content}` (base64) |
 * | `portico/content/subscribe`  | `endpoint?`                         | `null`, then `{type: "content_changed", endpoint, rev}` events |
 *
 * The phone never names an `endpoint`: an enrolled phone gets its own (naming another is `unauthorized`), so the
 * builders take it only for the kiosk card's sake and the app passes null. The flat `phone`/`cameras`/`actions` of
 * contract 2 and its `profile` and `pages` parameters are gone (the server refuses them with `invalid_format`).
 *
 * The profile IS the grant: the phone names an action or camera **id** from its own document (a caller's camera
 * `c-...` ids included) and never a service or an entity, so nothing here can ask HA to run anything else.
 */
data class ContentCamera(
    val id: String,
    val label: String,
    /** HA's own name for the camera (shown under the label when it differs). */
    val name: String?,
    val available: Boolean,
    /** Blocks (DEV-658): the size HA resolved for this tile; null when the server sent none or one this build does not know. */
    val size: CameraSize? = null,
    /**
     * Live video (DEV-713): the document asks for real video on this tile. The server sends `live: true` only when set, so absent,
     * `false` and any value that is not a JSON boolean all read as false. Parsed and cached, nothing reads it yet (stage S0, dark).
     */
    val live: Boolean = false,
    /**
     * Away-from-home video (DEV-713 S1b): where go2rtc's RTSP streams for this tile are, when the server gave them (a live Frigate-backed tile,
     * once the admin set `live_rtsp_base`). Read strictly ([LiveRtsp.parse]): anything but `rtsp://host[:port]/name` reads as absent.
     */
    val liveRtsp: LiveRtsp? = null,
    /**
     * Portico camera variants (DEV-768 P1/P3): the server found a small stream for this camera (`small: true`, sent only when set). Strict like
     * [live]: absent, `false` and anything that is not a JSON boolean read as false, so a camera of an older server offers one stream only.
     */
    val small: Boolean = false,
    /** 0.6.54: the stream heights the server measured (`streams.big.height` / `streams.small.height`), null for a variant it did not send. Strict: only a positive JSON integer counts. */
    val bigHeight: Int? = null,
    val smallHeight: Int? = null,
)

/** How wide a camera tile is drawn: [BIG] the full width, [SMALL] half of it. A value a later server adds is read as absent (null). */
enum class CameraSize(val wire: String) {
    BIG("big"),
    SMALL("small"),
    ;

    companion object {
        fun of(wire: String?): CameraSize? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a call button rings (portico-ha house calls design 14.1): `dashboard`, `door` or `phone` and the server's opaque endpoint id. The phone never sends it back. */
data class CallTarget(val type: String, val id: String)

data class ContentAction(
    val id: String,
    val label: String,
    /** `portico_open`, `lock_open`, `button_press`, `script_run` or `scene_activate`; others are shown but never special-cased. */
    val kind: String,
    val icon: String?,
    /** Ask on the phone before running it. The plugin does not rely on this; it is the user's safety net. */
    val confirm: Boolean,
    /** HA's name for what it uses (the lock, the script, the door). */
    val name: String?,
    val available: Boolean,
    /** `call` buttons only: who it rings. Null for every other kind. */
    val target: CallTarget? = null,
    /**
     * Why [available] is false, as the server sent it; null when available or unsaid. A `call` button: `not_available`,
     * `unknown_target`, `offline`, `push_only`, `dnd`, `busy`, `receive_off`, `disabled`. A `talk` button (blocks):
     * `talk_off`, `no_door`, `not_connected`, `busy`, `phone_only`.
     */
    val reason: String? = null,
    /** Blocks: a palette name (`blue`, ...) as sent; whether this build knows it is [com.meda.portico.core.content.ButtonPalette]'s business, not the parser's. */
    val color: String? = null,
    /** Blocks: false hides the text and leaves the icon (the server only allows that with a usable icon). Absent is true. */
    val showLabel: Boolean = true,
    /**
     * `call` buttons only (DEV-695): the server said `gate_call: true`, i.e. a press of this button goes through Talk's path to the
     * gate. False when the key is absent: an older server, or a button that is not (or, with the dial off, not now) the gate call.
     * [com.meda.portico.core.content.GateCall] reads it before the roster.
     */
    val gateCall: Boolean = false,
    /**
     * `talk` buttons only: a Talk element's own door (DEV-518), the socket door id its audio attaches to. It wins over the block's
     * `door` ([ContentBlock.door]); a Talk element placed in a block with no door has no other. Null for every other kind.
     */
    val door: String? = null,
) {
    val isCall: Boolean get() = kind == KIND_CALL
    val isTalk: Boolean get() = kind == KIND_TALK

    companion object {
        const val KIND_CALL = "call"

        /** Blocks: talk to the block's door. Ready only when the server says `available` (`TalkGate`); today it never does. */
        const val KIND_TALK = "talk"
    }
}

/**
 * Blocks (DEV-658): a title over a camera grid and a set of buttons. [door] is the Portico device the block's Talk and
 * open-door buttons act on and [doorAvailable] its live state (null: unsaid); Talk takes its door from here.
 */
data class ContentBlock(
    val id: String,
    /** As sent; the screen cleans it. Blank or absent: no heading is drawn. */
    val title: String?,
    val cameras: List<ContentCamera>,
    val actions: List<ContentAction>,
    val door: String? = null,
    val doorAvailable: Boolean? = null,
)

/**
 * One page of the document: a label over blocks. [blocks] is null for a page whose server sends none (an older
 * integration): [cameras] and [actions] are then the page's whole content and [blockList] wraps them into ONE block (the
 * auto size rule then applies, [CameraSizes]; 4.3 of the blocks design). When [blocks] is present, [cameras] and
 * [actions] are all of the blocks' items in block order (every kind: the document is the grant), and the server's own
 * flat copy beside the blocks is ignored.
 */
data class ContentPage(
    val id: String,
    val label: String,
    val cameras: List<ContentCamera>,
    val actions: List<ContentAction>,
    val blocks: List<ContentBlock>? = null,
) {
    /** The blocks Home draws: the server's, or the one made from the flat items (untitled: the page label is the top line). */
    val blockList: List<ContentBlock>
        get() = blocks ?: if (cameras.isEmpty() && actions.isEmpty()) emptyList() else listOf(ContentBlock(id, null, cameras, actions))

    companion object {
        /** A page made from its blocks: the flat items are derived from them, so the two can never disagree. */
        fun ofBlocks(id: String, label: String, blocks: List<ContentBlock>) =
            ContentPage(id, label, blocks.flatMap { it.cameras }, blocks.flatMap { it.actions }, blocks)
    }
}

/**
 * The auto size rule (design D5), owned by the phone for a page with no `blocks` and for a size it cannot read: the first
 * camera of a block is big, the others small. A size the server did send and this build knows is kept.
 */
object CameraSizes {
    fun effective(cameras: List<ContentCamera>): List<CameraSize> =
        cameras.mapIndexed { i, c -> c.size ?: if (i == 0) CameraSize.BIG else CameraSize.SMALL }
}

/**
 * What the screen shows for a caller (a door, another screen or `*`, the fallback) while it rings: its camera stills
 * and the buttons. Keyed in [ContentProfile.callers] by the caller's endpoint id (`Door.endpointId`), `*` for the rest.
 * [name] and [kind] are null for `*`. A2 draws these.
 */
data class ContentCaller(
    val name: String?,
    val kind: String?,
    val cameras: List<ContentCamera>,
    val ringButtons: List<ContentAction>,
    val callButtons: List<ContentAction>,
)

/**
 * One line of the call roster: who this endpoint may ring. [actionId] is what `portico/content/run` takes to place the
 * call; [reason] says why it is not [available] (`not_available`, ...). Parsed for M7; nothing in A1 acts on it.
 */
data class RosterEntry(
    val actionId: String,
    val id: String,
    val kind: String,
    val name: String?,
    val available: Boolean,
    val reason: String?,
    /** Everyone's `count` (how many phones a press would ring); null on every other row. */
    val count: Int? = null,
    /**
     * A `door` row only: the server sent `can_dial: true`, i.e. this door's driver reports `CanDial` now (the gate, M7 3f).
     * The key is absent otherwise. It is the one wire signal that a `call` button aimed at this door is placed by Talk's path
     * (`GateCall`); live state, never part of the document `rev`.
     */
    val canDial: Boolean = false,
)

/** A `{type: "content_changed", endpoint, rev}` event: [endpoint] and [rev] are null when the server names none (an admin's "every change"). */
data class ContentChange(val endpoint: String?, val rev: String?)

/**
 * One endpoint's document as HA resolved it at [rev]. Never anything secret: names, ids and availability only.
 *
 * [cameras] and [actions] are the FIRST page's (kept for older readers; Home draws [pageList]); [pages] and [callers]
 * are drawn by A2 (the pager, the ring and in-call screens) and [roster] is for M7 (additive: a field a later server
 * adds is tolerated and not kept).
 * [phone] is a leftover of contract 2 (the reply names no phone now): empty from a contract-3 server, it keeps a saved
 * profile of the older shape readable until the next fetch. [endpoint] and [kind] (`phone` / `dashboard`) come from the reply.
 */
data class ContentProfile(
    val phone: String,
    val rev: String,
    val cameras: List<ContentCamera>,
    val actions: List<ContentAction>,
    /** The home's name from the plugin (`home_name`), already cleaned by [HomeName.clean]; null when the plugin sets none. */
    val homeName: String? = null,
    val endpoint: String? = null,
    val kind: String? = null,
    val pages: List<ContentPage> = emptyList(),
    val callers: Map<String, ContentCaller> = emptyMap(),
    val roster: List<RosterEntry> = emptyList(),
    /**
     * Live video (DEV-713): the document-level `show_fps`, true when the household wants the frame rate shown on live tiles. The
     * server sends it only when true; absent or anything but a JSON boolean reads as false. Nothing reads it yet (stage S0, dark).
     */
    val showFps: Boolean = false,
    /**
     * DEV-807 (Panel mode): `phone_endpoint`, the id the panel is called by for house calls (its phone half); null from a server that
     * does not say, which for a panel means "this server does not support panel house calls" (design section 5 item 3 R8).
     */
    val phoneEndpoint: String? = null,
    /** DEV-807: `house_calls`, the server's "house calls are on for this login"; null when the server does not send it (design R9). */
    val houseCalls: Boolean? = null,
    /** DEV-807: `screens`, the slugs of the screens served to this login. Nothing reads it yet (slice 2 names the screen). */
    val screens: List<String> = emptyList(),
    /**
     * DEV-807 (slice 2): the screen's `wake.doors`, the door references that ring this panel (a screen's own document only). null = the
     * server sent no `wake` (absent: the panel rings for every door); empty = a panel set to ring for no door.
     */
    val wake: List<String>? = null,
) {
    /**
     * The pages Home draws: [pages], or, for a profile that carries only the flat first-page fields (a test's, or one
     * built by hand), one page made from them. No page and no flat content: none.
     */
    val pageList: List<ContentPage>
        get() = when {
            pages.isNotEmpty() -> pages
            cameras.isNotEmpty() || actions.isNotEmpty() -> listOf(ContentPage("", "", cameras, actions))
            else -> emptyList()
        }

    /** Nothing to draw on any page (a caller's buttons do not count: the call screens draw those). */
    val isEmpty: Boolean get() = pageList.all { it.cameras.isEmpty() && it.actions.isEmpty() }

    /** A camera of any page or of any caller, by id (the ids of the document are the grant). */
    fun findCamera(id: String): ContentCamera? =
        pageList.firstNotNullOfOrNull { p -> p.cameras.firstOrNull { it.id == id } }
            ?: callers.values.firstNotNullOfOrNull { c -> c.cameras.firstOrNull { it.id == id } }

    /** An action of any page or any caller's ring or call buttons, by id. */
    fun findAction(id: String): ContentAction? =
        pageList.firstNotNullOfOrNull { p -> p.actions.firstOrNull { it.id == id } }
            ?: callers.values.firstNotNullOfOrNull { c -> (c.ringButtons + c.callButtons).firstOrNull { it.id == id } }

    /** Every camera id the document names: a picture of any other camera is not worth keeping. */
    fun cameraIds(): Set<String> {
        val out = LinkedHashSet<String>()
        for (p in pageList) for (c in p.cameras) out.add(c.id)
        for (c in callers.values) for (cam in c.cameras) out.add(cam.id)
        return out
    }

    companion object {
        val EMPTY = ContentProfile("", "", emptyList(), emptyList())
    }
}

/**
 * The home's name as the Home screen may show it: the plugin sends an optional `home_name` (trimmed, at most 40
 * characters), and this is the phone's own guard so that a stray byte can never reach a TextView.
 */
object HomeName {
    const val MAX = 40

    /** Control, line-separator and paragraph-separator characters become a space; format characters (bidi marks) go; blank is null. */
    fun clean(raw: String?): String? = Label.clean(raw, MAX)
}

/**
 * Any text the server sends that a screen draws (a page, a camera, a button, a door): plain text only. A TextView never
 * interprets markup, so nothing is "escaped"; what the phone guards against is a stray control or bidi character, a
 * blank where a name should be and a label long enough to push the buttons off the screen.
 */
object Label {
    /** A camera, button or door name on a tile. */
    const val MAX = 60

    /** A page's tab. */
    const val TAB_MAX = 24

    /** Control, line-separator and paragraph-separator characters become a space; format characters (bidi marks) go; whitespace collapses; at most [max] code points; blank is null. */
    fun clean(raw: String?, max: Int): String? {
        if (raw == null) return null
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.FORMAT -> {}
                Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> sb.append(' ')
                Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE -> {}
                else -> sb.appendCodePoint(cp)
            }
        }
        var text = sb.toString().replace(Regex("\\s+"), " ").trim()
        if (text.codePointCount(0, text.length) > max) {
            text = text.substring(0, text.offsetByCodePoints(0, max)).trimEnd()
        }
        return text.ifEmpty { null }
    }

    /** [clean] of [raw] or, when nothing is left, of [fallback]; the empty string when both are blank. */
    fun text(raw: String?, max: Int = MAX, fallback: String? = null): String = clean(raw, max) ?: clean(fallback, max) ?: ""
}

object ContentCommands {
    const val GET = "portico/content/get"
    const val RUN = "portico/content/run"
    const val SNAPSHOT = "portico/content/snapshot"

    const val SUBSCRIBE = Handshake.CONTENT_SUBSCRIBE

    /** `portico/content/get`. [endpoint] null (the phone's own, what the app always sends) leaves it out. */
    fun get(endpoint: String? = null): JsonObject {
        require(endpoint == null || endpoint.length in 1..64) { "endpoint must be 1-64 chars" }
        return buildJsonObject { if (endpoint != null) put("endpoint", endpoint) }
    }

    /** `portico/content/subscribe`. [endpoint] null (what the app always sends: a phone hears its own) leaves it out. */
    fun subscribe(endpoint: String? = null): JsonObject = get(endpoint)

    fun run(actionId: String, endpoint: String? = null): JsonObject {
        require(actionId.length in 1..64) { "action_id must be 1-64 chars" }
        require(endpoint == null || endpoint.length in 1..64) { "endpoint must be 1-64 chars" }
        return buildJsonObject {
            put("action_id", actionId)
            if (endpoint != null) put("endpoint", endpoint)
        }
    }

    /** The server's `SNAPSHOT_WIDTH_MIN` / `SNAPSHOT_WIDTH_MAX` (blocks design question 7: the cap went from 1280 to 1920). */
    const val SNAPSHOT_WIDTH_MIN = 64
    const val SNAPSHOT_WIDTH_MAX = 1920

    /** [width] null leaves it out: the server's default is 640 (it allows [SNAPSHOT_WIDTH_MIN] to [SNAPSHOT_WIDTH_MAX]). */
    fun snapshot(cameraId: String, width: Int? = null, endpoint: String? = null): JsonObject {
        require(cameraId.length in 1..64) { "camera_id must be 1-64 chars" }
        require(width == null || width in SNAPSHOT_WIDTH_MIN..SNAPSHOT_WIDTH_MAX) { "width must be $SNAPSHOT_WIDTH_MIN-$SNAPSHOT_WIDTH_MAX" }
        require(endpoint == null || endpoint.length in 1..64) { "endpoint must be 1-64 chars" }
        return buildJsonObject {
            put("camera_id", cameraId)
            if (width != null) put("width", width)
            if (endpoint != null) put("endpoint", endpoint)
        }
    }

    // ---- results -------------------------------------------------------------------------

    enum class ErrorKind(val wire: String?) {
        UNAUTHORIZED("unauthorized"),
        UNKNOWN_ACTION("unknown_action"),
        UNKNOWN_CAMERA("unknown_camera"),

        /** An administrator (or a kiosk card) named an endpoint the integration does not have. The phone never names one. */
        UNKNOWN_ENDPOINT("unknown_endpoint"),
        RATE_LIMITED("rate_limited"),
        UNAVAILABLE("unavailable"),

        // A Talk button's refusals (blocks B7a). `not_connected` is the GATE not being connected to Home Assistant, not this phone's own link.
        NOT_ALLOWED("not_allowed"),
        NOT_AVAILABLE("not_available"),
        NO_DOOR("no_door"),
        NOT_CONNECTED("not_connected"),
        BUSY("busy"),
        TOO_LARGE("too_large"),
        FAILED("failed"),
        INVALID_FORMAT("invalid_format"),

        /** An integration without the content commands (older portico-ha). */
        UNKNOWN_COMMAND("unknown_command"),

        /** Any other code, including a Portico device's own error (`not_supported`, ...). */
        OTHER(null),
        ;

        companion object {
            fun of(code: String): ErrorKind = entries.firstOrNull { it.wire == code } ?: OTHER
        }
    }

    sealed interface Outcome {
        data class Profile(val profile: ContentProfile) : Outcome
        data object Ran : Outcome
        class Snapshot(val contentType: String, val bytes: ByteArray) : Outcome {
            override fun toString(): String = "Snapshot($contentType, ${bytes.size} bytes)"
        }
        data class Failed(val kind: ErrorKind, val code: String, val message: String) : Outcome

        /** The socket closed before the result arrived. */
        data object Lost : Outcome
    }

    private fun failed(r: HaInbound.Result): Outcome.Failed {
        val e = r.error ?: HaError("unknown_error", "")
        return Outcome.Failed(ErrorKind.of(e.code), e.code, e.message)
    }

    private fun malformed(what: String) = Outcome.Failed(ErrorKind.OTHER, "malformed_result", what)

    fun parseGet(r: HaInbound.Result): Outcome {
        if (!r.success) return failed(r)
        val o = r.result.asObject() ?: return malformed("content result is not an object")
        val rev = o.str("rev") ?: return malformed("content result without rev")
        val pages = (o["pages"] as? JsonArray)?.mapNotNull { el -> page(el) } ?: emptyList()
        val callers = (o["callers"] as? JsonObject)?.let { map ->
            val out = LinkedHashMap<String, ContentCaller>()
            for ((key, el) in map) {
                val c = el as? JsonObject ?: continue
                out[key] = ContentCaller(c.str("name"), c.str("kind"), cameras(c["cameras"]), actions(c["ring_buttons"]), actions(c["call_buttons"]))
            }
            out
        } ?: emptyMap()
        val roster = (o["roster"] as? JsonArray)?.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            RosterEntry(
                actionId = e.str("action_id") ?: return@mapNotNull null,
                id = e.str("id") ?: return@mapNotNull null,
                kind = e.str("kind") ?: return@mapNotNull null,
                name = e.str("name"),
                available = e.bool("available") ?: false,
                reason = e.str("reason"),
                count = e.int("count"),
                canDial = e.bool("can_dial") == true,
            )
        } ?: emptyList()
        val first = pages.firstOrNull()
        return Outcome.Profile(
            ContentProfile(
                phone = o.str("phone") ?: "",
                rev = rev,
                cameras = first?.cameras ?: emptyList(),
                actions = first?.actions ?: emptyList(),
                homeName = HomeName.clean(o.str("home_name")),
                endpoint = o.str("endpoint"),
                kind = o.str("kind"),
                pages = pages,
                callers = callers,
                roster = roster,
                showFps = o.bool("show_fps") == true,
                phoneEndpoint = o.str("phone_endpoint"),
                houseCalls = o.bool("house_calls"),
                screens = (o["screens"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
                wake = wakeDoors(o["wake"]),
            ),
        )
    }

    /** `wake.doors` of a screen's document: null when `wake` is absent or not an object (the wire is additive), else the door references. */
    private fun wakeDoors(el: JsonElement?): List<String>? {
        val w = el as? JsonObject ?: return null
        val doors = w["doors"] as? JsonArray ?: return emptyList()
        return doors.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { d -> d.isNotBlank() && d.length <= 256 } }
    }

    /** One page of `pages`: its `blocks` when the server sends them (the flat copy beside them is ignored), else its flat lists. */
    private fun page(el: JsonElement): ContentPage? {
        val p = el as? JsonObject ?: return null
        val id = p.str("id") ?: return null
        val label = p.str("label") ?: return null
        val blocks = p["blocks"] as? JsonArray ?: return ContentPage(id, label, cameras(p["cameras"]), actions(p["actions"]))
        return ContentPage.ofBlocks(
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

    private fun cameras(el: JsonElement?): List<ContentCamera> = (el as? JsonArray)?.mapNotNull { c ->
        val cam = c as? JsonObject ?: return@mapNotNull null
        ContentCamera(
            id = cam.str("id") ?: return@mapNotNull null,
            label = cam.str("label") ?: return@mapNotNull null,
            name = cam.str("name"),
            available = cam.bool("available") ?: false,
            size = CameraSize.of(cam.str("size")),
            live = cam.bool("live") == true,
            liveRtsp = LiveRtsp.parse(cam["live_rtsp"]),
            small = cam.bool("small") == true,
            bigHeight = streamHeight(cam, "big"),
            smallHeight = streamHeight(cam, "small"),
        )
    } ?: emptyList()

    /** `streams.<variant>.height` as a positive JSON integer; anything else (absent, a string, a float, zero, an unknown shape) is null. 0.6.59: the WIRE parser reads it too (0.6.54 only taught the cache decoder). */
    internal fun streamHeight(cam: JsonObject, variant: String): Int? =
        (((cam["streams"] as? JsonObject)?.get(variant)) as? JsonObject)?.int("height")?.takeIf { it in 1..100_000 }

    private fun actions(el: JsonElement?): List<ContentAction> = (el as? JsonArray)?.mapNotNull { x ->
        val a = x as? JsonObject ?: return@mapNotNull null
        val kind = a.str("kind") ?: return@mapNotNull null
        val call = kind == ContentAction.KIND_CALL
        val target = (a["target"] as? JsonObject)?.let { t ->
            val type = t.str("type")
            val tid = t.str("id")
            if (type != null && tid != null) CallTarget(type, tid) else null
        }
        // A call button never asks first, whatever the flag says (decision CB-9), and neither does a talk button
        // (blocks, CB-9); an unreadable flag on any other kind asks: the safe side.
        val confirm = if (call || kind == ContentAction.KIND_TALK) false else a.bool("confirm") ?: true
        ContentAction(
            id = a.str("id") ?: return@mapNotNull null,
            label = a.str("label") ?: return@mapNotNull null,
            kind = kind,
            icon = a.str("icon"),
            confirm = confirm,
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

    fun parseRun(r: HaInbound.Result): Outcome {
        if (!r.success) return failed(r)
        return if (r.result.asObject()?.bool("ran") == true) Outcome.Ran else malformed("run result without ran")
    }

    fun parseSnapshot(r: HaInbound.Result): Outcome {
        if (!r.success) return failed(r)
        val o = r.result.asObject() ?: return malformed("snapshot result is not an object")
        val type = o.str("content_type") ?: return malformed("snapshot without content_type")
        val text = o.str("content") ?: return malformed("snapshot without content")
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return malformed("snapshot content is not base64")
        }
        return Outcome.Snapshot(type, bytes)
    }

    /**
     * A `{type: "content_changed", endpoint, rev}` event of `portico/content/subscribe`: null when [event] is not one.
     * An unusable (absent, null, blank) `endpoint` or `rev` is null in the [ContentChange]: the caller refetches to be safe.
     */
    fun parseChanged(event: JsonObject): ContentChange? =
        if (event.str("type") == "content_changed") {
            ContentChange(event.str("endpoint")?.ifBlank { null }, event.str("rev")?.ifBlank { null })
        } else {
            null
        }
}
