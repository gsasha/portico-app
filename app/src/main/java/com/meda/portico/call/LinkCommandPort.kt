package com.meda.portico.call

import com.meda.portico.core.call.CommandPort
import com.meda.portico.core.call.CommandReply
import com.meda.portico.signaling.SignalingClient
import kotlinx.serialization.json.JsonObject

/**
 * The answer path's commands over the phone's one HA socket ([SignalingClient] -> `PorticoLink.sendCall`).
 * The send runs on the link's thread; every reply (including "not sent" and "socket closed first")
 * is handed to [onReplyThread], the answer path's serial executor, and arrives exactly once.
 */
class LinkCommandPort(
    private val signaling: () -> SignalingClient,
    private val onReplyThread: (() -> Unit) -> Unit,
) : CommandPort {
    override fun send(type: String, body: JsonObject, onReply: (CommandReply) -> Unit) {
        try {
            signaling().post { link ->
                val sent = link.sendCall(type, body) { r ->
                    onReplyThread { onReply(if (r == null) CommandReply.Lost else CommandReply.Reply(r)) }
                }
                if (!sent) onReplyThread { onReply(CommandReply.NotSent) }
            }
        } catch (e: Exception) {
            // The link's executor is shut down: nothing was sent.
            onReplyThread { onReply(CommandReply.NotSent) }
        }
    }
}
