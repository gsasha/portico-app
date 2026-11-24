package com.meda.portico.core.push

import com.meda.portico.core.call.AudioDirection
import com.meda.portico.core.call.AudioStats
import com.meda.portico.core.call.EndReason
import com.meda.portico.core.call.MicLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

/** The audio journal lines (DEV-698): golden text, unknowns as `?`, and what can never appear. */
class AudioLineTest {
    private val jlm = TimeZone.getTimeZone("Asia/Jerusalem")
    private val now = Instant.parse("2026-10-22T05:48:25.120Z").toEpochMilli() // 08:48:25.120 in Jerusalem
    private val call = "gso-1760000000000-12-a1b2c3d4e5f6"

    private val normal = AudioStats(
        up = AudioDirection("opus", 48000, 20, 98, 0),
        down = AudioDirection("opus", 48000, 20, 97, 0),
        mic = MicLevel.NORMAL,
    )

    private fun line(
        phase: AudioPhase = AudioPhase.START,
        stats: AudioStats? = normal,
        muted: Boolean = false,
        why: EndReason? = null,
        path: String = "answer",
        id: String? = call,
    ) = PushJournalFormat.audioLine(now, jlm, phase, path, id, stats, muted, why)

    @Test
    fun `golden - a normal start line`() {
        assertEquals(
            "08:48:25.120 audio start answer call=gso-17…(33) up=opus/48000/20ms down=opus/48000/20ms sent=98 updrop=0 recv=97 downdrop=0 mic=normal",
            line(),
        )
    }

    @Test
    fun `golden - an end line with the reason`() {
        val s = AudioStats(AudioDirection("opus", 48000, 20, 900, 1), AudioDirection("opus", 48000, 20, 880, 2), MicLevel.NORMAL)
        assertEquals(
            "08:48:25.120 audio end talk call=gso-17…(33) up=opus/48000/20ms down=opus/48000/20ms sent=900 updrop=1 recv=880 downdrop=2 mic=normal why=remote_hangup",
            line(AudioPhase.END, s, path = "talk", why = EndReason.REMOTE_HANGUP),
        )
    }

    @Test
    fun `golden - a silent mic, and a silent mic the person muted`() {
        val silent = normal.copy(mic = MicLevel.SILENT)
        assertTrue(line(stats = silent).endsWith("downdrop=0 mic=silent"))
        assertTrue(line(stats = silent, muted = true).endsWith("downdrop=0 mic=silent muted=yes"))
    }

    @Test
    fun `golden - drops are shown as counts`() {
        val s = normal.copy(up = normal.up.copy(lost = 12), down = normal.down.copy(lost = 40))
        assertTrue(line(stats = s).contains("updrop=12 recv=97 downdrop=40"))
    }

    @Test
    fun `golden - a 40 ms uplink and a different downlink codec read as they are`() {
        val s = normal.copy(up = normal.up.copy(ptimeMs = 40), down = AudioDirection("pcmu", 8000, 20, 97, 0))
        assertTrue(line(stats = s).contains("up=opus/48000/40ms down=pcmu/8000/20ms"))
    }

    @Test
    fun `unknown values are a question mark, never 0`() {
        val unknown = line(stats = AudioStats())
        assertEquals(
            "08:48:25.120 audio start answer call=gso-17…(33) up=?/?/? down=?/?/? sent=? updrop=? recv=? downdrop=? mic=?",
            unknown,
        )
        val none = line(stats = null, phase = AudioPhase.END, why = EndReason.FAILED)
        assertEquals(
            "08:48:25.120 audio end answer call=gso-17…(33) up=? down=? sent=? updrop=? recv=? downdrop=? mic=? why=failed",
            none,
        )
        val partial = line(stats = normal.copy(up = AudioDirection(codec = "opus", packets = 5), mic = null))
        assertTrue(partial.contains("up=opus/?/? "), partial)
        assertTrue(partial.contains("sent=5 updrop=? "), partial)
        assertTrue(partial.endsWith("mic=?"), partial)
    }

    @Test
    fun `a zero that was really read stays a zero`() {
        assertTrue(line(stats = normal.copy(down = normal.down.copy(packets = 0, lost = 0))).contains("recv=0 downdrop=0"))
    }

    @Test
    fun `a missing call id shows as none and the path word is sanitised`() {
        assertTrue(line(id = null).contains(" call=<none> "))
        assertTrue(line(path = "answer; token=abc").contains(" audio start ? "))
    }

    @Test
    fun `no line ever holds sdp, token, fingerprint, ice or a full call id`() {
        val hostile = AudioStats(
            up = AudioDirection("a=fingerprint:sha-256 AA:BB", 48000, 20, 1, 1),
            down = AudioDirection("opus; sdp=v=0 token=SECRET", 48000, 20, 1, 1),
            mic = MicLevel.LOW,
        )
        val lines = listOf(
            line(stats = hostile),
            line(AudioPhase.END, hostile, why = EndReason.FAILED),
            line(AudioPhase.END, null, muted = true, why = EndReason.REMOTE_HANGUP),
            line(stats = null),
        )
        for (l in lines) {
            for (banned in listOf("sdp", "token", "fingerprint", "ice-", "candidate", "SECRET", "a1b2c3d4e5f6", "1760000000000")) {
                assertFalse(l.lowercase().contains(banned.lowercase()), "$banned in: $l")
            }
            assertTrue(l.length <= PushJournal.MAX_LINE, l)
        }
    }

    @Test
    fun `the journal keeps its size cap with audio lines in it`() {
        val store = InMemoryPushJournalStore()
        val j = PushJournal(store, clockMs = { now }, zone = { jlm })
        repeat(PushJournal.MAX_LINES * 3) { j.audio(AudioPhase.END, "answer", "$call-$it", normal, false, EndReason.USER_HANGUP) }
        assertTrue(j.lines().size in 100..PushJournal.MAX_LINES, "${j.lines().size} lines")
        assertTrue((store.text ?: "").toByteArray().size <= PushJournal.MAX_BYTES)
        assertTrue(j.lines().all { it.contains(" audio end answer ") })
    }

    @Test
    fun `the journal writes the line as formatted, and a throwing store costs the line, not the caller`() {
        val store = InMemoryPushJournalStore()
        PushJournal(store, clockMs = { now }, zone = { jlm }).audio(AudioPhase.START, "house", call, normal, false, null)
        assertEquals(listOf(line(path = "house")), PushJournal(store).lines())
        val boom = object : PushJournalStore {
            override fun read(): String? = throw IllegalStateException("x")
            override fun write(text: String): Boolean = throw IllegalStateException("x")
        }
        PushJournal(boom).audio(AudioPhase.END, "talk", call, normal, false, EndReason.FAILED) // must not throw
    }
}
