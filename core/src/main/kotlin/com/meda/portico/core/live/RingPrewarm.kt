package com.meda.portico.core.live

import com.meda.portico.core.content.CameraViewport
import com.meda.portico.core.content.StreamPick
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.push.Redact

/** What the phone's DEFAULT network is, reduced to what the pre-warm needs. [UNKNOWN] is "no network, or cannot tell": the pre-warm does nothing. */
enum class DefaultNet { WIFI, MOBILE, UNKNOWN }

/** One camera of the page the ring opens, as the pre-warm needs it. [size] is the TILE size on that page; the stream is picked from it exactly as the screen would ([RingPrewarm.keyFor]). */
data class PrewarmCam(
    val cameraId: String,
    val size: CameraSize,
    val hasSmall: Boolean,
    /** The camera is usable (not unavailable, not offline). */
    val available: Boolean,
    /** What the screen would decide for this tile: this phone's LIVE-chip choice, else the document's `live`. */
    val wantsLive: Boolean,
    /** 0.6.58: the network class's video setting is "Live video small": the screen asks for the small stream wherever the camera has one. */
    val smallOnly: Boolean = false,
)

/** What the pre-warm decision looks at. */
data class PrewarmIn(
    /** Settings -> "Live video in this app". */
    val liveSwitchOn: Boolean,
    val paused: Boolean,
    /** `RoamingGate.blocking()`: foreign or roaming plan, not allowed for this trip. */
    val roamingBlocked: Boolean,
    val net: DefaultNet,
    /** The path of the open socket; only [LinkPath.DIRECT] (WebRTC at home) is pre-warmed. */
    val path: LinkPath?,
    /** The ring is up on this phone right now. */
    val ringing: Boolean,
    /** A pre-warm already exists for this call (one set per ring). */
    val alreadyForThisCall: Boolean,
    /** The cameras of the page the ring opens, in page order. */
    val cameras: List<PrewarmCam>,
    /** The Home screen is started already (the person is looking at it): its own plan streams its tiles, a second planner would fight it. */
    val screenUp: Boolean = false,
)

/** Why nothing was pre-warmed. [word] is what the Journal writes (`ui prewarm skip why=`). */
enum class PrewarmSkip(val word: String) {
    SWITCH_OFF("switch"),
    PAUSED("paused"),
    NOT_RINGING("notringing"),
    ALREADY("already"),
    SCREEN_UP("screenup"),
    NOT_WIFI("notwifi"),
    NETWORK_UNKNOWN("netunknown"),
    ROAMING("roaming"),
    PATH("path"),
    NO_CAMERAS("nocams"),
}

sealed interface PrewarmDecision {
    data class Start(val cameras: List<PrewarmCam>) : PrewarmDecision
    data class Skip(val why: PrewarmSkip) : PrewarmDecision
}

/** Why a pre-warm ended on its own account (`ui prewarm stop why=`). A hand-off is not a stop: the screen owns the sessions from then on. */
enum class PrewarmStop(val word: String) {
    /** The ring ended: answered elsewhere, declined, missed. */
    ENDED("ended"),

    /** [RingPrewarm.MAX_HOLD_MS] passed and nobody took the call. */
    TIMEOUT("timeout"),

    /** The pre-warm was switched off from outside (pause, the app shut down). */
    CANCELLED("cancelled"),
}

/**
 * the owner, D-20261111-be58: "if I get a call and the phone is on wifi, start connecting to the cameras right away, even before I took the
 * call", so the first live picture does not wait for a key frame (the doorbell's GOP is 5 s) when the Answer button is pressed. The pure
 * rules; the app side ([com.meda.portico.live] `RingPrewarm`'s glue) only applies them. No Android type here.
 *
 * Only when the phone is KNOWN to be on Wi-Fi/Ethernet (not mobile, not roaming, and an unknown network is "do nothing"), on the direct
 * (WebRTC) path, with the live switch on and not paused. It starts exactly the streams the screen would start for the page the ring opens:
 * the tiles that want live, at the stream [keyFor] picks (AUTO), and never more than [MAX_CAMERAS].
 */
object RingPrewarm {
    /** Never more than this many streams, however many tiles the page has (battery, decoders). */
    const val MAX_CAMERAS = 4

    /** A pre-warm nobody takes over closes this long after it started (a doorbell rings far shorter; this is the backstop). */
    const val MAX_HOLD_MS = 45_000L

    /** After the hand-off the screen's first plan keeps these streams alive this long even before its tiles are laid out (otherwise the first tick would close them as "off screen"). */
    const val PROTECT_MS = 4_000L

    fun decide(i: PrewarmIn): PrewarmDecision {
        val skip = when {
            !i.liveSwitchOn -> PrewarmSkip.SWITCH_OFF
            i.paused -> PrewarmSkip.PAUSED
            !i.ringing -> PrewarmSkip.NOT_RINGING
            i.alreadyForThisCall -> PrewarmSkip.ALREADY
            i.screenUp -> PrewarmSkip.SCREEN_UP
            i.net == DefaultNet.UNKNOWN -> PrewarmSkip.NETWORK_UNKNOWN
            i.net == DefaultNet.MOBILE -> PrewarmSkip.NOT_WIFI
            i.roamingBlocked -> PrewarmSkip.ROAMING
            i.path != LinkPath.DIRECT -> PrewarmSkip.PATH
            else -> null
        }
        if (skip != null) return PrewarmDecision.Skip(skip)
        val cams = i.cameras.filter { it.available && it.wantsLive }.distinctBy { it.cameraId }.take(MAX_CAMERAS)
        return if (cams.isEmpty()) PrewarmDecision.Skip(PrewarmSkip.NO_CAMERAS) else PrewarmDecision.Start(cams)
    }

    /** The stream the screen would ask for this tile: the viewport's rule with no pick (AUTO), i.e. [CameraViewport.autoStream]. Equal keys are what lets the screen adopt the session. */
    fun keyFor(c: PrewarmCam): LiveKey = LiveKey(c.cameraId, CameraViewport.stream(if (c.smallOnly) StreamPick.SMALL else StreamPick.AUTO, c.size, c.hasSmall))

    /** Why the pre-warm should end now, or null. A hand-off ends the pre-warm's own watch (the caller does not ask afterwards). */
    fun stopWhy(startedAtMs: Long, nowMs: Long, ringing: Boolean): PrewarmStop? = when {
        !ringing -> PrewarmStop.ENDED
        nowMs - startedAtMs >= MAX_HOLD_MS -> PrewarmStop.TIMEOUT
        else -> null
    }

    // ---- Journal lines (the text without its time; `PushJournal.cameraView` stamps it) ----

    fun startLine(doorRef: String, cams: Int): String = "ui prewarm start door=${Redact.prefix(doorRef)} cams=$cams"

    fun skipLine(doorRef: String, why: PrewarmSkip): String = "ui prewarm skip door=${Redact.prefix(doorRef)} why=${why.word}"

    fun firstFrameLine(cameraId: String, ms: Long): String = "ui prewarm first-frame cam=${Redact.prefix(cameraId)} ms=$ms"

    fun handoffLine(cameraId: String, ageMs: Long, playing: Boolean): String =
        "ui prewarm handoff cam=${Redact.prefix(cameraId)} age=${ageMs}ms playing=${if (playing) "yes" else "no"}"

    fun stopLine(why: String, heldMs: Long): String = "ui prewarm stop why=$why held=${heldMs}ms"
}

/**
 * One pre-warm: its call, its streams, when it began and which cameras have shown their first frame. Pure bookkeeping for the glue;
 * main thread. One per ring: [RingPrewarm.decide] is given [Tracker.has] as `alreadyForThisCall`.
 */
class PrewarmRun(val doorRef: String, val callId: String, val startedAtMs: Long, val keys: List<LiveKey>) {
    private val seen = HashSet<String>()

    /** True when the screen has taken the sessions over. */
    var handedOff: Boolean = false
        private set

    /** The first-frame time of [cameraId] in ms since the ring, once; null when it was already reported. */
    fun firstFrame(cameraId: String, nowMs: Long): Long? = if (cameraId in seen) null else { seen.add(cameraId); nowMs - startedAtMs }

    fun handoff() {
        handedOff = true
    }

    fun age(nowMs: Long): Long = nowMs - startedAtMs
}

/** The calls that have a pre-warm (one per ring, however often the ring state changes). */
class PrewarmTracker {
    private val calls = LinkedHashSet<String>()

    fun has(callId: String): Boolean = callId in calls

    fun add(callId: String) {
        calls.add(callId)
        if (calls.size > 32) calls.remove(calls.first()) // a day of doorbells never grows it
    }
}
