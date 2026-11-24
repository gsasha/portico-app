package com.meda.portico.core.call

/**
 * Why an outgoing call did not become a call, and what the screen does about it. The sibling of
 * [AnswerFailure]: plain text for a TextView, a [retryable] flag, nothing retried by itself (a retry is
 * always a new press of the button).
 *
 * The server's codes are in portico-ha's `README.md` ("Call buttons") and the house calls design 14.6 and
 * 14.8. [PlaceErrors.fromCode] is the one mapping; a code it does not know becomes
 * "The call could not be placed (<code>)".
 *
 * @property retryable pressing again may work (busy, no answer, a lost connection). False where pressing
 *   again cannot help: they declined, the button is off by design, the server cannot place calls.
 */
data class PlaceFailure(
    val kind: Kind,
    val code: String,
    val message: String,
    val retryable: Boolean,
) {
    enum class Kind {
        NOT_AVAILABLE,
        NOT_CALLABLE,
        TARGET_OFFLINE,
        TARGET_DND,
        RECEIVE_OFF,
        BUSY,
        DECLINED,
        NO_ANSWER,
        DIAL_DISABLED,
        GATE_BUSY,
        DOOR_RINGING,
        FORBIDDEN,
        RATE_LIMITED,
        MIC_DENIED,
        NOT_CONNECTED,
        NETWORK_LOST,
        TIMEOUT,
        ANOTHER_CALL,
        PAUSED,
        MEDIA,
        AUDIO_NOT_CONNECTED,
        FAILED,

        // ---- house calls (M7 3b-A): the codes `house.py`, `house_bridge.py` and `content/run` give a house call ----

        /** `forbidden`: a phone named "Everyone" (only a screen may). */
        EVERYONE_FORBIDDEN,

        /** `too_many_calls`: the house is at its cap of simultaneous calls. */
        TOO_MANY_CALLS,

        /** `unknown_call`: the call is not there any more (over, or never this phone's). */
        CALL_OVER,

        /** `already_answered`: another phone took the call first. */
        ALREADY_ANSWERED,

        /** `not_subscribed`: the phone is not on the calls stream yet, so it is not present. */
        NOT_SUBSCRIBED,

        /** `not_supported`, `already_open`, `no_transport`: the audio bridge cannot carry this call. */
        NO_AUDIO_BRIDGE,
        OTHER,
    }
}

object PlaceMessages {
    const val NOT_AVAILABLE = "This Home Assistant cannot place calls yet."
    const val NOT_CALLABLE = "This contact is not available any more."
    const val FORBIDDEN = "This phone may not place calls."
    const val DIAL_DISABLED = "Talk to the gate is switched off."
    const val GATE_BUSY = "The gate is busy."
    const val DOOR_RINGING = "The door is ringing."
    const val DOOR_RINGING_CANCELLED = "Cancelled, the door is ringing."
    const val RATE_LIMITED = "That button was just pressed. Wait a moment and try again."
    const val MIC_DENIED = "Portico needs the microphone to call. Allow it in the app's settings, then call again."
    const val NOT_CONNECTED = "Not connected to Home Assistant, so the call was not placed."
    const val NETWORK_LOST = "The connection to Home Assistant was lost."
    const val TIMEOUT = "Home Assistant did not reply in time."
    const val ANOTHER_CALL = "You are already on a call."
    const val PAUSED = "Portico is paused on this phone, so it cannot call. Resume it in Settings."
    const val MEDIA = "The phone could not start the call audio."
    const val AUDIO_NOT_CONNECTED = "Could not connect."
    const val FAILED = "The call could not be placed. Try again."
    const val EVERYONE_FORBIDDEN = "Only the kitchen screen can call everyone."
    const val TOO_MANY_CALLS = "The house is on too many calls at once. Try again in a moment."
    const val CALL_OVER = "That call is already over."
    const val ALREADY_ANSWERED = "Someone else already answered."
    const val NOT_SUBSCRIBED = "Not ready yet. Try again in a moment."
    const val NO_AUDIO_BRIDGE = "This Home Assistant cannot carry call audio yet."
    const val OTHER = "The call could not be placed"

    /** Who is called when the button has no name for them. */
    const val SOMEONE = "They"

    // "They" (no name known) takes the plural verbs; a name takes the singular.
    private fun be(name: String) = if (name == SOMEONE) "are" else "is"
    private fun have(name: String) = if (name == SOMEONE) "have" else "has"

    fun offline(name: String) = "$name ${be(name)} not reachable."
    fun dnd(name: String) = "$name ${have(name)} Do Not Disturb on."
    fun receiveOff(name: String) = "$name ${have(name)} calls switched off."
    fun busy(name: String) = "$name ${be(name)} on a call."
    fun declined(name: String) = "$name declined."
    fun noAnswer(name: String) = "$name did not answer."
}

object PlaceErrors {

    /** [name] is who the button rings, for the messages that say it; blank falls back to [PlaceMessages.SOMEONE]. */
    fun fromCode(code: String, name: String? = null): PlaceFailure {
        val who = AnswerErrors.plain(name) ?: PlaceMessages.SOMEONE
        fun f(kind: PlaceFailure.Kind, message: String, retryable: Boolean) = PlaceFailure(kind, code, message, retryable)
        return when (code) {
            // `unknown_action` and `unknown_command`: a server without call buttons (design 14.2).
            "not_available", "unknown_action", "unknown_command", "invalid_format" ->
                f(PlaceFailure.Kind.NOT_AVAILABLE, PlaceMessages.NOT_AVAILABLE, false)
            "not_callable" -> f(PlaceFailure.Kind.NOT_CALLABLE, PlaceMessages.NOT_CALLABLE, false)
            "target_offline" -> f(PlaceFailure.Kind.TARGET_OFFLINE, PlaceMessages.offline(who), true)
            "target_dnd" -> f(PlaceFailure.Kind.TARGET_DND, PlaceMessages.dnd(who), false)
            "receive_off" -> f(PlaceFailure.Kind.RECEIVE_OFF, PlaceMessages.receiveOff(who), false)
            "busy" -> f(PlaceFailure.Kind.BUSY, PlaceMessages.busy(who), true)
            "declined" -> f(PlaceFailure.Kind.DECLINED, PlaceMessages.declined(who), false)
            "no_answer" -> f(PlaceFailure.Kind.NO_ANSWER, PlaceMessages.noAnswer(who), true)
            "dial_disabled" -> f(PlaceFailure.Kind.DIAL_DISABLED, PlaceMessages.DIAL_DISABLED, false)
            "gate_busy" -> f(PlaceFailure.Kind.GATE_BUSY, PlaceMessages.GATE_BUSY, true)
            "yielded" -> f(PlaceFailure.Kind.DOOR_RINGING, PlaceMessages.DOOR_RINGING, true)
            "place_forbidden", "unauthorized" -> f(PlaceFailure.Kind.FORBIDDEN, PlaceMessages.FORBIDDEN, false)
            "rate_limited" -> f(PlaceFailure.Kind.RATE_LIMITED, PlaceMessages.RATE_LIMITED, true)
            "failed" -> f(PlaceFailure.Kind.FAILED, PlaceMessages.FAILED, true)
            "forbidden" -> f(PlaceFailure.Kind.EVERYONE_FORBIDDEN, PlaceMessages.EVERYONE_FORBIDDEN, false)
            "too_many_calls" -> f(PlaceFailure.Kind.TOO_MANY_CALLS, PlaceMessages.TOO_MANY_CALLS, true)
            "unknown_call" -> f(PlaceFailure.Kind.CALL_OVER, PlaceMessages.CALL_OVER, false)
            "already_answered" -> f(PlaceFailure.Kind.ALREADY_ANSWERED, PlaceMessages.ALREADY_ANSWERED, false)
            "not_subscribed" -> f(PlaceFailure.Kind.NOT_SUBSCRIBED, PlaceMessages.NOT_SUBSCRIBED, true)
            "not_supported", "already_open", "no_transport" -> f(PlaceFailure.Kind.NO_AUDIO_BRIDGE, PlaceMessages.NO_AUDIO_BRIDGE, false)
            else -> f(PlaceFailure.Kind.OTHER, "${PlaceMessages.OTHER} ($code).", true)
        }
    }

    fun micDenied() = PlaceFailure(PlaceFailure.Kind.MIC_DENIED, "mic_denied", PlaceMessages.MIC_DENIED, true)
    fun notConnected() = PlaceFailure(PlaceFailure.Kind.NOT_CONNECTED, "not_connected", PlaceMessages.NOT_CONNECTED, true)
    fun networkLost() = PlaceFailure(PlaceFailure.Kind.NETWORK_LOST, "network_lost", PlaceMessages.NETWORK_LOST, true)
    fun timeout() = PlaceFailure(PlaceFailure.Kind.TIMEOUT, "timeout", PlaceMessages.TIMEOUT, true)
    fun anotherCall() = PlaceFailure(PlaceFailure.Kind.ANOTHER_CALL, "another_call", PlaceMessages.ANOTHER_CALL, false)
    fun paused() = PlaceFailure(PlaceFailure.Kind.PAUSED, "paused", PlaceMessages.PAUSED, true)
    fun media() = PlaceFailure(PlaceFailure.Kind.MEDIA, "media", PlaceMessages.MEDIA, true)
    fun audioNotConnected() = PlaceFailure(PlaceFailure.Kind.AUDIO_NOT_CONNECTED, "audio_not_connected", PlaceMessages.AUDIO_NOT_CONNECTED, true)
    fun doorRingingCancelled() = PlaceFailure(PlaceFailure.Kind.DOOR_RINGING, "door_ringing", PlaceMessages.DOOR_RINGING_CANCELLED, true)

    /** Nobody answered within the app's own wait (the server's 45 s ring limit plus a margin). */
    fun noAnswer(name: String?) = fromCode("no_answer", name)

    /** The far end ended the call with the reason code of a `house_call` `ended` event. Null: not a failure to show (a plain hang-up). */
    fun fromEndReason(reason: String?, name: String?): PlaceFailure? = when (reason) {
        "declined" -> fromCode("declined", name)
        "busy" -> fromCode("busy", name)
        "no_answer" -> fromCode("no_answer", name)
        "dnd" -> fromCode("target_dnd", name)
        "error" -> audioNotConnected()
        // "answered_elsewhere" is a callee's word (another phone took the ring); a caller never sees it
        else -> null // cancelled, hangup, answered_elsewhere, anything new: an ordinary end
    }
}
