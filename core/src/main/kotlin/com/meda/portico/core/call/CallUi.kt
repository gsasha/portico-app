package com.meda.portico.core.call

import com.meda.portico.core.ha.DoorsCache

/** What the screen shows for a snapshot, decided here so the JVM can test it; the activity only maps it to widgets. */
object CallUi {

    /** The status line, as a key the activity maps to a string (or, for [Status.FAILURE], the failure's own message). */
    enum class Status { NONE, NEED_MIC, CONNECTING, IN_CALL, IN_CALL_MUTED, FAILURE, ENDED_USER, ENDED_REMOTE, ENDED_NETWORK, ENDED_CAP, ENDED_STOPPED, ENDED_OTHER }

    data class Controls(
        val hangUp: Boolean,
        /** The hang-up button reads "Cancel": nothing is connected yet. */
        val hangUpIsCancel: Boolean,
        val mute: Boolean,
        val close: Boolean,
        val openSettings: Boolean,
        /** Ended on something the user may simply try again (a refused microphone, a busy station). */
        val retry: Boolean,
        val keepScreenOn: Boolean,
        val timer: Boolean,
    )

    fun status(s: AnswerSnapshot): Status = when (s.phase) {
        Phase.IDLE -> Status.NONE
        Phase.AWAITING_MIC -> Status.NEED_MIC
        Phase.PREPARING, Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING -> Status.CONNECTING
        Phase.IN_CALL -> if (s.muted) Status.IN_CALL_MUTED else Status.IN_CALL
        Phase.ENDED -> when {
            s.failure != null -> Status.FAILURE
            s.endReason == EndReason.USER_HANGUP -> Status.ENDED_USER
            s.endReason == EndReason.REMOTE_HANGUP -> Status.ENDED_REMOTE
            s.endReason == EndReason.NETWORK_LOST -> Status.ENDED_NETWORK
            s.endReason == EndReason.TIME_CAP -> Status.ENDED_CAP
            s.endReason == EndReason.APP_STOPPED -> Status.ENDED_STOPPED
            else -> Status.ENDED_OTHER
        }
    }

    fun controls(s: AnswerSnapshot): Controls {
        val ended = s.phase == Phase.ENDED
        val live = s.phase != Phase.IDLE && !ended
        return Controls(
            hangUp = live,
            hangUpIsCancel = s.phase == Phase.AWAITING_MIC || s.phase == Phase.PREPARING || s.phase == Phase.CLAIMING,
            mute = s.phase == Phase.IN_CALL,
            close = !live,
            retry = ended && s.failure?.retryable == true,
            openSettings = ended && s.failure?.kind == AnswerFailure.Kind.MIC_DENIED,
            keepScreenOn = live,
            timer = s.phase == Phase.IN_CALL && s.connectedAtMs != null,
        )
    }

    /**
     * The screen has nothing left to say: a hang-up the user asked for, or no call at all. [heldExists]: a held call is still
     * there (15.13), and the screen must stay for its switcher ("Resume"); false (the default) is the pre-hold rule.
     */
    fun shouldFinish(state: CallUiState, heldExists: Boolean = false): Boolean {
        if (heldExists) return false
        val s = state.snapshot
        if (s.phase == Phase.IDLE && state.key == null) return true
        return s.phase == Phase.ENDED && s.endReason == EndReason.USER_HANGUP && s.failure == null
    }

    fun doorName(state: CallUiState, doors: DoorsCache): String? = state.key?.doorRef?.let(doors::nameFor)

    /** `mm:ss` from the connect time, never negative (a clock stepped back shows 00:00). */
    fun elapsed(connectedAtMs: Long, nowMs: Long): Pair<Long, Long> {
        val sec = ((nowMs - connectedAtMs) / 1000).coerceAtLeast(0)
        return sec / 60 to sec % 60
    }
}
