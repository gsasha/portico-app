package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize

/**
 * One live stream of the phone: a camera at a tile size (the card keys its sessions the same way). A size change is a new key.
 * [stream] is null for the WebRTC route (at home) and, for the RTSP route (away, DEV-713 S1b), the go2rtc stream NAME
 * (`gate_intercom_sub`): the route is part of the key, so a change of link path replaces the session, and the name is the only
 * thing of the address that may reach the Journal (never the host).
 */
data class LiveKey(val cameraId: String, val size: CameraSize, val stream: String? = null) {
    /** This stream is the RTSP route. */
    val rtsp: Boolean get() = stream != null

    override fun toString() = "$cameraId|${size.wire}" + (stream?.let { "|rtsp:$it" } ?: "")
}

/**
 * The CLASS of a reason live video is not running on a tile (DEV-713, the owner's decision 4: a small visible notice, never silent).
 * [word] is what the Journal writes. The wording on screen is the app's (`strings.xml`, `live_notice_*`).
 */
enum class LiveWhy(val word: String) {
    /** The phone's link is the tailnet (not at home) and this build has no RTSP client to play it with: stills stay. */
    AWAY("away"),

    /** The phone is away but the server gave this tile no RTSP address (`live_rtsp_base` not set, or the camera is not Frigate-backed): stills stay. */
    NOT_SET_UP("notsetup"),

    /** The server says this camera has no live video (`live_unavailable`, `unavailable`). */
    NO_LIVE("nolive"),

    /** The server's technical guard refused another stream (`too_many_live`). */
    TOO_MANY("toomany"),

    /** The phone could not make a video decoder or a renderer, or the connection was up and no picture came. */
    DECODER("decoder"),

    /** No connection to the camera server within the deadline, a failed peer connection, a lost socket. */
    CONNECT("connect"),

    /** Home Assistant refused or does not know the command (an older server, a grant). */
    SERVER("server"),

    /** DEV-713 S2: the phone is hot ([ThermalGuard]): every live tile is a still until it has cooled. Not a failure of the stream. */
    HOT("hot"),

    /** DEV-713 S2: more live videos than this phone's hardware decoders allow ([DecoderCap]); the tile stays a still. */
    PHONE_LIMIT("phonelimit"),

    /** A-10 / DEV-702: the phone is on a foreign or roaming mobile plan; the picture is held back until the viewer taps (the tap lasts for the trip). */
    ROAMING("roaming"),
    ;

    companion object {
        /** The class of a server error code, whether it answered the command or came as an `error` event. */
        fun ofCode(code: String): LiveWhy = when (code) {
            "too_many_live" -> TOO_MANY
            "live_unavailable", "unavailable" -> NO_LIVE
            else -> SERVER
        }
    }
}

/** Why a session was closed, for the Journal line (`live stop ... why=`). */
enum class StopWhy(val word: String) {
    /** The tile is completely off screen. */
    HIDDEN("hidden"),

    /** Home left the screen (onStop: another app, the screen off, a call screen). */
    BACKGROUND("background"),

    /** The app's pause feature is on. */
    PAUSED("paused"),

    /** Settings -> "Live video in this app" is off, or the viewer switched this tile to the still. */
    SWITCH("switch"),

    /** The link is not connected, or it is no longer the direct path, or the tile left the page / changed size. */
    CLOSED("closed"),

    /** DEV-713 S2: the phone is hot ([ThermalGuard]). */
    THERMAL("thermal"),

    /** A-10 / DEV-702: the roaming block holds the picture back. */
    ROAMING("roaming"),
}

/** The Journal's view of live video (observation only, like the audio lines). Ids are shortened by the formatter. */
interface LiveLog {
    /** The first frame arrived [ttffMs] after the attempt began. [stream] is the server's `main` / `sub`. */
    fun started(key: LiveKey, stream: String?, ttffMs: Long)

    /** An attempt failed; [willRetry] says whether another follows. */
    fun failed(key: LiveKey, why: LiveWhy, retry: Int, willRetry: Boolean)

    /** A session that was running or trying was closed on purpose after [playedMs] of video. */
    fun stopped(key: LiveKey, why: StopWhy, playedMs: Long)

    /** DEV-713 S2: the phone's thermal state crossed the line ([hot] true: live paused). [status] is Android's `THERMAL_STATUS_*` number. One line per transition. */
    fun thermal(status: Int, hot: Boolean) {}

    /** DEV-713 S2: live code threw somewhere it was fenced off ([at] names the place, [error] the exception class). The screen never sees it. */
    fun fault(at: String, error: String) {}

    /**
     * DEV-764 (camera diagnostics): one compact `ui <event>` line about a live stream ([key] null: about the whole screen). Observation only.
     * [fields] are numbers, booleans, enums or lowercase words; the journal's formatter drops anything else.
     */
    fun diag(event: String, key: LiveKey?, fields: List<Pair<String, Any?>> = emptyList()) {}

    companion object {
        val NONE = object : LiveLog {
            override fun started(key: LiveKey, stream: String?, ttffMs: Long) {}
            override fun failed(key: LiveKey, why: LiveWhy, retry: Int, willRetry: Boolean) {}
            override fun stopped(key: LiveKey, why: StopWhy, playedMs: Long) {}
        }
    }
}
