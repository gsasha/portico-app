package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * HOLD on the away (frames) path (M7 H2, design 15.13): the hub closes a phone's leg after 5 s without an uplink frame, so a held
 * call must keep sending SILENT frames, must drop what comes down, and must not close the channel.
 */
class FrameMediaHoldTest {

    private class Channel : FrameChannel {
        var listener: FrameListener? = null
        var onOpened: ((CommandReply) -> Unit)? = null
        val sent = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) {
            this.listener = listener
            this.onOpened = onOpened
        }

        override fun send(callId: String, payloadBase64: String): Boolean = sent.add(callId to payloadBase64)
        override fun close(callId: String) {
            closed += callId
        }
    }

    private class Pcm : PcmDevice {
        var onCapture: ((ShortArray) -> Unit)? = null
        var nextPlayout: (() -> ShortArray?)? = null
        var stops = 0
        override fun prepare(profile: AudioProfile) {}
        override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) {
            this.onCapture = onCapture
            this.nextPlayout = nextPlayout
        }

        override fun setMuted(muted: Boolean) {}
        override fun stop() {
            stops++
        }
    }

    private class Events : MediaListener {
        val log = mutableListOf<String>()
        override fun onConnected() { log += "connected" }
        override fun onDisconnected() { log += "disconnected" }
        override fun onFailed() { log += "failed" }
    }

    private val ch = Channel()
    private val pcm = Pcm()
    private val ev = Events()
    private val posted = ArrayDeque<() -> Unit>()
    private val media = FrameMedia(ch, pcm, ev, { posted.addLast(it) })

    private fun started() {
        media.start("c1", "gate") { }
        ch.onOpened!!(CommandReply.Reply(HaInbound.Result(1, true, buildJsonObject { put("codec", "l16") }, null)))
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    private fun loud() = ShortArray(AudioProfile.L16.frameSamples) { 9000 }
    private fun payload(frame: ShortArray) = Base64.getEncoder().encodeToString(L16Codec.encode(frame))
    private fun isSilence(b64: String) = Base64.getDecoder().decode(b64).toList().chunked(2).all { (lo, hi) -> ((lo.toInt() and 0xFF) or (hi.toInt() shl 8)).toShort().toInt() in -8..8 }

    @Test
    fun `a held call keeps sending silent uplink frames so the hub's 5 second rule never closes the leg`() {
        started()
        media.setHeld(true)
        repeat(130) { pcm.onCapture!!(loud()) } // 130 frames of 40 ms: 5.2 s of capture
        assertEquals(130, ch.sent.size, "one frame per capture tick, not nothing")
        assertTrue(ch.sent.all { isSilence(it.second) }, "and every one is silence: the room is not sent")
        assertTrue(ch.closed.isEmpty(), "the channel stays open")
        assertEquals(0, pcm.stops, "the device is not stopped: its clock paces the frames")
    }

    @Test
    fun `the downlink is dropped while held and the queue emptied`() {
        started()
        val tone = ShortArray(AudioProfile.L16.frameSamples) { 5000 }
        ch.listener!!.onFrame(payload(tone))
        ch.listener!!.onFrame(payload(tone))
        media.setHeld(true)
        assertNull(pcm.nextPlayout!!(), "what was queued before the hold is thrown away")
        repeat(4) { ch.listener!!.onFrame(payload(tone)) }
        assertNull(pcm.nextPlayout!!(), "and nothing that arrives while held is queued")
    }

    @Test
    fun `resume brings the room and the visitor back, and starts from fresh audio`() {
        started()
        val tone = ShortArray(AudioProfile.L16.frameSamples) { 5000 }
        media.setHeld(true)
        ch.listener!!.onFrame(payload(tone))
        media.setHeld(false)
        pcm.onCapture!!(loud())
        assertFalse(isSilence(ch.sent.last().second), "the microphone is sent again")
        assertNull(pcm.nextPlayout!!(), "no stale audio from the hold")
        ch.listener!!.onFrame(payload(tone))
        ch.listener!!.onFrame(payload(tone))
        assertNotNull(pcm.nextPlayout!!(), "new audio plays")
    }

    @Test
    fun `a mute set before the hold is still silence after the resume`() {
        started()
        media.setMuted(true)
        media.setHeld(true)
        media.setHeld(false)
        pcm.onCapture!!(loud())
        assertTrue(isSilence(ch.sent.last().second), "the person's mute survives the hold")
    }

    @Test
    fun `holding does not report the call as lost`() {
        started()
        media.setHeld(true)
        media.setHeld(false)
        assertEquals(listOf("connected"), ev.log)
    }

    @Test
    fun `closing a held call still closes the device and the channel`() {
        started()
        media.setHeld(true)
        media.close()
        assertEquals(1, pcm.stops)
        assertEquals(listOf("c1"), ch.closed)
        pcm.onCapture!!(loud())
        assertTrue(ch.sent.isEmpty(), "nothing is sent after the close")
    }

    @Test
    fun `the playout queue can be cleared and primes again`() {
        val q = PlayoutQueue()
        q.offer(ShortArray(4)); q.offer(ShortArray(4)); q.offer(ShortArray(4))
        assertNotNull(q.next())
        q.clear()
        assertEquals(0, q.size())
        q.offer(ShortArray(4))
        assertNull(q.next(), "one frame is below the prebuffer: primed again after a clear")
    }
}
