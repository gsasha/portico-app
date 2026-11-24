package com.meda.portico.call

import com.meda.portico.core.traffic.TrafficTally
import com.meda.portico.core.call.AudioProfile
import com.meda.portico.core.call.CommandReply
import com.meda.portico.core.call.FrameChannel
import com.meda.portico.core.call.FrameListener
import com.meda.portico.core.call.MediaCommands
import com.meda.portico.signaling.SignalingClient
import java.util.concurrent.ConcurrentHashMap

/**
 * The away-media frame channel over the phone's one HA socket (`PorticoLink.openStream` for
 * `portico/media_open` and its events, `sendQuiet` for the 25-per-second uplink). Every call into the
 * link runs on the link's own thread, via [SignalingClient.post].
 *
 * An uplink frame is dropped, not queued, when the socket already holds more than the call's backlog limit
 * ([com.meda.portico.core.call.AudioProfile.backlogBytes]: about five frames as they travel, 2 500 bytes at `pcmu` and 8 990 at `l16`) unsent: over TCP a stall would otherwise turn into seconds of delay (a stale frame is worse than none).
 * [send] then returns false, but the backlog is read on the link's thread, so the caller learns of a
 * drop on its next frame, one frame (40 ms) late.
 */
class LinkFrameChannel(
    private val signaling: () -> SignalingClient,
    /** The command that opens the channel for ([doorId], [callId], codec): a door's `portico/media_open`, or (M7 3b-B) a house call's `portico/house/media_open`. */
    private val openCommand: (doorId: String, callId: String, codec: String) -> Pair<String, kotlinx.serialization.json.JsonObject> =
        { doorId, callId, codec -> MediaCommands.OPEN to MediaCommands.open(doorId, callId, codec) },
    /**
     * A house call's `{t: "end", reason}` says the call is over, which the call's own `house_call` `ended` reports with the right words;
     * only `reason: error` is a failure of the audio. True maps every other reason to the ordinary end, so a far-end hang-up is
     * not shown as "Could not connect". A door's frame channel is unchanged (false).
     */
    private val houseEnds: Boolean = false,
) : FrameChannel {

    private val streams = ConcurrentHashMap<String, Int>()

    /** DEV-811 2.4: the backlog limit of each open call, from the codec it asked for (`pcmu` unless it asked for another known one). */
    private val limits = ConcurrentHashMap<String, Long>()

    /** Set on the link thread when the last frame was dropped for backlog; read by [send]. */
    @Volatile private var backlogged = false

    override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) {
        limits[callId] = (AudioProfile.forCodec(codec) ?: AudioProfile.PCMU).backlogBytes
        try {
            signaling().post { link ->
                val (type, body) = openCommand(doorId, callId, codec)
                val target = countedAudio(if (houseEnds) houseListener(listener) else listener)
                val id = link.openStream(
                    type,
                    body,
                    onReply = { r -> onOpened(if (r == null) CommandReply.Lost else CommandReply.Reply(r)) },
                    onEvent = { MediaCommands.dispatch(it, target) },
                    onLost = { listener.onLost() },
                )
                if (id == null) onOpened(CommandReply.NotSent) else streams[callId] = id
            }
        } catch (e: Exception) {
            onOpened(CommandReply.NotSent) // the link's executor is shut down
        }
    }

    private fun houseListener(inner: FrameListener) = object : FrameListener {
        override fun onFrame(payloadBase64: String) = inner.onFrame(payloadBase64)
        override fun onEnd(reason: String) = inner.onEnd(if (reason == "error") reason else "call_ended")
        override fun onLost() = inner.onLost()
    }

    /** A-10: call audio frames arriving over the link count as audio traffic (the payload; the envelope is in the OS total). */
    private fun countedAudio(inner: FrameListener) = object : FrameListener {
        override fun onFrame(payloadBase64: String) {
            TrafficTally.addAudio(payloadBase64.length.toLong())
            inner.onFrame(payloadBase64)
        }
        override fun onEnd(reason: String) = inner.onEnd(reason)
        override fun onLost() = inner.onLost()
    }

    override fun send(callId: String, payloadBase64: String): Boolean {
        return try {
            val dropped = backlogged
            val limit = limits[callId] ?: AudioProfile.PCMU.backlogBytes
            signaling().post { link ->
                backlogged = link.queuedBytes() > limit
                if (!backlogged) {
                    TrafficTally.addAudio(payloadBase64.length.toLong())
                    link.sendQuiet(MediaCommands.FRAME, MediaCommands.frame(callId, payloadBase64))
                }
            }
            !dropped
        } catch (e: Exception) {
            false
        }
    }

    override fun close(callId: String) {
        val id = streams.remove(callId)
        limits.remove(callId)
        try {
            signaling().post { link ->
                if (id != null) link.dropStream(id)
                link.sendQuiet(MediaCommands.CLOSE, MediaCommands.close(callId))
            }
        } catch (_: Exception) {
        }
    }
}
