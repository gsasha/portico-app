package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.push.Redact

/** How a camera viewport is laid out (DEV-768 P3): a half-width tile, a full-width tile, or the whole window. */
enum class ViewLayout(val word: String) {
    SMALL("small"),
    BIG("big"),
    FULLSCREEN("fullscreen"),

    /** Tablet (panel) mode only: the big tile with up to two other cameras of its block, small, to its right ([CameraGrid.partial]). Last, so the ordinals of the others stay. */
    PARTIAL("partial"),
    ;

    companion object {
        fun of(size: CameraSize, fullscreen: Boolean, partial: Boolean = false): ViewLayout =
            if (fullscreen) FULLSCREEN else if (partial) PARTIAL else if (size == CameraSize.BIG) BIG else SMALL
    }
}

/** Which video stream a viewport asks for: [AUTO] (what the phone resolves, see [CameraViewport.autoStream]) or a stream picked for this view. */
enum class StreamPick(val word: String) {
    AUTO("auto"),
    BIG("big"),
    SMALL("small"),

    /** 0.6.53, menu rows only (never a viewport's stream pick): the stills slideshow, no video. */
    STILLS("stills"),

    /** 0.6.53, menu row only: the view is turned off. */
    OFF("off"),
    ;

    companion object {
        fun of(word: String?): StreamPick? = entries.firstOrNull { it.word == word }
    }
}

/** One row of the resize menu. */
data class ResizeItem(val layout: ViewLayout, val checked: Boolean)

/**
 * One row of the settings menu ("Video size"). [resolvesTo] is set on Auto only: what it picks now. [quality] is the YouTube-style name of the
 * row's real vertical resolution ("720p", see [StreamLabels]); on Auto it is the quality of what Auto resolves to. Null while that stream's
 * height has not been seen yet (the app then words it "High" / "Low" / plain "Auto").
 */
data class StreamItem(val pick: StreamPick, val checked: Boolean, val resolvesTo: CameraSize? = null, val quality: String? = null)

/** The settings menu: its rows in order (Auto, the resolutions, stills, off), exactly one checked ([CameraModes.checked]). */
data class StreamMenu(val items: List<StreamItem>)

/**
 * DEV-768 P3, the camera viewport's pure rules (the owner, D-20261111-182d / 7e09 / 8038): the resize menu, the stream menu, and which stream
 * a viewport asks for. No Android type. The Compose-less view glue (`ViewportController`, `StillTiles`) only draws what this decides.
 */
object CameraViewport {
    /** A tap shows the control strip for this long (the owner: 3 s). */
    const val STRIP_MS = 3000L

    /**
     * The AUTO hook. Until P2 (the per-phone "use small camera streams" Setting) exists, Auto is today's behaviour: the tile's size decides
     * the stream (a Big tile plays the main stream, a Small tile the small one). P2 replaces this one function with the Setting's verdict;
     * nothing else changes. [tileSize] is the size of the tile on the page, also while the viewport is fullscreen, so entering fullscreen
     * never changes the stream (the owner, Q2: fullscreen stays on the chosen variant).
     */
    fun autoStream(tileSize: CameraSize): CameraSize = tileSize

    /** A pick the camera cannot honour (Small with no small stream) is Auto. */
    fun usable(pick: StreamPick, hasSmall: Boolean): StreamPick = when {
        pick == StreamPick.STILLS || pick == StreamPick.OFF -> StreamPick.AUTO // not streams
        pick == StreamPick.SMALL && !hasSmall -> StreamPick.AUTO
        else -> pick
    }

    /** The stream the viewport asks for: an explicit, honourable pick overrides Auto. This is the `size` of `content/live` and the key of the live session. */
    fun stream(pick: StreamPick, tileSize: CameraSize, hasSmall: Boolean): CameraSize = when (usable(pick, hasSmall)) {
        StreamPick.AUTO, StreamPick.STILLS, StreamPick.OFF -> autoStream(tileSize)
        StreamPick.BIG -> CameraSize.BIG
        StreamPick.SMALL -> CameraSize.SMALL
    }

    /** The resize menu: Big, [Partial, only when [partialOffered]: tablet mode and a block with other cameras], Small, Fullscreen, the current layout checked. */
    fun resizeItems(current: ViewLayout, partialOffered: Boolean = false): List<ResizeItem> =
        listOfNotNull(ViewLayout.BIG, ViewLayout.PARTIAL.takeIf { partialOffered }, ViewLayout.SMALL, ViewLayout.FULLSCREEN).map { ResizeItem(it, it == current) }

    /**
     * The settings menu (0.6.53, a YouTube-style text list): Auto, the resolutions (a camera with one stream offers only that one), "stills",
     * "off"; the row [CameraModes.checked] says is checked. A camera with two streams lists the bigger known picture first.
     */
    fun streamMenu(state: CameraState, tileSize: CameraSize, hasSmall: Boolean, heightOf: (CameraSize) -> Int? = { null }): StreamMenu {
        fun q(size: CameraSize): String? = heightOf(size)?.let { StreamLabels.quality(it) }
        val on = CameraModes.checked(if (state.pick == StreamPick.SMALL && !hasSmall) state.copy(pick = StreamPick.AUTO) else state)
        val rows = ArrayList<StreamItem>()
        if (hasSmall) {
            val auto = autoStream(tileSize)
            rows.add(StreamItem(StreamPick.AUTO, on == StreamPick.AUTO, auto, q(auto)))
            val streams = mutableListOf(StreamPick.BIG, StreamPick.SMALL)
            val hb = heightOf(CameraSize.BIG)
            val hs = heightOf(CameraSize.SMALL)
            if (hb != null && hs != null && hs > hb) streams.reverse()
            for (p in streams) rows.add(StreamItem(p, on == p, quality = q(if (p == StreamPick.BIG) CameraSize.BIG else CameraSize.SMALL)))
        } else {
            rows.add(StreamItem(StreamPick.BIG, on == StreamPick.BIG || on == StreamPick.AUTO, quality = q(CameraSize.BIG)))
        }
        rows.add(StreamItem(StreamPick.STILLS, on == StreamPick.STILLS))
        rows.add(StreamItem(StreamPick.OFF, on == StreamPick.OFF))
        return StreamMenu(rows)
    }

    /** What choosing [target] in the resize menu does: the new tile size (null: unchanged) and whether the viewport is fullscreen after it. */
    fun resize(current: ViewLayout, target: ViewLayout, tileSize: CameraSize): Resize = when (target) {
        ViewLayout.FULLSCREEN -> if (current == ViewLayout.FULLSCREEN) Resize(null, false) else Resize(null, true) // the checked item again = leave
        ViewLayout.BIG -> Resize(if (tileSize == CameraSize.BIG) null else CameraSize.BIG, false)
        ViewLayout.SMALL -> Resize(if (tileSize == CameraSize.SMALL) null else CameraSize.SMALL, false)
        // Partial is a big tile plus its neighbours: the tile is saved Big (so leaving partial for Big changes nothing), the checked item again = back to Big.
        ViewLayout.PARTIAL -> if (current == ViewLayout.PARTIAL) Resize(null, false) else Resize(if (tileSize == CameraSize.BIG) null else CameraSize.BIG, false, partial = true)
    }

    data class Resize(val newTileSize: CameraSize?, val fullscreen: Boolean, val partial: Boolean = false)

    /**
     * Tablet mode's partial layout: [view] with the block of camera [partialId] marked ([BlockView.partialBig]); that camera is drawn Big and the
     * up to [CameraGrid.PARTIAL_SIDE] cameras [CameraGrid.partial] picks beside it Small (a draw-time size only: nothing is saved for them, so
     * their own saved size comes back when partial ends). The same object when [partialId] is null or not on the page.
     */
    fun withPartial(view: HomeView, partialId: String?): HomeView {
        if (partialId == null || view.blocks.none { b -> b.cameras.any { it.id == partialId } }) return view
        return view.copy(blocks = view.blocks.map { b ->
            val at = b.cameras.indexOfFirst { it.id == partialId }
            if (at < 0) return@map b
            val side = CameraGrid.partial(b.cameras.size, at).side.toSet()
            b.copy(
                partialBig = partialId,
                cameras = b.cameras.mapIndexed { i, c -> if (i == at) c.copy(size = CameraSize.BIG) else if (i in side) c.copy(size = CameraSize.SMALL) else c },
            )
        })
    }

    /** `ui cam-pick` / `ui cam-resize` lines (the Journal text without its time); the camera id is shortened like every other id. */
    fun pickLine(cameraId: String, pick: StreamPick, stream: CameraSize, live: Boolean): String =
        "ui cam-pick cam=${Redact.prefix(cameraId)} pick=${pick.word} stream=${stream.wire} live=${if (live) "yes" else "no"}"

    /**
     * 0.6.59: the Journal line the gear menu writes when it builds its labels, so a "Low" where a height was expected is provable:
     * `ui menu-labels cam=.. big=720(server) small=288(server)`; each variant says where its number came from (played / server) or `none`.
     */
    fun menuLabelsLine(cameraId: String, played: (CameraSize) -> Int?, server: (CameraSize) -> Int?): String {
        fun part(size: CameraSize): String {
            val p = played(size)
            val v = server(size)
            return when {
                p != null -> "$p(played)"
                v != null && v > 0 -> "$v(server)"
                else -> "none"
            }
        }
        return "ui menu-labels cam=${Redact.prefix(cameraId)} big=${part(CameraSize.BIG)} small=${part(CameraSize.SMALL)}"
    }

    fun resizeLine(cameraId: String, from: ViewLayout, to: ViewLayout, stream: CameraSize): String =
        "ui cam-resize cam=${Redact.prefix(cameraId)} from=${from.word} to=${to.word} stream=${stream.wire}"
}

/** A viewport is one camera on one page. */
data class ViewportKey(val pageId: String, val cameraId: String)

/**
 * The state of the viewports that are open (the owner: "the viewport pick lasts only while that viewport is open", NO persistence): the stream
 * picks, and the one fullscreen viewport. In memory; [encode] / [decode] only carry it across a rotation (a new activity), never to disk.
 * [retain] drops what is no longer on the screen (a page change, a camera that left the document).
 */
class ViewportMemory {
    private val picks = LinkedHashMap<ViewportKey, StreamPick>()

    var fullscreen: ViewportKey? = null
        private set

    fun pick(key: ViewportKey): StreamPick = picks[key] ?: StreamPick.AUTO

    /** True if the pick changed. Auto is the absence of a pick. */
    fun setPick(key: ViewportKey, pick: StreamPick): Boolean {
        if (pick(key) == pick) return false
        if (pick == StreamPick.AUTO) picks.remove(key) else picks[key] = pick
        return true
    }

    fun setFullscreen(key: ViewportKey?) {
        fullscreen = key
    }

    /** Tablet mode: the one viewport in the partial layout (its camera big, neighbours small). Like fullscreen: open-only, never saved to disk. */
    var partial: ViewportKey? = null
        private set

    fun setPartial(key: ViewportKey?) {
        partial = key
    }

    /** Keeps only the viewports in [open]. True if something was dropped. */
    fun retain(open: Set<ViewportKey>): Boolean {
        val before = picks.size + (if (fullscreen != null) 1 else 0) + (if (partial != null) 1 else 0)
        picks.keys.retainAll(open)
        if (fullscreen != null && fullscreen !in open) fullscreen = null
        if (partial != null && partial !in open) partial = null
        return picks.size + (if (fullscreen != null) 1 else 0) + (if (partial != null) 1 else 0) != before
    }

    fun clear() {
        picks.clear()
        fullscreen = null
        partial = null
    }

    val isEmpty: Boolean get() = picks.isEmpty() && fullscreen == null && partial == null

    /** One line per entry, tab-separated (ids may hold any character but a tab or newline, which are replaced). */
    fun encode(): String {
        fun f(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        val lines = ArrayList<String>()
        fullscreen?.let { lines.add("F\t${f(it.pageId)}\t${f(it.cameraId)}") }
        partial?.let { lines.add("T\t${f(it.pageId)}\t${f(it.cameraId)}") }
        for ((k, p) in picks) lines.add("P\t${f(k.pageId)}\t${f(k.cameraId)}\t${p.word}")
        return lines.joinToString("\n")
    }

    companion object {
        /** Damaged or foreign text is an empty memory. */
        fun decode(text: String?): ViewportMemory {
            val m = ViewportMemory()
            if (text.isNullOrBlank()) return m
            for (line in text.lineSequence()) {
                val c = line.split('\t')
                when {
                    c.size == 3 && c[0] == "F" -> m.fullscreen = ViewportKey(c[1], c[2])
                    c.size == 3 && c[0] == "T" -> m.partial = ViewportKey(c[1], c[2])
                    c.size == 4 && c[0] == "P" -> StreamPick.of(c[3])?.takeIf { it == StreamPick.BIG || it == StreamPick.SMALL }?.let { m.picks[ViewportKey(c[1], c[2])] = it }
                }
            }
            return m
        }
    }
}

/**
 * Which camera shows its control strip, and until when (a tap shows it for [CameraViewport.STRIP_MS]; one strip at a time; an open menu
 * holds it, and closing the menu starts the 3 s again). Pure: the caller passes the clock.
 */
class ControlStrip(private val showMs: Long = CameraViewport.STRIP_MS) {
    private var id: String? = null
    private var until = 0L
    private var held = false

    /** A tap on [cameraId]'s tile. */
    fun show(cameraId: String, nowMs: Long) {
        if (cameraId != id) held = false
        id = cameraId
        until = nowMs + showMs
    }

    /** A menu of the strip opened ([open] true) or closed. Closing restarts the clock. */
    fun hold(open: Boolean, nowMs: Long) {
        held = open
        if (!open && id != null) until = nowMs + showMs
    }

    /** The camera whose strip is visible now, or null. */
    fun visibleId(nowMs: Long): String? = id?.takeIf { held || nowMs < until }

    /** When to look again (the strip's end), or null when nothing is waiting. */
    fun nextCheckAt(nowMs: Long): Long? = if (id != null && !held && nowMs < until) until else null

    fun hide() {
        id = null
        held = false
    }
}
