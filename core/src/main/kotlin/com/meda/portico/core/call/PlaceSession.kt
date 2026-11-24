package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import kotlinx.serialization.json.JsonObject

/**
 * Places ONE outgoing call from a call button: the path from the press to the call's end. The sibling of
 * [AnswerSession], with the same ports and the same single exit, in a different order (microphone, then
 * the server call, then wait for the far end):
 *
 * ```
 *   IDLE --begin--> AWAITING_MIC --granted--> PLACING --run ok--> RINGING --answered--> CONNECTING --audio up--> IN_CALL
 *     \_ mic already allowed ______________/     |                  |                      |                       |
 *                                                 | error/lost/10 s  | declined/busy/       | 10 s no audio,        | far hang-up, 300 s cap,
 *                                                 v                  | no_answer/door/45 s  | media failed          | media lost > 8 s, user
 *   every arrow that is not a forward step ends in ENDED, through finish() and nowhere else
 * ```
 *
 * **The Phase 2 rule.** Nothing here runs until [begin], and [begin] is called only by a user's press of a
 * call button ([PlaceCoordinator.press]). A ring, an event, a granted permission or a late callback never
 * opens the microphone and never sends `portico/content/run`: [onMicResult] does nothing unless a press is
 * waiting for it, and every other input only moves a session [begin] already started.
 *
 * **There is no confirm step** before a call (decision CB-9): [begin] places it.
 *
 * **Every way out goes through [finish], once**: timers cancelled, the media leg closed, the audio route
 * left, the foreground hook told, and `portico/hangup` sent when the server holds a call that is not
 * already over. A hang-up that could not be sent (no socket) is OWED: [onLinkRestored] sends it. (It does
 * not survive a process death: unlike a claimed door call, an unreleased outgoing call ends by the server's
 * own ring limit and media-loss rules.) A `content/run` reply that arrives after the session gave up on it
 * and granted a call is released at once, so a timed-out press cannot leave a phone ringing for nobody.
 *
 * Not thread-safe: the app runs it on one executor, tests drive it directly.
 */
class PlaceSession(
    val request: PlaceRequest,
    private val commands: CommandPort,
    private val media: PlaceMediaPort,
    private val audio: AudioRoute,
    private val mic: MicPermission,
    private val scheduler: Scheduler,
    private val wallClockMs: () -> Long,
    private val hooks: PlaceHooks = PlaceHooks.NONE,
    private val config: PlaceConfig = PlaceConfig(),
    private val log: HaLog = HaLog.NONE,
    private val onChange: (PlaceSnapshot) -> Unit = {},
) {
    var snapshot: PlaceSnapshot = PlaceSnapshot(PlacePhase.IDLE, answering = request.answerCallId != null)
        private set

    val phase: PlacePhase get() = snapshot.phase

    /** A hang-up the phone must still send when it has a socket again. */
    var hangupOwed: Boolean = false
        private set

    private var callId: String? = null
    private var descriptor: JsonObject? = null
    private var leg: PlaceLeg? = null
    private var audioEntered = false
    private var hangupInFlight = false

    /** What the `portico/hangup` says: cancelled while it rang, hangup once it was answered. Set when the call ends. */
    private var hangupReason = PlaceCommands.REASON_CANCELLED
    private var endRequested: EndReason? = null
    private var stepTimer: Cancellable? = null
    private var ringTimer: Cancellable? = null
    private var connectTimer: Cancellable? = null
    private var capTimer: Cancellable? = null
    private var graceTimer: Cancellable? = null

    /** Late callbacks from an earlier step are ignored: each step bumps this. */
    private var step = 0

    // ---- the user's press ---------------------------------------------------------------------

    /** The user pressed the call button. The only start. */
    fun begin() {
        if (phase != PlacePhase.IDLE) return
        log.log(Level.INFO, "place: pressed action=${request.actionId}")
        if (mic.granted()) place() else set(PlacePhase.AWAITING_MIC)
    }

    /** The microphone dialog's answer. Ignored unless a press is waiting for it. */
    fun onMicResult(granted: Boolean) {
        if (phase != PlacePhase.AWAITING_MIC) return
        if (granted) place() else finish(EndReason.FAILED, PlaceErrors.micDenied(), hangup = false)
    }

    /** The user's Cancel / Hang up. Works in every phase. */
    fun hangUp() = requestEnd(EndReason.USER_HANGUP)

    fun setMuted(muted: Boolean) {
        if (phase != PlacePhase.CONNECTING && phase != PlacePhase.IN_CALL) return
        leg?.setMuted(muted)
        change(snapshot.copy(muted = muted))
    }

    // ---- inputs from the rest of the app --------------------------------------------------------

    /** A `house_call` event for this phone. Only events about this call, while it is live, matter. */
    fun onHouseCall(event: HouseCallEvent) {
        if (event.callId != callId) return
        if (phase != PlacePhase.RINGING && phase != PlacePhase.CONNECTING && phase != PlacePhase.IN_CALL) return
        when (event.state) {
            // "active" while still ringing means the "answered" was missed: the far end is there.
            "answered", "active" -> if (phase == PlacePhase.RINGING) answered()
            "ended" -> remoteEnded(event.reason)
            else -> Unit // ringing (our own), anything new
        }
    }

    /**
     * A door started ringing at this house (or its Answer was pressed): a door call outranks a call button in
     * EVERY live state, a tap included (design Rule 0, 14.4). The call ends through [finish] with "Cancelled,
     * the door is ringing". Before the server has granted a call (AWAITING_MIC, PLACING) there is nothing to
     * hang up; a grant that arrives after this is released by [releaseLate].
     */
    fun onDoorRinging() {
        when (phase) {
            PlacePhase.AWAITING_MIC -> finish(EndReason.FAILED, PlaceErrors.doorRingingCancelled(), hangup = false)
            PlacePhase.PLACING, PlacePhase.RINGING, PlacePhase.CONNECTING, PlacePhase.IN_CALL ->
                finish(EndReason.FAILED, PlaceErrors.doorRingingCancelled(), hangup = true)
            PlacePhase.IDLE, PlacePhase.ENDED -> Unit
        }
    }

    /** The WebSocket to HA closed. A command in flight reports Lost itself; this covers a call already ringing or running. */
    fun onLinkLost() {
        when (phase) {
            // The server ends a ringing call when the caller's socket drops, and gives no reconnect grace (design 4.1).
            PlacePhase.RINGING -> finish(EndReason.NETWORK_LOST, PlaceErrors.networkLost(), hangup = true)
            PlacePhase.CONNECTING, PlacePhase.IN_CALL -> startGrace("link")
            else -> Unit
        }
    }

    /** The WebSocket is authenticated again. */
    fun onLinkRestored() {
        if (phase == PlacePhase.IN_CALL || phase == PlacePhase.CONNECTING) {
            cancelGrace()
        } else if (phase == PlacePhase.ENDED && hangupOwed) {
            sendHangup()
        }
    }

    /** The process is going away (service destroyed, task removed): release the call if it can be. */
    fun onAppStopping() {
        if (phase == PlacePhase.IDLE || phase == PlacePhase.ENDED) return
        finish(EndReason.APP_STOPPED, null, hangup = true)
    }

    // ---- the path ---------------------------------------------------------------------------------

    private fun place() {
        set(PlacePhase.PLACING)
        guarded { hooks.onMicReady() }
        val myStep = ++step
        armStep(myStep)
        val (type, body) = request.answerCallId?.let { HouseCommands.ANSWER to HouseCommands.answer(it) }
            ?: (PlaceCommands.RUN to PlaceCommands.place(request.actionId))
        commands.send(type, body) { reply ->
            if (myStep != step || phase != PlacePhase.PLACING) {
                releaseLate(reply)
                return@send
            }
            disarmStep()
            when (reply) {
                CommandReply.NotSent -> finish(EndReason.FAILED, PlaceErrors.notConnected(), hangup = false)
                CommandReply.Lost -> finish(EndReason.NETWORK_LOST, PlaceErrors.networkLost(), hangup = false)
                is CommandReply.Reply -> when (val p = parseGrant(reply.result)) {
                    is PlaceCommands.Placed.Refused ->
                        finish(endRequested ?: EndReason.FAILED, if (endRequested == null) p.failure else null, hangup = false)
                    is PlaceCommands.Placed.Granted -> granted(p)
                }
            }
        }
    }

    /** The server's grant of the call: a placed call's `content/run` result, or an answered ring's `house/answer` result (same shape for the session). */
    private fun parseGrant(r: com.meda.portico.core.ha.HaInbound.Result): PlaceCommands.Placed {
        if (request.answerCallId == null) return PlaceCommands.parsePlaced(r, request.name)
        return when (val a = HouseCommands.parseAnswer(r)) {
            is HouseCommands.Answered.Granted -> PlaceCommands.Placed.Granted(a.callId, a.media)
            is HouseCommands.Answered.Refused -> PlaceCommands.Placed.Refused(a.failure)
        }
    }

    private fun granted(p: PlaceCommands.Placed.Granted) {
        callId = p.callId
        descriptor = p.media
        val requested = endRequested
        if (requested != null) {
            // Cancel was pressed while the server was ringing them: release it now.
            finish(requested, null, hangup = true)
            return
        }
        if (request.answerCallId != null) {
            // An answered ring: the call is ours already, there is no ringing to wait for. Audio now (the press was the person's).
            change(snapshot.copy(callId = p.callId))
            answered()
            return
        }
        change(snapshot.copy(phase = PlacePhase.RINGING, callId = p.callId))
        ringTimer = scheduler.schedule(config.ringTimeoutMs) {
            if (phase == PlacePhase.RINGING) {
                finish(EndReason.FAILED, PlaceErrors.noAnswer(request.name), hangup = true)
            }
        }
    }

    /** The far end picked up: open the caller's leg now (never while ringing). */
    private fun answered() {
        ringTimer?.cancel()
        ringTimer = null
        try {
            audio.enter()
            audioEntered = true
        } catch (e: Exception) {
            log.log(Level.WARN, "place: audio route failed (${e.javaClass.simpleName})")
        }
        set(PlacePhase.CONNECTING)
        val myStep = ++step
        capTimer = scheduler.schedule(config.callCapMs) { onCap() }
        connectTimer = scheduler.schedule(config.connectTimeoutMs) {
            if (myStep == step && phase == PlacePhase.CONNECTING) {
                finish(EndReason.FAILED, PlaceErrors.audioNotConnected(), hangup = true)
            }
        }
        try {
            leg = media.open(callId ?: "", descriptor, object : MediaListener {
                override fun onConnected() { mediaConnected() }
                override fun onDisconnected() { mediaDisconnected() }
                override fun onFailed() { mediaFailed() }
            })
        } catch (e: Exception) {
            log.log(Level.WARN, "place: media open failed (${e.javaClass.simpleName})")
            finish(EndReason.FAILED, PlaceErrors.media(), hangup = true)
        }
    }

    // ---- media events --------------------------------------------------------------------------

    private fun mediaConnected() {
        when (phase) {
            PlacePhase.CONNECTING -> {
                connectTimer?.cancel()
                connectTimer = null
                cancelGrace()
                set(PlacePhase.IN_CALL, connectedAt = wallClockMs())
            }
            PlacePhase.IN_CALL -> cancelGrace()
            else -> Unit
        }
    }

    private fun mediaDisconnected() {
        if (phase == PlacePhase.IN_CALL) startGrace("media")
    }

    private fun mediaFailed() {
        when (phase) {
            PlacePhase.CONNECTING -> finish(EndReason.FAILED, PlaceErrors.audioNotConnected(), hangup = true)
            PlacePhase.IN_CALL -> finish(EndReason.NETWORK_LOST, PlaceErrors.networkLost(), hangup = true)
            else -> Unit
        }
    }

    // ---- ends ------------------------------------------------------------------------------------

    private fun requestEnd(reason: EndReason) {
        when (phase) {
            PlacePhase.IDLE, PlacePhase.ENDED -> Unit
            // Nothing was sent, nothing to release.
            PlacePhase.AWAITING_MIC -> finish(reason, null, hangup = false)
            // The server may already be ringing them: wait for the reply, then release.
            PlacePhase.PLACING -> endRequested = reason
            PlacePhase.RINGING, PlacePhase.CONNECTING, PlacePhase.IN_CALL -> finish(reason, null, hangup = true)
        }
    }

    /** The server ended the call: nothing to release. */
    private fun remoteEnded(reason: String?) {
        // "error" while talking is a lost call, not a call that never connected.
        val failure = if (reason == "error" && phase == PlacePhase.IN_CALL) PlaceErrors.networkLost()
        else PlaceErrors.fromEndReason(reason, request.name)
        finish(if (failure == null) EndReason.REMOTE_HANGUP else EndReason.FAILED, failure, hangup = false)
    }

    private fun onCap() {
        if (phase == PlacePhase.IDLE || phase == PlacePhase.ENDED) return
        finish(EndReason.TIME_CAP, null, hangup = true)
    }

    private fun startGrace(what: String) {
        if (graceTimer != null) return
        log.log(Level.INFO, "place: $what lost, waiting ${config.graceMs} ms")
        graceTimer = scheduler.schedule(config.graceMs) {
            graceTimer = null
            if (phase == PlacePhase.CONNECTING || phase == PlacePhase.IN_CALL) {
                finish(EndReason.NETWORK_LOST, PlaceErrors.networkLost(), hangup = true)
            }
        }
    }

    private fun cancelGrace() {
        graceTimer?.cancel()
        graceTimer = null
    }

    private fun armStep(forStep: Int) {
        stepTimer?.cancel()
        stepTimer = scheduler.schedule(config.stepTimeoutMs) {
            if (forStep == step && phase == PlacePhase.PLACING) {
                // The call may still have been placed: a late grant is released by releaseLate.
                finish(endRequested ?: EndReason.FAILED, if (endRequested == null) PlaceErrors.timeout() else null, hangup = false)
            }
        }
    }

    private fun disarmStep() {
        stepTimer?.cancel()
        stepTimer = null
    }

    /** A `content/run` reply for a press this session already gave up on: if it rang someone, release that call. */
    private fun releaseLate(reply: CommandReply) {
        val r = (reply as? CommandReply.Reply)?.result ?: return
        val granted = parseGrant(r) as? PlaceCommands.Placed.Granted ?: return
        if (callId == null) callId = granted.callId
        log.log(Level.INFO, "place: releasing a call granted after the press was abandoned")
        hangupReason = PlaceCommands.REASON_CANCELLED
        hangupOwed = true
        sendHangup()
    }

    /** The one exit. [hangup] is wanted only if the server holds a call (a call id) that is not already over. */
    private fun finish(reason: EndReason, failure: PlaceFailure?, hangup: Boolean) {
        if (phase == PlacePhase.ENDED) return
        step++ // every pending callback of this session is now stale
        disarmStep()
        ringTimer?.cancel()
        ringTimer = null
        connectTimer?.cancel()
        connectTimer = null
        capTimer?.cancel()
        capTimer = null
        cancelGrace()
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
            log.log(Level.WARN, "place: media close threw ${e.javaClass.simpleName}")
        }
        if (audioEntered) {
            audioEntered = false
            try {
                audio.leave()
            } catch (e: Exception) {
                log.log(Level.WARN, "place: audio leave threw ${e.javaClass.simpleName}")
            }
        }
        val owes = hangup && callId != null
        // The phase is still the one the call ended in: ringing (or before) is a cancel, answered is a hang-up.
        hangupReason = if (phase == PlacePhase.CONNECTING || phase == PlacePhase.IN_CALL) PlaceCommands.REASON_HANGUP else PlaceCommands.REASON_CANCELLED
        log.log(Level.INFO, "place: ended action=${request.actionId} reason=${reason.name.lowercase()}${failure?.let { " failure=${it.kind.name.lowercase()}" } ?: ""}${if (owes) " (releasing)" else ""}")
        val done = PlaceSnapshot(PlacePhase.ENDED, reason, failure, snapshot.muted, snapshot.connectedAtMs, callId, snapshot.answering)
        snapshot = done
        if (owes) {
            hangupOwed = true
            sendHangup()
        }
        guarded { onChange(done) }
        guarded { hooks.onEnded(done) }
    }

    private fun sendHangup() {
        val id = callId ?: return
        if (hangupInFlight) return
        hangupInFlight = true
        // A house call (`hso-`) ends with the house's own hang-up; the door's `portico/hangup` does not know its id (M7 3b-A).
        val (type, body) = HouseCommands.hangupFor(id, hangupReason)
        commands.send(type, body) { reply ->
            hangupInFlight = false
            // Any reply, even an error such as unknown_call (already over) or unknown_command (a server that
            // cannot be told), settles it; only a missing socket leaves it owed.
            if (reply is CommandReply.Reply) hangupOwed = false else hangupOwed = true
        }
    }

    private fun set(phase: PlacePhase, connectedAt: Long? = null) {
        change(snapshot.copy(phase = phase, connectedAtMs = connectedAt ?: snapshot.connectedAtMs))
    }

    private fun change(s: PlaceSnapshot) {
        snapshot = s
        guarded { onChange(s) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.ERROR, "place: a listener threw ${e.javaClass.simpleName}")
        }
    }
}
