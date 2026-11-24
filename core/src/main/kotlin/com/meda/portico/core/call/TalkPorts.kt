package com.meda.portico.core.call

// Talk to the door (blocks design 7.5, DEV-658 B7b). The app's side of a button that starts a call to the door the
// block names. INERT: the server answers every Talk button `available: false, reason: talk_off` until a driver can dial
// (P1 to P3), and nothing here runs for such a button. These are the ports the session needs; the answer path's own
// ([CommandPort], [AudioRoute], [MicPermission], [Scheduler], [MediaListener]) are reused as they are.

/** The audio leg of a talk: the phone's microphone and the door's voice, attached to the dialled call. */
interface TalkLeg {
    /** Stops the microphone and the connection. Idempotent, returns at once, never throws. */
    fun close()

    /** The session says why it is about to close the leg (DEV-698, for the journal's audio line). The default ignores it. */
    fun endedBecause(reason: EndReason) {}

    /** A word for why the leg failed (`no_offer`, `offer_refused_<code>`...), for the journal's talk-end line. Null when it did not fail. */
    fun lastFailure(): String? = null

    /**
     * How far the leg got (`gathering`, `offer_sent`, `answer_set`), for the talk-end line of a leg that never connected and never failed (the
     * audio timeout): it tells "no reply to the offer" from "answer applied, the media path never came up". Null when the leg does not say.
     */
    fun stage(): String? = null
}

/**
 * Opens the audio leg for the dialled call [callId] on the block's door [doorId].
 *
 * CONTRACT (what [TalkSession] relies on):
 * - [open] is called **once, only after the station has answered** (a `portico/calls` `answered` or `active` event for
 *   this call id), never while it rings: the microphone stays closed until someone has picked up.
 * - It throws if audio cannot start; the session then ends the talk and hangs up.
 * - The listener's `onConnected` means audio flows both ways; `onDisconnected` may be followed by `onConnected`;
 *   `onFailed` is final. Callbacks may come on any thread; the owner serialises them.
 * - Opening a leg sends no command that ends or starts a call. The real one ([AnswerPathTalkMedia]) sends exactly the
 *   answer path's `portico/webrtc_offer {call_id, door_id, sdp}`.
 */
fun interface TalkMediaPort {
    fun open(callId: String, doorId: String, listener: MediaListener): TalkLeg
}

/** Which button was pressed: the action id (all the server is ever told), the name to show, and the block's door (for the audio). */
data class TalkRequest(val actionId: String, val name: String, val doorId: String?)

enum class TalkPhase {
    IDLE,

    /** Pressed; the microphone dialog is up. Nothing has been sent. */
    AWAITING_MIC,

    /** `portico/content/run` is in flight: no call id yet. */
    STARTING,

    /** The call id is known; waiting for the station to answer, then for the audio. */
    CONNECTING,
    IN_CALL,
    ENDED,
}

data class TalkSnapshot(
    val phase: TalkPhase,
    val endReason: EndReason? = null,
    val failure: TalkFailure? = null,
    /** The server's id for the dialled call, once `content/run` returned it. */
    val callId: String? = null,
    /** The station has answered (the audio is being attached or flows). */
    val stationAnswered: Boolean = false,
    /** Wall-clock moment the audio first connected. */
    val connectedAtMs: Long? = null,
)

/** Hooks the app wires to the foreground service. All optional. */
interface TalkHooks {
    /** The microphone is allowed and the call is about to be started: start the foreground service. */
    fun onMicReady() {}

    /** The session reached ENDED (called once). */
    fun onEnded(snapshot: TalkSnapshot) {}

    companion object {
        val NONE = object : TalkHooks {}
    }
}

data class TalkConfig(
    /** How long `content/run` may wait for Home Assistant's reply. */
    val runTimeoutMs: Long = 10_000,
    /** From the send of `content/run` until the station answers; then the talk gives up and says so (design 7.3 said 15 s; the task asks about 20). */
    val connectTimeoutMs: Long = 20_000,
    /** From the station's answer to audio flowing. */
    val audioTimeoutMs: Long = 10_000,
    /**
     * The app's cap on one connected talk, from the station's answer: 120 s (DEV-692 decision D2). When it fires the session
     * hangs up (`portico/hangup`) and shows [TalkMessages.TIME_CAP]. gate-sip's own 300 s ([GATE_SIP_HARD_CAP_MS]) stays the hard
     * limit behind it, for a phone that cannot hang up; the app never relies on it.
     */
    val callCapMs: Long = APP_TALK_CAP_MS,
    /** How long a lost media path or lost WebSocket may recover during a talk. */
    val graceMs: Long = 8_000,
    /** How long the line saying why a talk ended stays on the button (design 7.3: 4 s). */
    val endedShownMs: Long = 4_000,
)

/** One Talk is capped at 120 s from the station's answer in the app (DEV-692 D2). */
const val APP_TALK_CAP_MS: Long = 120_000

/** gate-sip G2's own cap on a dialled call (300 s): the hard limit behind [APP_TALK_CAP_MS]; documented here, enforced by the gate. */
const val GATE_SIP_HARD_CAP_MS: Long = 300_000
