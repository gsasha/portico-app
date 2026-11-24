package com.meda.portico.core.call

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level

/**
 * The audio of a talk, over the ANSWER PATH's WebRTC (blocks design 7.5, decision D8): the same [MediaPort] (libwebrtc
 * in the app) and the same `portico/webrtc_offer {call_id, door_id, sdp}` an answered ring uses, against the call id the
 * dial returned. Non-trickle, like the answer path: the offer carries every candidate.
 *
 * ```
 *   open --> createOffer (gather) --> portico/webrtc_offer --> setRemoteAnswer --> the media listener says connected
 * ```
 *
 * Any failure on the way (no offer, no socket, a refusal such as `unknown_call`, a bad answer) reports
 * `onFailed` once; the [TalkSession] then ends the talk and hangs up. The leg sends nothing else: it never starts or
 * ends a call. After [TalkLeg.close] a late callback does nothing.
 *
 * Nothing opens one of these for a Talk button that is not ready: [TalkSession] opens the leg only after the station
 * has answered a call this phone dialled.
 */
class AnswerPathTalkMedia(
    private val media: MediaPort,
    private val commands: CommandPort,
    private val config: AnswerConfig = AnswerConfig(),
    private val log: HaLog = HaLog.NONE,
) : TalkMediaPort {

    override fun open(callId: String, doorId: String, listener: MediaListener): TalkLeg {
        val leg = Leg(listener)
        leg.stage = "gathering"
        val call = media.open(object : MediaListener {
            override fun onConnected() { if (!leg.closed) listener.onConnected() }
            override fun onDisconnected() { if (!leg.closed) listener.onDisconnected() }
            override fun onFailed() { leg.fail("media") }
        })
        leg.call = call
        call.createOffer(config.gatherTimeoutMs) { sdp ->
            if (leg.closed) return@createOffer
            if (sdp.isNullOrEmpty()) {
                leg.fail("no_offer")
                return@createOffer
            }
            leg.stage = "offer_sent"
            commands.send(AnswerCommands.WEBRTC_OFFER, AnswerCommands.webrtcOffer(callId, doorId, sdp)) { reply ->
                if (leg.closed) return@send
                when (reply) {
                    CommandReply.NotSent, CommandReply.Lost -> leg.fail("offer_not_sent")
                    is CommandReply.Reply -> when (val o = AnswerCommands.parseOffer(reply.result)) {
                        is AnswerCommands.Offer.Refused -> {
                            log.log(Level.WARN, "talk: the offer was refused (${o.failure.code})")
                            leg.fail("offer_refused_${o.failure.code.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(24)}")
                        }
                        is AnswerCommands.Offer.Answer -> {
                            leg.stage = "answer_received"
                            call.setRemoteAnswer(o.sdp) { ok -> if (ok) leg.stage = "answer_set" else leg.fail("bad_answer") }
                        }
                    }
                }
            }
        }
        return leg
    }

    private class Leg(private val listener: MediaListener) : TalkLeg {
        var call: MediaCall? = null

        @Volatile
        var closed = false
        private var failed = false

        @Volatile
        var why: String? = null

        override fun lastFailure(): String? = why

        @Volatile
        var stage: String? = null

        override fun stage(): String? = stage

        fun fail(because: String) {
            if (closed || failed) return
            failed = true
            why = because
            listener.onFailed()
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
        }
    }
}
