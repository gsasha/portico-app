package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The house call's audio, both ends (M7 3b-B): the choice of leg, the WebRTC signalling by `house/media_open`, and the frame counters. */
class HouseMediaTest {

    private class Signal : HouseSignal {
        var callId: String? = null
        var sdp: String? = null
        var onReply: ((CommandReply) -> Unit)? = null
        var onEvent: ((JsonObject) -> Unit)? = null
        var onLost: (() -> Unit)? = null
        var closed = 0
        override fun open(callId: String, sdp: String, onReply: (CommandReply) -> Unit, onEvent: (JsonObject) -> Unit, onLost: () -> Unit) {
            this.callId = callId; this.sdp = sdp; this.onReply = onReply; this.onEvent = onEvent; this.onLost = onLost
        }
        override fun close(callId: String) { closed++ }
    }

    private val rtc = FakeMedia()
    private val signal = Signal()
    private var frames = true
    private val paths = mutableListOf<FakeFramePath>()
    private val statsSeen = mutableListOf<Pair<String, FrameStats.Summary>>()
    private var clock = 0L
    private val listenerEvents = mutableListOf<String>()
    private val listener = object : MediaListener {
        override fun onConnected() { listenerEvents += "connected" }
        override fun onDisconnected() { listenerEvents += "disconnected" }
        override fun onFailed() { listenerEvents += "failed" }
    }
    private val m = HouseMedia(
        framePath = { l, _ -> FakeFramePath(l).also { paths += it } },
        rtc = rtc, signal = signal, preferFrames = { frames }, post = { it() }, clockMs = { clock },
        onFrameStats = { id, s -> statsSeen += id to s },
    )

    private fun ok(o: JsonObject) = CommandReply.Reply(HaInbound.Result(1, true, o, null))
    private fun err(code: String) = CommandReply.Reply(HaInbound.Result(1, false, null, HaError(code, "")))

    // ---- the choice ---------------------------------------------------------------------------------------------

    @Test
    fun `away (frames preferred) opens the frame path with the call id and the house placeholder, and no WebRTC at all`() {
        val leg = m.open("hso-1", null, listener)
        assertEquals(("hso-1" to HouseMedia.HOUSE_DOOR), paths.single().started)
        assertEquals(0, rtc.calls.size)
        paths.single().onResult!!(null)
        leg.setMuted(true)
        assertEquals(true, paths.single().mutedNow)
        leg.close()
        leg.close()
        assertEquals(1, paths.single().closes, "closed once")
        assertEquals(1, statsSeen.size, "the counters are written once, at the close")
        assertEquals("hso-1", statsSeen.single().first)
    }

    @Test
    fun `a frame channel that will not open fails the leg`() {
        m.open("hso-1", null, listener)
        paths.single().onResult!!(AnswerErrors.media())
        assertEquals(listOf("failed"), listenerEvents)
    }

    @Test
    fun `at home (frames not preferred) opens libwebrtc and no frame path`() {
        frames = false
        m.open("hso-1", null, listener)
        assertEquals(0, paths.size)
        assertEquals(1, rtc.calls.size)
    }

    // ---- WebRTC signalling ------------------------------------------------------------------------------------------

    private fun webrtcLeg(): PlaceLeg {
        frames = false
        val leg = m.open("hso-1", null, listener)
        rtc.calls.last().offerCb!!("v=0 offer")
        return leg
    }

    @Test
    fun `the offer goes in media_open with the call id and the sdp, then the server's sdp event is the remote answer, then connected`() {
        webrtcLeg()
        assertEquals("hso-1", signal.callId)
        assertEquals("v=0 offer", signal.sdp)
        signal.onReply!!(ok(buildJsonObject { put("kind", "webrtc") }))
        signal.onEvent!!(buildJsonObject { put("t", "sdp"); put("sdp", "v=0 answer") })
        assertEquals("v=0 answer", rtc.calls.single().answerSdp)
        rtc.calls.single().answerCb!!(true)
        rtc.calls.single().listener.onConnected()
        assertEquals(listOf("connected"), listenerEvents)
    }

    @Test
    fun `a refused offer, a frames answer to a WebRTC offer, a bad answer and a lost socket each fail the leg once`() {
        for (reply in listOf<CommandReply>(err("not_supported"), err("unknown_call"), ok(buildJsonObject { put("kind", "frames"); put("codec", "pcmu"); put("frame_bytes", 320); put("frame_ms", 40) }), CommandReply.Lost, CommandReply.NotSent)) {
            listenerEvents.clear()
            val leg = webrtcLeg()
            signal.onReply!!(reply)
            assertEquals(listOf("failed"), listenerEvents, "$reply")
            signal.onLost!!()
            assertEquals(listOf("failed"), listenerEvents, "only once")
            leg.close()
        }
        listenerEvents.clear()
        webrtcLeg()
        signal.onReply!!(ok(buildJsonObject { put("kind", "webrtc") }))
        signal.onEvent!!(buildJsonObject { put("t", "sdp"); put("sdp", "x") })
        rtc.calls.last().answerCb!!(false)
        assertEquals(listOf("failed"), listenerEvents)
    }

    @Test
    fun `no offer (gathering failed) fails the leg before anything is sent`() {
        frames = false
        m.open("hso-1", null, listener)
        rtc.calls.single().offerCb!!(null)
        assertEquals(listOf("failed"), listenerEvents)
        assertNull(signal.callId)
    }

    @Test
    fun `an end event is the ordinary end of the call unless its reason is error`() {
        webrtcLeg()
        signal.onReply!!(ok(buildJsonObject { put("kind", "webrtc") }))
        for (reason in listOf("hangup", "cancelled", "declined", "busy", "no_answer", "dnd", "answered_elsewhere", "call_ended")) {
            signal.onEvent!!(buildJsonObject { put("t", "end"); put("reason", reason) })
        }
        assertEquals(emptyList<String>(), listenerEvents, "the call's own ended event says how it ended, with the right words")
        signal.onEvent!!(buildJsonObject { put("t", "end"); put("reason", "error") })
        assertEquals(listOf("failed"), listenerEvents)
    }

    @Test
    fun `closing the WebRTC leg closes libwebrtc and the signalling once, and a late event after the close does nothing`() {
        val leg = webrtcLeg()
        leg.close()
        leg.close()
        assertEquals(1, rtc.calls.single().closes)
        assertEquals(1, signal.closed)
        signal.onReply!!(err("unknown_call"))
        signal.onEvent!!(buildJsonObject { put("t", "end"); put("reason", "error") })
        assertEquals(emptyList<String>(), listenerEvents)
    }

    @Test
    fun `the microphone mute reaches the WebRTC call`() {
        val leg = webrtcLeg()
        leg.setMuted(true)
        assertTrue(rtc.calls.single().micOff)
        leg.setMuted(false)
        assertFalse(rtc.calls.single().micOff)
    }

    // ---- the frame counters (design 15.17) ---------------------------------------------------------------------------

    private class Channel : FrameChannel {
        var listener: FrameListener? = null
        var accept = true
        val sent = mutableListOf<String>()
        override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) {
            this.listener = listener
            onOpened(CommandReply.Reply(HaInbound.Result(1, true, buildJsonObject { put("codec", "l16") }, null)))
        }
        override fun send(callId: String, payloadBase64: String): Boolean { sent += payloadBase64; return accept }
        override fun close(callId: String) {}
    }

    private class Pcm : PcmDevice {
        var capture: ((ShortArray) -> Unit)? = null
        var playout: (() -> ShortArray?)? = null
        override fun prepare(profile: AudioProfile) {}
        override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) { capture = onCapture; playout = nextPlayout }
        override fun setMuted(muted: Boolean) {}
        override fun stop() {}
    }

    private val b64 = java.util.Base64.getEncoder().encodeToString(ByteArray(AudioProfile.L16.wireBytes) { 0x7f })

    @Test
    fun `FrameMedia with counters counts frames sent, not sent, received, concealed and the largest gap - and a door's path with none counts nothing`() {
        val ch = Channel()
        val pcm = Pcm()
        val stats = FrameStats { clock }
        val fm = FrameMedia(ch, pcm, listener, { it() }, stats = stats)
        fm.start("hso-1", "house") { }
        assertNull(pcm.playout!!.invoke(), "nothing played yet: the first silence is not an underrun")
        clock = 0
        ch.listener!!.onFrame(b64); clock = 40
        ch.listener!!.onFrame(b64); clock = 400 // a 360 ms gap
        ch.listener!!.onFrame(b64)
        repeat(3) { pcm.capture!!(ShortArray(AudioProfile.L16.frameSamples)) }
        ch.accept = false
        repeat(2) { pcm.capture!!(ShortArray(AudioProfile.L16.frameSamples)) } // the socket's backlog: stale frames dropped
        // the speaker takes the three queued frames (2 are kept after the burst rule: prime, then drain), then runs dry
        var played = 0
        var silent = 0
        repeat(10) { if (pcm.playout!!.invoke() == null) silent++ else played++ }
        val sum = stats.summary()
        assertEquals(3L, sum.sent)
        assertEquals(2L, sum.dropped)
        assertEquals(3L, sum.received)
        assertEquals(360L, sum.maxGapMs)
        assertEquals(silent.toLong(), sum.concealed, "every silent slot after audio started is concealed (played $played)")
        assertTrue(sum.concealed >= 1)
        // no counters: the same path counts nothing and nothing throws
        val plain = FrameMedia(Channel(), Pcm(), listener, { it() })
        plain.start("gso-1", "door") { }
        plain.close()
    }
}
