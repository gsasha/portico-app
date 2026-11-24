package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.ha.TalkStateEvent
import com.meda.portico.core.ha.TalkWireState

/**
 * ONE talk to the door, from the press of a Talk button to the call's end (blocks design 7.3 and 7.5). The sibling of
 * [PlaceSession] and [AnswerSession]: the same ports and the same single exit, in the order the design gives.
 *
 * ```
 *   IDLE --begin--> AWAITING_MIC --granted--> STARTING --run ok (call id)--> CONNECTING --station answered, audio up--> IN_CALL
 *     \_ mic already allowed ____________/       |                              |                                      |
 *                                                | refused/lost/10 s            | gate ended, 20 s, audio 10 s         | gate hung up, 120 s cap,
 *                                                v                              v                                      v  link/media lost > 8 s, Stop
 *   every arrow that is not a forward step ends in ENDED, through finish() and nowhere else
 * ```
 *
 * **INERT (blocks B7b).** [gate] is the live answer to "does the document say this button is ready?" (the server's
 * `available`, which is `false, talk_off` for every door today). Nothing happens until [begin]; [begin] does nothing
 * unless [gate] is true; and [gate] is asked AGAIN right before `portico/content/run` is sent, so a button the server
 * switched off while the microphone dialog was up sends nothing. Every other input (an event, a permission result, a
 * timer, a hang-up) only moves a session [begin] already started, so with the gate shut no run, no hang-up, no offer
 * and no microphone is ever touched.
 *
 * **Every way out goes through [finish], once**: timers cancelled, the audio leg closed, the audio route left, the
 * foreground hook told, and `portico/hangup {call_id, reason}` sent when the server holds a call that is not already
 * over. A hang-up that could not be sent (no socket) is OWED: [onLinkRestored] sends it. A `content/run` reply that
 * arrives after the session gave up on it and granted a call is released at once.
 *
 * **Events.** The call id comes from the run reply. `portico/calls` events ([CallEvent]) for that call id and this
 * session's door move it: `answered` or `active` attaches the audio (once), `ended` ends it. An event that arrives
 * before the run reply (the server may publish first) is kept and replayed once the id is known. A ring of ANOTHER
 * call on the door ends the talk (a visitor outranks it). A snapshot saying the door is idle is not used: the
 * timeouts, the cap and `ended` bound the call.
 *
 * **`talk_state` (P3-C).** The server also tells the phone that placed the call, and only it, how the dialled `out-`
 * call is going: `{door_id, call_id, state: dialing | in_call | ended, reason?}` ([onTalkState]). `dialing` is the
 * state the session is already in; `in_call` is the station answering (the audio is attached with that id); `ended`
 * ends the session, its reason mapped to the failure lines ([TalkErrors.fromTalkEnd]). One for a call id this session
 * did not start is ignored, and so is one when no session is live: with no Talk press nothing here ever runs.
 *
 * Not thread-safe: the app runs it on one executor, tests drive it directly.
 */
class TalkSession(
    val request: TalkRequest,
    private val commands: CommandPort,
    private val media: TalkMediaPort,
    private val audio: AudioRoute,
    private val mic: MicPermission,
    private val scheduler: Scheduler,
    private val wallClockMs: () -> Long,
    /** True only while the document says this Talk button is ready. See the class comment: the inert switch. */
    private val gate: () -> Boolean,
    private val hooks: TalkHooks = TalkHooks.NONE,
    private val config: TalkConfig = TalkConfig(),
    private val log: HaLog = HaLog.NONE,
    private val onChange: (TalkSnapshot) -> Unit = {},
    /** The one place the way a talk ended is told (DEV-pending: which end path fired); the app writes it to the uploaded Journal. */
    private val onEndPath: (TalkEnd) -> Unit = {},
) {
    var snapshot: TalkSnapshot = TalkSnapshot(TalkPhase.IDLE)
        private set

    val phase: TalkPhase get() = snapshot.phase

    /** A hang-up the phone must still send when it has a socket again. */
    var hangupOwed: Boolean = false
        private set

    private var callId: String? = null
    private var pressedAtMs = 0L
    private var answeredAtMs = 0L
    private var leg: TalkLeg? = null
    private var audioEntered = false
    private var hangupInFlight = false
    private var hangupReason = PlaceCommands.REASON_CANCELLED
    private var endRequested: EndReason? = null
    /** Events that arrived before the run reply gave the call id: [CallEvent]s and [TalkStateEvent]s, in arrival order. */
    private val early = ArrayList<Any>()

    private var stepTimer: Cancellable? = null
    private var connectTimer: Cancellable? = null
    private var audioTimer: Cancellable? = null
    private var capTimer: Cancellable? = null
    private var graceTimer: Cancellable? = null

    /** Late callbacks from an earlier step are ignored: each step bumps this. */
    private var step = 0

    // ---- the user's press ---------------------------------------------------------------------

    /** The user pressed a READY Talk button. The only start; a no-op unless the gate says the button is ready. */
    fun begin() {
        if (phase != TalkPhase.IDLE) return
        if (!gate()) {
            log.log(Level.INFO, "talk: press ignored, the button is not ready action=${request.actionId}")
            return
        }
        pressedAtMs = wallClockMs()
        log.log(Level.INFO, "talk: pressed action=${request.actionId}")
        if (mic.granted()) start() else set(TalkPhase.AWAITING_MIC)
    }

    /** The microphone dialog's answer. Ignored unless a press is waiting for it. */
    fun onMicResult(granted: Boolean) {
        if (phase != TalkPhase.AWAITING_MIC) return
        if (granted) start() else finish(EndReason.FAILED, TalkErrors.micDenied(), hangup = false, via = "mic_denied")
    }

    /** Cancel (while connecting) or Stop (in the call). Works in every phase; a no-op when nothing started. */
    fun hangUp() = requestEnd(EndReason.USER_HANGUP)

    // ---- inputs from the rest of the app --------------------------------------------------------

    /** A `portico/calls` event. Only events about this door, while the talk is live, matter. */
    fun onCallEvent(event: CallEvent) {
        if (phase != TalkPhase.STARTING && phase != TalkPhase.CONNECTING && phase != TalkPhase.IN_CALL) return
        if (request.doorId != null && event.doorId != request.doorId) return
        if (event is CallEvent.NoLiveCall) return
        if (phase == TalkPhase.STARTING) {
            // The run reply (and so the call id) is not here yet: keep what the door said, bounded.
            if (early.size < MAX_EARLY) early += event
            return
        }
        handle(event)
    }

    /**
     * A `talk_state` event from the calls stream (P3-C). Only a live session reacts, and only to its own call id: an
     * event for any other id (a call this phone did not start) is ignored without a trace on the session. Like a
     * [CallEvent], one that arrives before the run reply is kept (bounded) and replayed once the id is known.
     */
    fun onTalkState(event: TalkStateEvent) {
        if (phase != TalkPhase.STARTING && phase != TalkPhase.CONNECTING && phase != TalkPhase.IN_CALL) return
        if (request.doorId != null && event.doorId != request.doorId) return
        if (phase == TalkPhase.STARTING) {
            if (early.size < MAX_EARLY) early += event
            return
        }
        handleTalkState(event)
    }

    private fun handleTalkState(event: TalkStateEvent) {
        if (event.callId != callId) {
            log.log(Level.DEBUG, "talk: talk_state for another call ignored")
            return
        }
        when (event.state) {
            TalkWireState.DIALING -> Unit // the session is already CONNECTING; nothing new to show
            TalkWireState.IN_CALL -> stationAnswered()
            TalkWireState.ENDED -> talkEnded(event.reason)
        }
    }

    /** The server says the dialled call is over, with a reason of its own (nothing to release). */
    private fun talkEnded(reason: String?) {
        if (snapshot.stationAnswered) {
            finish(if (TalkErrors.isTimeCap(reason)) EndReason.TIME_CAP else EndReason.REMOTE_HANGUP, null, hangup = false, via = "talk_state_ended")
        } else {
            finish(EndReason.FAILED, TalkErrors.fromTalkEnd(reason), hangup = false, via = "talk_state_ended")
        }
    }

    private fun handle(event: CallEvent) {
        when (event) {
            is CallEvent.NoLiveCall -> Unit
            is CallEvent.Ringing -> {
                // Our own call ringing is nothing new. ANOTHER call ringing on the door (a live one, not a snapshot) is a visitor.
                if (event.callId != callId && !event.snapshot) finish(EndReason.FAILED, TalkErrors.doorRinging(), hangup = true, via = "ring_event_other_call")
            }
            is CallEvent.Answered -> if (event.callId == callId) stationAnswered()
            is CallEvent.Active -> if (event.callId == callId) stationAnswered()
            is CallEvent.Ended -> if (event.callId == callId) remoteEnded()
        }
    }

    /**
     * A door started ringing at this house (or its Answer was pressed): a door call outranks a talk in every live state
     * (design 7.5). Before the server has granted a call there is nothing to hang up; a grant that arrives after this is
     * released by [releaseLate].
     */
    fun onDoorRinging() {
        when (phase) {
            TalkPhase.AWAITING_MIC -> finish(EndReason.FAILED, TalkErrors.doorRinging(), hangup = false, via = "door_ringing")
            TalkPhase.STARTING, TalkPhase.CONNECTING, TalkPhase.IN_CALL -> finish(EndReason.FAILED, TalkErrors.doorRinging(), hangup = true, via = "door_ringing")
            TalkPhase.IDLE, TalkPhase.ENDED -> Unit
        }
    }

    /** The WebSocket to HA closed. A command in flight reports Lost itself; this covers a talk already going. */
    fun onLinkLost() {
        if (phase == TalkPhase.CONNECTING || phase == TalkPhase.IN_CALL) startGrace("link")
    }

    /** The WebSocket is authenticated again. */
    fun onLinkRestored() {
        if (phase == TalkPhase.IN_CALL || phase == TalkPhase.CONNECTING) {
            cancelGrace()
        } else if (phase == TalkPhase.ENDED && hangupOwed) {
            sendHangup()
        }
    }

    /** The process is going away (service destroyed, task removed): release the call if it can be. */
    fun onAppStopping() {
        if (phase == TalkPhase.IDLE || phase == TalkPhase.ENDED) return
        finish(EndReason.APP_STOPPED, null, hangup = true, via = "app_stopping")
    }

    // ---- the path ---------------------------------------------------------------------------------

    private fun start() {
        // The gate again, at the last moment: the microphone dialog may have taken a while.
        if (!gate()) {
            log.log(Level.INFO, "talk: not started, the button went off action=${request.actionId}")
            finish(EndReason.FAILED, TalkErrors.fromCode("not_available"), hangup = false, via = "gate_off")
            return
        }
        set(TalkPhase.STARTING)
        guarded { hooks.onMicReady() }
        val myStep = ++step
        stepTimer = scheduler.schedule(config.runTimeoutMs) {
            if (myStep == step && phase == TalkPhase.STARTING) {
                // The call may still have been started: a late grant is released by releaseLate.
                finish(endRequested ?: EndReason.FAILED, if (endRequested == null) TalkErrors.timeout() else null, hangup = false, via = "run_timeout")
            }
        }
        connectTimer = scheduler.schedule(config.connectTimeoutMs) {
            if (myStep == step && (phase == TalkPhase.STARTING || phase == TalkPhase.CONNECTING) && !snapshot.stationAnswered) {
                finish(endRequested ?: EndReason.FAILED, if (endRequested == null) TalkErrors.noAnswer() else null, hangup = true, via = "no_answer_timeout")
            }
        }
        commands.send(TalkCommands.RUN, TalkCommands.start(request.actionId)) { reply ->
            if (myStep != step || phase != TalkPhase.STARTING) {
                releaseLate(reply)
                return@send
            }
            stepTimer?.cancel()
            stepTimer = null
            when (reply) {
                CommandReply.NotSent -> finish(EndReason.FAILED, TalkErrors.linkDown(), hangup = false, via = "run_not_sent")
                CommandReply.Lost -> finish(EndReason.NETWORK_LOST, TalkErrors.networkLost(), hangup = false, via = "run_lost")
                is CommandReply.Reply -> when (val p = TalkCommands.parseStarted(reply.result)) {
                    is TalkCommands.Started.Refused ->
                        finish(endRequested ?: EndReason.FAILED, if (endRequested == null) p.failure else null, hangup = false, via = "run_refused")
                    is TalkCommands.Started.Granted -> granted(p.callId)
                }
            }
        }
    }

    private fun granted(id: String) {
        callId = id
        val requested = endRequested
        if (requested != null) {
            // Cancel was pressed while the server was dialling: release it now.
            finish(requested, null, hangup = true, via = "user_during_run")
            return
        }
        change(snapshot.copy(phase = TalkPhase.CONNECTING, callId = id))
        val seen = early.toList()
        early.clear()
        for (e in seen) {
            if (phase != TalkPhase.CONNECTING && phase != TalkPhase.IN_CALL) break
            when (e) {
                is CallEvent -> handle(e)
                is TalkStateEvent -> handleTalkState(e)
            }
        }
    }

    /** The station picked up: open the audio leg now (never while it rings). */
    private fun stationAnswered() {
        if (phase != TalkPhase.CONNECTING || snapshot.stationAnswered) return
        val id = callId ?: return
        connectTimer?.cancel()
        connectTimer = null
        answeredAtMs = wallClockMs()
        change(snapshot.copy(stationAnswered = true))
        val door = request.doorId
        if (door == null) {
            log.log(Level.WARN, "talk: the block has no door to attach audio to")
            finish(EndReason.FAILED, TalkErrors.media(), hangup = true, via = "no_door")
            return
        }
        val myStep = ++step
        capTimer = scheduler.schedule(config.callCapMs) { onCap() }
        audioTimer = scheduler.schedule(config.audioTimeoutMs) {
            if (myStep == step && phase == TalkPhase.CONNECTING) {
                finish(EndReason.FAILED, TalkErrors.audioNotConnected(), hangup = true, via = "audio_timeout:${leg?.stage() ?: "?"}")
            }
        }
        try {
            audio.enter()
            audioEntered = true
        } catch (e: Exception) {
            log.log(Level.WARN, "talk: audio route failed (${e.javaClass.simpleName})")
        }
        try {
            leg = media.open(id, door, object : MediaListener {
                override fun onConnected() { mediaConnected() }
                override fun onDisconnected() { mediaDisconnected() }
                override fun onFailed() { mediaFailed() }
            })
        } catch (e: Exception) {
            log.log(Level.WARN, "talk: media open failed (${e.javaClass.simpleName})")
            finish(EndReason.FAILED, TalkErrors.media(), hangup = true, via = "media_open_threw")
        }
    }

    // ---- media events --------------------------------------------------------------------------

    private fun mediaConnected() {
        when (phase) {
            TalkPhase.CONNECTING -> {
                if (!snapshot.stationAnswered) return
                audioTimer?.cancel()
                audioTimer = null
                cancelGrace()
                set(TalkPhase.IN_CALL, connectedAt = wallClockMs())
            }
            TalkPhase.IN_CALL -> cancelGrace()
            else -> Unit
        }
    }

    private fun mediaDisconnected() {
        if (phase == TalkPhase.IN_CALL) startGrace("media")
    }

    private fun mediaFailed() {
        when (phase) {
            TalkPhase.CONNECTING -> if (snapshot.stationAnswered) finish(EndReason.FAILED, TalkErrors.audioNotConnected(), hangup = true, via = "media_failed_connecting:${leg?.lastFailure() ?: "?"}")
            TalkPhase.IN_CALL -> finish(EndReason.NETWORK_LOST, TalkErrors.networkLost(), hangup = true, via = "media_failed_in_call:${leg?.lastFailure() ?: "?"}")
            else -> Unit
        }
    }

    // ---- ends ------------------------------------------------------------------------------------

    private fun requestEnd(reason: EndReason) {
        when (phase) {
            TalkPhase.IDLE, TalkPhase.ENDED -> Unit
            // Nothing was sent, nothing to release.
            TalkPhase.AWAITING_MIC -> finish(reason, null, hangup = false, via = "user_before_run")
            // The server may already be dialling: wait for the reply, then release.
            TalkPhase.STARTING -> endRequested = reason
            TalkPhase.CONNECTING, TalkPhase.IN_CALL -> finish(reason, null, hangup = true, via = "user_hangup")
        }
    }

    /** The server says the call is over: nothing to release. Before the station answered it is a failed call. */
    private fun remoteEnded() {
        val failure = if (snapshot.stationAnswered) null else TalkErrors.stationEnded()
        finish(if (failure == null) EndReason.REMOTE_HANGUP else EndReason.FAILED, failure, hangup = false, via = "call_event_ended")
    }

    private fun onCap() {
        if (phase == TalkPhase.IDLE || phase == TalkPhase.ENDED) return
        finish(EndReason.TIME_CAP, null, hangup = true, via = "time_cap")
    }

    private fun startGrace(what: String) {
        if (graceTimer != null) return
        log.log(Level.INFO, "talk: $what lost, waiting ${config.graceMs} ms")
        graceTimer = scheduler.schedule(config.graceMs) {
            graceTimer = null
            if (phase == TalkPhase.CONNECTING || phase == TalkPhase.IN_CALL) {
                finish(EndReason.NETWORK_LOST, TalkErrors.networkLost(), hangup = true, via = "grace_expired")
            }
        }
    }

    private fun cancelGrace() {
        graceTimer?.cancel()
        graceTimer = null
    }

    /** A `content/run` reply for a press this session already gave up on: if it dialled, release that call. */
    private fun releaseLate(reply: CommandReply) {
        val r = (reply as? CommandReply.Reply)?.result ?: return
        val granted = TalkCommands.parseStarted(r) as? TalkCommands.Started.Granted ?: return
        if (callId == null) callId = granted.callId
        log.log(Level.INFO, "talk: releasing a call dialled after the press was abandoned")
        hangupReason = PlaceCommands.REASON_CANCELLED
        hangupOwed = true
        sendHangup()
    }

    /** The one exit. [hangup] is wanted only if the server holds a call (a call id) that is not already over. */
    private fun finish(reason: EndReason, failure: TalkFailure?, hangup: Boolean, via: String) {
        if (phase == TalkPhase.ENDED) return
        step++ // every pending callback of this session is now stale
        stepTimer?.cancel()
        stepTimer = null
        connectTimer?.cancel()
        connectTimer = null
        audioTimer?.cancel()
        audioTimer = null
        capTimer?.cancel()
        capTimer = null
        cancelGrace()
        early.clear()
        val l = leg
        leg = null
        try {
            l?.endedBecause(reason)
        } catch (_: Exception) {
            // a note for the journal; it never stops the close
        }
        try {
            l?.close()
        } catch (e: Exception) {
            log.log(Level.WARN, "talk: media close threw ${e.javaClass.simpleName}")
        }
        if (audioEntered) {
            audioEntered = false
            try {
                audio.leave()
            } catch (e: Exception) {
                log.log(Level.WARN, "talk: audio leave threw ${e.javaClass.simpleName}")
            }
        }
        val owes = hangup && callId != null
        // Before the station answered it is a cancel; once it has, it is a hang-up.
        hangupReason = if (snapshot.stationAnswered) PlaceCommands.REASON_HANGUP else PlaceCommands.REASON_CANCELLED
        log.log(Level.INFO, "talk: ended action=${request.actionId} via=$via reason=${reason.name.lowercase()}${failure?.let { " failure=${it.kind.name.lowercase()}" } ?: ""}${if (owes) " (releasing)" else ""}")
        val done = TalkSnapshot(TalkPhase.ENDED, reason, failure, callId, snapshot.stationAnswered, snapshot.connectedAtMs)
        snapshot = done
        if (owes) {
            hangupOwed = true
            sendHangup()
        }
        guarded {
            val now = wallClockMs()
            onEndPath(TalkEnd(via, reason, failure?.kind, snapshot.stationAnswered, owes, callId, if (pressedAtMs > 0) now - pressedAtMs else null, if (answeredAtMs > 0) now - answeredAtMs else null))
        }
        guarded { onChange(done) }
        guarded { hooks.onEnded(done) }
    }

    private fun sendHangup() {
        val id = callId ?: return
        if (hangupInFlight) return
        hangupInFlight = true
        commands.send(TalkCommands.HANGUP, TalkCommands.hangup(id, hangupReason)) { reply ->
            hangupInFlight = false
            // Any reply, even `unknown_call` (already over), settles it; only a missing socket leaves it owed.
            hangupOwed = reply !is CommandReply.Reply
        }
    }

    private fun set(phase: TalkPhase, connectedAt: Long? = null) {
        change(snapshot.copy(phase = phase, connectedAtMs = connectedAt ?: snapshot.connectedAtMs))
    }

    private fun change(s: TalkSnapshot) {
        snapshot = s
        guarded { onChange(s) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.ERROR, "talk: a listener threw ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val MAX_EARLY = 8
    }
}

/**
 * How one talk ended, told once from [TalkSession.finish]: [via] names the code path (one word per `finish` call, or
 * `media_failed_*:<why>` with the leg's own reason), [hangup] says a hang-up was sent, [sincePressMs] and [sinceAnswerMs] say how
 * soon. For the uploaded Journal, so a talk that vanished can be read without the phone.
 */
data class TalkEnd(
    val via: String,
    val reason: EndReason,
    val failure: TalkFailure.Kind?,
    val answered: Boolean,
    val hangup: Boolean,
    val callId: String?,
    val sincePressMs: Long?,
    val sinceAnswerMs: Long?,
)
