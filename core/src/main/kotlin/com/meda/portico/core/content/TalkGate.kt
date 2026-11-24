package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction

/**
 * The one rule for "can this Talk button be pressed?" (blocks design 7.3). Home draws the button from it AND the talk
 * path asks it again before it begins and before it sends, so there is no second opinion that could disagree.
 *
 * It is the SERVER's word, never the app's: a Talk button is ready only when the document says `available: true` and
 * the reason is not a call this phone already has going. Today no door reports `CanDial`, so every Talk button arrives
 * `available: false, reason: talk_off` and [ready] is false for all of them: the app starts nothing.
 *
 * Fails closed: an action that is missing, of another kind, not available, or on a link that is not up is not ready.
 */
object TalkGate {

    /** The server's `reason` for an AVAILABLE Talk button whose call this phone started and that is still going: a second press stops it. */
    private val UNDER_WAY = setOf("connecting", "in_call")

    /** [usable] is [HomeModel.usable]: the link is up and this server answers the content commands for this phone. */
    fun ready(action: ContentAction?, usable: Boolean, gate: GateCall? = null): Boolean =
        action != null && usable && action.available &&
            ((action.isTalk && !underWay(action)) || (action.isCall && gate?.doorId != null))

    /**
     * An available Talk button on which a call from this phone is still going, with no talk of this process behind it (the
     * app was restarted, say): it can only be waited out (the server's 300 s cap), because the app has no call id to stop it by.
     */
    fun underWay(action: ContentAction): Boolean = action.isTalk && action.available && action.reason in UNDER_WAY

    /**
     * DEV-764, observation only: WHY [ready] says what it says, in the same order as its conditions, as one word (`ready` when it is). [available] and
     * [reason] are the server's own (the reason only as a word, else null). Never changes what [ready] decides; a test holds the two together.
     */
    class Why(val word: String, val available: Boolean?, val reason: String?, val usable: Boolean)

    fun why(action: ContentAction?, usable: Boolean, gate: GateCall? = null): Why {
        val reason = action?.reason?.takeIf { Regex("[a-z0-9_]{1,24}").matches(it) }
        val word = when {
            action == null -> "no-action"
            !usable -> "link-not-usable"
            !action.available -> "not-available"
            action.isTalk && underWay(action) -> "under-way"
            action.isTalk -> "ready"
            action.isCall && gate?.doorId != null -> "ready"
            action.isCall -> "no-gate-door"
            else -> "not-talk"
        }
        return Why(word, action?.available, reason, usable)
    }
}
