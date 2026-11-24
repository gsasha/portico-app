package com.meda.portico.core.push

import com.meda.portico.core.ha.ConnectTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

/** DEV-694: a connect's timeline is one Journal line, so a posted Journal carries the numbers. */
class ConnectLineTest {
    private val tz = TimeZone.getTimeZone("Asia/Jerusalem")
    private val now = Instant.parse("2026-10-23T05:34:24.052Z").toEpochMilli() // 08:34:24.052

    @Test
    fun `the line carries the path and the stage offsets`() {
        val s = ConnectTimeline.Snapshot(0, listOf(
            ConnectTimeline.Mark(ConnectTimeline.Stage.TAILNET_STARTED, 0),
            ConnectTimeline.Mark(ConnectTimeline.Stage.LIBRARY_READY, 1_900),
            ConnectTimeline.Mark(ConnectTimeline.Stage.TAILNET_OPENED, 4_300),
        ))
        assertEquals(
            "08:34:24.052 net connect via=tailnet tailnet started +0 ms, library loaded +1.9 s, tailnet open +4.3 s",
            PushJournalFormat.connectLine(now, tz, "tailnet", ConnectTimeline.summary(s)),
        )
    }

    @Test
    fun `no timeline is just the path, and an odd path word is masked`() {
        assertEquals("08:34:24.052 net connect via=direct", PushJournalFormat.connectLine(now, tz, "direct", null))
        assertEquals("08:34:24.052 net connect via=?", PushJournalFormat.connectLine(now, tz, "http://evil.example/x", null))
    }

    @Test
    fun `it is written through the journal and survives the scrub`() {
        val store = object : PushJournalStore {
            var text: String? = null
            override fun read() = text
            override fun write(text: String): Boolean { this.text = text; return true }
        }
        val j = PushJournal(store, clockMs = { now }, zone = { tz })
        j.connected("direct", "direct open +120 ms")
        assertTrue(j.text().endsWith("net connect via=direct direct open +120 ms"), j.text())
        assertFalse(j.isEmpty())
    }
}
