package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Label
import com.meda.portico.core.live.CallerLive
import com.meda.portico.core.ring.LinkState

/*
 * A2: what the screens draw from the resolved document, as pure functions so the JVM tests them. The activities
 * map these to widgets and do nothing else: no rule below is repeated in an Activity.
 */

/** One tab of Home's pager. */
data class PageTab(val id: String, val label: String)

object Pager {
    /** Index of the page to show: [wantedId] if the document still has it, else the first page (0). */
    fun select(pages: List<ContentPage>, wantedId: String?): Int {
        if (wantedId == null) return 0
        val i = pages.indexOfFirst { it.id == wantedId }
        return if (i >= 0) i else 0
    }

    /**
     * The tabs: none for zero or one page (a single page needs no tab strip). A page whose label is blank after
     * cleaning is named by its position ("2"), never left as an empty tab.
     */
    fun tabs(pages: List<ContentPage>): List<PageTab> =
        if (pages.size < 2) emptyList() else pages.mapIndexed { i, p -> PageTab(p.id, Label.text(p.label, Label.TAB_MAX, (i + 1).toString())) }

    /**
     * The tabs with one more at the end (the roster page, M7 3b-A): every document page is a tab even when there is only one
     * (the strip is how the person reaches the extra page). With [extra] null this is exactly [tabs].
     */
    fun tabs(pages: List<ContentPage>, extra: PageTab?): List<PageTab> =
        if (extra == null) tabs(pages) else pages.mapIndexed { i, p -> PageTab(p.id, Label.text(p.label, Label.TAB_MAX, (i + 1).toString())) } + extra

    /** The index [direction] (+1 next, -1 previous) away from [index], held inside the pages; no wrap-around. */
    fun step(count: Int, index: Int, direction: Int): Int =
        if (count <= 0) 0 else (index + direction).coerceIn(0, count - 1)
}

/** What the Home title says: [Paused] (the app words it, amber), [Text] (plain text, already cleaned), or [Fallback] (the app's own name). */
sealed interface HomeTitle {
    data object Paused : HomeTitle
    data class Text(val text: String) : HomeTitle
    data object Fallback : HomeTitle

    companion object {
        /**
         * Pure: paused wins; else the selected page's label; else the home's name; else the app's own title, so the
         * screen is never blank. Both texts arrive cleaned (blank is null), see [HomeView.pageLabel] and [HomeView.homeName].
         */
        fun choose(paused: Boolean, pageLabel: String?, homeName: String?): HomeTitle = when {
            paused -> Paused
            pageLabel != null -> Text(pageLabel)
            homeName != null -> Text(homeName)
            else -> Fallback
        }
    }
}

/**
 * What the ONE Home top line shows in its title slot: [Paused] (the app words it, amber; the selector is out of the
 * way while it lasts), [Tabs] (two or more pages: the line IS the page selector), [Title] (one page's label or the
 * home's name, plain text) or [Fallback] (the app's own name). Pure.
 */
sealed interface TopLine {
    data object Paused : TopLine
    data object Tabs : TopLine
    data class Title(val text: String) : TopLine
    data object Fallback : TopLine

    companion object {
        /** Paused wins; else two or more tabs make the selector; else [HomeTitle.choose]'s text or fallback. */
        fun choose(paused: Boolean, tabCount: Int, pageLabel: String?, homeName: String?): TopLine = when {
            paused -> Paused
            tabCount > 1 -> Tabs
            else -> when (val t = HomeTitle.choose(false, pageLabel, homeName)) {
                is HomeTitle.Text -> Title(t.text)
                else -> Fallback
            }
        }

        /** Which tab is highlighted: exactly the one whose id is [selectedId] (the first when the id is null or unknown). */
        fun highlighted(tabs: List<PageTab>, selectedId: String?): List<Boolean> {
            val at = tabs.indexOfFirst { it.id == selectedId }.let { if (it >= 0) it else 0 }
            return tabs.indices.map { it == at }
        }
    }
}

/**
 * Whether a block's heading is drawn on Home. The top line already names the page (the title of a single page, the
 * selected tab of several), so a block titled like its page would say it twice under it: the heading is left out then
 * (trim and case ignored), and for a block with no title. A block with its own title keeps it. Pure.
 */
object BlockHeading {
    /** The heading to draw for a block titled [blockTitle] on a page labelled [pageLabel], or null for none. */
    fun choose(pageLabel: String?, blockTitle: String?): String? {
        val title = blockTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val page = pageLabel?.trim()?.takeIf { it.isNotEmpty() }
        return if (page != null && page.equals(title, ignoreCase = true)) null else blockTitle
    }
}

object Swipe {
    /**
     * +1 (next page) for a clear leftward swipe, -1 (previous) for a rightward one, 0 for anything else: too short, or
     * not mostly horizontal (a vertical scroll is never a page change).
     */
    fun direction(dx: Float, dy: Float, minPx: Float): Int {
        val ax = Math.abs(dx)
        if (ax < minPx || ax < 2f * Math.abs(dy)) return 0
        return if (dx < 0f) 1 else -1
    }
}

/** One row of a block's camera grid: the tile [cells] (into the block's cameras) and whether it is a [big] tile's own row. */
data class GridRow(val cells: List<Int>, val big: Boolean) {
    /** A small tile alone in its row (before a big one, or the last): drawn half width, left-aligned, with a spacer. */
    val lone: Boolean get() = !big && cells.size == 1
}

/**
 * Tablet mode's partial layout of one block: the [big] camera (index into the block's cameras) with up to [CameraGrid.PARTIAL_SIDE] others, [side],
 * stacked at its right; [rest] are the other cameras' rows (indices into the block's cameras), drawn below as usual.
 */
data class PartialPlan(val big: Int, val side: List<Int>, val rest: List<GridRow>)

/** Blocks (DEV-658, 6.1): how a block's camera tiles are laid out, from their sizes alone. */
object CameraGrid {
    const val PARTIAL_SIDE = 2

    /** The partial layout around camera [big] of [count]: the side cameras are the first [PARTIAL_SIDE] others in block order; the group comes first. [rest] is empty here, see [partialRows]. */
    fun partial(count: Int, big: Int): PartialPlan =
        PartialPlan(big, (0 until count).filter { it != big }.take(PARTIAL_SIDE), emptyList())

    /** [partial] with its [PartialPlan.rest]: the cameras left over flow into rows by [rows], from their [sizes]. */
    fun partialRows(sizes: List<CameraSize>, big: Int): PartialPlan {
        val g = partial(sizes.size, big)
        val left = sizes.indices.filter { it != big && it !in g.side }
        return g.copy(rest = rows(left.map { sizes[it] }).map { r -> r.copy(cells = r.cells.map { left[it] }) })
    }

    /**
     * A big tile takes a row; small tiles pair up in order; a small tile left without a partner (before a big one, or the
     * last) gets a row of its own. Every index appears exactly once, in order.
     */
    fun rows(sizes: List<CameraSize>): List<GridRow> {
        val rows = ArrayList<GridRow>()
        var waiting: Int? = null
        for ((i, size) in sizes.withIndex()) {
            if (size == CameraSize.BIG) {
                waiting?.let { rows.add(GridRow(listOf(it), big = false)) }
                waiting = null
                rows.add(GridRow(listOf(i), big = true))
            } else {
                val w = waiting
                if (w == null) {
                    waiting = i
                } else {
                    rows.add(GridRow(listOf(w, i), big = false))
                    waiting = null
                }
            }
        }
        waiting?.let { rows.add(GridRow(listOf(it), big = false)) }
        return rows
    }
}

/** Blocks (6.1): how a block's buttons are packed into rows. A row holds [PER_ROW] units; a text button is 2, an icon-only one 1. */
object ButtonRows {
    const val PER_ROW = 4
    const val TEXT_UNITS = 2
    const val ICON_UNITS = 1

    fun units(iconOnly: Boolean): Int = if (iconOnly) ICON_UNITS else TEXT_UNITS

    /** Rows of button indices, in order, filled left to right; a button that does not fit starts the next row. A width is clamped to 1..[PER_ROW]. */
    fun pack(units: List<Int>): List<List<Int>> {
        val rows = ArrayList<List<Int>>()
        var row = ArrayList<Int>()
        var used = 0
        for ((i, raw) in units.withIndex()) {
            val u = raw.coerceIn(1, PER_ROW)
            if (used + u > PER_ROW) {
                rows.add(row)
                row = ArrayList()
                used = 0
            }
            row.add(i)
            used += u
        }
        if (row.isNotEmpty()) rows.add(row)
        return rows
    }

    /** The empty space at the end of a row, in units: the activity adds a spacer of this weight so cells keep their width. */
    fun spare(units: List<Int>, row: List<Int>): Int = PER_ROW - row.sumOf { units[it].coerceIn(1, PER_ROW) }
}

/** What the ring or in-call screen draws for one caller: its name, camera stills and custom buttons. */
data class CallerView(val name: String?, val cameras: List<CameraTile>, val buttons: List<ButtonTile>)

object Callers {
    /** The `callers` key of the fallback entry: every caller the document names no entry for. */
    const val FALLBACK = "*"

    /**
     * The document's entry for the door whose endpoint id is [endpointId], else the `*` entry, else null (the screen
     * then behaves as it did before A2: no stills, no extra buttons). An entry with nothing in it is still the answer:
     * it says the editor chose to show nothing for that caller, which is not the same as having no entry.
     */
    fun lookup(profile: ContentProfile, endpointId: String?): ContentCaller? =
        endpointId?.let { profile.callers[it] } ?: profile.callers[FALLBACK]

    /**
     * The custom buttons of a list, in the server's order: a `call` button is left out (placing a call from a call
     * screen is M7's), and a repeated id is drawn once.
     */
    fun customButtons(list: List<ContentAction>): List<ContentAction> =
        list.filter { !it.isCall }.distinctBy { it.id }
}

enum class CallScreen { RING, IN_CALL }

object CallerModel {
    /**
     * The ring screen's ([CallScreen.RING]: `ring_buttons`) or the in-call screen's ([CallScreen.IN_CALL]:
     * `call_buttons`) extras for the door [endpointId]; null when the document has no entry for it and no `*`.
     * A picture is shown only while the link is up and the server answers for this phone; otherwise the tile says so.
     */
    fun build(
        profile: ContentProfile,
        endpointId: String?,
        screen: CallScreen,
        link: LinkState,
        support: ContentSupport,
        running: Set<String>,
        pictures: Set<String>,
    ): CallerView? {
        val caller = Callers.lookup(profile, endpointId) ?: return null
        val usable = HomeModel.usable(link, support)
        val actions = Callers.customButtons(if (screen == CallScreen.RING) caller.ringButtons else caller.callButtons)
        val cameras = caller.cameras.distinctBy { it.id }
        // DEV-713 S2: the caller's FIRST AVAILABLE camera is live by default, the rest are stills; the viewer's chip choice is LiveMemory's.
        // A caller's camera carries no `live` key, so a tile's `live` here is this rule (the "document's value" the memory compares with).
        val first = CallerLive.firstAvailableId(cameras)
        return CallerView(
            name = Label.clean(caller.name, Label.MAX),
            cameras = cameras.map { HomeModel.cameraTile(it, usable, pictures).copy(live = it.id == first) },
            buttons = actions.map { HomeModel.buttonTile(it, usable, running, calling = null) },
        )
    }
}

/**
 * One request in flight per camera, for the whole app (Home, the ring screen and the in-call screen share it): a
 * picture still on its way is not asked for again, however many screens or ticks want it. A request whose answer never
 * comes (the link dropped without telling us) is forgotten after [staleAfterMs] so a camera can never stay stuck.
 * Thread-safe: the UI asks, the link's thread answers.
 */
class StillGate(private val clockMs: () -> Long, private val staleAfterMs: Long = 10_000L) {
    private val sentAt = HashMap<String, Long>()

    /** True, and the camera is marked in flight, if nothing is in flight for [cameraId]. */
    @Synchronized
    fun tryBegin(cameraId: String): Boolean {
        val now = clockMs()
        val at = sentAt[cameraId]
        if (at != null && now - at in 0 until staleAfterMs) return false
        sentAt[cameraId] = now
        return true
    }

    /** The answer (a picture, a refusal or a loss) arrived. */
    @Synchronized
    fun end(cameraId: String) {
        sentAt.remove(cameraId)
    }

    @Synchronized
    fun clear() {
        sentAt.clear()
    }

    @Synchronized
    fun inFlight(): Set<String> = sentAt.keys.toSet()
}

/**
 * When one screen asks for fresh pictures: every [intervalMs] per camera. [tick] is called often (the screen's own
 * timer); a camera is asked for only when its interval has passed AND [request] accepted it (it returns false when
 * a request is already in flight or nothing can be sent), in which case it is offered again at the next tick rather
 * than a whole interval later. Not thread-safe: one screen's main thread.
 */
class StillSchedule(private val intervalMs: Long) {
    private val askedAt = HashMap<String, Long>()

    fun tick(cameraIds: Collection<String>, nowMs: Long, request: (String) -> Boolean) {
        askedAt.keys.retainAll(cameraIds.toSet())
        for (id in cameraIds) {
            val at = askedAt[id]
            if (at != null && nowMs - at in 0 until intervalMs) continue
            if (request(id)) askedAt[id] = nowMs
        }
    }

    /** The screen was hidden or the link dropped: the first pictures after it are not delayed. */
    fun reset() {
        askedAt.clear()
    }

    /**
     * One camera is due now (its tile changed size or its zoom crossed a width step, blocks B6): the next tick offers it
     * again. Whether it is really sent is still [tick]'s `request`: a still in flight keeps it waiting, never stacks.
     */
    fun reset(cameraId: String) {
        askedAt.remove(cameraId)
    }
}
