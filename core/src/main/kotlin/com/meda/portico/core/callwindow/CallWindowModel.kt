package com.meda.portico.core.callwindow

import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.CameraTile

/** 0.6.75 / 0.6.76: where the call window is. 0.6.76: it is the RINGING screen only; an answered call is Home's (the call bar). */
enum class CallWindowPhase { RINGING, FAILED }

/** The status line under the door name, as a key the activity maps to a string. */
enum class CallWindowStatus { INCOMING, INCOMING_RING_ONLY, FAILED }

/** Why the window closed; [word] is the journal's `(<reason>)`. */
enum class CloseReason(val word: String) {
    /** The ring ended without this phone answering: answered elsewhere, cancelled, expired, outranked, declined from the notification. */
    RING_ENDED("ring ended"),

    /** 0.6.76: this phone answered (the window's Answer, the notification's Answer or Home's bar): the call is on Home, the window is done. */
    ANSWERED("answered"),

    /** Opened with no call to show (a stale launch). */
    NO_CALL("no call"),
    DECLINED("declined"),
    BACK("back"),
    FAILURE_SHOWN("failure shown"),

    /** The Close button of a failure. */
    CLOSED_BY_USER("closed"),

    /** The system finished the window (not one of the above). */
    SYSTEM("system"),
}

/** What the call window draws. Everything is decided here; the activity only maps it to widgets. */
data class CallWindowView(
    val phase: CallWindowPhase,
    val doorRef: String,
    val callId: String,
    /** The door's name as the ring's notification names it; null = the activity's generic "Door". */
    val doorName: String?,
    val status: CallWindowStatus,
    /** The failure's own message, for [CallWindowStatus.FAILED]. */
    val failureMessage: String?,
    val showAnswer: Boolean,
    val showDecline: Boolean,
    val showClose: Boolean,
    val keepScreenOn: Boolean,
    /** The Ring page's cameras and buttons (empty lists when the page has none: the middle simply shows nothing). */
    val cameras: List<CameraTile>,
    val buttons: List<ButtonTile>,
) {
    val hasMiddle: Boolean get() = cameras.isNotEmpty() || buttons.isNotEmpty()
}

sealed interface CallWindowStep {
    data class Show(val view: CallWindowView) : CallWindowStep
    data class Finish(val reason: CloseReason) : CallWindowStep
}

/**
 * 0.6.75 / 0.6.76: the incoming-call window of a station call (Android's dialer look): top = who, middle = the Ring page's content, bottom =
 * Decline and Answer. Pure; the activity follows the app's own state (`stationCall()`, the answer state, the resolved Ring page) through [step].
 *
 * - A station call RINGING: the window shows it, Decline always, Answer where the call can be answered.
 * - A station call that is no longer ringing (connecting, in call): this phone answered, the call belongs to Home's call bar, so the window is
 *   done ([CloseReason.ANSWERED]). The window has no in-call phase.
 * - No station call: the ring ended (answered elsewhere, cancelled, expired, outranked, declined), so the window finishes, with one exception: an
 *   answer that FAILED (microphone refused, the door busy) while the window is up keeps it up with the failure's own words and a Close button
 *   until [FAILED_LINGER_MS] has passed, so a refusal is never a silent disappearance.
 */
object CallWindowModel {
    const val FAILED_LINGER_MS = 8_000L

    fun step(call: StationCall?, answer: CallUiState, caller: CallerView?, seen: CallWindowPhase?): CallWindowStep {
        val cameras = caller?.cameras ?: emptyList()
        val buttons = caller?.buttons ?: emptyList()
        if (call != null) {
            if (call.phase != StationPhase.RINGING) return CallWindowStep.Finish(CloseReason.ANSWERED)
            return CallWindowStep.Show(
                CallWindowView(
                    phase = CallWindowPhase.RINGING,
                    doorRef = call.doorRef,
                    callId = call.callId,
                    doorName = call.name,
                    status = if (call.canAnswer) CallWindowStatus.INCOMING else CallWindowStatus.INCOMING_RING_ONLY,
                    failureMessage = null,
                    showAnswer = call.canAnswer,
                    showDecline = true,
                    showClose = false,
                    keepScreenOn = true,
                    cameras = cameras,
                    buttons = buttons,
                ),
            )
        }
        val key = answer.key
        val snap = answer.snapshot
        val failure = snap.failure
        if (seen != null && key != null && snap.phase == Phase.ENDED && failure != null) {
            return CallWindowStep.Show(
                CallWindowView(
                    phase = CallWindowPhase.FAILED,
                    doorRef = key.doorRef,
                    callId = key.callId,
                    doorName = null,
                    status = CallWindowStatus.FAILED,
                    failureMessage = failure.message,
                    showAnswer = false,
                    showDecline = false,
                    showClose = true,
                    keepScreenOn = false,
                    cameras = cameras,
                    buttons = buttons,
                ),
            )
        }
        return CallWindowStep.Finish(if (seen == null) CloseReason.NO_CALL else CloseReason.RING_ENDED)
    }

    const val OPENED_LINE = "ring screen: call window"

    /** 0.6.76: journalled by Home when it takes the Answer (the window's button or the notification's action). */
    const val ANSWERED_HOME_LINE = "ring screen: answered -> home"

    fun closedLine(reason: CloseReason): String = "ring screen: call window closed (${reason.word})"
}
