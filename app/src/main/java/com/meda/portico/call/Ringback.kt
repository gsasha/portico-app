package com.meda.portico.call

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.panel.RingbackPolicy

/**
 * The ringback tone: what a caller hears while the far end rings, like a phone placing a call (D-20261121-c98a). It is the platform's own
 * ringing tone (`TONE_SUP_RINGTONE`, no bundled asset) on the voice-call stream, the stream a call's audio uses, whose level Panel mode
 * already holds at its configured value (the caller raises it first). Whether to play is [RingbackPolicy]'s decision, made by the caller;
 * this class only plays and stops, always on the main thread, and never throws.
 *
 * Bounded three ways, none of which depends on the call session: [update] false stops it; [RingbackPolicy.MAX_MS] after the start a
 * timer stops it; [stop] is idempotent and also runs when the app is stopping.
 *
 * ToneGenerator has no way to say which output it reached, so the Journal line says what could be read (the stream level), and the first
 * test on the tablet is by ear (the 0.6.93 sitting): see the design addendum.
 */
class Ringback(
    private val log: HaLog,
    /** Called on the main thread when the tone starts ([started] true) or could not start; the app writes its Journal line and holds the stream level. */
    private val onStart: (started: Boolean) -> Unit,
    /** Called on the main thread once per start, when it stops, with why (`end`, `cap`, `app`). */
    private val onStop: (why: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private val cap = Runnable { stopNow("cap") }

    /** The wanted state, from any thread. */
    fun update(wanted: Boolean) {
        main.post { if (wanted) startNow() else stopNow("end") }
    }

    private fun startNow() {
        if (tone != null) return
        var started = false
        try {
            val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
            started = t.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            if (started) {
                tone = t
                main.postDelayed(cap, RingbackPolicy.MAX_MS)
            } else {
                t.release()
            }
        } catch (e: Throwable) {
            log.log(HaLog.Level.WARN, "ringback: the tone could not start (${e.javaClass.simpleName})")
        }
        try { onStart(started) } catch (_: Throwable) { }
    }

    private fun stopNow(why: String) {
        val t = tone ?: return
        tone = null
        main.removeCallbacks(cap)
        try { t.stopTone() } catch (_: Throwable) { }
        try { t.release() } catch (_: Throwable) { }
        try { onStop(why) } catch (_: Throwable) { }
    }

    /** The process is going away. */
    fun appStopping() {
        main.post { stopNow("app") }
    }
}
