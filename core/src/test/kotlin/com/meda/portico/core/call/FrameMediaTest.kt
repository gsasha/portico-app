package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
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

class FrameMediaTest {

    private class Channel : FrameChannel {
        var openArgs: Triple<String, String, String>? = null
        var listener: FrameListener? = null
        var onOpened: ((CommandReply) -> Unit)? = null
        val sent = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        override fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit) {
            openArgs = Triple(doorId, callId, codec)
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
        var starts = 0
        var stops = 0
        var mutedNow = false
        var fail = false
        override fun prepare(profile: AudioProfile) {}
        override fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit) {
            if (fail) throw IllegalStateException("no mic")
            starts++
            this.onCapture = onCapture
            this.nextPlayout = nextPlayout
        }

        override fun setMuted(muted: Boolean) {
            this.mutedNow = muted
        }

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

    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    private fun ok() = CommandReply.Reply(HaInbound.Result(1, true, buildJsonObject { put("codec", "l16") }, null))

    private fun started(): List<AnswerFailure?> {
        val results = mutableListOf<AnswerFailure?>()
        media.start("c1", "gate") { results += it }
        ch.onOpened!!(ok())
        drain()
        return results
    }

    @Test
    fun `start opens the channel with the call and device and only then starts audio`() {
        var result: AnswerFailure? = AnswerErrors.media()
        media.start("c1", "gate") { result = it }
        assertEquals(Triple("gate", "c1", "l16"), ch.openArgs)
        assertEquals(0, pcm.starts, "no microphone before HA accepted the channel")
        ch.onOpened!!(ok())
        drain()
        assertNull(result)
        assertEquals(1, pcm.starts)
        assertEquals(listOf("connected"), ev.log)
    }

    @Test
    fun `a refused open reports the failure and never starts audio`() {
        var result: AnswerFailure? = null
        media.start("c1", "gate") { result = it }
        ch.onOpened!!(CommandReply.Reply(HaInbound.Result(1, false, null, HaError("away_media_disabled", ""))))
        drain()
        assertEquals("away_media_disabled", result!!.code)
        assertEquals(0, pcm.starts)
        assertTrue(ev.log.isEmpty())
    }

    @Test
    fun `lost or unsent open maps to the usual failures`() {
        var r1: AnswerFailure? = null
        media.start("c1", "gate") { r1 = it }
        ch.onOpened!!(CommandReply.Lost)
        drain()
        assertEquals("network_lost", r1!!.code)
    }

    @Test
    fun `a microphone that cannot start fails the call path`() {
        pcm.fail = true
        assertEquals("media", started().single()!!.code)
        assertTrue(ev.log.isEmpty())
    }

    @Test
    fun `captured audio is sent as a 1280 byte l16 frame`() {
        started()
        pcm.onCapture!!(ShortArray(640) { 1000 })
        val (id, b64) = ch.sent.single()
        assertEquals("c1", id)
        assertEquals(1280, Base64.getDecoder().decode(b64).size)
    }

    @Test
    fun `muted capture sends silence, not nothing, so HA's idle watchdog stays quiet`() {
        started()
        media.setMuted(true)
        assertTrue(pcm.mutedNow)
        pcm.onCapture!!(ShortArray(640) { 12000 })
        val bytes = Base64.getDecoder().decode(ch.sent.single().second)
        assertTrue(bytes.all { it == 0.toByte() }, "silence at l16 is zeros")
    }

    @Test
    fun `downlink frames reach playout after the prebuffer`() {
        started()
        val frame = Base64.getEncoder().encodeToString(L16Codec.encode(ShortArray(640) { 2000 }))
        ch.listener!!.onFrame(frame)
        assertNull(pcm.nextPlayout!!())
        ch.listener!!.onFrame(frame)
        assertEquals(640, pcm.nextPlayout!!()!!.size)
    }

    @Test
    fun `a malformed or wrong sized frame is dropped`() {
        started()
        ch.listener!!.onFrame("***")
        ch.listener!!.onFrame(Base64.getEncoder().encodeToString(ByteArray(7)))
        ch.listener!!.onFrame(Base64.getEncoder().encodeToString(ByteArray(320))) // a pcmu-sized frame at l16 is the wrong size
        ch.listener!!.onFrame(Base64.getEncoder().encodeToString(ByteArray(1280)))
        ch.listener!!.onFrame(Base64.getEncoder().encodeToString(ByteArray(1280)))
        assertNotNull(pcm.nextPlayout!!(), "only the two good frames were queued")
        assertNotNull(pcm.nextPlayout!!())
        assertNull(pcm.nextPlayout!!())
    }

    @Test
    fun `an unexpected end or a lost socket fails the media, the call ending does not`() {
        started()
        ch.listener!!.onEnd("call_ended")
        drain()
        assertEquals(listOf("connected"), ev.log)
        ch.listener!!.onEnd("idle")
        drain()
        assertEquals(listOf("connected", "failed"), ev.log)
    }

    @Test
    fun `a lost socket fails the media`() {
        started()
        ch.listener!!.onLost()
        drain()
        assertEquals("failed", ev.log.last())
    }

    @Test
    fun `close stops audio once, tells HA, and silences everything after`() {
        started()
        media.close()
        media.close()
        assertEquals(1, pcm.stops)
        assertEquals(listOf("c1"), ch.closed)
        pcm.onCapture!!(ShortArray(640))
        assertTrue(ch.sent.isEmpty(), "no frame leaves after close")
        ch.listener!!.onLost()
        drain()
        assertFalse(ev.log.contains("failed"))
    }

    @Test
    fun `closing before the reply still tells HA (it may have opened the channel) and a late reply starts nothing`() {
        media.start("c1", "gate") { }
        media.close()
        ch.onOpened!!(ok())
        drain()
        assertEquals(0, pcm.starts)
        assertEquals(listOf("c1"), ch.closed)
    }
}
