package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The audio of a HOUSE call, both ends (M7 3b-B; design 15.4, 14.7): the legs the answer path already has, declared to the
 * server's house bridge by `portico/house/media_open`. No new audio stack:
 *
 * - at home (the socket came direct): WebRTC through the SFU, the same [MediaPort] (libwebrtc) as a door answer. The offer carries
 *   every candidate (non-trickle, as the answer path does) and goes in `media_open {call_id, sdp}`; the server's answer arrives as
 *   an event `{t: "sdp", sdp}` on that command, and `setRemoteAnswer` finishes it;
 * - away (the socket came through the tailnet, which carries only the signalling socket): the frame channel, [FramePath] over
 *   [FrameMedia] exactly as the away door audio, `media_open {call_id, codec}`, frames as `{t: "f"}` events down and
 *   `portico/media_frame` up. Counters ([FrameStats]) are kept for this path and written when it closes (design 15.17).
 *
 * The leg is opened ONLY after the call is answered (the session's rule: [PlaceMediaPort.open] is called once the far end has
 * answered), so no microphone is open while a call rings. The first end to declare waits for the second (the hub opens one
 * bridge with both legs); the session's connect timeout (10 s) bounds that wait.
 *
 * An `{t: "end"}` on the media command means the call is over (the server's own `house_call` `ended` is what ends it for the
 * person, with the right words): only `reason: error` is a failure of the audio; every other reason is the ordinary end.
 */
class HouseMedia(
    /** The frame path for one call (a [FrameMedia] over the house frame channel), given the listener and the counters. Throws if audio cannot start. */
    private val framePath: (MediaListener, FrameStats) -> FramePath,
    /** libwebrtc behind a [MediaPort] (serialised onto the call thread by the app). */
    private val rtc: MediaPort,
    private val signal: HouseSignal,
    /** True when the audio must use frames from the start (the socket is on the tailnet, where UDP media cannot reach the SFU). */
    private val preferFrames: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onFrameStats: (callId: String, FrameStats.Summary) -> Unit = { _, _ -> },
    /** DEV-698: wraps the WebRTC [rtc] for one call (the app puts the audio journal around it); the identity by default. */
    private val audioFor: (callId: String, MediaPort) -> MediaPort = { _, port -> port },
    private val config: AnswerConfig = AnswerConfig(),
    private val log: HaLog = HaLog.NONE,
) : PlaceMediaPort {

    override fun open(callId: String, descriptor: JsonObject?, listener: MediaListener): PlaceLeg =
        if (preferFrames()) frames(callId, listener) else webrtc(callId, listener)

    // ---- frames ---------------------------------------------------------------------------------------------------

    private fun frames(callId: String, listener: MediaListener): PlaceLeg {
        val stats = FrameStats(clockMs)
        val path = framePath(listener, stats)
        path.start(callId, HOUSE_DOOR) { failure ->
            if (failure != null) {
                log.log(Level.WARN, "house: the frame channel did not open (${failure.code})")
                listener.onFailed()
            }
        }
        return object : PlaceLeg {
            private var closed = false
            override fun setMuted(muted: Boolean) = path.setMuted(muted)
            override fun close() {
                if (closed) return
                closed = true
                path.close()
                try {
                    onFrameStats(callId, stats.summary())
                } catch (_: Exception) {
                    // observation only
                }
            }
        }
    }

    // ---- WebRTC ---------------------------------------------------------------------------------------------------

    private fun webrtc(callId: String, listener: MediaListener): PlaceLeg {
        val leg = RtcLeg(callId, listener)
        val call = audioFor(callId, rtc).open(object : MediaListener {
            override fun onConnected() { if (!leg.closed) listener.onConnected() }
            override fun onDisconnected() { if (!leg.closed) listener.onDisconnected() }
            override fun onFailed() { leg.fail() }
        })
        leg.call = call
        call.createOffer(config.gatherTimeoutMs) { sdp ->
            post {
                if (leg.closed) return@post
                if (sdp.isNullOrEmpty()) {
                    leg.fail()
                    return@post
                }
                signal.open(
                    callId, sdp,
                    onReply = { reply -> post { leg.onReply(reply) } },
                    onEvent = { event -> post { leg.onEvent(event) } },
                    onLost = { post { leg.fail() } },
                )
            }
        }
        return leg
    }

    private inner class RtcLeg(private val callId: String, private val listener: MediaListener) : PlaceLeg {
        var call: MediaCall? = null

        @Volatile
        var closed = false
        private var failed = false

        fun fail() {
            if (closed || failed) return
            failed = true
            listener.onFailed()
        }

        fun onReply(reply: CommandReply) {
            if (closed) return
            when (reply) {
                CommandReply.NotSent, CommandReply.Lost -> fail()
                is CommandReply.Reply -> when (val o = HouseCommands.parseMediaOpen(reply.result)) {
                    HouseCommands.Opened.WebRtc -> Unit // wait for the server's answer event
                    is HouseCommands.Opened.Frames -> { log.log(Level.WARN, "house: asked for WebRTC, the server answered frames"); fail() }
                    is HouseCommands.Opened.Refused -> { log.log(Level.WARN, "house: the audio was refused (${o.failure.code})"); fail() }
                }
            }
        }

        fun onEvent(event: JsonObject) {
            if (closed) return
            when ((event["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
                "sdp" -> {
                    val sdp = (event["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (sdp.isNullOrEmpty()) fail() else call?.setRemoteAnswer(sdp) { ok -> if (!ok) post { fail() } }
                }
                "end" -> if ((event["reason"] as? JsonPrimitive)?.contentOrNull == "error") fail()
            }
        }

        override fun setMuted(muted: Boolean) {
            call?.setMuted(muted)
        }

        override fun endedBecause(reason: EndReason) {
            call?.endedBecause(reason)
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                call?.close()
            } catch (_: Exception) {
                // closing never throws into the session
            }
            try {
                signal.close(callId)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** The frame path wants a door id; a house call has none, so the channel is given this and ignores it. */
        const val HOUSE_DOOR = "house"
    }
}

/** The signalling of a house call's WebRTC leg: `portico/house/media_open {call_id, sdp}` and the events on it. Any thread; never throws. */
interface HouseSignal {
    /** Sends the offer; [onReply] gets the command's result once, then every event of the command goes to [onEvent]; [onLost] runs if the socket closes under it. */
    fun open(callId: String, sdp: String, onReply: (CommandReply) -> Unit, onEvent: (JsonObject) -> Unit, onLost: () -> Unit)

    /** Stops listening for this call's events and says goodbye (`portico/media_close`). */
    fun close(callId: String)
}
