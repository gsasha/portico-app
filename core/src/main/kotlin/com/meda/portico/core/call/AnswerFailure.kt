package com.meda.portico.core.call

/**
 * Why an answer attempt did not become a call, and what the screen and the ring do about it.
 *
 * Home Assistant's error codes come from `portico-ha` `signaling.py` (contract 3: `already_answered`,
 * `not_available`, `refused`, `unknown_door`, `not_supported`, `no_transport`, ...) and from the transport relayed
 * unchanged (`device_busy`, design §9.4). [AnswerErrors.fromCode] is the one mapping.
 *
 * @property message plain text for the screen (a TextView, never HTML).
 * @property dismissRing the call is not ours to answer any more: the ring must go, and Answer must
 *   not be offered again for this call.
 * @property retryable the user may press Answer again for the same call. Nothing here retries by
 *   itself: a retry is always a new press.
 */
data class AnswerFailure(
    val kind: Kind,
    val code: String,
    val message: String,
    val dismissRing: Boolean,
    val retryable: Boolean,
) {
    enum class Kind {
        ALREADY_ANSWERED,
        CALL_ENDED,
        DEVICE_BUSY,
        NOT_SUPPORTED,
        UNKNOWN_DOOR,
        SERVER_TOO_OLD,
        MIC_DENIED,
        NOT_CONNECTED,
        NETWORK_LOST,
        TIMEOUT,
        UNEXPECTED_MODE,
        ANOTHER_CALL,
        FIRST_NOT_CONNECTED,
        HOLD_FAILED,
        HELD_TOO_LONG,
        PAUSED,
        MEDIA,
        OTHER,
    }
}

object AnswerMessages {
    const val ALREADY_ANSWERED = "Someone else already answered this call"
    const val CALL_ENDED = "The call has ended"
    const val DEVICE_BUSY = "The door station is busy with another call. Try again in a moment."
    const val NOT_SUPPORTED = "This door cannot be answered from Home Assistant."
    const val REFUSED = "This door does not let Portico answer it."
    const val UNKNOWN_DOOR = "Home Assistant does not know this door any more."
    const val SERVER_TOO_OLD = "This Home Assistant cannot take answers from the app yet."
    const val MIC_DENIED = "Portico needs the microphone to answer. Allow it in the app's settings, then answer again."
    const val NOT_CONNECTED = "Not connected to Home Assistant, so the call was not answered."
    const val NETWORK_LOST = "The connection to Home Assistant was lost."
    const val TIMEOUT = "Home Assistant did not reply in time."
    const val UNEXPECTED_MODE = "Home Assistant asked for an answer path this app does not support."
    const val MEDIA = "The phone could not start the call audio."
    const val AUDIO_NOT_CONNECTED = "The call audio did not connect. Answering needs this phone to reach Home Assistant directly, on the home Wi-Fi or through a VPN."
    const val AWAY_MEDIA_DISABLED = "The call audio could not reach this phone, and Home Assistant has call audio over the connection switched off."
    const val ANOTHER_CALL = "You are already on a call."
    const val FIRST_NOT_CONNECTED = "Wait until the first call is connected."
    const val HOLD_FAILED = "The first call could not be put on hold."
    const val HELD_TOO_LONG = "The call ended: on hold too long."
    const val PAUSED = "Portico is paused on this phone, so it cannot answer. Resume it in Settings."
    const val OTHER = "The call could not be answered"

    /** Longest server-supplied text shown on screen. */
    const val MAX_SERVER_TEXT = 200
}

object AnswerErrors {

    /** [serverMessage] is shown only for `not_supported`, where it is the device's own reason. */
    fun fromCode(code: String, serverMessage: String? = null): AnswerFailure = when (code) {
        "already_answered" -> AnswerFailure(
            AnswerFailure.Kind.ALREADY_ANSWERED, code, AnswerMessages.ALREADY_ANSWERED, dismissRing = true, retryable = false,
        )
        "device_busy" -> AnswerFailure(
            AnswerFailure.Kind.DEVICE_BUSY, code, AnswerMessages.DEVICE_BUSY, dismissRing = false, retryable = true,
        )
        "not_supported" -> AnswerFailure(
            AnswerFailure.Kind.NOT_SUPPORTED, code, plain(serverMessage) ?: AnswerMessages.NOT_SUPPORTED, dismissRing = false, retryable = false,
        )
        // The door does not let Portico answer (contract 3's `refused`, which replaces `claims_disabled`: gate-sip with claims
        // switched off). Not "someone else answered": the ring stays and a later press may work once the operator switches
        // claims on, so it is retryable like device_busy.
        "refused" -> AnswerFailure(
            AnswerFailure.Kind.NOT_SUPPORTED, code, AnswerMessages.REFUSED, dismissRing = false, retryable = true,
        )
        // `not_available`: the call is gone, or the door cannot be answered now. The screen says the call ended (the ring
        // goes, as for any ended call); the server's own text is not shown.
        "not_available" -> AnswerFailure(
            AnswerFailure.Kind.CALL_ENDED, code, AnswerMessages.CALL_ENDED, dismissRing = true, retryable = false,
        )
        "unknown_door" -> AnswerFailure(
            AnswerFailure.Kind.UNKNOWN_DOOR, code, AnswerMessages.UNKNOWN_DOOR, dismissRing = false, retryable = false,
        )
        // Away call audio (AwayMedia): HA has it switched off for this door.
        "away_media_disabled" -> AnswerFailure(
            AnswerFailure.Kind.MEDIA, code, AnswerMessages.AWAY_MEDIA_DISABLED, dismissRing = false, retryable = false,
        )
        // An integration without the command, or one whose schema rejects ours.
        "unknown_command", "invalid_format" -> AnswerFailure(
            AnswerFailure.Kind.SERVER_TOO_OLD, code, AnswerMessages.SERVER_TOO_OLD, dismissRing = false, retryable = false,
        )
        else -> AnswerFailure(
            AnswerFailure.Kind.OTHER, code, "${AnswerMessages.OTHER} ($code).", dismissRing = false, retryable = true,
        )
    }

    fun callEnded(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.CALL_ENDED, "call_ended", AnswerMessages.CALL_ENDED, dismissRing = true, retryable = false)

    fun micDenied(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.MIC_DENIED, "mic_denied", AnswerMessages.MIC_DENIED, dismissRing = false, retryable = true)

    fun notConnected(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.NOT_CONNECTED, "not_connected", AnswerMessages.NOT_CONNECTED, dismissRing = false, retryable = true)

    fun networkLost(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.NETWORK_LOST, "network_lost", AnswerMessages.NETWORK_LOST, dismissRing = false, retryable = true)

    fun timeout(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.TIMEOUT, "timeout", AnswerMessages.TIMEOUT, dismissRing = false, retryable = true)

    fun unexpectedMode(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.UNEXPECTED_MODE, "unexpected_mode", AnswerMessages.UNEXPECTED_MODE, dismissRing = false, retryable = false)

    fun media(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.MEDIA, "media", AnswerMessages.MEDIA, dismissRing = false, retryable = true)

    fun audioNotConnected(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.MEDIA, "audio_not_connected", AnswerMessages.AUDIO_NOT_CONNECTED, dismissRing = false, retryable = true)

    fun anotherCall(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.ANOTHER_CALL, "another_call", AnswerMessages.ANOTHER_CALL, dismissRing = false, retryable = false)

    /** A second Answer while the first call is still connecting (15.13 rule 2). Retryable: pressing again once connected works. */
    fun firstNotConnected(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.FIRST_NOT_CONNECTED, "first_not_connected", AnswerMessages.FIRST_NOT_CONNECTED, dismissRing = false, retryable = true)

    /** The live call could not be put on hold, so nothing was changed and the new ring is still ringing. */
    fun holdFailed(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.HOLD_FAILED, "hold_failed", AnswerMessages.HOLD_FAILED, dismissRing = false, retryable = true)

    /** The 15.13 hold limit ended a held call. The screen prefixes the door ("Gate call ended: on hold too long"). */
    fun heldTooLong(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.HELD_TOO_LONG, "held_too_long", AnswerMessages.HELD_TOO_LONG, dismissRing = false, retryable = false)

    fun paused(): AnswerFailure =
        AnswerFailure(AnswerFailure.Kind.PAUSED, "paused", AnswerMessages.PAUSED, dismissRing = false, retryable = true)

    fun unknownDoor(): AnswerFailure = fromCode("unknown_door")

    /** Server text made safe for a TextView: control characters dropped, whitespace collapsed, capped. Null if nothing is left. */
    internal fun plain(text: String?): String? {
        if (text == null) return null
        val cleaned = text.filter { !it.isISOControl() || it == ' ' }.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= AnswerMessages.MAX_SERVER_TEXT) cleaned else cleaned.take(AnswerMessages.MAX_SERVER_TEXT - 1) + "…"
    }
}
