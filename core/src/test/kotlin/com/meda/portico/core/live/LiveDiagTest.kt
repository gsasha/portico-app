package com.meda.portico.core.live

import com.meda.portico.core.push.PushJournalFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveDiagTest {
    private fun good(i: Int) = RtcReading(fps = 15, decoded = 15L * i, dropped = 0, bytes = 50_000L * i, lost = 0, freezes = 0, jitterMs = 4)

    @Test
    fun `stall begins after two silent seconds and ends with how long frames were missing`() {
        val t = StallTracker()
        assertNull(t.sample(0, 10))
        assertNull(t.sample(1_000, 25))
        assertNull(t.sample(2_000, 25)) // 1 s silent
        assertEquals(StallTracker.Event.Began(2_000), t.sample(3_000, 25))
        assertNull(t.sample(4_000, 25)) // already reported
        assertEquals(StallTracker.Event.Ended(5_000), t.sample(6_000, 40))
        assertNull(t.sample(7_000, 55))
    }

    @Test
    fun `a stall that is still open when the stream ends is closed by end`() {
        val t = StallTracker()
        t.sample(0, 1); t.sample(1_000, 2); t.sample(3_000, 2); t.sample(4_000, 2)
        assertEquals(3_000L, t.end(4_000))
        assertNull(t.end(5_000))
    }

    @Test
    fun `a counter that goes back starts afresh`() {
        val t = StallTracker()
        t.sample(0, 100); t.sample(1_000, 100)
        assertNull(t.sample(2_000, 3))
        assertNull(t.end(2_000))
    }

    @Test
    fun `pacer writes the first three readings then one a minute`() {
        val p = StatsPacer()
        val out = (1..40).map { p.line(it * 5_000L, good(it)) }
        assertEquals(listOf(0, 1, 2), out.take(3).mapIndexedNotNull { i, f -> if (f != null) i else null })
        assertNull(out[3])
        val minute = out.mapIndexedNotNull { i, f -> if (f != null && i >= 3) i else null }
        assertEquals(listOf(14, 26, 38), minute) // 60 s apart (12 readings)
        val f = out[1]!!.toMap()
        assertEquals(15L, f["dec"]); assertEquals(80L, f["kbps"]); assertEquals(15, f["fps"]); assertNull(f["flag"])
    }

    @Test
    fun `a bad reading is written at once, for twelve readings in a row, then held to one a minute`() {
        val p = StatsPacer()
        for (i in 1..5) p.line(i * 5_000L, good(i))
        val frozen = good(5).copy(fps = 0)
        val outs = (6..30).map { p.line(it * 5_000L, frozen) }
        assertEquals(12, outs.take(12).count { it != null })
        assertEquals("bad", outs[0]!!.toMap()["flag"])
        assertTrue(outs.drop(12).count { it != null } <= 2)
    }

    @Test
    fun `words from the platform are made safe and a diag line carries them`() {
        assertEquals("c2_qti_avc_decoder", StatsPacer.word("c2.qti.avc.decoder"))
        assertNull(StatsPacer.word(""))
        assertEquals("host-srflx", StatsPacer.path("host", "srflx"))
        val line = PushJournalFormat.diagLine(
            0, java.util.TimeZone.getTimeZone("UTC"), "ui", "lv-stats",
            listOf("cam" to PushJournalFormat.DiagId("camera.front_door"), "fps" to 0, "flag" to "bad", "x" to null, "codec" to StatsPacer.word("MediaCodec h.264")),
        )
        assertTrue(line.startsWith("00:00:00.000 ui lv-stats cam="), line)
        assertTrue(line.contains(" fps=0 flag=bad codec=mediacodec_h_264"), line)
    }
}
