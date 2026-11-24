package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.RosterEntry

/**
 * A `call` button aimed at the gate (DEV-692, decision D1). The gate is a door that can dial: the server places a call to it
 * with the SAME dial as the Talk kind and replies `{ran, call_id: "out-...", media: null}`; what follows is `talk_state` frames
 * to this phone, never `house_call` events. So such a button must start the app's Talk session, not a [PlaceSession][com.meda.portico.core.call.PlaceSession]
 * (which waits 60 s for a house-call answer that never comes). The Talk implementation stays the only one; this is only
 * another way to start it.
 *
 * THE WIRE. Newer servers (DEV-695) put `gate_call: true` on the resolved `call` action itself ([ContentAction.gateCall]) while a
 * press of it goes through Talk's path, whatever the phone's roster shows (`contract/ws_v3.json` "content preview: the gate
 * hidden from the roster, the call button still carries gate_call"). That is read first. An older server sends no such key, only
 * `target: {type, id}`; the roster entry of the same endpoint then says it: `{id, kind: "door", can_dial: true}` (portico-ha README
 * "A `call` button aimed at the gate", "content get: the gate is in the roster, callable, with can_dial"), present only while the
 * door's driver reports `CanDial` exactly true, and absent from a roster that hides the gate (`roster_hide`). The audio needs the
 * door's socket id (`door_id`), which `portico/doors` gives with the same endpoint id ([Door.endpointId]). So a button is a gate
 * call when its target is a `door` and the action is marked or its roster entry has `can_dial`, and it can be started when that
 * door is also in the doors cache.
 *
 * Anything else (a door that cannot dial, a phone, a screen, an unmarked button on a roster that does not list the door) is not
 * a gate call and keeps its call-button behaviour untouched.
 *
 * @property doorId the door's socket id for the audio, or null when `portico/doors` has not given it: such a button is a gate
 * call that cannot start yet (drawn off); it must never fall back to a house-call session.
 */
data class GateCall(val doorId: String?) {
    companion object {
        /** [action] as a gate call, or null when it is not one. Pure. */
        fun of(action: ContentAction?, roster: List<RosterEntry>, doors: List<Door>): GateCall? {
            if (action == null || !action.isCall) return null
            val target = action.target ?: return null
            if (target.type != "door") return null
            // The action's own marker first (DEV-695): it holds when the phone's roster hides the gate. No marker is an older server
            // (or not the gate call): fall back to the roster row, as before.
            if (!action.gateCall && roster.none { it.kind == "door" && it.id == target.id && it.canDial }) return null
            return GateCall(doors.firstOrNull { it.endpointId == target.id }?.doorId)
        }
    }
}

/** What a press on Home does. The one decision, pure, so it is tested without a phone (HomeActivity only executes it). */
enum class PressRoute {
    /** A house-call button: the outgoing-call session ([com.meda.portico.core.call.PlaceCoordinator]). */
    PLACE,

    /** A house call of this phone is live on this button: reopen its screen. */
    REOPEN_CALL,

    /** The Talk session starts (a Talk button, or a call button aimed at the gate). */
    TALK_START,

    /** The Talk of this phone on this button is connecting or in the call: cancel or stop it. */
    TALK_STOP,

    /** Nothing is sent. */
    NONE,

    /** Any other kind: the ordinary action path. */
    ACTION,
    ;

    companion object {
        fun of(tile: ButtonTile): PressRoute = when {
            tile.kind == "talk" || tile.talkRoute -> when (tile.state) {
                ButtonState.READY -> TALK_START
                ButtonState.TALK_CONNECTING, ButtonState.TALK_IN_CALL -> TALK_STOP
                else -> NONE
            }
            tile.kind == "call" -> when (tile.state) {
                ButtonState.CALLING -> REOPEN_CALL
                ButtonState.READY -> PLACE
                else -> NONE
            }
            else -> ACTION
        }
    }
}
