package com.meda.portico.core.content

import com.meda.portico.core.call.TalkErrors
import com.meda.portico.core.call.TalkPhase
import com.meda.portico.core.call.TalkUiState
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.CameraSizes
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentBlock
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.Label
import com.meda.portico.core.ha.RosterEntry
import com.meda.portico.core.ha.HomeName
import com.meda.portico.core.ha.LiveRtsp
import com.meda.portico.core.ring.LinkState

/** The one line at the top of the Home screen; the app maps each to a string. [NONE] shows nothing. */
enum class Banner {
    NONE,
    NOT_CONFIGURED,
    PAUSED,
    AUTH_REJECTED,

    /** DEV-544: the phone's refresh token is gone at Home Assistant: enrol again. */
    SIGNED_OUT,
    URL_REFUSED,

    /** Not connected: the cached content is shown, the buttons are off, pictures are not shown. */
    OFFLINE,
    CONNECTING,
    NO_SERVER_SUPPORT,
    NOT_ENROLLED,

    /** Connected and answered, but the profile has no cameras and no buttons. */
    NO_CONTENT,
}

enum class ButtonState {
    READY,
    RUNNING,

    /** Not connected: nothing is queued for later, so the button is off. */
    OFFLINE,

    /** HA says the entity it uses is unavailable right now. */
    UNAVAILABLE,

    // ---- `talk` buttons only (blocks B7b). A Talk button that is off is [UNAVAILABLE] with a [ButtonReason]; these two are a talk of this phone under way. ----

    /** Talk was pressed and the gate has not answered yet (the microphone dialog, the dial, the ring, the audio). The button is tappable: it cancels. */
    TALK_CONNECTING,

    /** Audio flows. The button is tappable: it stops the talk. */
    TALK_IN_CALL,

    /** APP-03: a call from the station to this button's door is ringing on this phone. Green and pulsing; Answer and Decline are in the call row, so the tile itself does nothing. */
    RINGING_IN,

    // ---- `call` buttons only (house calls design 14.6, 14.8). The server's `reason` picks one; the app words each. ----

    /** A call from this very button is live: it shows that, and a second press goes nowhere. */
    CALLING,

    /** The person or screen is not reachable (`offline`, and `push_only` until the push ring for calls exists, CB-7). */
    TARGET_OFFLINE,

    /** They are on a call, or a door is ringing there. */
    TARGET_BUSY,

    /** Do Not Disturb is on there. */
    TARGET_DND,

    /** The callee's own "may receive house calls" switch is off. */
    TARGET_RECEIVE_OFF,

    /** The target was removed or disabled after the button was made (`unknown_target`, `disabled`, no reason given). */
    TARGET_GONE,

    /** This Home Assistant cannot place calls at all yet (`not_available`). */
    CALLS_NOT_AVAILABLE,
}

enum class PictureState {
    PICTURE,

    /** Connected and the camera is fine, no picture yet (the first one is on its way). */
    LOADING,
    CAMERA_UNAVAILABLE,

    /** Not connected: an old picture is never shown as if it were live. */
    OFFLINE,
}

/**
 * [size] is the width Home draws the tile at (the ring and in-call screens ignore it: one column, full width): the phone's
 * own choice (blocks B6) or, with none, [defaultSize], the one Home Assistant gave (its `size`, else the auto rule).
 */
data class CameraTile(
    val id: String,
    val label: String,
    val detail: String?,
    val picture: PictureState,
    val size: CameraSize = CameraSize.BIG,
    val defaultSize: CameraSize = size,
    /**
     * DEV-713: what the tile does when this phone has not chosen: the document's `live` for a Home tile; on the ring and in-call screens
     * (S2) the caller's first available camera is true and the others false ([CallerLive]).
     */
    val live: Boolean = false,
    /** DEV-713 S1b: the server's away-from-home RTSP addresses for this camera (validated), or null when it gave none. Only `:core`'s live plan reads it. */
    val liveRtsp: LiveRtsp? = null,
    /** DEV-768 P3: the camera has a small stream as well as its main one (the server's `small: true`); the viewport's settings menu offers Small only then. */
    val hasSmall: Boolean = false,
    /** 0.6.53: the viewer turned this camera view off (video-size menu): no video, no stills; the tile draws its own off state. Set by [HomeModel.withOff]. */
    val off: Boolean = false,
    /** 0.6.54: the stream heights the server measured for the main / small stream (null: not sent). The gear menu names a stream by them until it has played. */
    val bigHeight: Int? = null,
    val smallHeight: Int? = null,
) {
    /** The server's height for [size], or null. */
    fun serverHeight(size: CameraSize): Int? = if (size == CameraSize.BIG) bigHeight else smallHeight

    /** False when the server sent an empty label: the tile then draws no name line at all (nor [detail]) and is described neutrally. */
    val named: Boolean get() = label.isNotEmpty()
}

/**
 * Why a button that is off is off, from the server's `reason` (blocks: `talk` sends one). The app words each.
 * A reason this build does not know is no reason at all: the plain "unavailable" line.
 */
enum class ButtonReason(val wire: String) {
    TALK_OFF("talk_off"),
    NO_DOOR("no_door"),
    NOT_CONNECTED("not_connected"),
    BUSY("busy"),
    PHONE_ONLY("phone_only"),

    /** An AVAILABLE Talk button whose call from this phone is still connecting / going, with no talk of this process behind it (the app restarted): it can only be waited out. */
    CONNECTING("connecting"),
    IN_CALL("in_call"),
    ;

    companion object {
        fun of(wire: String?): ButtonReason? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * [color] is a palette name this build knows (lower case), else null: the default button. [iconOnly] is true when the
 * server hid the text AND an icon will be drawn (a button is never left with neither), and always for a Talk or call button (APP-02). [reason] is set only for a
 * [ButtonState.UNAVAILABLE] button the server gave a known reason for.
 */
data class ButtonTile(
    val id: String,
    val label: String,
    val detail: String?,
    val kind: String,
    val icon: String?,
    val confirm: Boolean,
    val state: ButtonState,
    val color: String? = null,
    val iconOnly: Boolean = false,
    val reason: ButtonReason? = null,
    /** A Talk button: the block's door (the audio attaches to it); null for every other kind. */
    val door: String? = null,
    /** A Talk button: the one line saying how this phone's last talk ended (shown a few seconds), else null. */
    val endLine: String? = null,
    /**
     * A `call` button aimed at the gate (DEV-692, [GateCall]): its press starts the Talk session, not a house call. [door] is then
     * the door's socket id for the audio (null: `portico/doors` has not named it yet, the button is off).
     */
    val talkRoute: Boolean = false,
)

/**
 * One block of the selected page as Home draws it: a heading ([title], null for none), a grid of camera tiles and rows of
 * buttons. The two row lists are pure ([CameraGrid], [ButtonRows]); the activity maps them to widgets.
 */
data class BlockView(
    val id: String,
    val title: String?,
    val cameras: List<CameraTile>,
    val buttons: List<ButtonTile>,
    /** Tablet mode: the id of this block's camera drawn in the partial layout ([CameraViewport.withPartial]), else null. */
    val partialBig: String? = null,
) {
    /** The partial layout of this block, or null (none chosen, or the camera is not here). */
    val partialPlan: PartialPlan? get() = partialBig?.let { id -> cameras.indexOfFirst { it.id == id }.takeIf { it >= 0 } }?.let { CameraGrid.partialRows(cameras.map { it.size }, it) }

    val cameraRows: List<GridRow> get() = CameraGrid.rows(cameras.map { it.size })
    val buttonRows: List<List<Int>> get() = ButtonRows.pack(buttons.map { ButtonRows.units(it.iconOnly) })
}

/** [homeName] is the plugin's name for the home (plain text, cleaned), or null: the screen then keeps its own title. */
data class HomeView(
    val banner: Banner,
    /** The SELECTED page's blocks (the only ones drawn, and the only ones whose pictures are fetched); a block with nothing in it is left out. */
    val blocks: List<BlockView>,
    val homeName: String? = null,
    /** One tab per page when there is more than one page; empty for one page or none. */
    val tabs: List<PageTab> = emptyList(),
    /** Index of the selected page in [tabs] (0 when there are no tabs). */
    val selected: Int = 0,
    /** Id of the selected page, null when there is none; the screen keeps it so a refresh does not flip back to the first page. */
    val selectedId: String? = null,
    /** The selected page's label as plain text (cleaned, at most [Label.MAX]); null with no page or a blank label. The Home title. */
    val pageLabel: String? = null,
    /** M7 3b-A: the roster page, present ONLY when the server says house calls are on for this phone (null otherwise: no tab, no page, no row). */
    val roster: RosterView? = null,
    /** DEV-713: the document's `show_fps`: live tiles draw their frame rate. */
    val showFps: Boolean = false,
) {
    /** The roster page is the selected one (its rows are drawn instead of blocks). */
    val rosterSelected: Boolean get() = roster != null && selectedId == RosterModel.TAB_ID

    /** Every camera tile of the selected page, in block order (the pictures to fetch and to put on screen). */
    val cameras: List<CameraTile> get() = blocks.flatMap { it.cameras }

    /** Every button of the selected page, in block order. */
    val buttons: List<ButtonTile> get() = blocks.flatMap { it.buttons }
}

/**
 * Pure: the content, the link and what is in flight -> what the Home screen draws. Every rule about
 * when a button works or a picture may be shown lives here, so it is tested without a phone.
 *
 * The rules that matter:
 * - Not connected: the cached tiles stay (so the screen is not empty before the socket is up), but
 *   every button is off and no picture is shown. Nothing is queued: a gate that opens when the signal
 *   comes back minutes later is worse than none.
 * - A detail line repeats HA's own name under the label only when it differs from it.
 */
object HomeModel {
    fun build(
        profile: ContentProfile,
        link: LinkState,
        support: ContentSupport,
        answered: Boolean,
        running: Set<String>,
        pictures: Set<String>,
        /** The action id of the call button whose call is live, if any (PlaceCoordinator). */
        calling: String? = null,
        /** The page the screen shows (by id); a missing or unknown one is the first page. */
        selectedPageId: String? = null,
        /** The sizes this phone chose by tapping tiles (blocks B6); null: every tile at the server's size. */
        sizes: SizeMemory? = null,
        /** The talk of this phone (blocks B7b): what the Talk buttons show while it is live or has just ended; null: every Talk button follows the server alone. */
        talk: TalkUiState? = null,
        /** The doors cache ([com.meda.portico.core.ha.DoorsCache.all]): a call button aimed at the gate needs its door's socket id for the audio. */
        doors: List<Door> = emptyList(),
        /** APP-03: the call from the station (ringing, answering or in the call), shown on the Talk button of its door; null: none. */
        station: StationCall? = null,
    ): HomeView {
        val connected = link == LinkState.CONNECTED
        val banner = when {
            link == LinkState.NOT_CONFIGURED -> Banner.NOT_CONFIGURED
            link == LinkState.PAUSED -> Banner.PAUSED
            link == LinkState.AUTH_REJECTED -> Banner.AUTH_REJECTED
            link == LinkState.SIGNED_OUT -> Banner.SIGNED_OUT
            link == LinkState.URL_REFUSED -> Banner.URL_REFUSED
            link == LinkState.CONNECTING -> Banner.CONNECTING
            !connected -> Banner.OFFLINE
            support == ContentSupport.NO -> Banner.NO_SERVER_SUPPORT
            support == ContentSupport.NOT_ENROLLED -> Banner.NOT_ENROLLED
            answered && profile.isEmpty -> Banner.NO_CONTENT
            else -> Banner.NONE
        }
        val usable = usable(link, support)

        val pages = profile.pageList
        // M7 3b-A: the roster page exists only when the server says house calls are on for this phone. With them off `roster` is
        // null and nothing below differs from before: same tabs, same selection, same blocks (`HomeModelRosterTest` pins it).
        val roster = RosterModel.build(profile.roster, usable, calling)
        if (roster != null && (selectedPageId == RosterModel.TAB_ID || pages.isEmpty())) {
            val tabs = Pager.tabs(pages, RosterModel.tab())
            return HomeView(banner, emptyList(), HomeName.clean(profile.homeName), tabs, tabs.lastIndex, RosterModel.TAB_ID, RosterModel.TAB_LABEL, roster, profile.showFps)
        }
        val index = Pager.select(pages, selectedPageId)
        val page = pages.getOrNull(index)
        val pageLabel = page?.let { Label.clean(it.label, Label.MAX) }
        // The top line names the page: a block heading that repeats it is left out ([BlockHeading]).
        val blocks = page?.blockList?.mapNotNull { blockView(it, usable, running, pictures, calling, page.id, sizes, talk, profile.roster, doors, station) }
            ?.map { it.copy(title = BlockHeading.choose(pageLabel, it.title)) } ?: emptyList()
        val tabs = if (roster == null) Pager.tabs(pages) else Pager.tabs(pages, RosterModel.tab())
        val shown = HomeView(banner, blocks, HomeName.clean(profile.homeName), tabs, index, page?.id, pageLabel)
        val drawn = if (profile.showFps) shown.copy(showFps = true) else shown // DEV-713: live tiles draw their frame rate
        return if (roster == null) drawn else drawn.copy(roster = roster)
    }

    /** A block as drawn, or null for one with no cameras and no buttons. Sizes the server did not give (or this build does not know) follow the auto rule. */
    internal fun blockView(
        b: ContentBlock,
        usable: Boolean,
        running: Set<String>,
        pictures: Set<String>,
        calling: String?,
        pageId: String = "",
        memory: SizeMemory? = null,
        talk: TalkUiState? = null,
        roster: List<RosterEntry> = emptyList(),
        doors: List<Door> = emptyList(),
        station: StationCall? = null,
    ): BlockView? {
        if (b.cameras.isEmpty() && b.actions.isEmpty()) return null
        val defaults = CameraSizes.effective(b.cameras)
        return BlockView(
            id = b.id,
            title = Label.clean(b.title, Label.MAX),
            cameras = b.cameras.mapIndexed { i, c ->
                cameraTile(c, usable, pictures, memory?.effective(pageId, c.id, defaults[i]) ?: defaults[i], defaults[i])
            },
            buttons = b.actions.map { buttonTile(it, usable, running, calling, talk, b.door, GateCall.of(it, roster, doors), station) },
        )
    }

    /** The buttons only work when the link is up AND this server answers the content commands for this phone. */
    fun usable(link: LinkState, support: ContentSupport): Boolean =
        link == LinkState.CONNECTED && support != ContentSupport.NO && support != ContentSupport.NOT_ENROLLED

    /** [view] with the cameras whose id is in [offIds] marked off (0.6.53). The same object when none is. */
    fun withOff(view: HomeView, offIds: Set<String>): HomeView {
        if (offIds.isEmpty() || view.blocks.none { b -> b.cameras.any { it.id in offIds } }) return view
        return view.copy(blocks = view.blocks.map { b -> b.copy(cameras = b.cameras.map { if (it.id in offIds) it.copy(off = true) else it }) })
    }

    internal fun cameraTile(c: ContentCamera, usable: Boolean, pictures: Set<String>, size: CameraSize = CameraSize.BIG, defaultSize: CameraSize = size): CameraTile {
        // A camera's picture identifies itself: an empty or blank label is "no name at all" ("" here, not the entity id as for a
        // button), and HA's own name under it goes too.
        val label = Label.text(c.label, Label.MAX)
        return CameraTile(
            id = c.id,
            label = label,
            detail = if (label.isEmpty()) null else Label.clean(c.name, Label.MAX)?.takeIf { it != label },
            picture = when {
                !usable -> PictureState.OFFLINE
                !c.available -> PictureState.CAMERA_UNAVAILABLE
                c.id in pictures -> PictureState.PICTURE
                else -> PictureState.LOADING
            },
            size = size,
            defaultSize = defaultSize,
            live = c.live,
            liveRtsp = c.liveRtsp,
            hasSmall = c.small,
            bigHeight = c.bigHeight,
            smallHeight = c.smallHeight,
        )
    }

    internal fun buttonTile(
        a: ContentAction,
        usable: Boolean,
        running: Set<String>,
        calling: String?,
        talk: TalkUiState? = null,
        door: String? = null,
        /** Non-null: this `call` button is aimed at the gate, so it behaves as a Talk button ([GateCall]). */
        gate: GateCall? = null,
        /** APP-03: the station call; it makes the Talk button of ITS door look like the call, whoever started it. */
        station: StationCall? = null,
    ): ButtonTile {
        val label = Label.text(a.label, Label.MAX, a.id)
        // A talk of THIS phone on THIS button: live (connecting or in the call) it outranks everything, a lost link included
        // (Stop must stay reachable), or just ended (its one line is shown over the server's state).
        val mine = if (a.isTalk || gate != null) talk?.takeIf { it.request?.actionId == a.id } else null
        // The Talk element's own door wins over the block's (the server resolves the press against it); a block with no door has only this one.
        val talkDoor = a.door ?: door
        val tileDoor = if (a.isTalk) talkDoor else gate?.doorId
        val here = station?.takeIf { (a.isTalk || gate != null) && tileDoor != null && it.doorId == tileDoor }
        val state = when {
            mine != null && mine.isLive -> if (mine.snapshot.phase == TalkPhase.IN_CALL) ButtonState.TALK_IN_CALL else ButtonState.TALK_CONNECTING
            // APP-03: a call from the station to this door, ringing or answered, outranks the server's state (the green look, End must stay reachable).
            here != null -> when (here.phase) {
                StationPhase.RINGING -> ButtonState.RINGING_IN
                StationPhase.CONNECTING -> ButtonState.TALK_CONNECTING
                StationPhase.IN_CALL -> ButtonState.TALK_IN_CALL
            }
            !usable -> ButtonState.OFFLINE
            a.isCall && a.id == calling -> ButtonState.CALLING
            a.id in running -> ButtonState.RUNNING
            a.isCall && !a.available -> callState(a.reason)
            // A call button aimed at the gate is the Talk session: ready on the server's `available` and a known door, else off.
            gate != null -> if (TalkGate.ready(a, usable, gate)) ButtonState.READY else ButtonState.UNAVAILABLE
            // Talk is ready only when the server says so ([TalkGate]); today it says `available: false, talk_off` for every door.
            a.isTalk -> if (TalkGate.ready(a, usable)) ButtonState.READY else ButtonState.UNAVAILABLE
            !a.available -> ButtonState.UNAVAILABLE
            else -> ButtonState.READY
        }
        return ButtonTile(
            id = a.id,
            label = label,
            detail = Label.clean(a.name, Label.MAX)?.takeIf { it != label },
            kind = a.kind,
            icon = a.icon,
            // Nobody is asked "are you sure" before a call or a talk (CB-9): not honoured even if a server sends it.
            confirm = a.confirm && !a.isCall && !a.isTalk,
            state = state,
            color = ButtonPalette.of(a.color)?.name,
            // APP-02: a Talk or call button is the phone icon alone, whatever the document asks for; its name is the TalkBack label and tooltip.
            iconOnly = (!a.showLabel || a.isTalk || a.isCall) && HomeIcons.resolve(a.icon, a.kind) != null,
            reason = if (state == ButtonState.UNAVAILABLE && a.isTalk) ButtonReason.of(a.reason) else null,
            door = if (a.isTalk) talkDoor else gate?.doorId,
            endLine = if (mine != null && mine.snapshot.phase == TalkPhase.ENDED) TalkErrors.endLine(mine.snapshot) else null,
            talkRoute = gate != null,
        )
    }

    /** Why a call button is off, from the server's `reason`. An unknown or missing reason means the target is gone. */
    internal fun callState(reason: String?): ButtonState = when (reason) {
        "offline", "push_only" -> ButtonState.TARGET_OFFLINE
        "busy" -> ButtonState.TARGET_BUSY
        "dnd" -> ButtonState.TARGET_DND
        "receive_off" -> ButtonState.TARGET_RECEIVE_OFF
        "not_available" -> ButtonState.CALLS_NOT_AVAILABLE
        else -> ButtonState.TARGET_GONE // unknown_target, disabled, null
    }
}
