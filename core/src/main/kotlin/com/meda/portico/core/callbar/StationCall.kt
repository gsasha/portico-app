package com.meda.portico.core.callbar

import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.content.ButtonTile

/** APP-03: where a call from the station is. A station call has no screen of its own; every surface follows this. */
enum class StationPhase { RINGING, CONNECTING, IN_CALL }

/**
 * APP-03: the one station call the main screen shows, from the ring (not yet answered) through the answer to the end.
 * [doorRef] is the push contract's door reference (the ring's key); [doorId] is the socket's id of the same door, which is
 * what a Talk button's block names ([ButtonTile.door]); null when the doors cache does not know it (no button can match).
 */
data class StationCall(
    val doorRef: String,
    val doorId: String?,
    val callId: String,
    val phase: StationPhase,
    val name: String?,
    /** Whether Answer is offered (false for a ring-only door, or a refusal such as another live call). Meaningful while RINGING. */
    val canAnswer: Boolean,
)

/** A ring that is up on this phone (the ring state's door -> call). */
data class RingNow(val doorRef: String, val callId: String)

/** What the main screen draws for a station call, besides the call bar which it always gets. */
enum class StationSurface {
    /** The calling door's Talk button on the visible page shows the call, and the bar is below it. */
    TALK_BUTTON_AND_BAR,

    /** The visible page has no Talk button for that door: the bar alone, labelled with the door name. */
    BAR_ONLY,
}

object StationCalls {
    /**
     * The station call to show. An answer under way (connecting or connected) outranks the ring; with none, the ring that is up;
     * with neither, null (idle: the button and the bar are gone at once). A held or ended answer is not a station call.
     * [canAnswer] is the coordinator's own test, so the button and the press cannot disagree.
     */
    fun of(
        ring: RingNow?,
        answer: CallUiState,
        nameOf: (doorRef: String) -> String?,
        doorIdOf: (doorRef: String) -> String?,
        canAnswer: (RingNow) -> Boolean,
    ): StationCall? {
        val key = answer.key
        if (answer.isLive && key != null) {
            val phase = if (answer.snapshot.phase == Phase.IN_CALL) StationPhase.IN_CALL else StationPhase.CONNECTING
            return StationCall(key.doorRef, doorIdOf(key.doorRef), key.callId, phase, nameOf(key.doorRef), canAnswer = false)
        }
        if (ring == null) return null
        return StationCall(ring.doorRef, doorIdOf(ring.doorRef), ring.callId, StationPhase.RINGING, nameOf(ring.doorRef), canAnswer(ring))
    }

    /** Whether the calling door has a Talk (or gate call) button among the visible page's [buttons], whatever its state. */
    fun surface(buttons: List<ButtonTile>, call: StationCall?): StationSurface =
        if (call?.doorId != null && buttons.any { (it.kind == "talk" || it.talkRoute) && it.door == call.doorId }) StationSurface.TALK_BUTTON_AND_BAR
        else StationSurface.BAR_ONLY
}
