package com.meda.portico.core.call

import com.meda.portico.core.push.AudioPhase
import com.meda.portico.core.push.PushJournal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Journals the audio facts of ONE call (DEV-698), around any [MediaPort]: the answer path, the Talk path and the house call's
 * WebRTC leg all open their audio through one, so one decorator covers all three. Per call it writes at most TWO lines:
 *
 * - `audio start`, once, [FIRST_READ_MS] after the audio first connects (one `getStats`, no per-frame work);
 * - `audio end`, once, when the call's media is closed, with the reason the session gave ([MediaCall.endedBecause]).
 *
 * The rate limit is the pair of once-guards: a reconnect flap fires `onConnected` again and a second close is a no-op, neither
 * writes another line. Observation only: nothing here changes what the media does, and a stats read that throws or never
 * answers costs the line's numbers (printed `?`), never the call. With no journal it is a plain pass-through.
 */
class AudioJournalMedia(
    private val real: MediaPort,
    private val journal: PushJournal?,
    /** `answer`, `talk` or `house`: a short lowercase word. */
    private val path: String,
    private val callId: String,
    private val scheduler: Scheduler,
    private val firstReadAfterMs: Long = FIRST_READ_MS,
    /** 0.6.95: asked at the first read (`start`) and before the media closes (`end`) to journal the Android audio environment; the app answers it. */
    private val onEnv: ((String) -> Unit)? = null,
) : MediaPort {

    override fun open(listener: MediaListener): MediaCall {
        if (journal == null) return real.open(listener)
        val run = Run(journal)
        val inner = real.open(object : MediaListener {
            override fun onConnected() {
                run.connected()
                listener.onConnected()
            }

            override fun onDisconnected() = listener.onDisconnected()
            override fun onFailed() = listener.onFailed()
        })
        run.call = inner
        return Journaled(inner, run)
    }

    private inner class Run(private val journal: PushJournal) {
        @Volatile
        var call: MediaCall? = null

        @Volatile
        var why: EndReason? = null

        /** The microphone was muted or the call held at some point: a silent microphone is then the person's doing. */
        @Volatile
        var muted = false

        @Volatile
        private var timer: Cancellable? = null
        private val started = AtomicBoolean(false)
        private val ended = AtomicBoolean(false)

        fun connected() {
            if (ended.get() || !started.compareAndSet(false, true)) return
            timer = try {
                scheduler.schedule(firstReadAfterMs) { readStart() }
            } catch (_: Throwable) {
                null
            }
        }

        private fun readStart() {
            if (ended.get()) return
            try { onEnv?.invoke("start") } catch (_: Throwable) {}
            val c = call
            if (c == null) {
                write(AudioPhase.START, null)
                return
            }
            try {
                c.audioStats { s -> if (!ended.get()) write(AudioPhase.START, s) }
            } catch (_: Throwable) {
                write(AudioPhase.START, null)
            }
        }

        /** Close [c], reporting once: the end line gets the last stats the media could read before it tore down. */
        fun close(c: MediaCall, sink: (AudioStats?) -> Unit) {
            if (!ended.compareAndSet(false, true)) {
                c.closeReporting(sink) // a repeat: the media's own idempotent close; no second line
                return
            }
            try {
                timer?.cancel()
            } catch (_: Throwable) {
            }
            try { onEnv?.invoke("end") } catch (_: Throwable) {}
            try {
                c.closeReporting { s ->
                    write(AudioPhase.END, s)
                    sink(s)
                }
            } catch (_: Throwable) {
                try {
                    c.close()
                } catch (_: Throwable) {
                }
                write(AudioPhase.END, null)
            }
        }

        private fun write(phase: AudioPhase, stats: AudioStats?) {
            journal.audio(phase, path, callId, stats, muted, if (phase == AudioPhase.END) why else null)
        }
    }

    private inner class Journaled(private val inner: MediaCall, private val run: Run) : MediaCall {
        override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) = inner.createOffer(gatherTimeoutMs, onSdp)
        override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) = inner.setRemoteAnswer(sdp, onDone)

        override fun setMuted(muted: Boolean) {
            if (muted) run.muted = true
            inner.setMuted(muted)
        }

        override fun setHeld(held: Boolean) {
            inner.setHeld(held) // throws when the media cannot hold: nothing changed, so nothing is noted
            if (held) run.muted = true
        }

        override fun audioStats(onStats: (AudioStats?) -> Unit) = inner.audioStats(onStats)

        override fun endedBecause(reason: EndReason) {
            run.why = reason
            inner.endedBecause(reason)
        }

        override fun closeReporting(onStats: (AudioStats?) -> Unit) = run.close(inner, onStats)
        override fun close() = run.close(inner) {}
    }

    companion object {
        /** After the first connect: long enough for the packet counters and the level to mean something, short enough to catch a dead microphone. */
        const val FIRST_READ_MS = 2_000L
    }
}
