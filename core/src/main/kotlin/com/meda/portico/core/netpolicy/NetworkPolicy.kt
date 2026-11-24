package com.meda.portico.core.netpolicy

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.roaming.RoamingFacts

/** Which kind of connection the phone is on, as Settings sees it (0.6.58): three rows of choices, one per class. */
enum class NetClass(val key: String) {
    WIFI("wifi"),
    MOBILE("mobile"),
    ROAMING("roaming"),
    ;

    companion object {
        fun of(key: String?): NetClass? = entries.firstOrNull { it.key == key }
    }
}

/** "Stay connected" keeps the link to Home Assistant open (the connection service); "Push notification" does not, the push rings the phone. */
enum class ConnectionMode(val key: String) {
    STAY("stay"),
    PUSH("push"),
    ;

    companion object {
        fun of(key: String?): ConnectionMode? = entries.firstOrNull { it.key == key }
    }
}

/** What the camera tiles may use on a network class. A CAP: the per-camera choices (Auto, a size, stills, off) work inside it. */
enum class VideoMode(val key: String) {
    LIVE_BIG("live_big"),
    LIVE_SMALL("live_small"),
    STILLS("stills"),
    OFF("off"),
    ;

    /** Live video of any size is allowed. */
    val live: Boolean get() = this == LIVE_BIG || this == LIVE_SMALL

    companion object {
        fun of(key: String?): VideoMode? = entries.firstOrNull { it.key == key }
    }
}

/** The six choices, one connection mode and one video mode per class. */
data class NetworkChoices(
    val connection: Map<NetClass, ConnectionMode>,
    val video: Map<NetClass, VideoMode>,
) {
    fun connection(c: NetClass): ConnectionMode = connection[c] ?: ConnectionMode.STAY
    fun video(c: NetClass): VideoMode = video[c] ?: VideoMode.STILLS

    /** The connection service is wanted while at least one class keeps the link. */
    val anyStay: Boolean get() = NetClass.entries.any { connection(it) == ConnectionMode.STAY }
}

/** What the video rules say right now: the [mode] that applies, and [roamingHold]: the roaming block holds every picture back until the viewer taps. */
data class VideoVerdict(val mode: VideoMode, val roamingHold: Boolean = false) {
    /** No live command, no stills: the tile draws its Off state (not for the roaming hold, which draws its own notice). */
    val picturesOff: Boolean get() = mode == VideoMode.OFF && !roamingHold
    val live: Boolean get() = mode.live
    val smallOnly: Boolean get() = mode == VideoMode.LIVE_SMALL
}

/** One camera after the cap: [off] no picture at all, [wantsLive] live video, [stream] the stream to ask for. */
data class CappedCamera(val off: Boolean, val wantsLive: Boolean, val stream: CameraSize)

/** The pure rules behind Settings' "on Wi-Fi / on mobile / on roaming" rows (0.6.58). */
object NetworkPolicy {

    /**
     * Defaults that reproduce 0.6.57: "Stay connected" and "Live video in this app" were one switch each for every network, and the roaming
     * block held the picture back on a roaming plan. So: Wi-Fi and Mobile follow the old switches, Roaming video is Off (a tap shows it for
     * the trip, see [video]); Roaming's connection follows the old switch like the others.
     */
    fun defaults(oldStayConnected: Boolean, oldLiveVideo: Boolean): NetworkChoices {
        val conn = if (oldStayConnected) ConnectionMode.STAY else ConnectionMode.PUSH
        val vid = if (oldLiveVideo) VideoMode.LIVE_BIG else VideoMode.STILLS
        return NetworkChoices(
            connection = NetClass.entries.associateWith { conn },
            video = mapOf(NetClass.WIFI to vid, NetClass.MOBILE to vid, NetClass.ROAMING to VideoMode.OFF),
        )
    }

    /**
     * The class of the phone's data connection, or null for "no default network right now" (the caller keeps its last answer).
     * Wi-Fi or a cable, or provably at home (the direct link is up while a Wi-Fi exists), is [NetClass.WIFI]; anything else is
     * [NetClass.ROAMING] when the roaming verdict says away, else [NetClass.MOBILE]. Unknown is Mobile.
     */
    fun classify(f: RoamingFacts, away: Boolean): NetClass? = when {
        f.lanVerified || f.wifiOrEthernet -> NetClass.WIFI
        !f.haveDefaultNet -> null
        away -> NetClass.ROAMING
        else -> NetClass.MOBILE
    }

    /**
     * The video rules for [cls]. On roaming a setting of Off is the roaming block as it was: every picture held back
     * ([VideoVerdict.roamingHold]) until the viewer taps ([tripAllowed]); after the tap the trip uses the Mobile setting.
     * Any other roaming setting is the viewer's own choice and is never held back.
     */
    fun video(cls: NetClass, choices: NetworkChoices, tripAllowed: Boolean): VideoVerdict {
        val mode = choices.video(cls)
        if (cls == NetClass.ROAMING && mode == VideoMode.OFF) {
            return if (tripAllowed) VideoVerdict(choices.video(NetClass.MOBILE)) else VideoVerdict(VideoMode.OFF, roamingHold = true)
        }
        return VideoVerdict(mode)
    }

    /**
     * One camera inside the cap. [off] and [wantsLive] are the viewer's per-camera choices, [stream] the stream the viewport would ask for,
     * [hasSmall] whether the camera has a small stream. Off cap: off. Stills cap: never live. Live small: the small stream when there is one
     * (a camera with one stream plays it). Live big: as chosen.
     */
    fun cap(v: VideoVerdict, off: Boolean, wantsLive: Boolean, stream: CameraSize, hasSmall: Boolean): CappedCamera = CappedCamera(
        off = off || v.picturesOff,
        wantsLive = wantsLive && v.live,
        stream = if (v.smallOnly && hasSmall) CameraSize.SMALL else stream,
    )
}
