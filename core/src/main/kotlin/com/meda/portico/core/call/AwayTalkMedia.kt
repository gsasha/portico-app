package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level

/**
 * The audio of a Talk call for a phone that may be AWAY from home: WebRTC at home (unchanged), call audio as frames on the HA
 * WebSocket (`portico/media_open`, see [AwayMedia]) when the phone's link is on the tailnet, where UDP media cannot reach HA.
 * Plan: the estate repo automation/portico_front_door_talk_plan.md section 13.
 *
 * Wraps the Talk session's normal [TalkMediaPort] ([webrtc], the answer path's WebRTC) and the answer path's own [AwayMedia]:
 *
 * ```
 *   open --+-- away.preferFrames() ------------------------------> frames
 *          '-- WebRTC --- connected ----------------------------> stays WebRTC (a later failure is the session's, as before)
 *                    '-- failed or [fallbackAfterMs] without connecting ---> close the peer connection, then frames
 * ```
 *
 * Frames open only on the same conditions a WebRTC leg does: [TalkSession] calls [open] once, after the station answered.
 * The frame leg's `media_open` is accepted by the server for an `out-` id the phone placed. It reports `onConnected` when
 * the channel is open and audio is running, `onFailed` (final) when the channel is refused, ends on its own or the socket
 * dies; a refusal's code goes to [lastFailure]. [stage] says which path the leg is on, for the talk-end journal line.
 * [away] is null-checked by the caller: with the Away call audio switch off the app never builds this class, so the
 * session is the WebRTC-only one it was.
 */
class AwayTalkMedia(
    private val webrtc: TalkMediaPort,
    private val away: AwayMedia,
    private val scheduler: Scheduler,
    private val fallbackAfterMs: Long = AnswerConfig().fallbackAfterMs,
    private val log: HaLog = HaLog.NONE,
) : TalkMediaPort {

    override fun open(callId: String, doorId: String, listener: MediaListener): TalkLeg {
        val leg = Leg(callId, doorId, listener)
        leg.begin()
        return leg
    }

    private inner class Leg(private val callId: String, private val doorId: String, private val listener: MediaListener) : TalkLeg {
        @Volatile private var closed = false
        @Volatile private var usingFrames = false
        @Volatile private var connected = false
        @Volatile private var failed = false
        @Volatile private var why: String? = null
        @Volatile private var stage: String? = null
        @Volatile private var via = ""      // "pref_" or "fb_<why>_": how the leg came to be on frames
        private var rtc: TalkLeg? = null
        private var frames: FramePath? = null
        private var timer: Cancellable? = null

        fun begin() {
            val prefer = try {
                away.preferFrames()
            } catch (e: Exception) {
                false
            }
            if (prefer) {
                via = "pref_"
                startFrames()
            } else {
                startWebrtc()
            }
        }

        private fun startWebrtc() {
            timer = scheduler.schedule(fallbackAfterMs) {
                if (!closed && !connected && !usingFrames) switchToFrames("timeout")
            }
            val l = try {
                webrtc.open(callId, doorId, object : MediaListener {
                    override fun onConnected() {
                        if (closed || usingFrames) return
                        connected = true
                        cancelTimer()
                        listener.onConnected()
                    }

                    override fun onDisconnected() {
                        if (!closed && !usingFrames) listener.onDisconnected()
                    }

                    override fun onFailed() {
                        if (closed || usingFrames) return
                        if (connected) listener.onFailed() else switchToFrames("rtc_failed")
                    }
                })
            } catch (e: Exception) {
                log.log(Level.WARN, "talk: media open failed (${e.javaClass.simpleName}), trying frames")
                switchToFrames("rtc_threw")
                return
            }
            rtc = l
            if (usingFrames) closeRtc() // the failure callback ran before open returned
        }

        private fun switchToFrames(because: String) {
            if (closed || usingFrames) return
            log.log(Level.INFO, "talk: UDP audio did not connect ($because), moving to frames")
            usingFrames = true
            cancelTimer()
            closeRtc()
            via = "fb_${because}_"
            startFrames()
        }

        private fun startFrames() {
            usingFrames = true
            stage = "${via}frames_opening"
            val path = try {
                away.open(object : MediaListener {
                    override fun onConnected() {
                        if (closed) return
                        stage = "${via}frames_open"
                        listener.onConnected()
                    }

                    override fun onDisconnected() {
                        if (!closed) listener.onDisconnected()
                    }

                    override fun onFailed() = fail("frames_lost")
                })
            } catch (e: Exception) {
                log.log(Level.WARN, "talk: frame media open failed (${e.javaClass.simpleName})")
                fail("frames_open_threw")
                return
            }
            frames = path
            if (closed) {
                path.close()
                return
            }
            path.start(callId, doorId) { f ->
                if (f != null) fail("frames_" + f.code.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(24))
            }
        }

        private fun fail(because: String) {
            if (closed || failed) return
            failed = true
            why = because
            listener.onFailed()
        }

        private fun cancelTimer() {
            try {
                timer?.cancel()
            } catch (_: Exception) {
            }
            timer = null
        }

        private fun closeRtc() {
            val l = rtc ?: return
            rtc = null
            try {
                l.close()
            } catch (_: Exception) {
            }
        }

        override fun lastFailure(): String? = why ?: rtc?.lastFailure()

        override fun stage(): String? = if (usingFrames) stage else rtc?.stage()

        override fun endedBecause(reason: EndReason) {
            rtc?.endedBecause(reason)
        }

        override fun close() {
            if (closed) return
            closed = true
            cancelTimer()
            closeRtc()
            try {
                frames?.close()
            } catch (_: Exception) {
            }
        }
    }
}
