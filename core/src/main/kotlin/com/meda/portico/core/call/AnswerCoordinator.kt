package com.meda.portico.core.call

import com.meda.portico.core.call.AnswerFailure.Kind
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.CallStore
import com.meda.portico.core.push.HoldStep
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat.DiagId
import com.meda.portico.core.push.Redact
import com.meda.portico.core.push.Source

/** What the in-call screen follows. [generation] counts answer attempts, so a screen asks for the microphone once per attempt. */
data class CallUiState(val key: CallKey?, val snapshot: AnswerSnapshot, val generation: Int) {
    /** An answer attempt is under way or connected (the path recheck never interrupts one). */
    val isLive: Boolean get() = key != null && snapshot.phase != Phase.IDLE && snapshot.phase != Phase.ENDED
}

/**
 * The app's one answer path: started only by [press] (or [holdAndAnswer], the second-call entry that H2's screens wire and
 * that does nothing while [HoldConfig.HOLD_ENABLED] is off), and wired to the ring, the shared call store and the foreground
 * service. With hold off there is at most one [AnswerSession] at a time, exactly as before M7; with it on there are at most
 * two ([SessionSet]): one live, one held (design 15.13), and the legacy [state] / [addListener] keep following the one that is
 * not held, so a screen that knows nothing of hold sees nothing new. Everything runs on [post] (the
 * app's single answer thread), so a session needs no locks; [state] and [addListener] may be read
 * from any thread.
 *
 * What a press does, in order:
 * 1. refuses (with a state the screen can show) when the phone is paused, another call is live, the
 *    doors cache does not know the door, or the shared store says the call is not ringing any more (answered elsewhere / ended / missed);
 * 2. silences the ring on this phone ([stopRing]; nothing is told to HA);
 * 3. begins the session.
 * Just before the claim goes out the call is recorded ANSWERED in the shared store ("the answer path
 * records it before it acts", `CallState`), so HA's own `answered` event for the phone's claim is
 * STALE and never posts "Answered elsewhere", and the `ended` after it never posts "Missed call".
 * If the claim was never sent ([Kind.NOT_CONNECTED]) the record is taken back, so nobody is shown
 * an answer that did not happen.
 * A failure that dismisses the ring ([AnswerFailure.dismissRing]) also records the call as over and
 * stops the ring.
 *
 * Hold (15.13), when enabled: a second ring is answered only through [holdAndAnswer] (the live call must be connected; it is put
 * on hold FIRST, then the new one begins), [resume] switches (old off, then new on, from a press only), [endHeld] ends the held
 * call, events are routed by call id, the foreground service stops only when the LAST session has ended, and a held call that
 * ends does so quietly (the legacy [state] is not touched; [sessionsState] says it).
 */
class AnswerCoordinator(
    private val newSession: (callId: String, doorId: String?, hooks: AnswerHooks, onChange: (AnswerSnapshot) -> Unit) -> AnswerSession,
    private val callStore: CallStore,
    private val doors: DoorsCache,
    private val sweep: AnswerSweep,
    /** Runs a task on the answer thread, in order. */
    private val post: (() -> Unit) -> Unit,
    private val stopRing: (doorRef: String, callId: String, why: String) -> Unit,
    private val serviceStart: () -> Unit,
    private val serviceStop: () -> Unit,
    private val paused: () -> Boolean,
    private val wallClockMs: () -> Long,
    /** Runs on the answer thread just before an answer begins: a door call outranks an outgoing call, which ends first. */
    private val beforeBegin: () -> Unit = {},
    private val log: HaLog = HaLog.NONE,
    /** THE GATE ([HoldConfig.HOLD_ENABLED]); a test turns it on to prove the second-call path. */
    private val holdEnabled: Boolean = HoldConfig.HOLD_ENABLED,
    /** The push and ring journal (M7): write-only observation of the hold, switch and resume steps; never read here. */
    private val journal: PushJournal? = null,
    /** DEV-764: the link's state at a press (`sock`, `path`, `up`, `ver`, `doors`, `calls`, `cont`), for the `ui ans-press` line. Observation only. */
    private val linkFields: () -> List<Pair<String, Any?>> = { emptyList() },
) {
    private val sessions = SessionSet()

    private companion object {
        /** Not a refusal: the press repeats one already under way. */
        val SAME_CALL = AnswerErrors.anotherCall().copy(code = "same_call")
    }
    private var generation = 0

    @Volatile
    private var current: CallUiState = CallUiState(null, AnswerSnapshot(Phase.IDLE), 0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(CallUiState) -> Unit>()

    @Volatile
    private var setState: SessionsState = SessionsState(emptyList(), 0)
    private val setListeners = java.util.concurrent.CopyOnWriteArrayList<(SessionsState) -> Unit>()
    private var quietEnds = 0

    /** Another app has the audio focus (H2). Only ever true while hold is enabled and a session is active. */
    private var focusLost = false

    /** The session the screens follow: the one that is not held (or the last that ended). */
    fun state(): CallUiState = current

    /** Every door session, for H2's switcher. Empty of meaning (one entry at most) while hold is off. */
    fun sessionsState(): SessionsState = setState

    /** True while any door session has not ended, live or held: the phone is busy for another call. */
    fun hasActiveSession(): Boolean = sessions.active().isNotEmpty()

    fun addSessionsListener(l: (SessionsState) -> Unit): () -> Unit {
        setListeners += l
        post { l(setState) }
        return { setListeners.remove(l) }
    }

    /** Delivers the current state now (on the answer thread) and every change after; returns the remover. */
    fun addListener(l: (CallUiState) -> Unit): () -> Unit {
        listeners += l
        post { l(current) }
        return { listeners.remove(l) }
    }

    /** The server said this door is ring-only (`answerable: false`): the ring screen offers no Answer and says where to answer. */
    fun isRingOnly(doorRef: String): Boolean = !doors.answerableFor(doorRef)

    /**
     * Whether the Answer button should be offered for this ring: nothing in [refusal] stands in the
     * way. The same test [press] applies, so the button and the press can never disagree.
     */
    fun canAnswer(doorRef: String, callId: String): Boolean = refusal(CallKey(doorRef, callId), withHold = false) == null

    /**
     * What the ring screen may offer for this ring (15.13): the plain Answer, "Hold the first call and answer", a disabled one
     * with the reason, or nothing (a third ring). While hold is off this is [RingOffer.ANSWER] exactly when [canAnswer] is.
     */
    fun ringOffer(doorRef: String, callId: String): RingOffer {
        val key = CallKey(doorRef, callId)
        return when (sessions.decide(key, holdEnabled)) {
            SessionSet.Decision.SAME_CALL, SessionSet.Decision.REFUSE_ANOTHER_CALL -> RingOffer.NONE
            SessionSet.Decision.REFUSE_BOTH_IN_USE -> RingOffer.BOTH_IN_USE
            SessionSet.Decision.REFUSE_FIRST_NOT_CONNECTED -> RingOffer.WAIT_FOR_FIRST
            SessionSet.Decision.HOLD_AND_BEGIN -> if (refusal(key, withHold = true) == null) RingOffer.HOLD_AND_ANSWER else RingOffer.NONE
            SessionSet.Decision.BEGIN -> if (refusal(key, withHold = false) == null) RingOffer.ANSWER else RingOffer.NONE
        }
    }

    /**
     * Why a press for [key] would be refused right now, or null if it would begin. Reads only. [withHold]: the press is the
     * "hold the live call and answer" one, which is the only press that may begin on top of a connected live call.
     */
    private fun refusal(key: CallKey, withHold: Boolean): AnswerFailure? {
        val live = current
        val livePhase = live.snapshot.phase
        when (sessions.decide(key, holdEnabled)) {
            // The same call pressed twice (a re-delivered intent) is the same answer; another call is refused.
            SessionSet.Decision.SAME_CALL -> return SAME_CALL
            SessionSet.Decision.REFUSE_ANOTHER_CALL, SessionSet.Decision.REFUSE_BOTH_IN_USE -> return AnswerErrors.anotherCall()
            SessionSet.Decision.REFUSE_FIRST_NOT_CONNECTED -> return AnswerErrors.firstNotConnected()
            SessionSet.Decision.HOLD_AND_BEGIN -> if (!withHold) return AnswerErrors.anotherCall()
            SessionSet.Decision.BEGIN -> Unit
        }
        if (paused()) return AnswerErrors.paused()
        // A door the doors cache no longer knows cannot be answered; refuse before the ring is touched.
        if (doors.doorIdFor(key.doorRef) == null) return AnswerErrors.unknownDoor()
        // The server said this door is ring-only: there is nothing to answer, so no press ever starts an audio attempt.
        if (!doors.answerableFor(key.doorRef)) return AnswerErrors.fromCode("not_supported")
        return when (callStore.get(key)?.state) {
            null, CallState.RINGING -> null
            // The phone's own earlier attempt, recorded ANSWERED before its claim: a retryable failure may be tried again.
            CallState.ANSWERED ->
                if (live.key == key && livePhase == Phase.ENDED && live.snapshot.failure?.retryable == true) null else AnswerErrors.fromCode("already_answered")
            CallState.ENDED, CallState.MISSED -> AnswerErrors.callEnded()
        }
    }

    /** THE press of Answer (a button, or the ring notification's Answer action). The only way an answer begins. */
    fun press(doorRef: String, callId: String) = post { attempt(CallKey(doorRef, callId), withHold = false) }

    /**
     * The press of "Hold <first door> and answer" on a ring that comes while a call is live (15.13). With hold off this is
     * refused exactly as a plain [press] is ("You are already on a call."). Otherwise, in order: every refusal that would stop a
     * plain answer is checked FIRST (nothing is put on hold for an answer that cannot begin); the live call goes on hold (its
     * microphone and speaker off); then the new call begins. If the live call cannot be held, nothing changes and the ring stays.
     */
    fun holdAndAnswer(doorRef: String, callId: String) = post { attempt(CallKey(doorRef, callId), withHold = true) }

    private fun attempt(key: CallKey, withHold: Boolean) {
        val why = refusal(key, withHold)
        jrDiag(
            "ans-press", key,
            listOf<Pair<String, Any?>>(
                "hold" to withHold, "decide" to why?.code?.lowercase()?.replace(Regex("[^a-z0-9]"), "-"),
                "store" to callStore.get(key)?.state, "phase" to current.snapshot.phase,
            ) + (try { linkFields() } catch (_: Throwable) { emptyList() }),
        )
        when {
            why === SAME_CALL -> return
            why != null -> return reject(key, why)
        }
        if (sessions.decide(key, holdEnabled) == SessionSet.Decision.HOLD_AND_BEGIN) {
            // Old off, then new on: the live call is held before the new one so much as asks for the microphone.
            val live = sessions.live()
            jr { it.hold(HoldStep.HOLD_REQUESTED, live?.key?.doorRef, live?.callId) }
            if (live == null || !live.session.setHeld(true)) {
                jr { it.hold(HoldStep.HOLD_REFUSED, live?.key?.doorRef, live?.callId) }
                return reject(key, AnswerErrors.holdFailed())
            }
            jr { it.hold(HoldStep.HOLD_APPLIED, live.key.doorRef, live.callId) }
            jr { it.hold(HoldStep.SWITCHED, key.doorRef, key.callId) }
        }
        // A hang-up still owed for the previous call goes out now if it can: a new claim replaces its record.
        for (old in sessions.all) if (old.ended) old.session.onLinkRestored()
        sessions.dropEnded()
        begin(key)
    }

    /**
     * Makes [callId] (a held call) the live one: the live call, if any, goes on hold FIRST, then the held one resumes (speaker,
     * then microphone, with the mute it had). With no live call (it ended) this is the "Resume" of a call left held. A user's
     * press only: nothing resumes by itself. [onResult] runs on the answer thread.
     */
    fun resume(callId: String, onResult: (SwitchResult) -> Unit = {}) = post { onResult(doResume(callId)) }

    private fun doResume(callId: String): SwitchResult {
        if (!holdEnabled) return SwitchResult.DISABLED
        val target = sessions.find(callId)?.takeIf { it.held }
        if (target == null) {
            jr { it.hold(HoldStep.RESUME_REFUSED, null, callId, SwitchResult.NOT_HELD) }
            return SwitchResult.NOT_HELD
        }
        val live = sessions.live()
        if (live != null) {
            if (!live.connected) {
                jr { it.hold(HoldStep.RESUME_REFUSED, target.key.doorRef, callId, SwitchResult.LIVE_NOT_CONNECTED) }
                return SwitchResult.LIVE_NOT_CONNECTED
            }
            jr { it.hold(HoldStep.HOLD_REQUESTED, live.key.doorRef, live.callId) }
            if (!live.session.setHeld(true)) {
                jr { it.hold(HoldStep.HOLD_REFUSED, live.key.doorRef, live.callId) }
                jr { it.hold(HoldStep.RESUME_REFUSED, target.key.doorRef, callId, SwitchResult.HOLD_FAILED) }
                return SwitchResult.HOLD_FAILED
            }
            jr { it.hold(HoldStep.HOLD_APPLIED, live.key.doorRef, live.callId) }
        }
        val heldForMs = target.heldAtMs?.let { wallClockMs() - it }
        if (!target.session.setHeld(false)) {
            // The held call would not come back: put the live one back as it was, so the person is not left with two held calls.
            val back = live?.session?.setHeld(false)
            if (live != null) jr { it.hold(HoldStep.HOLD_ROLLED_BACK, live.key.doorRef, live.callId, null, null, if (back == true) 1 else 0) }
            jr { it.hold(HoldStep.RESUME_REFUSED, target.key.doorRef, callId, SwitchResult.RESUME_FAILED) }
            return SwitchResult.RESUME_FAILED
        }
        log.log(Level.INFO, "answer: switched to call=${Redact.prefix(callId)}")
        jr { it.hold(if (live != null) HoldStep.SWITCHED else HoldStep.RESUMED, target.key.doorRef, callId, null, heldForMs) }
        return if (live != null) SwitchResult.SWITCHED else SwitchResult.RESUMED
    }

    /**
     * AUDIO FOCUS (H2), from the system's callback on any thread. Another app took the audio (a phone call, a player): the live
     * session is silenced and [SessionsState.audioInterrupted] says so; the audio coming back restores it as it was (the
     * person's mute stays in force, a held call stays held). It never starts a session and never resumes a held one. With hold
     * off ([HoldConfig.HOLD_ENABLED]) this does nothing at all, as the empty focus listener did before.
     */
    fun onAudioFocusChange(change: Int) = post {
        if (!holdEnabled) return@post
        when (AudioFocusPolicy.interpret(change)) {
            AudioFocusPolicy.Event.LOST -> {
                if (sessions.active().isEmpty()) return@post
                focusLost = true
                sessions.live()?.session?.setAudioSuspended(true)
                jr { it.hold(HoldStep.FOCUS_LOST, null, null, null, null, sessions.active().size) }
                publishSet()
            }
            AudioFocusPolicy.Event.REGAINED -> {
                if (!focusLost) return@post
                focusLost = false
                for (t in sessions.active()) t.session.setAudioSuspended(false)
                jr { it.hold(HoldStep.FOCUS_REGAINED, null, null, null, null, sessions.active().size) }
                publishSet()
            }
            AudioFocusPolicy.Event.IGNORE -> Unit
        }
    }

    /**
     * A ring that comes when both door sessions are in use is posted quietly (no sound, no full-screen takeover, 15.13 failure f):
     * true only with hold enabled and two sessions that have not ended. Always false with hold off.
     */
    fun thirdRingIsQuiet(): Boolean = holdEnabled && sessions.active().size >= HoldConfig.MAX_DOOR_SESSIONS

    /** "End held call": hangs up the held call only. It ends quietly. */
    fun endHeld(callId: String) = post { sessions.find(callId)?.takeIf { it.held }?.session?.hangUp() }

    private fun begin(key: CallKey) {
        try {
            beforeBegin()
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: ending the outgoing call threw ${e.javaClass.simpleName}")
        }
        val doorId = doors.doorIdFor(key.doorRef)
        log.log(Level.INFO, "answer: press door=${Redact.prefix(key.doorRef)} call=${Redact.prefix(key.callId)}")
        try {
            stopRing(key.doorRef, key.callId, "answer pressed")
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: stopping the ring threw ${e.javaClass.simpleName}")
        }
        var before: CallRecord? = null
        lateinit var slot: SessionSet.Slot
        val hooks = object : AnswerHooks {
            override fun onMicReady() = serviceStart()
            override fun onClaiming() {
                before = callStore.get(key)
                record(key, CallState.ANSWERED)
            }
            override fun onEnded(snapshot: AnswerSnapshot) {
                // The service (and with it the microphone) outlives every session but the last (15.13 step 5).
                if (!sessions.active().any { it !== slot }) {
                    focusLost = false
                    serviceStop()
                }
                val f = snapshot.failure
                // The claim never left the phone (the socket was down): nobody answered, so the call is still ringing.
                if (f?.kind == Kind.NOT_CONNECTED) unrecord(key, before)
                if (f != null && f.dismissRing) {
                    record(key, if (f.kind == Kind.CALL_ENDED) CallState.ENDED else CallState.ANSWERED)
                    try {
                        stopRing(key.doorRef, key.callId, "answer refused: ${f.code}")
                    } catch (_: Exception) {
                    }
                }
            }
        }
        generation++
        val gen = generation
        slot = SessionSet.Slot(key, doorId, gen)
        val s = newSession(key.callId, doorId, hooks) { snap ->
            slot.snapshot = snap
            // The switcher's hold clock starts at the moment the session first reads held, and is cleared at the resume.
            if (!snap.held) slot.heldAtMs = null else if (slot.heldAtMs == null) slot.heldAtMs = wallClockMs()
            if (snap.phase == Phase.ENDED && snap.held) quietEnds++
            if (snap.phase == Phase.ENDED && snap.held) {
                val heldForMs = slot.heldAtMs?.let { wallClockMs() - it }
                jr { it.hold(if (snap.endReason == EndReason.HOLD_TIMEOUT) HoldStep.HOLD_LIMIT else HoldStep.HELD_ENDED, key.doorRef, key.callId, snap.endReason, heldForMs) }
            }
            // A held call (and one that ended while held) never moves the screen's state: its end is quiet.
            if (!snap.held) publish(CallUiState(key, snap, gen))
            publishSet()
        }
        slot.session = s
        slot.snapshot = s.snapshot
        sessions.add(slot)
        publish(CallUiState(key, s.snapshot, gen))
        publishSet()
        s.begin()
    }

    fun onMicResult(granted: Boolean) = post { sessions.live()?.session?.onMicResult(granted) }

    /** Hang up: the live call only (15.13); a held call is ended with [endHeld]. */
    fun hangUp() = post { sessions.live()?.session?.hangUp() }

    /** Hang up every door call, live and held (the pause: nothing may be left held on a socket that is about to close). */
    fun hangUpAll() = post { for (t in sessions.active()) t.session.hangUp() }

    fun setMuted(muted: Boolean) = post { sessions.live()?.session?.setMuted(muted) }

    /** Routed by call id (a door's `NoLiveCall` by door id), never "to the session". */
    fun onCallEvent(event: CallEvent) = post {
        val targets = when (event) {
            is CallEvent.NoLiveCall -> sessions.forDoor(event.doorId)
            is CallEvent.Ringing -> listOfNotNull(sessions.find(event.callId))
            is CallEvent.Answered -> listOfNotNull(sessions.find(event.callId))
            is CallEvent.Active -> listOfNotNull(sessions.find(event.callId))
            is CallEvent.Ended -> listOfNotNull(sessions.find(event.callId))
        }
        for (t in targets) t.session.onCallEvent(event)
    }

    /** The link went down: every session starts its own grace. */
    fun onLinkLost() = post { for (t in sessions.all) t.session.onLinkLost() }

    /** The socket is authenticated: let every session know, else release a call a dead process left. */
    fun onLinkReady() = post {
        val all = sessions.all
        for (t in all) t.session.onLinkRestored()
        sweep.run(all.map { it.callId }.toSet())
    }

    /** The process is going away: release every live and held call. Safe to call when there is none. */
    fun appStopping() = post { for (t in sessions.all) t.session.onAppStopping() }

    // ---- internals ---------------------------------------------------------------------------------

    private fun jrDiag(event: String, key: CallKey, fields: List<Pair<String, Any?>>) {
        try {
            journal?.diag("ui", event, listOf<Pair<String, Any?>>("call" to DiagId(key.callId), "door" to DiagId(key.doorRef)) + fields)
        } catch (_: Throwable) {
        }
    }

    private fun reject(key: CallKey, failure: AnswerFailure) {
        jrDiag("ans-refuse", key, listOf("code" to failure.code.lowercase().replace(Regex("[^a-z0-9]"), "-"), "kind" to failure.kind))
        log.log(Level.INFO, "answer: press refused call=${Redact.prefix(key.callId)} ${failure.kind.name.lowercase()}")
        generation++
        publish(CallUiState(key, AnswerSnapshot(Phase.ENDED, EndReason.FAILED, failure), generation))
    }

    /** Journal calls never throw into the answer path (the journal catches too; this is the second belt). */
    private inline fun jr(block: (PushJournal) -> Unit) {
        val j = journal ?: return
        try {
            block(j)
        } catch (e: Throwable) {
        }
    }

    private fun publishSet() {
        val next = SessionsState(
            sessions.all.map { SessionView(it.key, it.snapshot, it.generation, it.heldAtMs) },
            setState.seq + 1,
            quietEnds,
            audioInterrupted = focusLost && sessions.active().isNotEmpty(),
        )
        setState = next
        for (l in setListeners) {
            try {
                l(next)
            } catch (e: Exception) {
                log.log(Level.ERROR, "answer: a listener threw ${e.javaClass.simpleName}")
            }
        }
    }

    private fun publish(s: CallUiState) {
        current = s
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Exception) {
                log.log(Level.ERROR, "answer: a listener threw ${e.javaClass.simpleName}")
            }
        }
    }

    /** Takes back the ANSWERED [record] wrote just before a claim that was never sent: restores the [before] record (or none). */
    private fun unrecord(key: CallKey, before: CallRecord?) {
        try {
            callStore.atomically { s ->
                if (s.get(key)?.state == CallState.ANSWERED) {
                    s.remove(key)
                    if (before != null) s.put(before)
                }
            }
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: could not take back the answered record (${e.javaClass.simpleName})")
        }
    }

    /** Moves the shared record forward only (the store's merge rule keeps a finished call finished). */
    private fun record(key: CallKey, state: CallState) {
        try {
            val now = wallClockMs()
            callStore.atomically { s ->
                val ex = s.get(key)
                if (ex == null || ex.state == CallState.RINGING || (ex.state == CallState.ANSWERED && state == CallState.ENDED)) {
                    s.put(CallRecord(key.doorRef, key.callId, state, state.seq, ex?.tMs ?: now, Source.WS, now))
                }
            }
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: could not record the call (${e.javaClass.simpleName})")
        }
    }
}
