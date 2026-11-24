package com.meda.portico.core.content

import com.meda.portico.core.ha.Label
import com.meda.portico.core.ha.RosterEntry

/*
 * M7 3b-A: the roster page of Home's pager (house calls design 15.2, 15.14; editor design: the page deferred from M6).
 *
 * THE GATE. A phone shows the page, and the app does anything at all about house calls, only when the server's
 * `content/get` roster says house calls are on for this phone ([HouseGate]). When house calls are off the server's registry is
 * the Null one and every phone, screen and group row says `not_available`, so [HouseGate.enabled] is false and [RosterModel.build]
 * is null: Home has no page, no tab, no row, the pager is what it was (golden tests in `HomeModelRosterTest`).
 */

/** Whether the server has house calls on for this phone, read from the roster alone. Pure. */
object HouseGate {
    /** The roster `reason` an install with house calls off gives for every phone, screen and group (the Null registry). */
    const val OFF_REASON = "not_available"

    /** The kinds the page draws. A `door` row is the gate's (3f, Talk's path) and is not a house call: never drawn here, never a signal. */
    val PAGE_KINDS: Set<String> = setOf("phone", "dashboard", "group")

    /**
     * True when at least one person, screen or group row has a reason other than `not_available`. With house calls on the
     * registry gives each of them a real answer (reachable, `offline`, `dnd`, `busy`, `receive_off`, `push_only`, `no_receivers`...);
     * with them off it gives `not_available` for all. An empty roster, or one of doors only, is off. A row with no reason that is
     * unavailable is not a signal either (never produced by the server; treated as off).
     */
    fun enabled(roster: List<RosterEntry>): Boolean =
        roster.any { it.kind in PAGE_KINDS && (it.reason != OFF_REASON && (it.available || it.reason != null)) }
}

/** The dot of a row. Words go with it (a dot is never the only signal). */
enum class Presence { ONLINE, PUSH_ONLY, OFFLINE, DND, BUSY, OFF }

/** What a row says under the name, from the server's `reason`. The app maps each to a string. */
enum class RosterStatus {
    AVAILABLE,

    /** `push_only`: it will ring by notification (3c). */
    BY_NOTIFICATION,
    NOT_REACHABLE,

    /** `dnd`: the callee's "Do not disturb". */
    DO_NOT_DISTURB,

    /** `busy`: on a call, or a door is ringing there. */
    ON_A_CALL,

    /** `receive_off`: the callee has not switched house calls on for their phone. */
    CALLS_OFF,

    /** `no_receivers`: Everyone, and no phone is set to receive. */
    NO_RECEIVERS,

    /** `unknown_target`, `disabled`, a reason this build does not know. */
    GONE,

    /** `not_available` on a row of an install that has house calls on (e.g. a screen the registry does not know). */
    NOT_AVAILABLE,

    /** The phone has no connection to Home Assistant: every row is off, nothing is queued. */
    NOT_CONNECTED,

    /** This phone's own call is live on this row. */
    CALLING,
}

enum class RowState { READY, CALLING, DISABLED }

/**
 * One row of the roster page. [name] is already cleaned (never raw server text beyond [Label]'s limits). [count] is Everyone's
 * "how many phones it will ring" (null elsewhere). [actionId] is all the server is ever told when the row is pressed.
 */
data class RosterRow(
    val actionId: String,
    val id: String,
    val kind: String,
    val name: String,
    val presence: Presence,
    val status: RosterStatus,
    val state: RowState,
    val count: Int? = null,
) {
    val isEveryone: Boolean get() = kind == "group"
}

/** The page: rows in the server's order (Everyone first, where the server sends it). */
data class RosterView(val rows: List<RosterRow>)

object RosterModel {
    /** The pager tab of the page. Not a document page id: a document cannot make one start with `@`. */
    const val TAB_ID = "@house"

    /** The tab's label (a plain constant, as a document page's label is plain text). */
    const val TAB_LABEL = "Call"

    fun tab(): PageTab = PageTab(TAB_ID, TAB_LABEL)

    /**
     * The page, or null when house calls are off for this phone (the page does not exist). [usable]: the socket is up and the
     * server answers content commands ([HomeModel.usable]); without it the saved rows are drawn off. [calling]: the action id of
     * this phone's live outgoing call, if any.
     */
    fun build(roster: List<RosterEntry>, usable: Boolean, calling: String?): RosterView? {
        if (!HouseGate.enabled(roster)) return null
        val rows = roster.filter { it.kind in HouseGate.PAGE_KINDS }.map { row(it, usable, calling) }
        return RosterView(rows)
    }

    internal fun row(e: RosterEntry, usable: Boolean, calling: String?): RosterRow {
        val name = Label.text(e.name, Label.MAX, if (e.kind == "group") "Everyone" else e.id)
        val reason = e.reason
        val (presence, status) = when {
            !e.available && reason == null -> Presence.OFF to RosterStatus.GONE
            reason == null -> Presence.ONLINE to RosterStatus.AVAILABLE
            reason == "push_only" -> Presence.PUSH_ONLY to RosterStatus.BY_NOTIFICATION
            reason == "offline" -> Presence.OFFLINE to RosterStatus.NOT_REACHABLE
            reason == "dnd" -> Presence.DND to RosterStatus.DO_NOT_DISTURB
            reason == "busy" -> Presence.BUSY to RosterStatus.ON_A_CALL
            reason == "receive_off" -> Presence.OFF to RosterStatus.CALLS_OFF
            reason == "no_receivers" -> Presence.OFF to RosterStatus.NO_RECEIVERS
            reason == "not_available" -> Presence.OFF to RosterStatus.NOT_AVAILABLE
            else -> Presence.OFF to RosterStatus.GONE // unknown_target, disabled, a reason from a newer server
        }
        // A reason the server gives alongside `available: true` (`push_only`) still allows the call; every other reason with
        // `available: false` does not. An `available: true` row with no known reason is callable.
        val callable = e.available
        val isCalling = calling != null && calling == e.actionId
        return when {
            isCalling -> RosterRow(e.actionId, e.id, e.kind, name, presence, RosterStatus.CALLING, RowState.CALLING, countOf(e))
            !usable -> RosterRow(e.actionId, e.id, e.kind, name, Presence.OFF, RosterStatus.NOT_CONNECTED, RowState.DISABLED, countOf(e))
            !callable -> RosterRow(e.actionId, e.id, e.kind, name, presence, status, RowState.DISABLED, countOf(e))
            else -> RosterRow(e.actionId, e.id, e.kind, name, presence, status, RowState.READY, countOf(e))
        }
    }

    private fun countOf(e: RosterEntry): Int? = if (e.kind == "group") e.count else null
}
