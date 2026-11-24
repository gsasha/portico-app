package com.meda.portico.core.call

import com.meda.portico.core.ha.ContentCommands.ErrorKind

/**
 * Why a talk did not become a call (or did not stay one), as plain text for the button's line. The sibling of
 * [PlaceFailure]. Nothing is retried by itself: a retry is always a new press.
 *
 * The server's codes are portico-ha's `content/run` refusals for a Talk id (README "Talk to the door"): `not_allowed`,
 * `no_door`, `not_available`, `not_connected` (the GATE is not connected: not the phone's own link, which is
 * [TalkErrors.linkDown]), `busy`, `rate_limited`, `failed`. [TalkErrors.fromCode] is the one mapping, through the
 * content commands' [ErrorKind].
 */
data class TalkFailure(
    val kind: Kind,
    val code: String,
    val message: String,
    val retryable: Boolean,
) {
    enum class Kind {
        /** `not_available`: Talk to the door is switched off at the server. */
        SWITCHED_OFF,
        NOT_ALLOWED,
        NO_DOOR,

        /** The gate (the door) is not connected to Home Assistant. */
        GATE_NOT_CONNECTED,
        GATE_BUSY,
        RATE_LIMITED,
        FAILED,
        SERVER_TOO_OLD,

        /** The phone's own link to Home Assistant: nothing was sent. */
        LINK_DOWN,
        MIC_DENIED,
        NETWORK_LOST,
        TIMEOUT,

        /** The station did not answer within the app's wait, or ended the call before answering (busy, declined). */
        NO_ANSWER,
        STATION_ENDED,
        AUDIO_NOT_CONNECTED,
        MEDIA,
        DOOR_RINGING,
        ANOTHER_CALL,
        PAUSED,
        PREVIOUS_ENDED,
        OTHER,
    }
}

object TalkMessages {
    const val SWITCHED_OFF = "Talk to the door is switched off."
    const val NOT_ALLOWED = "This phone may not talk to the door."
    const val NO_DOOR = "The door for this button was removed."
    const val GATE_NOT_CONNECTED = "The gate is not connected."
    const val GATE_BUSY = "The gate is busy."
    const val RATE_LIMITED = "That button was just pressed. Wait a moment and try again."
    const val FAILED = "The gate could not be called. Try again."
    const val SERVER_TOO_OLD = "This Home Assistant cannot do Talk to the door yet."
    const val LINK_DOWN = "Not connected to Home Assistant, so nothing was sent."
    const val MIC_DENIED = "Portico needs the microphone to talk. Allow it in the app's settings, then try again."
    const val NETWORK_LOST = "The connection to Home Assistant was lost."
    const val TIMEOUT = "Home Assistant did not reply in time."
    const val NO_ANSWER = "The gate did not answer."
    const val STATION_ENDED = "The gate ended the call before it was answered."
    const val AUDIO_NOT_CONNECTED = "The audio did not connect."
    const val MEDIA = "The phone could not start the call audio."
    const val DOOR_RINGING = "Cancelled, the door is ringing."
    const val ANOTHER_CALL = "You are already on a call."
    const val PAUSED = "Portico is paused on this phone, so it cannot talk. Resume it in Settings."
    const val PREVIOUS_ENDED = "A call from this phone was still running and has been ended. Press Talk again."
    const val OTHER = "Talk to the door could not start"

    /** Not failures: the lines for a talk that ended on its own. */
    const val GATE_HUNG_UP = "The gate hung up."
    /** The app's own 120 s cap ([TalkConfig.callCapMs]) and gate-sip's 300 s hard limit (`time_cap`) end with this same line. */
    const val TIME_CAP = "Time limit reached."
}

object TalkErrors {

    /** A refusal code of `portico/content/run` (or of an unknown Home Assistant) as a failure. */
    fun fromCode(code: String): TalkFailure {
        fun f(kind: TalkFailure.Kind, message: String, retryable: Boolean) = TalkFailure(kind, code, message, retryable)
        return when (ErrorKind.of(code)) {
            ErrorKind.NOT_AVAILABLE, ErrorKind.UNAVAILABLE -> f(TalkFailure.Kind.SWITCHED_OFF, TalkMessages.SWITCHED_OFF, false)
            ErrorKind.NOT_ALLOWED, ErrorKind.UNAUTHORIZED -> f(TalkFailure.Kind.NOT_ALLOWED, TalkMessages.NOT_ALLOWED, false)
            ErrorKind.NO_DOOR -> f(TalkFailure.Kind.NO_DOOR, TalkMessages.NO_DOOR, false)
            ErrorKind.NOT_CONNECTED -> f(TalkFailure.Kind.GATE_NOT_CONNECTED, TalkMessages.GATE_NOT_CONNECTED, true)
            ErrorKind.BUSY -> f(TalkFailure.Kind.GATE_BUSY, TalkMessages.GATE_BUSY, true)
            ErrorKind.RATE_LIMITED -> f(TalkFailure.Kind.RATE_LIMITED, TalkMessages.RATE_LIMITED, true)
            ErrorKind.FAILED -> f(TalkFailure.Kind.FAILED, TalkMessages.FAILED, true)
            // A server without the content commands or without Talk, or one whose schema rejects the request.
            ErrorKind.UNKNOWN_ACTION, ErrorKind.UNKNOWN_COMMAND, ErrorKind.INVALID_FORMAT ->
                f(TalkFailure.Kind.SERVER_TOO_OLD, TalkMessages.SERVER_TOO_OLD, false)
            else -> f(TalkFailure.Kind.OTHER, "${TalkMessages.OTHER} ($code).", true)
        }
    }

    fun linkDown() = TalkFailure(TalkFailure.Kind.LINK_DOWN, "link_down", TalkMessages.LINK_DOWN, true)
    fun micDenied() = TalkFailure(TalkFailure.Kind.MIC_DENIED, "mic_denied", TalkMessages.MIC_DENIED, true)
    fun networkLost() = TalkFailure(TalkFailure.Kind.NETWORK_LOST, "network_lost", TalkMessages.NETWORK_LOST, true)
    fun timeout() = TalkFailure(TalkFailure.Kind.TIMEOUT, "timeout", TalkMessages.TIMEOUT, true)
    fun noAnswer() = TalkFailure(TalkFailure.Kind.NO_ANSWER, "no_answer", TalkMessages.NO_ANSWER, true)
    fun stationEnded() = TalkFailure(TalkFailure.Kind.STATION_ENDED, "station_ended", TalkMessages.STATION_ENDED, true)
    fun audioNotConnected() = TalkFailure(TalkFailure.Kind.AUDIO_NOT_CONNECTED, "audio_not_connected", TalkMessages.AUDIO_NOT_CONNECTED, true)
    fun media() = TalkFailure(TalkFailure.Kind.MEDIA, "media", TalkMessages.MEDIA, true)
    fun doorRinging() = TalkFailure(TalkFailure.Kind.DOOR_RINGING, "door_ringing", TalkMessages.DOOR_RINGING, true)
    fun anotherCall() = TalkFailure(TalkFailure.Kind.ANOTHER_CALL, "another_call", TalkMessages.ANOTHER_CALL, false)
    fun paused() = TalkFailure(TalkFailure.Kind.PAUSED, "paused", TalkMessages.PAUSED, true)
    fun previousEnded() = TalkFailure(TalkFailure.Kind.PREVIOUS_ENDED, "hung_up", TalkMessages.PREVIOUS_ENDED, true)

    /**
     * The failure for a `talk_state` `ended` that came BEFORE the station answered, by the server's `reason` (P3-B):
     * the refusals of the dial (`busy`, `not_connected`, `failed`, `not_available`) reuse [fromCode]'s lines; `no_answer`
     * and `timeout` are the gate not answering; anything else, or none, is the station ending the call (busy, declined).
     */
    fun fromTalkEnd(reason: String?): TalkFailure = when (reason?.lowercase()) {
        "busy", "not_connected", "failed", "not_available" -> fromCode(reason.lowercase())
        "no_answer", "timeout" -> noAnswer()
        else -> stationEnded()
    }

    /** A `talk_state` `ended` reason that means the call reached the server's time cap. */
    fun isTimeCap(reason: String?): Boolean = reason?.lowercase().let { it == "time_cap" || it == "cap" }

    /** The one line the button shows for how a talk ended, or null when the user ended it (nothing to say). */
    fun endLine(s: TalkSnapshot): String? {
        if (s.phase != TalkPhase.ENDED) return null
        s.failure?.let { return it.message }
        return when (s.endReason) {
            EndReason.REMOTE_HANGUP -> TalkMessages.GATE_HUNG_UP
            EndReason.TIME_CAP -> TalkMessages.TIME_CAP
            else -> null
        }
    }
}
