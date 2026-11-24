package com.meda.portico.core.call

import kotlinx.serialization.json.JsonObject

/**
 * DEV-764 (app fixes): a [CommandPort] that does not fail a command just because the link is still getting ready.
 *
 * The link reports "ready" the moment `portico/version` answers, but `portico/doors`, `portico/calls/subscribe` and the content
 * subscription come a moment later (over the tailnet, a second or more). A press of Answer in that window either finds the socket not
 * ready ([CommandReply.NotSent]) or is answered `not_subscribed` by the server, which the person read as "Not ready yet. Try again in a
 * moment." while the ring was running out. So here those two outcomes, and only those, are tried again every [pollMs] until [maxWaitMs]
 * have passed since the first send (well inside the ring window and the session's own 10 s step timer); then the last outcome is handed on
 * unchanged, so a link that never gets ready still ends in the same clear refusal as before. A real answer (granted, refused for any
 * other reason, [CommandReply.Lost]) is handed on at once.
 *
 * DEV-811: a `NotSent` of a non-hangup command is retried up to max([maxWaitMs], [lostWaitMs]) while [stillWanted] says the ring is up
 * (the link can take ~10 s after the ring to reconnect on cellular); once the ring is gone the bound is [maxWaitMs] again. `not_subscribed`
 * keeps [maxWaitMs]: it means the link is up and the server refused, so a long wait only hides a real fault.
 *
 * `hangup` is never delayed. [onNotSubscribed] runs once per command on the first `not_subscribed`, so the link can send its
 * `calls/subscribe` again if the server refused it (a retry of a still-pending subscribe would only duplicate it, so the link decides).
 * [note] gets `wait`/`gaveup` words for the journal. The retry runs on the [scheduler]'s thread; [onReply] is called exactly once.
 */
class PatientCommandPort(
    private val inner: CommandPort,
    private val scheduler: Scheduler,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxWaitMs: Long = 4_000,
    private val pollMs: Long = 250,
    private val onNotSubscribed: () -> Unit = {},
    private val note: (type: String, what: String, waitedMs: Long) -> Unit = { _, _, _ -> },
    /**
     * DEV-764 aftermath: how long, in total, a command whose socket DIED under it ([CommandReply.Lost], the link watchdog tore a stalled
     * tunnel down) is sent again after the reconnect. 0 (the default) keeps the old behaviour: `Lost` is handed on at once. It must stay
     * under the session's own step timer (10 s), or a late resend could claim a call nobody is answering any more.
     */
    private val lostWaitMs: Long = 0,
    /** Asked before every resend after a `Lost` (and for the first one): false (the ring ended, the call was answered elsewhere) hands the `Lost` on. */
    private val stillWanted: (type: String) -> Boolean = { true },
) : CommandPort {

    override fun send(type: String, body: JsonObject, onReply: (CommandReply) -> Unit) {
        val hangup = type.endsWith("hangup")
        val t0 = clockMs()
        var asked = false
        var noted = false
        var lostSeen = false

        fun wanted(): Boolean = try {
            stillWanted(type)
        } catch (_: Throwable) {
            true
        }

        fun attempt() {
            inner.send(type, body) { reply ->
                val notSubscribed = !hangup && reply is CommandReply.Reply && !reply.result.success && reply.result.error?.code == NOT_SUBSCRIBED
                val lost = reply === CommandReply.Lost && lostWaitMs > 0 && (lostSeen || wanted())
                if (lost && !lostSeen) {
                    lostSeen = true
                    safeNote(type, "reconnect", clockMs() - t0) // the socket died under it: waiting for the link to come back
                }
                // after a Lost the link is being rebuilt: NotSent (not up yet) is expected until then, hangup included
                val retryable = lost || notSubscribed || (reply === CommandReply.NotSent && (!hangup || lostSeen))
                val waited = clockMs() - t0
                // DEV-811: a NotSent (socket down) of a non-hangup command waits as long as a Lost would (the link was seen to need ~6 s to
                // come back on cellular), but only while the ring is still up; past maxWaitMs the ring is asked, so a gone ring costs nothing.
                val extended = lostSeen || (reply === CommandReply.NotSent && !hangup && lostWaitMs > maxWaitMs && waited + pollMs > maxWaitMs && wanted())
                val bound = if (extended) maxOf(maxWaitMs, lostWaitMs) else maxWaitMs
                if (!retryable || waited + pollMs > bound || (lostSeen && !wanted())) {
                    if (retryable) safeNote(type, "gaveup", waited)
                    onReply(reply)
                    return@send
                }
                if (notSubscribed && !asked) {
                    asked = true
                    try {
                        onNotSubscribed()
                    } catch (_: Throwable) {
                    }
                }
                if (!noted && !lostSeen) {
                    noted = true
                    safeNote(type, "wait", waited)
                }
                try {
                    scheduler.schedule(pollMs) { attempt() }
                } catch (_: Throwable) {
                    onReply(reply) // the timer is gone (shutdown): hand the refusal on
                }
            }
        }
        attempt()
    }

    private fun safeNote(type: String, what: String, waited: Long) {
        try {
            note(type, what, waited)
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val NOT_SUBSCRIBED = "not_subscribed"
    }
}
