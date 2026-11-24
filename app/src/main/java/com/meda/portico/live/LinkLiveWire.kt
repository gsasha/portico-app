package com.meda.portico.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LiveCandidate
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.live.LiveWire
import com.meda.portico.core.live.LiveWireCall
import com.meda.portico.core.live.LiveWireSink
import com.meda.portico.signaling.SignalingClient

/**
 * The live commands over the phone's one HA socket (DEV-713 S1): `PorticoLink.openStream` for `portico/content/live` and its
 * events, `sendQuiet` for `live_ice` and `live_close`. Every call into the link runs on the link's own thread, via
 * [SignalingClient.post]; what comes back is handed to [post] (the live thread), in the order it arrived.
 *
 * Nothing is queued: a command sent while the socket is not ready is reported [LiveWireSink.onNotSent] and the session retries by
 * its own rules. A cancelled call drops its stream (so the link keeps no listener for a finished session); its reply may still come,
 * and the session then closes the session it opened by name.
 */
class LinkLiveWire(
    private val signaling: () -> SignalingClient,
    private val post: (() -> Unit) -> Unit,
) : LiveWire {

    private class Call(private val signaling: () -> SignalingClient) : LiveWireCall {
        private var id: Int? = null
        private var cancelled = false

        /** On the link thread, once the command is sent: true when the call was cancelled in the meantime (drop it now). */
        @Synchronized
        fun sent(commandId: Int): Boolean {
            id = commandId
            return cancelled
        }

        @Synchronized
        fun isCancelled(): Boolean = cancelled

        override fun cancel() {
            val dropId: Int?
            synchronized(this) {
                cancelled = true
                dropId = id
            }
            if (dropId != null) {
                try {
                    signaling().post { it.dropStream(dropId) }
                } catch (_: Exception) {
                    // the link's executor is shut down
                }
            }
        }
    }

    override fun open(cameraId: String, sdp: String, size: CameraSize, sink: LiveWireSink): LiveWireCall {
        val call = Call(signaling)
        try {
            signaling().post { link ->
                if (call.isCancelled()) return@post // torn down before it was sent: nothing to open
                val body = try {
                    LiveCommands.live(cameraId, sdp, size)
                } catch (_: IllegalArgumentException) {
                    post { sink.onNotSent() }
                    return@post
                }
                val id = link.openStream(
                    LiveCommands.LIVE,
                    body,
                    onReply = { r -> post { sink.onReply(LiveCommands.parseReply(r)) } },
                    onEvent = { e -> post { sink.onEvent(LiveCommands.parseEvent(e)) } },
                    onLost = { post { sink.onLost() } },
                )
                if (id == null) {
                    post { sink.onNotSent() }
                } else if (call.sent(id)) {
                    link.dropStream(id)
                }
            }
        } catch (_: Exception) {
            post { sink.onNotSent() } // the link's executor is shut down
        }
        return call
    }

    override fun ice(sessionId: String, candidate: LiveCandidate) {
        send(LiveCommands.LIVE_ICE) { LiveCommands.ice(sessionId, candidate) }
    }

    override fun close(sessionId: String) {
        send(LiveCommands.LIVE_CLOSE) { LiveCommands.close(sessionId) }
    }

    private fun send(type: String, body: () -> kotlinx.serialization.json.JsonObject) {
        try {
            signaling().post { link ->
                try {
                    link.sendQuiet(type, body())
                } catch (_: IllegalArgumentException) {
                    // an unusable id: nothing to send
                }
            }
        } catch (_: Exception) {
            // the link's executor is shut down: the socket closing ended the session on the server
        }
    }
}
