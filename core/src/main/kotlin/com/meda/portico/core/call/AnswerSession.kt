package com.meda.portico.core.call

import com.meda.portico.core.call.AnswerCommands.Claim
import com.meda.portico.core.call.AnswerCommands.Offer
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat.DiagId
import com.meda.portico.core.push.Redact

/**
 * Answers ONE door call: the whole path from a press of Answer to the call's end, in the card's
 * message order (`portico-card.js` `_answer`): microphone, the WebRTC offer with its ICE candidates
 * gathered, `portico/answer`, `portico/webrtc_offer`, the remote answer, audio up.
 *
 * **The Phase 2 rule.** Nothing here runs until [begin], and [begin] is called only by a user's
 * press of Answer (`AnswerCoordinator.press`, from a button). A ring, a call event, a granted
 * permission or a late callback never opens the microphone and never sends `portico/answer`:
 * [onMicResult] does nothing unless the session is waiting for it after a press, and every other
 * input only moves a session that [begin] already started.
 *
 * **Every way out goes through [finish], once**: timers cancelled, the media closed, the audio
 * route left, the foreground hook told, and `portico/hangup` sent when the phone holds the server's
 * claim and the call is not already over. A hang-up that could not be sent (no socket) is OWED:
 * [onLinkRestored] sends it, and the [ActiveCallStore] record outlives the process so the next
 * start can send it (`AnswerSweep`).
 *
 * Not thread-safe: the app runs it on one executor, tests drive it directly. [Scheduler], the
 * [CommandPort] and the [MediaCall] callbacks may arrive on other threads; the owner re-posts them.
 */
class AnswerSession(
    val callId: String,
    private val doorId: String?,
    private val commands: CommandPort,
    private val media: MediaPort,
    private val audio: AudioRoute,
    private val mic: MicPermission,
    private val scheduler: Scheduler,
    private val store: ActiveCallStore,
    private val wallClockMs: () -> Long,
    private val hooks: AnswerHooks = AnswerHooks.NONE,
    private val config: AnswerConfig = AnswerConfig(),
    private val log: HaLog = HaLog.NONE,
    private val onChange: (AnswerSnapshot) -> Unit = {},
    /**
     * Away call audio (see `AwayMedia`): null = off, and then nothing below differs from the WebRTC-only
     * session. With it, a call whose signaling runs through the tailnet carries its audio as frames from
     * the start, and any other call moves to frames if its WebRTC audio has not connected after
     * [AnswerConfig.fallbackAfterMs] or the peer connection fails.
     */
    private val away: AwayMedia? = null,
    /** DEV-764: write-only observation (`ui ans-*` lines, each with the milliseconds since the press). Never read by the path. */
    private val journal: PushJournal? = null,
) {
    var snapshot: AnswerSnapshot = AnswerSnapshot(Phase.IDLE)
        private set

    val phase: Phase get() = snapshot.phase

    /** True once the server granted the claim (the phone then owes it a hang-up). */
    var claimed: Boolean = false
        private set

    /** A hang-up the phone must still send when it has a socket again. */
    var hangupOwed: Boolean = false
        private set

    private var call: MediaCall? = null
    private var audioEntered = false
    private var hangupInFlight = false
    private var endRequested: EndReason? = null
    private var stepTimer: Cancellable? = null
    private var capTimer: Cancellable? = null
    private var connectTimer: Cancellable? = null
    private var graceTimer: Cancellable? = null
    private var fallbackTimer: Cancellable? = null
    private var holdTimer: Cancellable? = null

    /** Another app has the audio focus: the media is silenced by the app, not by a press (H2). Only while IN_CALL and not held. */
    private var audioSuspended = false

    /** Away media: the audio is carried as frames (from the start, or after the move). */
    private var usingFrames = false
    private var framePath: FramePath? = null

    /** The WebRTC call was retired by the move to frames: its late callbacks mean nothing. */
    private var webrtcRetired = false

    /** Late callbacks from an earlier set-up step are ignored: each step bumps this. */
    private var step = 0

    private val id: String get() = Redact.prefix(callId)

    private var pressedAtMs = 0L

    /** DEV-764: one `ui ans-<event>` line: the call (shortened), the milliseconds since [begin], then [fields]. Never throws. */
    private fun jr(event: String, vararg fields: Pair<String, Any?>) {
        val j = journal ?: return
        try {
            j.diag("ui", event, listOf<Pair<String, Any?>>("call" to DiagId(callId), "ms" to (wallClockMs() - pressedAtMs)) + fields.toList())
        } catch (_: Throwable) {
        }
    }

    // ---- the user's press ---------------------------------------------------------------------

    /** The user pressed Answer. The only start. */
    fun begin() {
        if (phase != Phase.IDLE) return
        pressedAtMs = wallClockMs()
        jr("ans-begin", "door" to (doorId != null), "mic" to try { mic.granted() } catch (_: Exception) { null })
        if (doorId == null) {
            finish(EndReason.FAILED, AnswerErrors.unknownDoor(), hangup = false)
            return
        }
        log.log(Level.INFO, "answer: pressed call=$id")
        if (mic.granted()) {
            prepare()
        } else {
            set(Phase.AWAITING_MIC)
        }
    }

    /** The microphone dialog's answer. Ignored unless a press is waiting for it. */
    fun onMicResult(granted: Boolean) {
        if (phase != Phase.AWAITING_MIC) return
        if (granted) prepare() else finish(EndReason.FAILED, AnswerErrors.micDenied(), hangup = false)
    }

    /** The user's Hang up (or Cancel). */
    fun hangUp() = requestEnd(EndReason.USER_HANGUP)

    fun setMuted(muted: Boolean) {
        if (phase != Phase.CONNECTING && phase != Phase.IN_CALL && phase != Phase.NEGOTIATING) return
        // A held call has no microphone to switch: its mute is applied when it is resumed (15.13 rule 3).
        if (snapshot.held) return
        call?.setMuted(muted)
        framePath?.setMuted(muted)
        change(snapshot.copy(muted = muted))
    }

    /**
     * HOLD / RESUME (design 15.13), phone-local: nothing is sent to the server and no leg is closed. Only the coordinator calls
     * this, and only from a user's press (a hold is never started by a timer or an event, and a resume never is: the Phase 2
     * rule). Holding starts the hold limit ([AnswerConfig.holdLimitMs]); resuming cancels it and puts the mute the person had
     * set back. Returns true when the call is now in the asked state; false (and NOTHING changed) when the call is not `IN_CALL`
     * or the media could not do it.
     */
    fun setHeld(held: Boolean): Boolean {
        if (phase != Phase.IN_CALL) return false
        if (snapshot.held == held) return true
        try {
            call?.setHeld(held)
            framePath?.setHeld(held)
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: hold=$held refused by the media (${e.javaClass.simpleName})")
            return false
        }
        // A hold or a resume is the person's word and replaces any silencing the audio focus did.
        audioSuspended = false
        if (held) {
            holdTimer?.cancel()
            holdTimer = scheduler.schedule(config.holdLimitMs) { onHoldLimit() }
            log.log(Level.INFO, "answer: on hold call=$id")
        } else {
            holdTimer?.cancel()
            holdTimer = null
            log.log(Level.INFO, "answer: resumed call=$id")
            // Belt and braces: whatever the media did on resume, the person's mute is the last word.
            try {
                if (snapshot.muted) {
                    call?.setMuted(true)
                    framePath?.setMuted(true)
                }
            } catch (e: Exception) {
                log.log(Level.WARN, "answer: re-muting after resume threw ${e.javaClass.simpleName}")
            }
        }
        change(snapshot.copy(held = held))
        return true
    }

    /**
     * AUDIO FOCUS (H2): another app (a phone call, a player) took the audio, so the call is silenced - the same media state as a
     * hold, but NOT a hold: the session is not [AnswerSnapshot.held], no hold limit starts, and the person's mute is untouched.
     * [suspended] false puts the audio back as it was (the mute the person had stays in force). Only the coordinator calls it,
     * on the system's focus callback; it never opens a microphone that was not open before the loss. Returns true when the
     * session is now in the asked state; false (nothing changed) when it is not an `IN_CALL`, un-held call or the media refused.
     */
    fun setAudioSuspended(suspended: Boolean): Boolean {
        if (suspended == audioSuspended) return true
        if (suspended) {
            if (phase != Phase.IN_CALL || snapshot.held) return false
            try {
                call?.setHeld(true)
                framePath?.setHeld(true)
            } catch (e: Exception) {
                log.log(Level.WARN, "answer: silencing for the audio focus refused by the media (${e.javaClass.simpleName})")
                return false
            }
            audioSuspended = true
            log.log(Level.INFO, "answer: audio focus lost call=$id")
            return true
        }
        audioSuspended = false
        if (phase != Phase.IN_CALL || snapshot.held) return true
        try {
            call?.setHeld(false)
            framePath?.setHeld(false)
            if (snapshot.muted) {
                call?.setMuted(true)
                framePath?.setMuted(true)
            }
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: restoring after the audio focus threw ${e.javaClass.simpleName}")
        }
        log.log(Level.INFO, "answer: audio focus back call=$id")
        return true
    }

    /** True while the audio focus silences this call. */
    val audioSuspendedNow: Boolean get() = audioSuspended

    // ---- inputs from the rest of the app --------------------------------------------------------

    /** The shared call-state feed (the socket). Only events about this call matter. */
    fun onCallEvent(event: CallEvent) {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return
        when (event) {
            is CallEvent.Ended -> if (event.callId == callId) remoteOver()
            is CallEvent.NoLiveCall -> if (event.doorId == doorId && claimed) remoteOver()
            is CallEvent.Answered -> answeredBySomeone(event.callId)
            is CallEvent.Active -> answeredBySomeone(event.callId)
            is CallEvent.Ringing -> Unit
        }
    }

    // Before the phone claims, "answered" can only be somebody else. After it, the phone's own
    // claim produces this event: the claim's reply decides.
    private fun answeredBySomeone(eventCall: String) {
        if (eventCall == callId && !claimed && phase != Phase.CLAIMING) {
            finish(EndReason.FAILED, AnswerErrors.fromCode("already_answered"), hangup = false)
        }
    }

    /** The WebSocket to HA closed. In-flight commands report Lost themselves; this covers a call already running. */
    fun onLinkLost() {
        if (phase == Phase.CONNECTING || phase == Phase.IN_CALL) startGrace("link")
    }

    /** The WebSocket is authenticated again. */
    fun onLinkRestored() {
        if (phase == Phase.IN_CALL || phase == Phase.CONNECTING) {
            // Media may still be fine; the server's events resume. Its snapshot ends the call if it is over.
            cancelGrace()
        } else if (phase == Phase.ENDED && hangupOwed) {
            sendHangup(AnswerCommands.REASON_ERROR)
        }
    }

    /** The process is going away (service destroyed, task removed): release the call if it can be. */
    fun onAppStopping() {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return
        finish(EndReason.APP_STOPPED, null, hangup = true, hangupReason = AnswerCommands.REASON_TIMEOUT)
    }

    // ---- the path ---------------------------------------------------------------------------------

    private fun prepare() {
        set(Phase.PREPARING)
        guarded { hooks.onMicReady() }
        val myStep = ++step
        if (away != null && preferFrames(away)) {
            // Signaling runs through the tailnet: UDP media cannot reach HA from here. No peer connection, no offer.
            jr("ans-path", "path" to "frames", "why" to pathWhy(away))
            jr("ans-offer-skip", "why" to "prefer-frames")
            usingFrames = true
            if (!openFramePath(away)) return
            claim(null)
            return
        }
        if (away != null) jr("ans-path", "path" to "webrtc", "why" to pathWhy(away))
        val c = try {
            media.open(object : MediaListener {
                override fun onConnected() { if (!webrtcRetired) mediaConnected() }
                override fun onDisconnected() { if (!webrtcRetired) mediaDisconnected() }
                override fun onFailed() { if (!webrtcRetired) mediaFailed() }
            })
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: media open failed (${e.javaClass.simpleName})")
            finish(EndReason.FAILED, AnswerErrors.media(), hangup = false)
            return
        }
        call = c
        jr("ans-offer-ask", "tmo" to config.gatherTimeoutMs)
        c.createOffer(config.gatherTimeoutMs) { sdp ->
            if (myStep != step || phase != Phase.PREPARING) {
                jr("ans-offer", "r" to "stale", "has" to !sdp.isNullOrEmpty(), "phase" to phase)
                return@createOffer
            }
            jr("ans-offer", "r" to (if (sdp.isNullOrEmpty()) "empty" else "ok"), "len" to sdp?.length)
            if (sdp.isNullOrEmpty()) {
                finish(EndReason.FAILED, AnswerErrors.media(), hangup = false)
            } else {
                claim(sdp)
            }
        }
    }

    private fun preferFrames(a: AwayMedia): Boolean = try {
        a.preferFrames()
    } catch (e: Exception) {
        false
    }

    private fun pathWhy(a: AwayMedia): String = try { a.pathWhy() } catch (e: Exception) { "?" }

    /** Opens the frame path; on failure the call is ended (no claim was made yet when this is the first open). */
    private fun openFramePath(a: AwayMedia): Boolean {
        return try {
            framePath = a.open(object : MediaListener {
                override fun onConnected() { if (usingFrames) mediaConnected() }
                override fun onDisconnected() { if (usingFrames) mediaDisconnected() }
                override fun onFailed() { if (usingFrames) mediaFailed() }
            })
            true
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: frame media open failed (${e.javaClass.simpleName})")
            finish(EndReason.FAILED, AnswerErrors.media(), hangup = claimed, hangupReason = AnswerCommands.REASON_ERROR)
            false
        }
    }

    private fun claim(offerSdp: String?) {
        if (endRequested != null) {
            finish(endRequested!!, null, hangup = false)
            return
        }
        set(Phase.CLAIMING)
        jr("ans-claim-send", "sdp" to (offerSdp != null))
        guarded { hooks.onClaiming() }
        val myStep = ++step
        armStep(myStep)
        commands.send(AnswerCommands.ANSWER, AnswerCommands.answer(doorId!!, callId)) { reply ->
            if (myStep != step || phase != Phase.CLAIMING) {
                jr("ans-claim", "r" to "stale", "phase" to phase)
                return@send
            }
            disarmStep()
            jr("ans-claim", "r" to claimWord(reply))
            when (reply) {
                CommandReply.NotSent -> finish(EndReason.FAILED, AnswerErrors.notConnected(), hangup = false)
                CommandReply.Lost -> finish(EndReason.NETWORK_LOST, AnswerErrors.networkLost(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
                is CommandReply.Reply -> when (val c = AnswerCommands.parseClaim(reply.result)) {
                    is Claim.Refused -> finish(endRequested ?: EndReason.FAILED, if (endRequested == null) c.failure else null, hangup = false)
                    is Claim.Granted -> granted(c.mode, offerSdp)
                }
            }
        }
    }

    /** DEV-764: what a claim reply was, as a word: `granted`, `refused:<code>`-style words are left to the command line; here only the class. */
    private fun claimWord(reply: CommandReply): String = when (reply) {
        CommandReply.NotSent -> "notsent"
        CommandReply.Lost -> "lost"
        is CommandReply.Reply -> when (val c = AnswerCommands.parseClaim(reply.result)) {
            is Claim.Refused -> "refused-" + c.failure.code.lowercase().replace(Regex("[^a-z0-9]"), "-").take(20)
            is Claim.Granted -> "granted"
        }
    }

    private fun granted(mode: String?, offerSdp: String?) {
        claimed = true
        try {
            store.save(ActiveCall(doorId!!, callId, wallClockMs()))
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: could not record the active call (${e.javaClass.simpleName})")
        }
        capTimer = scheduler.schedule(config.callCapMs) { onCap() }
        val requested = endRequested
        if (requested != null) {
            finish(requested, null, hangup = true)
            return
        }
        if (mode != AnswerCommands.MODE_RELAY) {
            log.log(Level.WARN, "answer: unexpected mode from the server")
            finish(EndReason.FAILED, AnswerErrors.unexpectedMode(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
            return
        }
        if (usingFrames) {
            startFrames()
            return
        }
        if (offerSdp == null) {
            finish(EndReason.FAILED, AnswerErrors.media(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
            return
        }
        set(Phase.NEGOTIATING)
        jr("ans-offer-send", "mode" to mode)
        val myStep = ++step
        armStep(myStep)
        commands.send(AnswerCommands.WEBRTC_OFFER, AnswerCommands.webrtcOffer(callId, doorId!!, offerSdp)) { reply ->
            if (myStep != step || phase != Phase.NEGOTIATING) {
                jr("ans-offer-reply", "r" to "stale", "phase" to phase)
                return@send
            }
            disarmStep()
            jr("ans-offer-reply", "r" to (if (reply is CommandReply.Reply) (if (AnswerCommands.parseOffer(reply.result) is Offer.Answer) "answer" else "refused") else "lost"))
            when (reply) {
                CommandReply.NotSent, CommandReply.Lost -> finish(EndReason.NETWORK_LOST, AnswerErrors.networkLost(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
                is CommandReply.Reply -> when (val o = AnswerCommands.parseOffer(reply.result)) {
                    is Offer.Refused -> finish(EndReason.FAILED, o.failure, hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
                    is Offer.Answer -> remoteAnswer(o.sdp)
                }
            }
        }
    }

    private fun remoteAnswer(sdp: String) {
        val c = call ?: return
        try {
            audio.enter()
            audioEntered = true
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: audio route failed (${e.javaClass.simpleName})")
        }
        set(Phase.CONNECTING)
        jr("ans-remote-answer", "len" to sdp.length)
        val myStep = ++step
        connectTimer = scheduler.schedule(config.connectTimeoutMs) {
            if (myStep == step && phase == Phase.CONNECTING) {
                finish(EndReason.FAILED, AnswerErrors.audioNotConnected(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
            }
        }
        if (away != null) {
            fallbackTimer = scheduler.schedule(config.fallbackAfterMs) {
                if (myStep == step && phase == Phase.CONNECTING && !usingFrames) switchToFrames()
            }
        }
        c.setRemoteAnswer(sdp) { ok ->
            if (myStep == step && !ok && (phase == Phase.CONNECTING || phase == Phase.IN_CALL)) {
                finish(EndReason.FAILED, AnswerErrors.media(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
            }
        }
    }

    // ---- media events --------------------------------------------------------------------------

    private fun mediaConnected() {
        jr("ans-media", "ev" to "connected", "phase" to phase, "frames" to usingFrames)
        when (phase) {
            Phase.CONNECTING -> {
                connectTimer?.cancel()
                connectTimer = null
                fallbackTimer?.cancel()
                fallbackTimer = null
                cancelGrace()
                set(Phase.IN_CALL, connectedAt = wallClockMs())
            }
            Phase.IN_CALL -> cancelGrace()
            else -> Unit
        }
    }

    private fun mediaDisconnected() {
        jr("ans-media", "ev" to "disconnected", "phase" to phase, "frames" to usingFrames)
        if (phase == Phase.IN_CALL) startGrace("media")
    }

    private fun mediaFailed() {
        jr("ans-media", "ev" to "failed", "phase" to phase, "frames" to usingFrames)
        if (phase == Phase.CONNECTING && away != null && !usingFrames) {
            // Never connected over UDP: try the frames before giving up.
            switchToFrames()
        } else if (phase == Phase.CONNECTING) {
            // Never connected: the phone cannot reach the media endpoint (UDP) from where it is.
            finish(EndReason.FAILED, AnswerErrors.audioNotConnected(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
        } else if (phase == Phase.IN_CALL) {
            finish(EndReason.NETWORK_LOST, AnswerErrors.networkLost(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
        }
    }

    // ---- away media: frames -------------------------------------------------------------------------

    /** The claim is held: ask HA to open the audio channel and start the microphone and speaker. */
    private fun startFrames() {
        val path = framePath ?: return
        if (!audioEntered) {
            try {
                audio.enter()
                audioEntered = true
            } catch (e: Exception) {
                log.log(Level.WARN, "answer: audio route failed (${e.javaClass.simpleName})")
            }
        }
        set(Phase.CONNECTING)
        val myStep = ++step
        connectTimer?.cancel()
        connectTimer = scheduler.schedule(config.connectTimeoutMs) {
            if (myStep == step && phase == Phase.CONNECTING) {
                finish(EndReason.FAILED, AnswerErrors.audioNotConnected(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
            }
        }
        path.start(callId, doorId!!) { failure ->
            if (myStep != step || usingFrames.not()) return@start
            if (failure != null) finish(EndReason.FAILED, failure, hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
        }
    }

    /** WebRTC audio did not come up: retire the peer connection and carry the audio as frames. Once per call. */
    private fun switchToFrames() {
        val a = away ?: return
        if (usingFrames || phase != Phase.CONNECTING) return
        log.log(Level.INFO, "answer: UDP audio did not connect, moving to frames")
        jr("ans-to-frames")
        usingFrames = true
        webrtcRetired = true
        fallbackTimer?.cancel()
        fallbackTimer = null
        val old = call
        call = null
        try {
            old?.close()
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: media close threw ${e.javaClass.simpleName}")
        }
        if (!openFramePath(a)) return
        startFrames()
    }

    // ---- ends ------------------------------------------------------------------------------------

    private fun requestEnd(reason: EndReason) {
        when (phase) {
            Phase.IDLE, Phase.ENDED -> Unit
            // Nothing was claimed, nothing to release.
            Phase.AWAITING_MIC, Phase.PREPARING -> finish(reason, null, hangup = false)
            // A claim is in flight and may still be granted: wait for its reply, then release.
            Phase.CLAIMING -> endRequested = reason
            Phase.NEGOTIATING, Phase.CONNECTING, Phase.IN_CALL -> finish(reason, null, hangup = true, hangupReason = AnswerCommands.REASON_USER)
        }
    }

    private fun remoteOver() {
        when (phase) {
            Phase.AWAITING_MIC, Phase.PREPARING -> finish(EndReason.FAILED, AnswerErrors.callEnded(), hangup = false)
            Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING, Phase.IN_CALL -> finish(EndReason.REMOTE_HANGUP, null, hangup = false)
            else -> Unit
        }
    }

    private fun onCap() {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return
        finish(EndReason.TIME_CAP, null, hangup = true, hangupReason = AnswerCommands.REASON_TIMEOUT)
    }

    private fun onHoldLimit() {
        holdTimer = null
        if (phase != Phase.IN_CALL || !snapshot.held) return
        finish(EndReason.HOLD_TIMEOUT, AnswerErrors.heldTooLong(), hangup = true, hangupReason = AnswerCommands.REASON_TIMEOUT)
    }

    private fun startGrace(what: String) {
        if (graceTimer != null) return
        log.log(Level.INFO, "answer: $what lost, waiting ${config.graceMs} ms")
        graceTimer = scheduler.schedule(config.graceMs) {
            graceTimer = null
            if (phase == Phase.CONNECTING || phase == Phase.IN_CALL) {
                finish(EndReason.NETWORK_LOST, AnswerErrors.networkLost(), hangup = true, hangupReason = AnswerCommands.REASON_ERROR)
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
            if (forStep == step && (phase == Phase.CLAIMING || phase == Phase.NEGOTIATING)) {
                finish(endRequested ?: EndReason.FAILED, if (endRequested == null) AnswerErrors.timeout() else null, hangup = claimed || phase == Phase.CLAIMING, hangupReason = AnswerCommands.REASON_ERROR)
            }
        }
    }

    private fun disarmStep() {
        stepTimer?.cancel()
        stepTimer = null
    }

    /**
     * The one exit. [hangup] is wanted only if the phone holds the claim; a claim whose reply never
     * came (timeout while CLAIMING) may still have been granted, so the hang-up is sent anyway and
     * an `unknown_call` / not-claimed answer is simply cleared.
     */
    private fun finish(reason: EndReason, failure: AnswerFailure?, hangup: Boolean, hangupReason: String = AnswerCommands.REASON_USER) {
        if (phase == Phase.ENDED) return
        step++ // every pending callback of this session is now stale
        disarmStep()
        capTimer?.cancel()
        capTimer = null
        holdTimer?.cancel()
        holdTimer = null
        audioSuspended = false
        connectTimer?.cancel()
        connectTimer = null
        cancelGrace()
        fallbackTimer?.cancel()
        fallbackTimer = null
        val fp = framePath
        framePath = null
        try {
            fp?.close()
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: frame media close threw ${e.javaClass.simpleName}")
        }
        val c = call
        call = null
        try {
            c?.endedBecause(reason)
        } catch (_: Exception) {
            // a note for the journal; it never stops the close
        }
        try {
            c?.close()
        } catch (e: Exception) {
            log.log(Level.WARN, "answer: media close threw ${e.javaClass.simpleName}")
        }
        if (audioEntered) {
            audioEntered = false
            try {
                audio.leave()
            } catch (e: Exception) {
                log.log(Level.WARN, "answer: audio leave threw ${e.javaClass.simpleName}")
            }
        }
        val wasClaiming = phase == Phase.CLAIMING
        val owes = hangup && (claimed || wasClaiming)
        jr("ans-end", "reason" to reason, "code" to failure?.code, "from" to phase, "claimed" to claimed, "owes" to owes, "frames" to usingFrames)
        log.log(Level.INFO, "answer: ended call=$id reason=${reason.name.lowercase()}${failure?.let { " failure=${it.kind.name.lowercase()}" } ?: ""}${if (owes) " (releasing)" else ""}")
        val done = AnswerSnapshot(Phase.ENDED, reason, failure, snapshot.muted, snapshot.connectedAtMs, snapshot.held)
        snapshot = done
        if (claimed && !owes) clearRecord()
        if (owes) {
            if (!claimed) {
                // The claim may have been granted after we gave up on it: remember it so a restart can release it.
                try {
                    store.save(ActiveCall(doorId ?: "", callId, wallClockMs()))
                } catch (_: Exception) {
                }
            }
            sendHangup(hangupReason)
        }
        guarded { onChange(done) }
        guarded { hooks.onEnded(done) }
    }

    private fun sendHangup(reason: String) {
        if (hangupInFlight) return
        hangupInFlight = true
        hangupOwed = true
        commands.send(AnswerCommands.HANGUP, AnswerCommands.hangup(callId, reason)) { reply ->
            hangupInFlight = false
            when (reply) {
                // Any reply, even an error such as unknown_call (the call is already over), settles it.
                is CommandReply.Reply -> {
                    hangupOwed = false
                    clearRecord()
                }
                CommandReply.NotSent, CommandReply.Lost -> hangupOwed = true
            }
        }
    }

    private fun clearRecord() {
        try {
            // Only our own record: the store is a set (15.13 d), and another call's record is not ours to take out.
            store.remove(callId)
        } catch (_: Exception) {
        }
    }

    private fun set(phase: Phase, connectedAt: Long? = null) {
        change(snapshot.copy(phase = phase, connectedAtMs = connectedAt ?: snapshot.connectedAtMs))
    }

    private fun change(s: AnswerSnapshot) {
        snapshot = s
        guarded { onChange(s) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.ERROR, "answer: a listener threw ${e.javaClass.simpleName}")
        }
    }
}

/**
 * After a process death: the calls the previous process claimed and never released (a SET: a phone may have held two door
 * calls, 15.13 d). For every record no live session owns, release it with the server (any reply settles it; the call may long
 * be over, which is `unknown_call`) and take that record out. Does nothing it was not left: it never answers anything.
 */
class AnswerSweep(
    private val commands: CommandPort,
    private val store: ActiveCallStore,
    private val log: HaLog = HaLog.NONE,
) {
    fun run(liveCallId: String?) = run(setOfNotNull(liveCallId))

    /** [liveCallIds]: the calls a session of this process owns; every other record is a leftover. */
    fun run(liveCallIds: Set<String>) {
        val left = try {
            store.loadAll()
        } catch (e: Exception) {
            emptyList()
        }
        for (rec in left) {
            if (rec.callId in liveCallIds) continue
            log.log(Level.INFO, "answer: releasing a call the previous run left call=${Redact.prefix(rec.callId)}")
            commands.send(AnswerCommands.HANGUP, AnswerCommands.hangup(rec.callId, AnswerCommands.REASON_ERROR)) { reply ->
                if (reply is CommandReply.Reply) {
                    try {
                        // Take out only the record we released: another call may have been claimed meanwhile.
                        store.remove(rec.callId)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
