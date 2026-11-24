package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.LiveStep
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** DEV-713 S1b: the away route's Journal lines carry `route=rtsp` and the stream NAME, and never an address, a port or a credential. */
class RtspJournalTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun journal() = PushJournal(InMemoryPushJournalStore(), { 3_723_456L }, { utc })

    @Test
    fun `the three lines of the away route`() {
        assertEquals(
            "01:02:03.456 live start cam=gate-1…(23) size=small route=rtsp stream=gate_intercom_sub ttff=2100ms",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.START, "gate-1-0123456789abcdef", "small", ttffMs = 2100, rtspStream = "gate_intercom_sub"),
        )
        assertEquals(
            "01:02:03.456 live fail cam=…(4) size=big route=rtsp stream=gate_intercom why=connect retry=1 next=yes",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.FAIL, "gate", "big", why = "connect", retry = 1, willRetry = true, rtspStream = "gate_intercom"),
        )
        assertEquals(
            "01:02:03.456 live stop cam=…(4) size=big route=rtsp stream=gate_intercom why=hidden secs=12",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.STOP, "gate", "big", why = "hidden", playedSecs = 12, rtspStream = "gate_intercom"),
        )
    }

    @Test
    fun `the home route's lines are exactly what they were`() {
        assertEquals(
            "01:02:03.456 live start cam=gate-1…(23) size=big stream=main ttff=840ms",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.START, "gate-1-0123456789abcdef", "big", stream = "main", ttffMs = 840),
        )
    }

    @Test
    fun `the session's log writes the name from the key, never the address`() {
        val j = journal()
        val key = LiveKey("camera.gate_intercom_main", CameraSize.SMALL, "gate_intercom_sub")
        j.liveLog.started(key, key.stream, 1_200)
        j.liveLog.failed(key, LiveWhy.CONNECT, 0, true)
        j.liveLog.stopped(key, StopWhy.BACKGROUND, 61_500)
        assertEquals(
            listOf(
                "01:02:03.456 live start cam=camera…(25) size=small route=rtsp stream=gate_intercom_sub ttff=1200ms",
                "01:02:03.456 live fail cam=camera…(25) size=small route=rtsp stream=gate_intercom_sub why=connect retry=0 next=yes",
                "01:02:03.456 live stop cam=camera…(25) size=small route=rtsp stream=gate_intercom_sub why=background secs=61",
            ),
            j.lines(),
        )
    }

    @Test
    fun `nothing but a plain stream name can reach a line`() {
        for (hostile in listOf(
            "rtsp://u:p@192.168.1.6:8554/gate", "192.168.1.6", "gate@host", "a b", "gate?x=1", "gate/x", "..", "a:b", "", "x".repeat(65), "gäte", "gate\nlive start",
        )) {
            val line = PushJournalFormat.liveLine(1L, utc, LiveStep.START, "c", "big", rtspStream = hostile)
            assertTrue("stream=? " in line || line.endsWith("stream=?"), "$hostile -> $line")
            assertFalse("@" in line || "10.2" in line || "://" in line || "\n" in line, line)
        }
        // and a wrongly built key cannot carry an address either
        val j = journal()
        j.liveLog.stopped(LiveKey("c", CameraSize.BIG, "rtsp://u:p@192.168.1.6:8554/gate"), StopWhy.CLOSED, 0)
        val line = j.lines().single()
        assertFalse("u:p" in line || "192.168.1.6" in line || "8554" in line, line)
    }
}
