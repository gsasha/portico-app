package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.ha.LiveCommands

/** Time, as the session sees it. The app posts [after] onto the live thread; the tests advance a fake. */
interface LiveClock {
    fun nowMs(): Long

    /** Runs [task] once, on the session's thread, after [delayMs]. Returns the cancel action. */
    fun after(delayMs: Long, task: () -> Unit): () -> Unit
}

/** What a peer connection tells its session. Every call arrives on the session's thread (the app posts them). */
interface LivePeerListener {
    /** ICE / DTLS connected (media may flow). */
    fun onConnected()

    /** The first decoded frame reached the video sink. */
    fun onFirstFrame()

    /** ICE disconnected; it may recover. */
    fun onDisconnected()

    /** The peer connection failed or closed. */
    fun onFailed()

    /** DEV-764: the peer connection's own state moved ([what] `pc` or `ice`, [state] libwebrtc's word, lowercase). Observation only. */
    fun onState(what: String, state: String) {}
}

/**
 * One RECEIVE-ONLY, VIDEO-ONLY peer connection (the app's libwebrtc one; a fake in the tests). Its offer has one video line and no
 * audio line, and nothing here ever opens the microphone.
 */
interface LivePeer {
    /** Builds the offer and calls [onSdp] with the local description once ICE gathering is complete or [gatherTimeoutMs] has passed; null on failure. */
    fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit)

    fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit)

    /** Only after the answer was applied. A bad candidate is not a failure. */
    fun addRemoteCandidate(candidate: LiveCandidate)

    /** The running total of frames that reached the video sink (any thread). */
    fun frameCount(): Long

    /** DEV-764: one reading of the receiver's statistics (null: none could be had). [onReading] runs on the session's thread. Observation only. */
    fun stats(onReading: (RtcReading?) -> Unit) {
        onReading(null)
    }

    fun close()
}

/** May throw (no decoder, no factory, no peer connection): the session then fails with [LiveWhy.DECODER]. */
fun interface LivePeerFactory {
    fun create(listener: LivePeerListener): LivePeer
}

/** What the websocket tells a session about its `portico/content/live` command. All on the session's thread. */
interface LiveWireSink {
    /** The command could not be sent (the socket is not ready). */
    fun onNotSent()
    fun onReply(reply: LiveCommands.Reply)
    fun onEvent(event: LiveCommands.Event)

    /** The socket closed under the stream. */
    fun onLost()
}

interface LiveWireCall {
    /** Stops delivering this command's events (`PorticoLink.dropStream`). The reply may still arrive; the session closes it by name. */
    fun cancel()
}

/** The websocket side of live video. The app implements it over `PorticoLink` on the link's own thread. */
interface LiveWire {
    fun open(cameraId: String, sdp: String, size: CameraSize, sink: LiveWireSink): LiveWireCall

    /** Fire and forget; nothing waits for the answer. */
    fun ice(sessionId: String, candidate: LiveCandidate)

    /** `portico/content/live_close`, fire and forget. */
    fun close(sessionId: String)
}

/**
 * One live stream of one tile (DEV-713 S1), from "the tile wants it" to "the tile let go": the card's `LiveView` in `:core`, with
 * the rules the owner decided on top. Single-threaded: the owner calls it, and passes its ports' callbacks in, on ONE thread.
 *
 * The exchange (non-trickle): a peer with one recvonly video line gathers, its offer goes in `portico/content/live`; the result and
 * the `session` event give the session id, the `answer` event is applied, the server's `candidate` events are added after it (queued
 * until then), and the end-of-candidates mark goes back once the id is known. The first decoded frame is PLAYING.
 *
 * - **Deadline.** No first frame within [Config.firstFrameMs] (6 s) is a failure: [LiveWhy.DECODER] if the peer was connected (the
 *   video came and could not be shown), else [LiveWhy.CONNECT].
 * - **Failures.** An error answer to the command is final (the server will say the same again). An `error` event, a failed or lost
 *   peer, an ICE disconnect that does not recover in [Config.disconnectMs], a lost socket and the deadline retry after 2 s, 5 s and
 *   15 s (the count starts again after a good run), then the session stays [State.FAILED] (the tile on its still, with the notice)
 *   until [start] is called again (a tap on the chip) or the tile comes back into view (a new session).
 * - **Teardown** (stop, failure, tile gone) always ends with exactly one `live_close` per session id the server gave, even when the
 *   id only arrives after the teardown (a late reply closes by name).
 */
class LiveSession(
    val key: LiveKey,
    private val peers: LivePeerFactory,
    private val wire: LiveWire,
    private val clock: LiveClock,
    private val log: LiveLog = LiveLog.NONE,
    private val config: Config = Config(),
    private val onChange: () -> Unit = {},
) : LiveRun {
    data class Config(
        val firstFrameMs: Long = 6_000,
        val disconnectMs: Long = 5_000,
        val gatherMs: Long = 1_000,
        val retryMs: List<Long> = listOf(2_000, 5_000, 15_000),
        val fpsMs: Long = 1_000,
        /** DEV-764: how often the receiver's statistics are read (the pacer decides which readings reach the journal). */
        val statsMs: Long = 5_000,
    )

    enum class State { IDLE, STARTING, PLAYING, RETRYING, FAILED, STOPPED }

    override var state: State = State.IDLE
        private set

    /** The class of the last failure, for the notice. Cleared by [start] and by the first frame. */
    override var why: LiveWhy? = null
        private set

    /** The stream the server used for the running session (`main` / `sub`). */
    var stream: String? = null
        private set

    /** How many offers this session has sent (a test and the Journal read it). */
    var opens: Int = 0
        private set

    /** `--` or `N fps`, measured while PLAYING. */
    override val fpsLabel: String get() = fps.label

    private val fps = FpsMeter()
    private val stall = StallTracker()
    private var retries = 0
    private var retryTimer: (() -> Unit)? = null
    private var cur: Attempt? = null
    private var playingSince = 0L
    private val closedIds = HashSet<String>()

    private inner class Attempt {
        var peer: LivePeer? = null
        var call: LiveWireCall? = null
        var sessionId: String? = null
        var endSent = false
        var answered = false
        var peerConnected = false
        val queued = ArrayList<LiveCandidate>()
        var startedAt = clock.nowMs()
        var deadline: (() -> Unit)? = null
        var disconnect: (() -> Unit)? = null
        var meter: (() -> Unit)? = null
        var statsTimer: (() -> Unit)? = null
        val pacer = StatsPacer()
        var pcState = "new"
        var iceState = "new"
    }

    private fun since(a: Attempt): Long = clock.nowMs() - a.startedAt

    /** DEV-764: one diagnostic line; the log never throws into the session. */
    private fun diag(event: String, vararg fields: Pair<String, Any?>) {
        try {
            log.diag(event, key, fields.toList())
        } catch (_: Exception) {
        }
    }

    /** DEV-764: an open fps stall ends here (the session stops or fails while frames are still missing). */
    private fun endStall(a: Attempt?, end: String) {
        val down = stall.end(clock.nowMs()) ?: return
        diag("lv-fpsback", "down" to down, "end" to end, "pc" to a?.pcState, "ice" to a?.iceState)
    }

    /** Begins (or, from [State.FAILED], begins again, as a tap on the chip does). A running or retrying session is left alone. */
    override fun start() {
        if (state != State.IDLE && state != State.FAILED) return
        retries = 0
        why = null
        open()
    }

    /** Closes the session for good. Idempotent. */
    override fun stop(reason: StopWhy) {
        if (state == State.STOPPED) return
        val active = state == State.STARTING || state == State.PLAYING || state == State.RETRYING
        val played = if (state == State.PLAYING) clock.nowMs() - playingSince else 0L
        endStall(cur, "stop")
        retryTimer?.invoke()
        retryTimer = null
        teardown()
        state = State.STOPPED
        if (active) log.stopped(key, reason, played)
        onChange()
    }

    // ---- an attempt ----------------------------------------------------------------------------

    private fun open() {
        retryTimer = null
        teardown()
        val a = Attempt()
        cur = a
        state = State.STARTING
        stream = null
        fps.reset()
        stall.reset()
        diag("lv-open", "route" to "webrtc", "retry" to retries, "opens" to opens)
        onChange()
        a.deadline = clock.after(config.firstFrameMs) { if (cur === a) fail(a, if (a.peerConnected) LiveWhy.DECODER else LiveWhy.CONNECT, true) }
        val peer = try {
            peers.create(listener(a))
        } catch (_: Exception) {
            fail(a, LiveWhy.DECODER, false)
            return
        }
        a.peer = peer
        try {
            peer.createOffer(config.gatherMs) { sdp -> onOffer(a, sdp) }
        } catch (_: Exception) {
            fail(a, LiveWhy.DECODER, false)
        }
    }

    private fun onOffer(a: Attempt, sdp: String?) {
        if (cur !== a) return
        if (sdp == null) return fail(a, LiveWhy.CONNECT, true)
        // the builder's own rules (video only, at most 16 KB) are checked before anything is sent
        try {
            LiveCommands.live(key.cameraId, sdp, key.size)
        } catch (_: IllegalArgumentException) {
            return fail(a, LiveWhy.CONNECT, false)
        }
        opens++
        diag("lv-sig", "step" to "offer", "ms" to since(a))
        a.call = wire.open(key.cameraId, sdp, key.size, sink(a))
    }

    private fun sink(a: Attempt) = object : LiveWireSink {
        override fun onNotSent() {
            if (cur === a) fail(a, LiveWhy.CONNECT, true)
        }

        override fun onReply(reply: LiveCommands.Reply) = onReply(a, reply)
        override fun onEvent(event: LiveCommands.Event) = onEvent(a, event)

        override fun onLost() {
            if (cur === a) fail(a, LiveWhy.CONNECT, true)
        }
    }

    private fun onReply(a: Attempt, reply: LiveCommands.Reply) {
        if (cur !== a) {
            // torn down while the answer was on its way: the session it opened is closed by name
            if (reply is LiveCommands.Reply.Opened) closeId(reply.sessionId)
            return
        }
        when (reply) {
            is LiveCommands.Reply.Opened -> adopt(a, reply.sessionId, reply.stream)
            is LiveCommands.Reply.Refused -> fail(a, LiveWhy.ofCode(reply.code), false)
            LiveCommands.Reply.Malformed -> fail(a, LiveWhy.SERVER, false)
            LiveCommands.Reply.Lost -> fail(a, LiveWhy.CONNECT, true)
        }
    }

    private fun adopt(a: Attempt, id: String, streamName: String?) {
        if (streamName != null) stream = streamName
        if (a.sessionId == null) {
            a.sessionId = id
            diag("lv-sig", "step" to "session", "stream" to streamName, "ms" to since(a))
        }
        if (!a.endSent && a.sessionId == id) {
            a.endSent = true
            wire.ice(id, LiveCandidate.END) // non-trickle: everything is in the offer
        }
    }

    private fun onEvent(a: Attempt, e: LiveCommands.Event) {
        if (cur !== a) {
            if (e is LiveCommands.Event.Session) closeId(e.sessionId)
            return
        }
        when (e) {
            is LiveCommands.Event.Session -> adopt(a, e.sessionId, e.stream)
            is LiveCommands.Event.Answer -> {
                val peer = a.peer ?: return
                try {
                    peer.setRemoteAnswer(e.sdp) { ok -> onAnswerApplied(a, ok) }
                } catch (_: Exception) {
                    fail(a, LiveWhy.CONNECT, true)
                }
            }
            is LiveCommands.Event.Candidate -> if (a.answered) addCandidate(a, e.candidate) else a.queued.add(e.candidate)
            is LiveCommands.Event.Error -> fail(a, LiveWhy.ofCode(e.code), true)
            LiveCommands.Event.Other -> Unit
        }
    }

    private fun onAnswerApplied(a: Attempt, ok: Boolean) {
        if (cur !== a) return
        diag("lv-sig", "step" to "answer", "ok" to ok, "ms" to since(a))
        if (!ok) return fail(a, LiveWhy.CONNECT, true)
        a.answered = true
        val queued = a.queued.toList()
        a.queued.clear()
        for (c in queued) addCandidate(a, c)
    }

    private fun addCandidate(a: Attempt, c: LiveCandidate) {
        try {
            a.peer?.addRemoteCandidate(c)
        } catch (_: Exception) {
            // one bad candidate is not a failure
        }
    }

    private fun listener(a: Attempt) = object : LivePeerListener {
        override fun onConnected() {
            if (cur !== a) return
            a.peerConnected = true
            a.disconnect?.invoke()
            a.disconnect = null
        }

        override fun onFirstFrame() {
            if (cur === a && state == State.STARTING) playing(a)
        }

        override fun onDisconnected() {
            if (cur !== a || a.disconnect != null) return
            a.disconnect = clock.after(config.disconnectMs) { if (cur === a) fail(a, LiveWhy.CONNECT, true) }
        }

        override fun onFailed() {
            if (cur === a) fail(a, LiveWhy.CONNECT, true)
        }

        override fun onState(what: String, state: String) {
            if (cur !== a) return
            if (what == "pc") a.pcState = state else a.iceState = state
            diag("lv-ice", "what" to what, "state" to state, "ms" to since(a))
        }
    }

    private fun playing(a: Attempt) {
        state = State.PLAYING
        why = null
        retries = 0
        a.deadline?.invoke()
        a.deadline = null
        val now = clock.nowMs()
        playingSince = now
        log.started(key, stream, now - a.startedAt)
        fps.reset()
        stall.reset()
        meter(a)
        statsTick(a)
        onChange()
    }

    /** DEV-764: one reading of the receiver's statistics every [Config.statsMs] while PLAYING; the pacer decides which reach the journal. */
    private fun statsTick(a: Attempt) {
        a.statsTimer = clock.after(config.statsMs) {
            if (cur === a && state == State.PLAYING) {
                try {
                    a.peer?.stats { r ->
                        if (r != null && cur === a && state == State.PLAYING) {
                            val f = a.pacer.line(clock.nowMs(), r)
                            if (f != null) diag("lv-stats", *f.toTypedArray(), "stream" to stream, "up" to (clock.nowMs() - playingSince) / 1000)
                        }
                    }
                } catch (_: Exception) {
                    // observation only
                }
                statsTick(a)
            }
        }
    }

    /** Samples the frame count about once a second while PLAYING (the label is drawn only when the document asks for it). */
    private fun meter(a: Attempt) {
        a.meter = clock.after(config.fpsMs) {
            if (cur === a && state == State.PLAYING) {
                val now = clock.nowMs()
                val total = a.peer?.frameCount() ?: 0L
                fps.sample(now, total)
                when (val e = stall.sample(now, total)) {
                    is StallTracker.Event.Began -> diag("lv-fps0", "silent" to e.silentMs, "stream" to stream, "pc" to a.pcState, "ice" to a.iceState, "up" to (now - playingSince) / 1000)
                    is StallTracker.Event.Ended -> diag("lv-fpsback", "down" to e.downMs, "end" to "frames", "pc" to a.pcState, "ice" to a.iceState)
                    null -> Unit
                }
                onChange()
                meter(a)
            }
        }
    }

    private fun fail(a: Attempt, reason: LiveWhy, retriable: Boolean) {
        if (cur !== a || state == State.STOPPED) return
        endStall(a, "fail")
        teardown()
        why = reason
        val retry = retries
        val again = retriable && retries < config.retryMs.size
        log.failed(key, reason, retry, again)
        if (again) {
            state = State.RETRYING
            val delay = config.retryMs[retries++]
            retryTimer = clock.after(delay) {
                retryTimer = null
                if (state == State.RETRYING) open()
            }
        } else {
            state = State.FAILED
        }
        onChange()
    }

    /** Ends the current attempt: its timers, its stream, its server session (closed by name, once) and its peer. */
    private fun teardown() {
        val a = cur ?: return
        cur = null
        a.deadline?.invoke()
        a.disconnect?.invoke()
        a.meter?.invoke()
        a.statsTimer?.invoke()
        a.call?.cancel()
        a.sessionId?.let { closeId(it) }
        try {
            a.peer?.close()
        } catch (_: Exception) {
            // already closed
        }
    }

    private fun closeId(id: String) {
        if (closedIds.add(id)) wire.close(id)
    }
}
