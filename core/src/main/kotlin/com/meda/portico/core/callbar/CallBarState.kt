package com.meda.portico.core.callbar

import com.meda.portico.core.call.AnswerSnapshot
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.call.PlacePhase
import com.meda.portico.core.call.PlaceUiState
import com.meda.portico.core.call.RouteChoice
import com.meda.portico.core.call.RouteDevice
import com.meda.portico.core.call.TalkPhase
import com.meda.portico.core.call.TalkUiState

/** APP-02: where the person is in a call, as every call surface (button, bar, notification) needs it. */
enum class CallPhase {
    IDLE, DIALING, IN_CALL, ENDED,

    /** APP-03: a station call is ringing on this phone and has not been answered. */
    RINGING,
}

/** APP-03: the ring a [CallPhase.RINGING] bar answers or declines; [canAnswer] false for a ring-only door (Decline only). */
data class RingTarget(val doorRef: String, val callId: String, val canAnswer: Boolean)

/** One of the three words of the route chooser. */
enum class CallRoute { SPEAKER, BLUETOOTH, PHONE }

/**
 * APP-02: the one thing the call surfaces draw, whichever of the three call paths (Talk, a call button, a ring answered)
 * is live. Pure: no Android, no clock of its own.
 *
 * [name] is the called party's name as the server gave it (a door, dashboard or phone name), never a typed id; null only
 * when the server has not named it, and then the surfaces say "Calling" with no name rather than invent one.
 */
data class CallBarState(
    val phase: CallPhase,
    val name: String?,
    /** Wall-clock moment the audio first connected; null until then (the duration does not run while dialling). */
    val connectedAtMs: Long?,
    /** APP-03: set only for [CallPhase.RINGING]. */
    val ring: RingTarget? = null,
) {
    /** The notification exists exactly while the call is dialling or connected. */
    val isActive: Boolean get() = phase == CallPhase.DIALING || phase == CallPhase.IN_CALL

    /** APP-03: the bar is drawn while the call is active, and also while a station call rings (Answer / Decline instead of End). */
    val isShown: Boolean get() = isActive || phase == CallPhase.RINGING

    /** `mm:ss` (`h:mm:ss` from an hour); `00:00` until connected. */
    fun duration(nowMs: Long): String = CallClock.format(connectedAtMs?.let { nowMs - it } ?: 0L)

    companion object {
        val IDLE = CallBarState(CallPhase.IDLE, null, null)
    }
}

object CallClock {
    /** `mm:ss`, `h:mm:ss` from one hour; never negative (a clock stepped back shows 00:00). */
    fun format(elapsedMs: Long): String {
        val total = (elapsedMs / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

object CallBars {
    fun fromTalk(s: TalkUiState): CallBarState = CallBarState(
        phase = when (s.snapshot.phase) {
            TalkPhase.IDLE -> CallPhase.IDLE
            TalkPhase.AWAITING_MIC, TalkPhase.STARTING, TalkPhase.CONNECTING -> CallPhase.DIALING
            TalkPhase.IN_CALL -> CallPhase.IN_CALL
            TalkPhase.ENDED -> CallPhase.ENDED
        },
        name = s.request?.name,
        connectedAtMs = s.snapshot.connectedAtMs,
    )

    fun fromPlace(s: PlaceUiState): CallBarState = CallBarState(
        phase = when (s.snapshot.phase) {
            PlacePhase.IDLE -> CallPhase.IDLE
            PlacePhase.AWAITING_MIC, PlacePhase.PLACING, PlacePhase.RINGING, PlacePhase.CONNECTING -> CallPhase.DIALING
            PlacePhase.IN_CALL -> CallPhase.IN_CALL
            PlacePhase.ENDED -> CallPhase.ENDED
        },
        name = s.request?.name,
        connectedAtMs = s.snapshot.connectedAtMs,
    )

    /** [name]: the door's name resolved by the app from the server's roster (`CallUi.doorName`). A held call is not the live one. */
    fun fromAnswer(s: CallUiState, name: String?): CallBarState {
        val snap: AnswerSnapshot = s.snapshot
        val phase = when {
            s.key == null -> CallPhase.IDLE
            else -> when (snap.phase) {
                Phase.IDLE -> CallPhase.IDLE
                Phase.AWAITING_MIC, Phase.PREPARING, Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING -> CallPhase.DIALING
                Phase.IN_CALL -> CallPhase.IN_CALL
                Phase.ENDED -> CallPhase.ENDED
            }
        }
        return CallBarState(phase, name, snap.connectedAtMs)
    }

    /**
     * The one call to show. At most one of the three is live (each coordinator refuses to start while another is), so the
     * order only matters for a moment of overlap: a door call outranks a button call, which outranks a talk, the same rule
     * as the coordinators' own. None live: [CallBarState.IDLE] (an ended call is not shown: the surfaces disappear at once).
     */
    fun pick(answer: CallBarState, place: CallBarState, talk: CallBarState, ringing: CallBarState = CallBarState.IDLE): CallBarState =
        listOf(answer, place, talk).firstOrNull { it.isActive } ?: ringing.takeIf { it.phase == CallPhase.RINGING } ?: CallBarState.IDLE

    /** APP-03: a station call that rings and is not answered yet; anything else (including an answered one: [fromAnswer] draws that) is IDLE. */
    fun fromRing(s: StationCall?): CallBarState =
        if (s == null || s.phase != StationPhase.RINGING) CallBarState.IDLE
        else CallBarState(CallPhase.RINGING, s.name, null, RingTarget(s.doorRef, s.callId, s.canAnswer))
}

/** The route chooser (Speaker / Bluetooth / Phone) that replaces A-06's one cycling key. */
object CallRoutes {
    /** The words offered, in order. Bluetooth only while a Bluetooth audio device is connected; Phone only on a device with an earpiece. */
    fun available(hasEarpiece: Boolean, bluetoothConnected: Boolean): List<CallRoute> = buildList {
        add(CallRoute.SPEAKER)
        if (bluetoothConnected) add(CallRoute.BLUETOOTH)
        if (hasEarpiece) add(CallRoute.PHONE)
    }

    /** With one word on offer there is nothing to choose: the chooser is not drawn. */
    fun chooserVisible(available: List<CallRoute>): Boolean = available.size >= 2

    /** What a tap on [route] stores. */
    fun choiceFor(route: CallRoute): RouteChoice = when (route) {
        CallRoute.SPEAKER -> RouteChoice.SPEAKER
        CallRoute.BLUETOOTH -> RouteChoice.BLUETOOTH
        CallRoute.PHONE -> RouteChoice.EARPIECE
    }

    /**
     * Which word is lit: where the sound actually goes now, so Auto (nothing chosen yet) lights the word the proximity rule
     * picked. A wired or USB headset (a [RouteDevice.HEADSET] with no Bluetooth) lights nothing: it has no word.
     */
    fun lit(device: RouteDevice, bluetoothConnected: Boolean): CallRoute? = when (device) {
        RouteDevice.SPEAKER -> CallRoute.SPEAKER
        RouteDevice.EARPIECE -> CallRoute.PHONE
        RouteDevice.HEADSET -> if (bluetoothConnected) CallRoute.BLUETOOTH else null
    }
}
