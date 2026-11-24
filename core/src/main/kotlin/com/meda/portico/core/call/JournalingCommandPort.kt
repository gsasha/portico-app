package com.meda.portico.core.call

import com.meda.portico.core.push.PushJournal
import kotlinx.serialization.json.JsonObject

/**
 * DEV-764: a [CommandPort] that writes one `ui cmd` line per command to the journal, then hands the reply on UNCHANGED. It is how a refusal,
 * a `NotSent` or a `Lost` of `portico/answer`, `portico/webrtc_offer`, `portico/hangup`, `portico/content/run`, the place commands and the rest
 * shows up with its server code and the link's handshake state at that moment.
 *
 * `ui cmd answer ok ms=120` for a reply that succeeded; for anything else the line adds the failure and [linkFields] (`sock`, `path`, `up`,
 * `ver`, `doors`, `calls`, `cont`: the state of the link when the command was SENT, which is when it mattered): `ui cmd answer err code=not_subscribed
 * ms=40 sock=connected up=900 ver=yes doors=no calls=no cont=no`, `... notsent ...`, `... lost ...`. Only the command's last path segment (a fixed
 * word), a server error code (a word) and numbers go in: never the body, an id, an SDP or a URL. Observation only: the journal catches, and
 * a throw from [linkFields] costs the fields, never the command.
 */
class JournalingCommandPort(
    private val inner: CommandPort,
    private val journal: PushJournal?,
    private val linkFields: () -> List<Pair<String, Any?>> = { emptyList() },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : CommandPort {
    override fun send(type: String, body: JsonObject, onReply: (CommandReply) -> Unit) {
        val j = journal
        if (j == null) {
            inner.send(type, body, onReply)
            return
        }
        val name = type.substringAfterLast('/').replace('_', '-')
        val t0 = clockMs()
        val atSend = try {
            linkFields()
        } catch (_: Throwable) {
            emptyList()
        }
        inner.send(type, body) { reply ->
            try {
                val ms = clockMs() - t0
                when (reply) {
                    is CommandReply.Reply ->
                        if (reply.result.success) j.diag("ui", "cmd", listOf("c" to name, "r" to "ok", "ms" to ms))
                        else j.diag("ui", "cmd", listOf("c" to name, "r" to "err", "code" to reply.result.error?.code, "ms" to ms) + atSend)
                    CommandReply.NotSent -> j.diag("ui", "cmd", listOf("c" to name, "r" to "notsent", "ms" to ms) + atSend)
                    CommandReply.Lost -> j.diag("ui", "cmd", listOf("c" to name, "r" to "lost", "ms" to ms) + atSend)
                }
            } catch (_: Throwable) {
            }
            onReply(reply)
        }
    }
}
