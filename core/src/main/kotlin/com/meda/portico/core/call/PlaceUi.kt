package com.meda.portico.core.call

/**
 * What the outgoing-call screen shows for a state, decided here so the JVM can test it; the activity only
 * maps it to widgets. The controls are [CallUi.Controls], the same ones the in-call screen uses.
 */
object PlaceUi {

    enum class Status {
        NONE, NEED_MIC, PLACING, ANSWERING, RINGING_OUT, CONNECTING, IN_CALL, IN_CALL_MUTED, FAILURE,
        ENDED_USER, ENDED_REMOTE, ENDED_NETWORK, ENDED_CAP, ENDED_STOPPED, ENDED_OTHER,
    }

    fun status(s: PlaceSnapshot): Status = when (s.phase) {
        PlacePhase.IDLE -> Status.NONE
        PlacePhase.AWAITING_MIC -> Status.NEED_MIC
        PlacePhase.PLACING -> if (s.answering) Status.ANSWERING else Status.PLACING
        PlacePhase.RINGING -> Status.RINGING_OUT
        PlacePhase.CONNECTING -> Status.CONNECTING
        PlacePhase.IN_CALL -> if (s.muted) Status.IN_CALL_MUTED else Status.IN_CALL
        PlacePhase.ENDED -> when {
            s.failure != null -> Status.FAILURE
            s.endReason == EndReason.USER_HANGUP -> Status.ENDED_USER
            s.endReason == EndReason.REMOTE_HANGUP -> Status.ENDED_REMOTE
            s.endReason == EndReason.NETWORK_LOST -> Status.ENDED_NETWORK
            s.endReason == EndReason.TIME_CAP -> Status.ENDED_CAP
            s.endReason == EndReason.APP_STOPPED -> Status.ENDED_STOPPED
            else -> Status.ENDED_OTHER
        }
    }

    fun controls(s: PlaceSnapshot): CallUi.Controls {
        val ended = s.phase == PlacePhase.ENDED
        val live = s.phase != PlacePhase.IDLE && !ended
        return CallUi.Controls(
            hangUp = live,
            // Nothing is connected until the far end has answered: the button reads "Cancel" until then.
            hangUpIsCancel = s.phase == PlacePhase.AWAITING_MIC || s.phase == PlacePhase.PLACING || s.phase == PlacePhase.RINGING,
            mute = s.phase == PlacePhase.IN_CALL,
            close = !live,
            // Only where trying again can help (busy, no answer, a lost connection); never after "declined".
            retry = ended && s.failure?.retryable == true,
            openSettings = ended && s.failure?.kind == PlaceFailure.Kind.MIC_DENIED,
            keepScreenOn = live,
            timer = s.phase == PlacePhase.IN_CALL && s.connectedAtMs != null,
        )
    }

    /** The screen has nothing left to say: a hang-up the user asked for, or no call at all. */
    fun shouldFinish(state: PlaceUiState): Boolean {
        val s = state.snapshot
        if (s.phase == PlacePhase.IDLE && state.request == null) return true
        return s.phase == PlacePhase.ENDED && s.endReason == EndReason.USER_HANGUP && s.failure == null
    }
}
