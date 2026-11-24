package com.meda.portico.core.call

/**
 * Puts every callback of a [MediaPort] (libwebrtc calls back on its own threads) onto the answer
 * path's executor [post], so [AnswerSession] only ever runs on one thread. Calls INTO the media
 * (open, createOffer, setRemoteAnswer, setMuted, close) pass straight through: they are made from
 * that executor already.
 */
class SerialMedia(private val real: MediaPort, private val post: (() -> Unit) -> Unit) : MediaPort {

    override fun open(listener: MediaListener): MediaCall {
        val inner = real.open(object : MediaListener {
            override fun onConnected() = post { listener.onConnected() }
            override fun onDisconnected() = post { listener.onDisconnected() }
            override fun onFailed() = post { listener.onFailed() }
        })
        return object : MediaCall {
            override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) =
                inner.createOffer(gatherTimeoutMs) { sdp -> post { onSdp(sdp) } }

            override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) =
                inner.setRemoteAnswer(sdp) { ok -> post { onDone(ok) } }

            override fun setMuted(muted: Boolean) = inner.setMuted(muted)
            override fun setHeld(held: Boolean) = inner.setHeld(held)
            override fun close() = inner.close()
            override fun audioStats(onStats: (AudioStats?) -> Unit) = inner.audioStats(onStats)
            override fun closeReporting(onStats: (AudioStats?) -> Unit) = inner.closeReporting(onStats)
            override fun endedBecause(reason: EndReason) = inner.endedBecause(reason)
        }
    }
}
