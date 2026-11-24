package com.meda.portico.call

import com.meda.portico.core.call.CommandReply
import com.meda.portico.core.call.HouseCommands
import com.meda.portico.core.call.HouseSignal
import com.meda.portico.core.call.MediaCommands
import com.meda.portico.signaling.SignalingClient
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The signalling of a house call's WebRTC leg over the phone's one HA socket (M7 3b-B): `portico/house/media_open {call_id, sdp}`
 * through `PorticoLink.openStream`, so the server's answer (`{t: "sdp"}`) and its end (`{t: "end"}`) arrive as events on that
 * command. Every call into the link runs on the link's own thread, via [SignalingClient.post]. The goodbye is the shared
 * `portico/media_close`, as for the frame channel.
 */
class LinkHouseSignal(private val signaling: () -> SignalingClient) : HouseSignal {

    private val streams = ConcurrentHashMap<String, Int>()

    override fun open(callId: String, sdp: String, onReply: (CommandReply) -> Unit, onEvent: (JsonObject) -> Unit, onLost: () -> Unit) {
        try {
            signaling().post { link ->
                val id = link.openStream(
                    HouseCommands.MEDIA_OPEN,
                    HouseCommands.mediaOpen(callId, sdp = sdp),
                    onReply = { r -> onReply(if (r == null) CommandReply.Lost else CommandReply.Reply(r)) },
                    onEvent = onEvent,
                    onLost = onLost,
                )
                if (id == null) onReply(CommandReply.NotSent) else streams[callId] = id
            }
        } catch (e: Exception) {
            onReply(CommandReply.NotSent) // the link's executor is shut down
        }
    }

    override fun close(callId: String) {
        val id = streams.remove(callId)
        try {
            signaling().post { link ->
                if (id != null) link.dropStream(id)
                link.sendQuiet(MediaCommands.CLOSE, MediaCommands.close(callId))
            }
        } catch (_: Exception) {
        }
    }
}
