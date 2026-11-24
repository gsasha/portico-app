package com.meda.portico.core.call

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The talk's audio over the answer path's WebRTC, against the same fakes the answer path is tested with. */
class TalkAudioTest {

    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val events = mutableListOf<String>()
    private val listener = object : MediaListener {
        override fun onConnected() { events += "connected" }
        override fun onDisconnected() { events += "disconnected" }
        override fun onFailed() { events += "failed" }
    }
    private val port = AnswerPathTalkMedia(media, cmds)

    private fun answerWith(sdp: String) = cmds.last("portico/webrtc_offer").ok(buildJsonObject { put("sdp", sdp) })

    @Test
    fun `the offer is the answer path's webrtc_offer with the dialled call id and the door`() {
        val leg = port.open("call-1", "door-1", listener)
        val call = media.calls.single()
        assertEquals(AnswerConfig().gatherTimeoutMs, call.gatherTimeout)
        assertEquals(emptyList<String>(), cmds.types(), "nothing is sent before the offer exists")
        call.offerCb!!("v=0 offer")
        val sent = cmds.sent.single()
        assertEquals("portico/webrtc_offer", sent.type)
        assertEquals(obj("call_id" to "call-1", "door_id" to "door-1", "sdp" to "v=0 offer"), sent.body)
        answerWith("v=0 answer")
        assertEquals("v=0 answer", call.answerSdp)
        call.answerCb!!(true)
        call.listener.onConnected()
        assertEquals(listOf("connected"), events)
        leg.close()
        assertEquals(1, call.closes)
        assertEquals(listOf("portico/webrtc_offer"), cmds.types(), "the leg never starts or ends a call")
    }

    @Test
    fun `the leg says how far it got - for a talk that times out without failing`() {
        val leg = port.open("call-1", "door-1", listener)
        assertEquals("gathering", leg.stage())
        val call = media.calls.single()
        call.offerCb!!("v=0 offer")
        assertEquals("offer_sent", leg.stage(), "no reply yet: the server never answered")
        answerWith("v=0 answer")
        assertEquals("answer_received", leg.stage())
        call.answerCb!!(true)
        assertEquals("answer_set", leg.stage(), "the answer is applied; only the media path is missing")
    }

    @Test
    fun `no offer, no socket, a refusal and a bad answer are each one failure`() {
        // no offer
        port.open("c", "d", listener)
        media.calls.last().offerCb!!(null)
        assertEquals(listOf("failed"), events)
        events.clear()
        // blank offer
        port.open("c", "d", listener)
        media.calls.last().offerCb!!("")
        assertEquals(listOf("failed"), events)
        events.clear()
        // no socket
        cmds.connected = false
        port.open("c", "d", listener)
        media.calls.last().offerCb!!("offer")
        assertEquals(listOf("failed"), events)
        events.clear()
        cmds.connected = true
        // refused
        port.open("c", "d", listener)
        media.calls.last().offerCb!!("offer")
        cmds.last("portico/webrtc_offer").error("unknown_call")
        assertEquals(listOf("failed"), events)
        events.clear()
        // lost
        port.open("c", "d", listener)
        media.calls.last().offerCb!!("offer")
        cmds.sent.last().lost()
        assertEquals(listOf("failed"), events)
        events.clear()
        // an answer without sdp
        port.open("c", "d", listener)
        media.calls.last().offerCb!!("offer")
        cmds.sent.last().ok(buildJsonObject { })
        assertEquals(listOf("failed"), events)
        events.clear()
        // an answer the media layer rejects
        port.open("c", "d", listener)
        val call = media.calls.last()
        call.offerCb!!("offer")
        cmds.sent.last().ok(buildJsonObject { put("sdp", "answer") })
        call.answerCb!!(false)
        assertEquals(listOf("failed"), events)
    }

    @Test
    fun `failure is reported once, and the media layer failing is the same failure`() {
        port.open("c", "d", listener)
        val call = media.calls.single()
        call.listener.onFailed()
        call.listener.onFailed()
        call.offerCb!!(null)
        assertEquals(listOf("failed"), events)
    }

    @Test
    fun `after close a late callback does nothing - no offer is sent, nothing is reported`() {
        val leg = port.open("c", "d", listener)
        val call = media.calls.single()
        leg.close()
        leg.close()
        assertEquals(1, call.closes, "closed once")
        call.offerCb!!("offer")
        assertEquals(emptyList<String>(), cmds.types())
        call.listener.onConnected()
        call.listener.onFailed()
        assertEquals(emptyList<String>(), events)

        val leg2 = port.open("c", "d", listener)
        val call2 = media.calls.last()
        call2.offerCb!!("offer")
        leg2.close()
        cmds.last("portico/webrtc_offer").ok(buildJsonObject { put("sdp", "answer") })
        assertEquals(null, call2.answerSdp, "an answer that arrives after close is not applied")
    }

    @Test
    fun `a media layer that cannot open throws out of open, for the session to report`() {
        media.failOpen = true
        assertThrows(IllegalStateException::class.java) { port.open("c", "d", listener) }
        assertEquals(emptyList<String>(), cmds.types())
    }

    @Test
    fun `disconnected comes through, and is the media layer's business to recover from`() {
        port.open("c", "d", listener)
        media.calls.single().listener.onDisconnected()
        assertEquals(listOf("disconnected"), events)
        assertFalse(events.contains("failed"))
    }

    @Test
    fun `a talk session end to end over the real audio leg and the answer path's fakes`() {
        val r = TalkRig()
        val real = AnswerPathTalkMedia(media, r.cmds)
        val s = TalkSession(r.request, r.cmds, real, r.audio, { true }, r.sched, { 5L }, { true })
        s.begin()
        r.cmds.last("portico/content/run").ok(talkStarted())
        s.onCallEvent(answeredEv())
        val call = media.calls.single()
        call.offerCb!!("offer")
        assertEquals(obj("call_id" to TALK_CALL, "door_id" to TALK_DOOR, "sdp" to "offer"), r.cmds.last("portico/webrtc_offer").body)
        r.cmds.last("portico/webrtc_offer").ok(buildJsonObject { put("sdp", "answer") })
        call.answerCb!!(true)
        call.listener.onConnected()
        assertEquals(TalkPhase.IN_CALL, s.phase)
        s.hangUp()
        assertEquals(1, call.closes)
        assertEquals(listOf("portico/content/run", "portico/webrtc_offer", "portico/hangup"), r.cmds.types())
    }
}
