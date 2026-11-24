package com.meda.portico.core.live

/**
 * What one pull of H.264 from go2rtc over RTSP tells its session (DEV-713 S1b; the app's Go client, a fake in the tests).
 *
 * [onFormat] and [onNal] are the DATA path: they arrive on the Go client's own thread, are never posted, and must never block
 * (the session hands them straight to the decoder, whose queue is bounded). [onEnded] is a CONTROL event and arrives on the
 * session's thread (the app posts it).
 */
interface RtspListener {
    /** The parameter sets (Annex-B, start code included) and the picture size, before any frame and again when the camera changes them. */
    fun onFormat(sps: ByteArray, pps: ByteArray, width: Int, height: Int)

    /** One complete Annex-B NAL unit; [key] is an IDR slice. */
    fun onNal(nal: ByteArray, ptsUs: Long, key: Boolean)

    /** The connection ended on its own ([kind] is a plain word: `dial`, `rtsp`, `stalled`, `lost`, `protocol`); it is not called after [RtspSource.stop]. */
    fun onEnded(kind: String)
}

/** One running pull. [stop] closes the connection and tells go2rtc; idempotent and never blocks long. */
interface RtspSource {
    fun stop()
}

/** Starts a pull of [url] (already validated by [com.meda.portico.core.ha.LiveRtsp]). Returns at once; may throw (no Go client in this build). */
fun interface RtspSourceFactory {
    fun open(url: String, listener: RtspListener): RtspSource
}

/** What the decoder tells its session. Every call arrives on the session's thread (the app posts them). */
interface RtspDecoderListener {
    /** The first frame was decoded and handed to the surface. */
    fun onFirstFrame()

    /** The decoder broke while running (not at creation, which throws). */
    fun onFailed()
}

/**
 * The H.264 decoder of one tile (the app's `MediaCodec` into a plain `SurfaceView`; a fake in the tests). [format] and [nal] come from
 * the Go client's thread: they only queue (a bounded queue that resyncs on an IDR when full) and never block. The decoder configures
 * itself from the parameter sets and starts at an IDR.
 */
interface RtspDecoder {
    fun format(sps: ByteArray, pps: ByteArray, width: Int, height: Int)
    fun nal(nal: ByteArray, ptsUs: Long, key: Boolean)

    /** The running total of frames decoded to the surface (any thread). */
    fun frameCount(): Long

    /** Idempotent. */
    fun release()
}

/** May throw (no decoder for H.264, no surface machinery): the session then fails with [LiveWhy.DECODER] and does not retry. */
fun interface RtspDecoderFactory {
    fun create(key: LiveKey, listener: RtspDecoderListener): RtspDecoder
}

/**
 * One AWAY-from-home live stream of one tile (DEV-713 S1b): RTSP/H.264 pulled from go2rtc through the tailnet node, decoded by the
 * phone's hardware into the tile's `SurfaceView`. The RTSP counterpart of [LiveSession], with the same states, notices, retry
 * schedule and Journal lines (`route=rtsp`, the stream NAME and never the address). Single-threaded like [LiveSession], except for the
 * data path ([RtspListener.onFormat] / [RtspListener.onNal]).
 *
 * - **IDR gate.** Nothing reaches the decoder before the parameter sets AND an IDR; after a reconnect a new attempt starts with the gate
 *   shut again, so the decoder is never fed the middle of a picture.
 * - **Deadline.** No first decoded frame within [Config.firstFrameMs] (10 s: TCP over the tailnet starts slower than WebRTC) is a
 *   failure: [LiveWhy.DECODER] if NAL units did arrive (the video came and could not be shown), else [LiveWhy.CONNECT].
 * - **Watchdog.** Once NAL units have come, none for [Config.stallMs] ends the attempt. (The Go client has its own 5 s watchdog; this
 *   one catches a Go client that is wedged and reports nothing.)
 * - **Failures.** A decoder that cannot be made is final ([LiveWhy.DECODER], the tile on its still with the notice). Everything else
 *   (the connection ending, the watchdog, the deadline, a decoder that broke) retries after 2 s, 5 s and 15 s (the count starts
 *   again after a good run), then the session stays [LiveSession.State.FAILED] until [start] (a tap on the chip) or until the tile
 *   comes back into view (a new session).
 * - **Teardown** (stop, failure) closes the connection once and releases the decoder once.
 */
class RtspLiveSession(
    val key: LiveKey,
    private val url: String,
    private val sources: RtspSourceFactory,
    private val decoders: RtspDecoderFactory,
    private val clock: LiveClock,
    private val log: LiveLog = LiveLog.NONE,
    private val config: Config = Config(),
    private val onChange: () -> Unit = {},
) : LiveRun {
    data class Config(
        val firstFrameMs: Long = FIRST_FRAME_MS,
        val stallMs: Long = STALL_MS,
        val retryMs: List<Long> = listOf(2_000, 5_000, 15_000),
        val tickMs: Long = 1_000,
    )

    override var state: LiveSession.State = LiveSession.State.IDLE
        private set

    override var why: LiveWhy? = null
        private set

    /** How many pulls this session has started (a test and the Journal read it). */
    var opens: Int = 0
        private set

    override val fpsLabel: String get() = fps.label

    private val fps = FpsMeter()
    private val stall = StallTracker()
    private var retries = 0
    private var retryTimer: (() -> Unit)? = null
    private var cur: Attempt? = null
    private var playingSince = 0L

    /** DEV-764: one diagnostic line; the log never throws into the session. */
    private fun diag(event: String, vararg fields: Pair<String, Any?>) {
        try {
            log.diag(event, key, fields.toList())
        } catch (_: Exception) {
        }
    }

    private fun endStall(end: String) {
        val down = stall.end(clock.nowMs()) ?: return
        diag("lv-fpsback", "down" to down, "end" to end)
    }

    private inner class Attempt {
        @Volatile var decoder: RtspDecoder? = null
        @Volatile var source: RtspSource? = null
        @Volatile var formatSeen = false
        @Volatile var keySeen = false
        @Volatile var anyNal = false
        @Volatile var lastNalAt = clock.nowMs()
        @Volatile var dead = false
        val startedAt = clock.nowMs()
        var deadline: (() -> Unit)? = null
        var tick: (() -> Unit)? = null
    }

    override fun start() {
        if (state != LiveSession.State.IDLE && state != LiveSession.State.FAILED) return
        retries = 0
        why = null
        open()
    }

    override fun stop(reason: StopWhy) {
        if (state == LiveSession.State.STOPPED) return
        val active = state == LiveSession.State.STARTING || state == LiveSession.State.PLAYING || state == LiveSession.State.RETRYING
        val played = if (state == LiveSession.State.PLAYING) clock.nowMs() - playingSince else 0L
        endStall("stop")
        retryTimer?.invoke()
        retryTimer = null
        teardown()
        state = LiveSession.State.STOPPED
        if (active) log.stopped(key, reason, played)
        onChange()
    }

    // ---- an attempt ----------------------------------------------------------------------------

    private fun open() {
        retryTimer = null
        teardown()
        val a = Attempt()
        cur = a
        state = LiveSession.State.STARTING
        fps.reset()
        stall.reset()
        diag("lv-open", "route" to "rtsp", "retry" to retries, "opens" to opens)
        onChange()
        a.deadline = clock.after(config.firstFrameMs) { if (cur === a) fail(a, if (a.anyNal) LiveWhy.DECODER else LiveWhy.CONNECT, true) }
        a.tick = clock.after(config.tickMs) { tick(a) }
        a.decoder = try {
            decoders.create(key, decoderListener(a))
        } catch (_: Exception) {
            fail(a, LiveWhy.DECODER, false)
            return
        }
        opens++
        try {
            a.source = sources.open(url, sourceListener(a))
        } catch (_: Exception) {
            fail(a, LiveWhy.CONNECT, true)
            return
        }
        if (a.dead) a.source?.stop() // a callback inside open() already failed this attempt
    }

    private fun sourceListener(a: Attempt) = object : RtspListener {
        override fun onFormat(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
            if (a.dead) return
            val d = a.decoder ?: return
            a.formatSeen = true
            d.format(sps, pps, width, height)
        }

        override fun onNal(nal: ByteArray, ptsUs: Long, key: Boolean) {
            com.meda.portico.core.traffic.TrafficTally.addVideo(nal.size.toLong()) // A-10: counted on receipt, whatever the decoder does with it
            if (a.dead) return
            val d = a.decoder ?: return
            a.lastNalAt = clock.nowMs()
            a.anyNal = true
            if (!a.formatSeen) return // nothing can be decoded before the parameter sets
            if (!a.keySeen) {
                if (!key) return // the gate: the decoder starts at an IDR
                a.keySeen = true
            }
            d.nal(nal, ptsUs, key)
        }

        override fun onEnded(kind: String) {
            if (cur === a) fail(a, LiveWhy.CONNECT, true)
        }
    }

    private fun decoderListener(a: Attempt) = object : RtspDecoderListener {
        override fun onFirstFrame() {
            if (cur === a && state == LiveSession.State.STARTING) playing(a)
        }

        override fun onFailed() {
            if (cur === a) fail(a, LiveWhy.DECODER, true)
        }
    }

    private fun playing(a: Attempt) {
        state = LiveSession.State.PLAYING
        why = null
        retries = 0
        a.deadline?.invoke()
        a.deadline = null
        val now = clock.nowMs()
        playingSince = now
        log.started(key, key.stream, now - a.startedAt)
        fps.reset()
        stall.reset()
        onChange()
    }

    /** About once a second: the watchdog, and (while PLAYING) one reading of the frame count for the label. */
    private fun tick(a: Attempt) {
        if (cur !== a) return
        val now = clock.nowMs()
        if (a.anyNal && now - a.lastNalAt >= config.stallMs) return fail(a, LiveWhy.CONNECT, true)
        if (state == LiveSession.State.PLAYING) {
            val total = a.decoder?.frameCount() ?: 0L
            fps.sample(now, total)
            when (val e = stall.sample(now, total)) {
                is StallTracker.Event.Began -> diag("lv-fps0", "silent" to e.silentMs, "up" to (now - playingSince) / 1000)
                is StallTracker.Event.Ended -> diag("lv-fpsback", "down" to e.downMs, "end" to "frames")
                null -> Unit
            }
            onChange()
        }
        a.tick = clock.after(config.tickMs) { tick(a) }
    }

    private fun fail(a: Attempt, reason: LiveWhy, retriable: Boolean) {
        if (cur !== a || state == LiveSession.State.STOPPED) return
        endStall("fail")
        teardown()
        why = reason
        val retry = retries
        val again = retriable && retries < config.retryMs.size
        log.failed(key, reason, retry, again)
        if (again) {
            state = LiveSession.State.RETRYING
            val delay = config.retryMs[retries++]
            retryTimer = clock.after(delay) {
                retryTimer = null
                if (state == LiveSession.State.RETRYING) open()
            }
        } else {
            state = LiveSession.State.FAILED
        }
        onChange()
    }

    /** Ends the current attempt: its timers, its connection (once) and its decoder (once). */
    private fun teardown() {
        val a = cur ?: return
        cur = null
        a.dead = true
        a.deadline?.invoke()
        a.tick?.invoke()
        try {
            a.source?.stop()
        } catch (_: Exception) {
            // already closed
        }
        try {
            a.decoder?.release()
        } catch (_: Exception) {
            // already released
        }
    }

    companion object {
        /** TCP over the tailnet starts slower than WebRTC on the LAN (S1: 6 s). */
        const val FIRST_FRAME_MS = 10_000L

        /** No NAL unit for this long, after some came: the connection is dead. The Go client's own watchdog is 5 s, so it normally ends first. */
        const val STALL_MS = 6_000L
    }
}
