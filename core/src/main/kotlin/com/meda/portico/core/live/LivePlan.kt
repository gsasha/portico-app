package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.LiveRtsp

/** What is true of the whole screen right now (the owner's decisions 3, 7 and 9). */
data class LiveGlobal(
    /** Settings -> "Live video in this app". Off: no live command is ever sent and the app is 0.6.15. */
    val switchOn: Boolean,

    /** Home is started (not in the background, the screen on). */
    val shown: Boolean,

    /** The app's pause feature is on. */
    val paused: Boolean,

    /** The link to Home Assistant is up. */
    val connected: Boolean,

    /**
     * The path of the open socket: [LinkPath.DIRECT] at home (WebRTC), [LinkPath.TAILNET] away (RTSP through the tailnet, S1b),
     * null while not known (nothing is attempted and nothing is said).
     */
    val path: LinkPath?,

    /** This build has the Go RTSP client (`gocore.aar`). Always true when the path is the tailnet; false in a build without it. */
    val rtspReady: Boolean = true,

    /** DEV-713 S2: the phone is hot ([ThermalGuard]): every tile is a still, with the notice, until it is not. */
    val thermalHot: Boolean = false,

    /** DEV-713 S2: how many live videos this phone can decode at once ([DecoderCap]); tiles beyond it stay stills. Unlimited until a probe says otherwise. */
    val maxStreams: Int = Int.MAX_VALUE,

    /**
     * A-10 / DEV-702: the phone is on a roaming or foreign mobile plan and the viewer has not allowed pictures for this trip
     * (`RoamingGate.blocking`). Nothing is opened, every usable tile shows the notice, and a chip tap lifts the block. It is
     * asked even when the Settings switch is off, because stills are held back too (the switch only governs live video).
     */
    val roamingBlocked: Boolean = false,
)

/** One camera tile, as the plan needs it. */
data class LiveTileIn(
    val cameraId: String,
    val size: CameraSize,

    /** The camera is usable now (the tile has, or is getting, a picture). */
    val available: Boolean,

    /** What the viewer's memory and the document's `live` say ([LiveMemory.effective]): this tile should be live. */
    val wantsLive: Boolean,

    /** The tile is at least partly on screen (not scrolled completely out of view). */
    val onScreen: Boolean,

    /** The server's away-from-home addresses for this tile (`live_rtsp`), already validated; null: it has none. */
    val rtsp: LiveRtsp? = null,

    /** 0.6.53: the viewer turned this camera view OFF (video-size menu, `CameraChoices`): no session, no chip, no notice. The caller also asks for no stills. */
    val off: Boolean = false,
)

/** What the plan decided for one tile. */
data class TilePlan(
    /** The LIVE chip is drawn at all (switch on, screen live, camera usable). */
    val chip: Boolean,
    val wantsLive: Boolean,

    /** The stream this tile should have now, or null. */
    val key: LiveKey?,

    /** It wants live but the link is the tailnet and this build cannot play RTSP: the notice says so and nothing is opened. */
    val away: Boolean,

    /** It wants live, the link is the tailnet, and the server gave this tile no RTSP address: the notice says so and nothing is opened. */
    val notSetUp: Boolean = false,

    /** It wants live but the phone is hot: nothing is opened and the notice says so. */
    val thermal: Boolean = false,

    /** It wants live and could stream, but the phone's decoders are all in use by tiles before it: nothing is opened and the notice says so. */
    val capped: Boolean = false,

    /** A-10: held back because of the roaming block: nothing is opened and the notice says so. */
    val roaming: Boolean = false,
)

data class LivePlanResult(
    /** Every stream that should run now; every other session is closed. */
    val wanted: Set<LiveKey>,

    /** By camera id. */
    val tiles: Map<String, TilePlan>,

    /** Why a session that is not wanted is closed, when [stop] has no word for its camera (the whole screen is closed, or the tile is gone). */
    val idle: StopWhy,

    /** Per camera id: why that camera's session is closed when it is not wanted. */
    val stop: Map<String, StopWhy> = emptyMap(),

    /** The RTSP address of each wanted RTSP stream ([LiveKey.rtsp]). Only the app's connection code reads it; it never reaches the Journal or the screen. */
    val urls: Map<LiveKey, String> = emptyMap(),
) {
    fun reasonFor(key: LiveKey): StopWhy = stop[key.cameraId] ?: idle
}

/**
 * The live/still decision of the Home screen, pure (DEV-713 S1). One call per tick / scroll / state change:
 *
 * 1. Switch off: nothing is wanted, no chip, no notice (the app behaves as 0.6.15, and no live command can be sent).
 * 2. Home not shown (onStop, screen off), the pause feature on, or the link not connected: nothing is wanted, no chip.
 * 3. Otherwise each usable camera tile has a chip. A tile that wants live ([LiveTileIn.wantsLive]) streams when it is on screen, by
 *    the link's path: DIRECT (at home) is WebRTC; TAILNET (away) is RTSP through the tailnet when the tile has an address
 *    ([LiveTileIn.rtsp]: the sub stream for a Small tile, else the main one), and otherwise shows "not set up for this camera" and
 *    opens nothing. An unknown path attempts and says nothing. A tile completely off screen does not stream.
 */
object LivePlan {
    /**
     * 0.6.51: the one Journal line for "the link is connected but its path is unknown, so no live session can start" (the 0.6.50 field
     * failure: chips stuck on PENDING). Null when that is not the situation. The caller writes it once per entry into the state.
     */
    fun pathUnknownLine(g: LiveGlobal, anyWantsLive: Boolean): String? =
        if (g.switchOn && g.shown && !g.paused && g.connected && g.path == null && anyWantsLive) "live idle why=path-unknown" else null

    fun plan(g: LiveGlobal, tiles: List<LiveTileIn>): LivePlanResult {
        val closed: StopWhy? = when {
            !g.switchOn -> StopWhy.SWITCH
            !g.shown -> StopWhy.BACKGROUND
            g.paused -> StopWhy.PAUSED
            !g.connected -> StopWhy.CLOSED
            else -> null
        }
        val screenLive = g.shown && !g.paused && g.connected
        if (closed != null && !(g.roamingBlocked && screenLive)) return LivePlanResult(emptySet(), emptyMap(), closed)
        if (g.roamingBlocked) {
            // A-10: roaming holds the picture back, live and still. Every usable tile gets the chip and the notice; nothing is opened.
            val held = LinkedHashMap<String, TilePlan>()
            val why = HashMap<String, StopWhy>()
            for (t in tiles) {
                if (t.off) {
                    held[t.cameraId] = TilePlan(chip = false, wantsLive = false, key = null, away = false)
                    why[t.cameraId] = StopWhy.SWITCH
                    continue
                }
                val usable = t.available
                held[t.cameraId] = TilePlan(chip = usable, wantsLive = usable && t.wantsLive, key = null, away = false, roaming = usable)
                why[t.cameraId] = StopWhy.ROAMING
            }
            return LivePlanResult(emptySet(), held, StopWhy.ROAMING, why)
        }
        val wanted = LinkedHashSet<LiveKey>()
        val out = LinkedHashMap<String, TilePlan>()
        val stop = HashMap<String, StopWhy>()
        val urls = LinkedHashMap<LiveKey, String>()
        for (t in tiles) {
            if (t.off) {
                // 0.6.53: off by the viewer's choice: nothing is opened, no chip, no notice (the tile draws its own "off" state)
                out[t.cameraId] = TilePlan(chip = false, wantsLive = false, key = null, away = false)
                stop[t.cameraId] = StopWhy.SWITCH
                continue
            }
            if (!t.available) {
                out[t.cameraId] = TilePlan(chip = false, wantsLive = false, key = null, away = false)
                stop[t.cameraId] = StopWhy.CLOSED
                continue
            }
            var key: LiveKey? = null
            var away = false
            var notSetUp = false
            var thermal = false
            var capped = false
            // the route is the link's path (DEV-713 S1b): DIRECT = WebRTC (S1); TAILNET = RTSP, with the sub stream for a Small tile and the
            // main one for a Big tile (the owner, Q8) when the server gave this tile an address; no address = "not set up for this camera"
            var routed = false
            if (t.wantsLive && g.thermalHot) {
                thermal = true // hot: a still, whatever the route (ThermalGuard journals the transition, the session's stop says why=thermal)
            } else if (t.wantsLive) {
                when (g.path) {
                    LinkPath.DIRECT -> {
                        routed = true
                        if (t.onScreen) key = LiveKey(t.cameraId, t.size)
                    }
                    LinkPath.TAILNET -> {
                        val url = t.rtsp?.forSize(t.size)
                        val name = url?.let { LiveRtsp.nameOf(it) }
                        when {
                            !g.rtspReady -> away = true
                            url == null || name == null -> notSetUp = true
                            else -> {
                                routed = true
                                if (t.onScreen) {
                                    key = LiveKey(t.cameraId, t.size, name)
                                    urls[key] = url
                                }
                            }
                        }
                    }
                    null -> Unit
                }
            }
            // the decoder-instance guard: tiles are served in order, one stream each; a tile that would be over the phone's limit stays a still
            if (key != null && key !in wanted && wanted.size >= g.maxStreams.coerceAtLeast(1)) {
                urls.remove(key)
                key = null
                capped = true
            }
            if (key != null) wanted.add(key)
            out[t.cameraId] = TilePlan(chip = true, wantsLive = t.wantsLive, key = key, away = away, notSetUp = notSetUp, thermal = thermal, capped = capped)
            // why a session of this camera that is not the wanted one is closed: the viewer chose the still, the tile is completely off
            // screen (only on a path that has a route); otherwise the link changed path or the tile changed size (its old key goes)
            stop[t.cameraId] = when {
                !t.wantsLive -> StopWhy.SWITCH
                thermal -> StopWhy.THERMAL
                routed && !t.onScreen -> StopWhy.HIDDEN
                else -> StopWhy.CLOSED
            }
        }
        return LivePlanResult(wanted, out, StopWhy.CLOSED, stop, urls)
    }
}

enum class ChipLook {
    /** No chip. */
    NONE,

    /** A still that can go live: dim "Live" with a play glyph; a tap makes it live. */
    OFFER,

    /** Set to live but no video yet (connecting, waiting to retry, gave up, away, off screen): dim "Live..."; a tap makes it a still (asks again if it gave up). */
    PENDING,

    /** Real video is rendering: red dot and LIVE; a tap makes it a still. */
    ON,

    /** A-10: the roaming block holds the picture back: "Roaming. Tap to show"; a tap lifts the block for the trip. */
    ROAMING,
}

/** What the screen reads for one tile: [ui] to draw, [key] the stream the renderer is bound to (null: none wanted now), [canRetry] a tap restarts a session that gave up. */
data class LiveTileView(val ui: TileLiveUi, val key: LiveKey?, val canRetry: Boolean = false)

/** What one tile draws for live video. */
data class TileLiveUi(
    val chip: ChipLook,

    /** Real video is rendering and shown: the renderer is visible and the still is not needed. */
    val video: Boolean,

    /** The fps text (`--`, `N fps`, `0 fps`), only on a playing tile and only when the document asks for it. */
    val fps: String?,

    /** The small notice, or null. */
    val notice: LiveWhy?,
) {
    companion object {
        val NONE = TileLiveUi(ChipLook.NONE, video = false, fps = null, notice = null)

        /**
         * [plan] is the tile's, [state] / [why] / [fpsLabel] the running session's (null state: none yet). [zoomed]: a pinch is on the
         * tile, so the still is shown (the chip stays ON, the stream keeps running).
         */
        fun of(plan: TilePlan?, state: LiveSession.State?, why: LiveWhy?, fpsLabel: String, showFps: Boolean, zoomed: Boolean): TileLiveUi {
            if (plan == null || !plan.chip) return NONE
            if (plan.roaming) return TileLiveUi(ChipLook.ROAMING, video = false, fps = null, notice = LiveWhy.ROAMING)
            if (!plan.wantsLive) return TileLiveUi(ChipLook.OFFER, video = false, fps = null, notice = null)
            if (plan.thermal) return TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = LiveWhy.HOT)
            if (plan.away) return TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = LiveWhy.AWAY)
            if (plan.notSetUp) return TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = LiveWhy.NOT_SET_UP)
            if (plan.capped) return TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = LiveWhy.PHONE_LIMIT)
            return when (state) {
                LiveSession.State.PLAYING ->
                    TileLiveUi(ChipLook.ON, video = !zoomed, fps = if (showFps) fpsLabel else null, notice = null)
                LiveSession.State.RETRYING, LiveSession.State.FAILED ->
                    TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = why)
                // starting (a notice stays from the failure before it), not started, off screen
                else -> TileLiveUi(ChipLook.PENDING, video = false, fps = null, notice = if (state == LiveSession.State.STARTING) why else null)
            }
        }
    }
}
