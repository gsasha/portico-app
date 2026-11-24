package com.meda.portico.fcm

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.CallStateSink
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushMessage

/**
 * Where a decided socket outcome goes: to the ring path, unless this phone is paused (a socket event already queued when
 * the pause began alerts nothing). This is what `AppGraph` used to say inline, moved here unchanged so the push and ring
 * journal (0.5.12-alpha) can sit beside it and a JVM test can drive it. The journal is write-only observation: it is told
 * what was decided and what was held back, before the dispatcher runs, and its failure changes nothing.
 */
class SocketOutcomeRoute(
    private val dispatch: (Outcome) -> Unit,
    private val paused: () -> Boolean,
    private val log: HaLog,
    private val journal: PushJournal? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** The size of the doors cache's `knownDoorRefs`, for the journal line only. */
    private val knownDoors: () -> Int? = { null },
) {
    fun onOutcome(msg: PushMessage, outcome: Outcome) {
        observe {
            it.decided(msg, outcome, clockMs(), try { knownDoors() } catch (e: Exception) { null })
        }
        // Pause: a socket event already queued when the pause began alerts nothing.
        if (paused()) {
            observe { it.gated(outcome, "paused") }
            log.log(HaLog.Level.INFO, "portico push: socket ${outcome.contractName} not alerted (paused)")
        } else {
            dispatch(outcome)
        }
    }

    private inline fun observe(block: (PushJournal) -> Unit) {
        val j = journal ?: return
        try {
            block(j)
        } catch (e: Throwable) {
            // write-only observation: never into the decision path
        }
    }
}

/** Tells the journal about each `portico/calls` event as it arrives, then hands it on untouched. */
class JournalingCallSink(private val delegate: CallStateSink, private val journal: PushJournal?) : CallStateSink {
    override fun onCallEvent(event: CallEvent) {
        try {
            journal?.socketEvent(event)
        } catch (e: Throwable) {
            // write-only observation
        }
        delegate.onCallEvent(event)
    }
}
