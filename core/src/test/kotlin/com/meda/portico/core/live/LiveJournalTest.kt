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

/** The `live start|fail|stop` Journal lines (DEV-713): short, a reason class, no SDP, no token, the camera id shortened. */
class LiveJournalTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun journal() = PushJournal(InMemoryPushJournalStore(), { 3_723_456L }, { utc })

    @Test
    fun `the three lines`() {
        assertEquals(
            "01:02:03.456 live start cam=gate-1…(23) size=big stream=main ttff=840ms",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.START, "gate-1-0123456789abcdef", "big", stream = "main", ttffMs = 840),
        )
        assertEquals(
            "01:02:03.456 live fail cam=…(4) size=small why=connect retry=1 next=yes",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.FAIL, "gate", "small", why = "connect", retry = 1, willRetry = true),
        )
        assertEquals(
            "01:02:03.456 live stop cam=…(4) size=big why=hidden secs=12",
            PushJournalFormat.liveLine(3_723_456L, utc, LiveStep.STOP, "gate", "big", why = "hidden", playedSecs = 12),
        )
    }

    @Test
    fun `the session's log writes them to the journal`() {
        val j = journal()
        val key = LiveKey("camera.gate_intercom_main", CameraSize.BIG)
        j.liveLog.started(key, "main", 1_200)
        j.liveLog.failed(key, LiveWhy.DECODER, 2, false)
        j.liveLog.stopped(key, StopWhy.BACKGROUND, 61_500)
        assertEquals(
            listOf(
                "01:02:03.456 live start cam=camera…(25) size=big stream=main ttff=1200ms",
                "01:02:03.456 live fail cam=camera…(25) size=big why=decoder retry=2 next=no",
                "01:02:03.456 live stop cam=camera…(25) size=big why=background secs=61",
            ),
            j.lines(),
        )
    }

    @Test
    fun `no offer, candidate, address or token can reach a line`() {
        val line = PushJournalFormat.liveLine(1L, utc, LiveStep.FAIL, "c", "big", why = "connect")
        assertFalse("v=0" in line || "candidate" in line || "10." in line)
        // every word the session can pass is a plain word the formatter accepts
        for (w in LiveWhy.entries) assertTrue(Regex("[a-z]+").matches(w.word), w.name)
        for (w in StopWhy.entries) assertTrue(Regex("[a-z]+").matches(w.word), w.name)
        assertFalse("?" in PushJournalFormat.liveLine(1L, utc, LiveStep.STOP, "c", "big", why = StopWhy.CLOSED.word))
    }
}
