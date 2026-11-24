package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.push.PushJournalFormat
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [FramePath] over a [FrameChannel] and a [PcmDevice]: the away-from-home audio of ONE call.
 *
 * [post] puts every callback that arrives on another thread (the socket's, the audio device's) onto the
 * answer thread, so the session and this class run on one thread. Capture runs on the device's thread and
 * goes straight to the channel (thread-safe); while muted it sends silence rather than nothing, because
 * HA ends a leg that hears no uplink for 5 s.
 */
class FrameMedia(
    private val channel: FrameChannel,
    private val pcm: PcmDevice,
    private val listener: MediaListener,
    private val post: (() -> Unit) -> Unit,
    private val log: HaLog = HaLog.NONE,
    /** Counters for this call's audio; one is made when none is given (DEV-811 P1: every call on the frames path counts). */
    stats: FrameStats? = null,
    /** DEV-811 P2: wideband (`l16`, 16 kHz) or, for Compatibility or a marked device, `pcmu`; [AudioChoice.why] is journalled. */
    private val choice: AudioChoice = AudioChoice.WIDEBAND,
    /** Runs the 2-second first-frame check; null (a test) = no check. */
    private val scheduler: Scheduler? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** A start failure at 16 kHz: the device is marked "wideband failed" (the app stores it). */
    private val onWidebandFailed: () -> Unit = {},
    /** The mark word (`none`, `temp`, `perm`) at the moment the call began. */
    private val markWord: () -> String = { "none" },
    /** Called once when the audio closes, if it ever opened (the journal's two lines). */
    private val onReport: ((FrameReport) -> Unit)? = null,
    /** 0.6.95: diagnostic lines (`frames-trk` at the start and end, `frames-win` and `frames-lvl` every [WINDOW_MS]); null (a test) = none. Observation only. */
    private val onDiag: ((String, List<Pair<String, Any?>>) -> Unit)? = null,
    /** 0.6.95: asked at the start and the end of the audio (`start`, `end`) to journal the Android audio environment; the app answers it. */
    private val onEnv: ((String) -> Unit)? = null,
) : FramePath {

    private val counts: FrameStats = stats ?: FrameStats(clockMs)
    private val dev = DeviceStats()
    private val closed = AtomicBoolean(false)
    private var callId: String? = null
    private var opened = false
    private val playout = PlayoutQueue()

    /** The profile this call runs at: the choice, or `pcmu` when the 16 kHz device could not be prepared. Set by [start]. */
    @Volatile
    private var profile: AudioProfile = choice.profile
    private var why: String = choice.why
    private var mark: String = "none"
    private var fail: String = "none"
    private var startedAtMs = -1L

    @Volatile
    private var firstFrameMs = -1L

    @Volatile
    private var firstRxMs = -1L
    private var windowTimer: Cancellable? = null
    private var windowN = 0
    private var windowAtMs = -1L
    private var watchdog: Cancellable? = null
    private var prepared = false

    @Volatile
    private var muted = false

    /** On hold (15.13): the downlink is dropped and the uplink is silence. The device is NOT stopped: its capture clock paces the silent frames. */
    @Volatile
    private var held = false
    private var silenceB64 = ""

    private val channelListener = object : FrameListener {
        override fun onFrame(payloadBase64: String) {
            if (closed.get() || held) return
            val bytes = try {
                Base64.getDecoder().decode(payloadBase64)
            } catch (e: IllegalArgumentException) {
                return
            }
            val p = profile
            if (bytes.size != p.wireBytes) return
            counts.onReceived()
            if (firstRxMs < 0 && startedAtMs >= 0) firstRxMs = (clockMs() - startedAtMs).coerceAtLeast(0)
            playout.offer(p.decode(bytes))
        }

        override fun onEnd(reason: String) {
            counts.onEnd()
            post {
                if (closed.get()) return@post
                // The call itself ending reaches the session through the call events; only an unexpected end is a failure.
                if (reason != "call_ended" && reason != "closed_by_phone" && reason != "unsubscribed") listener.onFailed()
            }
        }

        override fun onLost() = post {
            if (!closed.get()) listener.onFailed()
        }
    }

    /**
     * Prepares the device (DEV-811 2.1: before the open, which on a door IS the answer), then asks HA to open the channel with the
     * profile's codec, and on the reply starts the prepared device. A device that cannot be prepared at 16 kHz is prepared at 8 kHz and
     * the call uses `pcmu` (journalled: `why=prep16`); one that cannot be prepared at all fails the call with the usual media failure
     * before any open. Every exit before the start releases the prepared device.
     */
    override fun start(callId: String, doorId: String, onResult: (AnswerFailure?) -> Unit) {
        this.callId = callId
        mark = try { markWord() } catch (_: Exception) { "none" }
        if (!prepareDevice()) {
            post {
                if (!closed.get()) onResult(AnswerErrors.media())
            }
            return
        }
        val p = profile
        channel.open(doorId, callId, p.codec, channelListener) { reply ->
            post {
                if (closed.get()) return@post
                when (reply) {
                    CommandReply.NotSent -> { release(); onResult(AnswerErrors.notConnected()) }
                    CommandReply.Lost -> { release(); onResult(AnswerErrors.networkLost()) }
                    is CommandReply.Reply -> when (val o = MediaCommands.parseOpen(reply.result, p)) {
                        is MediaCommands.Opened.Refused -> { release(); onResult(o.failure) }
                        is MediaCommands.Opened.Ok -> {
                            opened = true
                            try {
                                startedAtMs = clockMs()
                                counts.onStarted()
                                pcm.start(::onCapture, { nextPlayout() }, ::onDeviceError)
                            } catch (e: Exception) {
                                log.log(Level.WARN, "answer: frame audio could not start (${e.javaClass.simpleName})")
                                fail = "start"
                                markWidebandFailed()
                                release()
                                onResult(AnswerErrors.media())
                                return@post
                            }
                            diagStart()
                            onResult(null)
                            listener.onConnected()
                            // Verify it runs: a first capture frame within the limit, else the call ends and the device is marked.
                            watchdog = scheduler?.schedule(FIRST_FRAME_LIMIT_MS) { post { checkFirstFrame() } }
                        }
                    }
                }
            }
        }
    }

    private fun prepareDevice(): Boolean {
        val want = choice.profile
        try {
            pcm.prepare(want)
            profile = want
            prepared = true
        } catch (e: Exception) {
            release()
            if (!want.wideband) {
                log.log(Level.WARN, "answer: frame audio device could not be prepared (${e.javaClass.simpleName})")
                return false
            }
            log.log(Level.WARN, "answer: wideband device could not be prepared (${e.javaClass.simpleName}); using 8 kHz")
            try {
                pcm.prepare(AudioProfile.PCMU)
                profile = AudioProfile.PCMU
                why = "prep16"
                prepared = true
            } catch (e2: Exception) {
                release()
                log.log(Level.WARN, "answer: frame audio device could not be prepared at 8 kHz (${e2.javaClass.simpleName})")
                return false
            }
        }
        silenceB64 = Base64.getEncoder().encodeToString(profile.encode(ShortArray(profile.frameSamples)))
        return true
    }

    private fun diag(event: String, fields: List<Pair<String, Any?>>) {
        val sink = onDiag ?: return
        try {
            sink(event, listOf<Pair<String, Any?>>("call" to PushJournalFormat.DiagId(callId)) + fields)
        } catch (_: Exception) {
        }
    }

    private fun trkFields(at: String): List<Pair<String, Any?>> {
        val p = profile
        val facts = try { pcm.facts() } catch (_: Exception) { emptyList() }
        return listOf<Pair<String, Any?>>(
            "at" to at, "codec" to p.codec, "rate" to p.rate, "framems" to p.frameSamples * 1000 / p.rate, "why" to why, "mark" to mark,
        ) + facts
    }

    /** The audio is up: its settings, the Android environment, and the window timer. */
    private fun diagStart() {
        if (onDiag == null && onEnv == null) return
        diag("frames-trk", trkFields("start"))
        try { onEnv?.invoke("start") } catch (_: Exception) {}
        windowAtMs = clockMs()
        armWindow()
    }

    private fun armWindow() {
        if (onDiag == null) return
        windowTimer = scheduler?.schedule(WINDOW_MS) { post { window() } }
    }

    private fun window() {
        if (closed.get()) return
        armWindow()
        emitWindow()
    }

    private fun emitWindow() {
        if (onDiag == null || windowN >= MAX_WINDOWS) return
        windowN++
        val now = clockMs()
        val dt = (now - windowAtMs).coerceAtLeast(0)
        windowAtMs = now
        val (e1, f1) = FrameWindowWords.flow(windowN, dt, counts.takeWindow())
        diag(e1, f1)
        val (e2, f2) = FrameWindowWords.level(windowN, dev.takeWindow(), firstRxMs, firstFrameMs)
        diag(e2, f2)
    }

    /** The audio is closing (the device still exists): the last partial window, the end settings and the environment. */
    private fun diagEnd() {
        if (onDiag == null && onEnv == null) return
        try { windowTimer?.cancel() } catch (_: Exception) {}
        windowTimer = null
        emitWindow()
        diag("frames-trk", trkFields("end"))
        try { onEnv?.invoke("end") } catch (_: Exception) {}
    }

    private fun release() {
        try {
            pcm.stop()
        } catch (_: Exception) {
        }
    }

    private fun markWidebandFailed() {
        if (!profile.wideband) return
        try {
            onWidebandFailed()
        } catch (_: Exception) {
        }
    }

    /** A read or write error from the device's thread after the start. */
    private fun onDeviceError(why: String) = post { deviceFailed(why) }

    private fun checkFirstFrame() {
        if (closed.get() || firstFrameMs >= 0) return
        deviceFailed("noframe")
    }

    private fun deviceFailed(why: String) {
        if (closed.get() || fail != "none") return
        fail = why.filter { it in 'a'..'z' || it in '0'..'9' }.take(12).ifEmpty { "error" }
        log.log(Level.WARN, "answer: frame audio device failed ($fail)")
        markWidebandFailed()
        listener.onFailed()
    }

    private fun onCapture(frame: ShortArray) {
        val id = callId
        if (closed.get() || id == null) return
        if (firstFrameMs < 0) firstFrameMs = (clockMs() - startedAtMs).coerceAtLeast(0)
        dev.onUp(frame)
        val p = profile
        val b64 = if (muted || held) silenceB64 else Base64.getEncoder().encodeToString(p.encode(frame))
        val ok = channel.send(id, b64)
        counts.onSent(ok)
    }

    /** The speaker's next frame; a missing one after audio started is counted as concealed (played as silence), a run of them as one underrun. */
    private fun nextPlayout(): ShortArray? {
        val f = playout.next()
        if (!held) {
            counts.onQueue(playout.size())
            if (f == null) counts.onConcealed() else counts.onPlayed()
        }
        if (f != null) dev.onDown(f)
        return f
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        try {
            pcm.setMuted(muted)
        } catch (_: Exception) {
        }
    }

    /**
     * HOLD on the frames path: nothing is closed, the channel stays open, and the uplink keeps flowing as SILENT frames at the
     * capture clock's pace, so the hub's 5 s rule (a leg with no uplink is closed) never fires. The downlink is dropped on arrival
     * and the playout queue emptied, so the speaker plays silence (the device writes silence when the queue is empty). The
     * recorder keeps reading and its samples are thrown away unread by [onCapture]: the same way a mute works here.
     */
    override fun setHeld(held: Boolean) {
        this.held = held
        // Whatever was queued before the hold (or while held) is stale: nobody should hear it at resume.
        playout.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watchdog?.cancel()
        watchdog = null
        counts.onStopped()
        if (opened) diagEnd()
        release()
        val id = callId
        if (id != null) {
            try {
                channel.close(id)
            } catch (_: Exception) {
            }
        }
        val sink = onReport
        if (sink != null && opened) {
            try {
                val d = try { pcm.report() } catch (_: Exception) { DeviceReport.NONE }
                sink(
                    FrameReport(
                        callId, profile, counts.summary(), dev.upPeak(), dev.dnPeak(), dev.upClip(), dev.dnClip(), d,
                        why, mark, firstFrameMs, fail,
                    ),
                )
            } catch (_: Exception) {
                // observation only
            }
        }
    }

    companion object {
        /** The first capture frame must arrive within this long after the start (DEV-811 2.1; revisited with the first real numbers). */
        const val FIRST_FRAME_LIMIT_MS = 2_000L

        /** 0.6.95: the length of one diagnostic window, and the most windows one call writes (20 minutes). */
        const val WINDOW_MS = 5_000L
        const val MAX_WINDOWS = 240
    }
}

/** What one call's frame audio looked like when it closed: the flow numbers, the device numbers and how the profile was chosen. */
class FrameReport(
    val callId: String?,
    val profile: AudioProfile,
    val flow: FrameStats.Summary,
    val upPeak: Int,
    val dnPeak: Int,
    val upClip: Long,
    val dnClip: Long,
    val device: DeviceReport,
    /** Why 8 kHz was used: `none`, `compat`, `prep16` or `mark`. */
    val why: String,
    /** The device's mark when the call began: `none`, `temp` or `perm`. */
    val mark: String,
    /** Milliseconds from the start to the first capture frame; -1 if none came. */
    val firstMs: Long,
    /** How the device failed, if it did: `none`, `start`, `noframe` or a read/write word. */
    val fail: String,
)
