package com.meda.portico.core.call

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AwayMediaTest {

    // ---- G.711: the same values as portico-ha internal/wsmedia --------------------------------

    @Test
    fun `mu-law reference points match the Go side`() {
        assertEquals(0xFF.toByte(), G711.encode(0.toShort()))
        assertEquals(0x80.toByte(), G711.encode(32767.toShort()))
        assertEquals(0x00.toByte(), G711.encode(Short.MIN_VALUE))
        assertEquals(0.toShort(), G711.decode(0xFF.toByte()))
        assertEquals(0x7F.toByte(), G711.encode((-1).toShort()))
    }

    @Test
    fun `mu-law round trip stays within the codec's quantisation error`() {
        for (v in -32768..32767 step 7) {
            val back = G711.decode(G711.encode(v.toShort())).toInt()
            assertTrue(abs(back - v) <= maxOf(8, abs(v) / 15 + 1), "$v -> $back")
        }
    }

    @Test
    fun `a frame round trips with its length`() {
        val frame = ShortArray(AudioProfile.PCMU.frameSamples) { (it * 90 - 14000).toShort() }
        val bytes = G711.encode(frame)
        assertEquals(320, bytes.size)
        assertEquals(320, G711.decode(bytes).size)
    }

    // ---- the playout queue -------------------------------------------------------------------

    private fun f(v: Int) = ShortArray(AudioProfile.PCMU.frameSamples) { v.toShort() }

    @Test
    fun `nothing plays until two frames are queued`() {
        val q = PlayoutQueue()
        q.offer(f(1))
        assertNull(q.next())
        q.offer(f(2))
        assertEquals(1, q.next()!![0].toInt())
        assertEquals(2, q.next()!![0].toInt())
    }

    @Test
    fun `an underrun is silence, then it primes again`() {
        val q = PlayoutQueue()
        q.offer(f(1)); q.offer(f(2))
        q.next(); q.next()
        assertNull(q.next())
        q.offer(f(3))
        assertNull(q.next(), "one frame is not enough after an underrun")
        q.offer(f(4))
        assertEquals(3, q.next()!![0].toInt())
    }

    @Test
    fun `a burst after a stall drops the oldest instead of adding permanent delay`() {
        val q = PlayoutQueue()
        for (i in 1..7) q.offer(f(i))
        assertEquals(PlayoutQueue.TARGET, q.size())
        assertEquals(5, q.next()!![0].toInt(), "the newest frames are kept")
    }

    // ---- commands ------------------------------------------------------------------------------

    @Test
    fun `the open command and the frame are shaped as the contract says`() {
        assertEquals(
            buildJsonObject { put("door_id", "gate"); put("call_id", "c1"); put("codec", "pcmu") },
            MediaCommands.open("gate", "c1"),
        )
        assertEquals(buildJsonObject { put("call_id", "c1"); put("d", "QUJD") }, MediaCommands.frame("c1", "QUJD"))
    }

    @Test
    fun `an open reply is parsed, a refusal maps to a failure`() {
        val ok = HaInbound.Result(1, true, buildJsonObject { put("codec", "pcmu"); put("frame_ms", 40) }, null)
        assertEquals(MediaCommands.Opened.Ok("pcmu"), MediaCommands.parseOpen(ok, AudioProfile.PCMU))
        val no = HaInbound.Result(1, false, null, HaError("away_media_disabled", "x"))
        val refused = MediaCommands.parseOpen(no, AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals("away_media_disabled", refused.failure.code)
        assertEquals(AnswerFailure.Kind.MEDIA, refused.failure.kind)
        val odd = HaInbound.Result(1, true, buildJsonObject { put("codec", "l16") }, null)
        assertTrue(MediaCommands.parseOpen(odd, AudioProfile.PCMU) is MediaCommands.Opened.Refused)
    }

    @Test
    fun `events dispatch to the listener and unknown ones are ignored`() {
        val got = mutableListOf<String>()
        val l = object : FrameListener {
            override fun onFrame(payloadBase64: String) { got += "f:$payloadBase64" }
            override fun onEnd(reason: String) { got += "end:$reason" }
            override fun onLost() {}
        }
        MediaCommands.dispatch(buildJsonObject { put("t", "f"); put("d", "QUJD") }, l)
        MediaCommands.dispatch(buildJsonObject { put("t", "end"); put("reason", "idle") }, l)
        MediaCommands.dispatch(buildJsonObject { put("t", "zzz") }, l)
        MediaCommands.dispatch(buildJsonObject { put("t", "f") }, l)
        assertEquals(listOf("f:QUJD", "end:idle"), got)
    }
}
