package com.meda.portico.core.stay

import com.meda.portico.core.call.PathChoice
import com.meda.portico.core.call.RouteChoice
import com.meda.portico.core.netpolicy.ConnectionMode
import com.meda.portico.core.ring.ChannelState

/**
 * Journal words for the connection policy (DEV-811 follow-up, 0.6.92). Everything here is a fixed word: no id, address, name or free text,
 * so the lines pass `JournalLines.check` (the reporter's secret filter) and are readable on their own.
 */
object StayWhy {
    /** Every `evaluate(..)` reason the app uses, as a journal word. Anything not in the table is `other`. */
    private val WORDS = mapOf(
        "a screen closed" to "screen-closed",
        "a screen opened" to "screen-opened",
        "battery saver on" to "battery-saver-on",
        "battery saver off" to "battery-saver-off",
        "boot or update" to "boot-or-update",
        "call ended" to "call-ended",
        "enrolled" to "enrolled",
        "grace ended" to "grace-ended",
        "network" to "network",
        "network changed" to "network",
        "panel mode" to "panel-mode",
        "paused" to "paused",
        "process start" to "process-start",
        "push" to "push",
        "resumed" to "resumed",
        "ring cap" to "ring-cap",
        "ring ended" to "ring-ended",
        "ring started" to "ring-started",
        "service started" to "service-started",
        "settings saved" to "settings-saved",
        "tailnet path" to "tailnet-path",
        "the switch changed" to "switch-changed",
    )

    fun word(why: String): String = WORDS[why] ?: "other"

    val ALL: Set<String> get() = WORDS.values.toSet() + "other"

    /** The Journal event for a connection decision: `stay-release`, `stay-connect`, `stay-defer`, `stay-idle`. */
    fun event(c: StayConnectedPolicy.Connection): String = when (c) {
        StayConnectedPolicy.Connection.HOLD -> "stay-connect"
        StayConnectedPolicy.Connection.RELEASE -> "stay-release"
        StayConnectedPolicy.Connection.DEFER -> "stay-defer"
        StayConnectedPolicy.Connection.IDLE -> "stay-idle"
    }
}

/** Where the Journal lines of [StayConnected] go (`net <event> key=value`); the app writes them with `PushJournal.diag`. */
fun interface StayJournalSink {
    fun event(event: String, fields: List<Pair<String, Any?>>)

    companion object {
        val NONE = StayJournalSink { _, _ -> }
    }
}

/** Everything that decides how the connection, the ring and the call behave, as words (see [StayJournalLines]). */
data class ConfigFacts(
    val versionName: String,
    val versionCode: Int,
    val mobile: ConnectionMode?,
    val wifi: ConnectionMode?,
    val roaming: ConnectionMode?,
    /** Setup "Reconnect while ringing": true = Make-before-break, false = Classic. */
    val linkRace: Boolean,
    val path: PathChoice,
    /** Setup "Compatibility" (forces 8 kHz). */
    val compat: Boolean,
    val route: RouteChoice,
    val ringChannel: ChannelState,
    val ringBypassDnd: Boolean?,
    val notificationsOn: Boolean?,
    /** Setup "Answer without unlocking" (default on). */
    val answerWithoutUnlock: Boolean = true,
    /** Setup "Unlock when answering" (default on). */
    val unlockWhenAnswering: Boolean = true,
    /** 0.6.97: Panel mode on (the wall-tablet mode: the idle return to Fully only runs here). */
    val panel: Boolean = false,
    /** 0.6.97: the app is enrolled (the Panel idle count is suspended while it is not). */
    val enrolled: Boolean = false,
    /** 0.6.97: the Panel idle timeout in minutes (Setup; default 5). */
    val idleMin: Int = 0,
    /** 0.6.97: the length of the visible countdown bar in seconds (fixed by the design: 10). */
    val barSec: Int = 0,
)

/**
 * The the owner-2026-11-21 settings snapshot: one `net cfg` line (the settings) and one `net inputs` line (what the stay policy sees now and
 * what it decides), written at app start, on every settings change, and at the start of each ring and call, so a test can be read from the
 * Journal without asking what was set. Fixed words only.
 */
object StayJournalLines {
    /** Why a snapshot was taken (the `why=` word). */
    val WHYS = setOf("start", "setting", "ring", "call", "route", "setup")

    fun cfg(why: String, f: ConfigFacts): Pair<String, List<Pair<String, Any?>>> = "cfg" to listOf(
        "why" to (if (why in WHYS) why else "other"),
        "ver" to f.versionName.lowercase().replace('.', '-').replace(Regex("[^a-z0-9_-]"), "_"),
        "code" to f.versionCode,
        "mob" to word(f.mobile),
        "wifi" to word(f.wifi),
        "roam" to word(f.roaming),
        "race" to (if (f.linkRace) "mbb" else "classic"),
        "path" to f.path.name.lowercase(),
        "compat" to f.compat,
        "route" to f.route.name.lowercase(),
        "ringch" to f.ringChannel.name.lowercase(),
        "bypass" to (f.ringBypassDnd ?: "unknown"),
        "notif" to (f.notificationsOn ?: "unknown"),
        "ansnolock" to f.answerWithoutUnlock,
        "unlockans" to f.unlockWhenAnswering,
        "panel" to f.panel,
        "enrolled" to f.enrolled,
        "idlemin" to f.idleMin,
        "barsec" to f.barSec,
    )

    /** [cls] is the current network class word (`wifi`, `mobile`, `roaming`), [uiShown] the number of visible screens. */
    fun inputs(why: String, i: StayConnectedPolicy.Inputs, cls: String, uiShown: Int): Pair<String, List<Pair<String, Any?>>> = "inputs" to listOf(
        "why" to (if (why in WHYS) why else "other"),
        "cls" to (if (cls in CLASSES) cls else "unknown"),
        "net" to i.net.name.lowercase(),
        "ui" to uiShown.coerceIn(0, 9),
        "call" to i.callLive,
        "ring" to i.ringLive,
        "grace" to i.inGrace,
        "psave" to i.powerSave,
        "tn" to i.onTailnet,
        "push" to i.pushHere,
        "paused" to i.paused,
        "svc" to i.serviceRunning,
        "dec" to StayConnectedPolicy.decide(i).connection.name.lowercase(),
    )

    private val CLASSES = setOf("wifi", "mobile", "roaming")

    private fun word(m: ConnectionMode?): String = m?.key ?: "unknown"
}
