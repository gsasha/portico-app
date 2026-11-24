package com.meda.portico.core.push

/**
 * The local "ring for door calls on this phone" switch (spec §3.4, P2 plan §3.6; default ON per
 * §7 Q2), applied at the outcome-handler boundary, AFTER [Decision.receive] has recorded the
 * message. So with the switch off the store still learns every call (a later socket or push
 * message for it stays silent), the app posts no door-call alert, and nothing is unregistered:
 * the server's per-phone flag is the push control.
 *
 * - [Outcome.Ring], [Outcome.Dismiss], [Outcome.Missed]: door-call alerts, handled only when on.
 *   (With the switch off nothing rang, so there is nothing for a dismiss to stop.)
 * - [Outcome.Silent]: always passed (it posts nothing anyway).
 * - [Outcome.Quiet] (`kind: test`, Q6): always passed. It is the owner's push ring notification check, not a
 *   door call, and is never a ring.
 */
object RingGate {
    fun shouldHandle(outcome: Outcome, ringOnThisPhone: Boolean): Boolean = when (outcome) {
        is Outcome.Silent, is Outcome.Quiet -> true
        is Outcome.Ring, is Outcome.Dismiss, is Outcome.Missed -> ringOnThisPhone
    }
}
