package com.meda.portico.core.call

/*
 * The screens of HOLD AND SWITCH (design 15.13, "Screens, notification"), decided here as plain data and plain English so the
 * JVM can test every one; the activities and the service only map them to widgets. Every model returns "nothing to add" when
 * no call is held, no held call ended quietly and the audio is not interrupted, which is every state there is while
 * [HoldConfig.HOLD_ENABLED] is off - so with the gate off the screens are drawn exactly as before.
 */

/** The words of the hold screens that carry a door name or a number. Static labels (Switch, Decline...) are in `strings.xml`. */
object HoldText {
    const val GENERIC_DOOR = "Door"

    /** `m:ss`, never negative. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val r = s % 60
        return "${s / 60}:${if (r < 10) "0$r" else "$r"}"
    }

    /** Spoken form: "42 seconds", "1 minute", "1 minute 18 seconds". */
    fun spoken(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        fun u(n: Long, one: String) = if (n == 1L) "$n $one" else "$n ${one}s"
        return when {
            m == 0L -> u(r, "second")
            r == 0L -> u(m, "minute")
            else -> "${u(m, "minute")} ${u(r, "second")}"
        }
    }

    fun onCallWith(live: String) = "You are on a call with $live."
    fun holdAndAnswer(live: String) = "Hold $live and answer"
    const val WAIT_FOR_FIRST = "Wait until your first call is connected, then answer this one."
    const val BOTH_IN_USE = "Both of your calls are in use. You cannot answer this one."
    const val INTERRUPTED = "Another app is using the audio. Your call is paused until it stops."

    fun onHold(door: String) = "On hold: $door"
    fun resume(door: String) = "Resume $door"
    const val WAIT_TO_SWITCH = "Wait until the call is connected, then switch."
    fun ended(door: String) = "$door call ended."
    fun endedHeldTooLong(door: String) = "$door call ended: on hold too long."
    fun nowTalking(live: String, held: String?) = if (held == null) "Now talking to $live." else "Now talking to $live. $held on hold."
    fun switchFailed(r: SwitchResult, live: String?, held: String?): String? = when (r) {
        SwitchResult.HOLD_FAILED -> "Could not put ${live ?: "the call"} on hold, so nothing changed."
        SwitchResult.RESUME_FAILED -> "Could not go back to ${held ?: "that call"}. ${live ?: "Your call"} is as it was."
        SwitchResult.LIVE_NOT_CONNECTED -> WAIT_TO_SWITCH
        SwitchResult.SWITCHED, SwitchResult.RESUMED, SwitchResult.NOT_HELD, SwitchResult.DISABLED -> null
    }

    /** The one TalkBack node of the strip. */
    fun stripDescription(door: String, heldFor: Long, left: Long, resume: Boolean, liveEnded: String?): String {
        val state = "$door, on hold for ${spoken(heldFor)}, ${spoken(left)} left."
        return if (resume) "${liveEnded?.let { "$it " } ?: ""}$state Double tap to resume." else "$state Double tap to switch."
    }

    // ---- notification ------------------------------------------------------------------------------
    fun notifTitleLive(door: String) = "On a call: $door"
    fun notifTitleConnecting(door: String) = "Connecting to $door"
    fun notifTitleOnlyHeld(door: String) = "Call on hold: $door"
    fun notifTextHeld(door: String) = "$door on hold"
    const val NOTIF_TEXT_ONLY_HELD = "Tap to open the call and resume it."
}

/** What the ring screen adds when a call is already live (15.13, "Ring screen while on a call"). */
data class RingHoldView(
    /** "You are on a call with Gate." null = nothing to add (no call is live, or the pre-hold screen). */
    val onCallLine: String?,
    /** The second button, "Hold <live> and answer". null = not offered. */
    val holdAnswerLabel: String?,
    val holdAnswerEnabled: Boolean,
    /** The reason the answer is not offered or not enabled; one quiet line. */
    val reasonLine: String?,
    /** Decline replaces "Stop ringing on this phone" while a call is live. */
    val declineIsDecline: Boolean,
) {
    companion object {
        val NOTHING = RingHoldView(null, null, false, null, false)
    }
}

object RingHoldModel {
    /** [liveName] is the door of the call this phone is on (live, else held); null when there is none. */
    fun build(offer: RingOffer, liveName: String?): RingHoldView = when (offer) {
        RingOffer.ANSWER, RingOffer.NONE -> RingHoldView.NOTHING
        RingOffer.HOLD_AND_ANSWER -> {
            val live = liveName ?: HoldText.GENERIC_DOOR
            RingHoldView(HoldText.onCallWith(live), HoldText.holdAndAnswer(live), true, null, true)
        }
        RingOffer.WAIT_FOR_FIRST -> {
            val live = liveName ?: HoldText.GENERIC_DOOR
            RingHoldView(HoldText.onCallWith(live), HoldText.holdAndAnswer(live), false, HoldText.WAIT_FOR_FIRST, true)
        }
        RingOffer.BOTH_IN_USE -> RingHoldView(null, null, false, HoldText.BOTH_IN_USE, true)
    }
}

/** The strip under the live call (15.13): the held call, its clock, and the one button that switches to it. */
data class HeldStripView(
    val callId: String,
    val doorName: String,
    /** "On hold: Entrance". */
    val title: String,
    /** "On hold for 0:42. 1:18 left." */
    val detail: String,
    /** "Resume <door>" when the live call ended, else "Switch". */
    val isResume: Boolean,
    val switchLabel: String,
    val switchEnabled: Boolean,
    /** Shown when [switchEnabled] is false. */
    val disabledReason: String?,
    /** The whole strip as one TalkBack node. */
    val description: String,
)

data class InCallHoldView(
    val strip: HeldStripView?,
    /** Replaces the status line when the live call ended while a call is held ("Gate call ended."), else the failure's own message. */
    val liveEndedLine: String?,
    /** A held call ended quietly: one line, no sound. */
    val quietEndLine: String?,
    val interruptedLine: String?,
    val liveCallId: String?,
    val heldCallId: String?,
    /** A held call exists: the screen must not close (`CallUi.shouldFinish`). */
    val heldExists: Boolean,
) {
    companion object {
        val NOTHING = InCallHoldView(null, null, null, null, null, null, false)
    }
}

object InCallHoldModel {

    /** The word on the Switch button; the door is in the strip's title and its TalkBack description. */
    const val SWITCH_LABEL = "Switch"

    /**
     * @param legacy `AnswerCoordinator.state()`: the call the screen follows (the one that is not held, or the last that ended)
     * @param nowMs the wall clock; the hold clock is `nowMs - heldAtMs`
     * @param holdLimitMs the hold limit, for "time left"
     * @param nameOf a door ref's display name, null when the cache does not know it
     */
    fun build(legacy: CallUiState, sessions: SessionsState, nowMs: Long, holdLimitMs: Long, nameOf: (String) -> String?): InCallHoldView {
        fun name(ref: String?): String = ref?.let(nameOf) ?: HoldText.GENERIC_DOOR
        val held = sessions.held
        val live = sessions.live
        val quiet = sessions.lastQuietEnd
        val interrupted = if (sessions.audioInterrupted) HoldText.INTERRUPTED else null
        val quietLine = quiet?.let {
            val n = name(it.key.doorRef)
            if (it.snapshot.endReason == EndReason.HOLD_TIMEOUT) HoldText.endedHeldTooLong(n) else HoldText.ended(n)
        }
        if (held == null) return InCallHoldView(null, null, quietLine, interrupted, live?.key?.callId, null, false)

        val heldName = name(held.key.doorRef)
        val heldFor = held.heldAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) } ?: 0L
        val left = (holdLimitMs / 1000 - heldFor).coerceAtLeast(0)
        val noLive = live == null
        val liveEnded = if (noLive) liveEndedLine(legacy, ::name) else null
        val enabled = noLive || live?.snapshot?.phase == Phase.IN_CALL
        val strip = HeldStripView(
            callId = held.key.callId,
            doorName = heldName,
            title = HoldText.onHold(heldName),
            detail = "On hold for ${HoldText.clock(heldFor)}. ${HoldText.clock(left)} left.",
            isResume = noLive,
            switchLabel = if (noLive) HoldText.resume(heldName) else SWITCH_LABEL,
            switchEnabled = enabled,
            disabledReason = if (enabled) null else HoldText.WAIT_TO_SWITCH,
            description = HoldText.stripDescription(heldName, heldFor, left, noLive, liveEnded) +
                (if (enabled) "" else " ${HoldText.WAIT_TO_SWITCH}"),
        )
        return InCallHoldView(strip, liveEnded, quietLine, interrupted, live?.key?.callId, held.key.callId, true)
    }

    private fun liveEndedLine(legacy: CallUiState, name: (String?) -> String): String? {
        val s = legacy.snapshot
        val key = legacy.key
        if (key == null || s.phase != Phase.ENDED) return null
        // A failure keeps its own words (a refused second answer says why); anything else is "<door> call ended."
        return s.failure?.message ?: HoldText.ended(name(key.doorRef))
    }

    /**
     * What to say aloud when the screen moved from [prev] to [next]; null = nothing. Only a switch speaks: the live call
     * became the call that was held a moment ago. [nameOfCall] maps a call id to its door name.
     */
    fun announcement(prev: InCallHoldView?, next: InCallHoldView, nameOfCall: (String) -> String): String? {
        val before = prev ?: return null
        val now = next.liveCallId ?: return null
        if (now == before.liveCallId) return null
        if (before.heldCallId != now) return null
        return HoldText.nowTalking(nameOfCall(now), next.heldCallId?.let(nameOfCall))
    }
}

/** The one ongoing call notification when a call is held (15.13); `HoldNoticeModel.build` null = the unchanged single-call notification. */
data class HoldNotice(val title: String, val text: String, val hangUp: Boolean)

object HoldNoticeModel {
    fun build(sessions: SessionsState, nameOf: (String) -> String?): HoldNotice? {
        val held = sessions.held ?: return null
        fun name(ref: String) = nameOf(ref) ?: HoldText.GENERIC_DOOR
        val heldName = name(held.key.doorRef)
        val live = sessions.live ?: return HoldNotice(HoldText.notifTitleOnlyHeld(heldName), HoldText.NOTIF_TEXT_ONLY_HELD, hangUp = false)
        val liveName = name(live.key.doorRef)
        val title = if (live.snapshot.phase == Phase.IN_CALL) HoldText.notifTitleLive(liveName) else HoldText.notifTitleConnecting(liveName)
        return HoldNotice(title, HoldText.notifTextHeld(heldName), hangUp = true)
    }
}
