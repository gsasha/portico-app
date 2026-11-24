package com.meda.portico.core.call

import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** The per-call decorator: at most one start line and one end line, the reason and the mute noted, and a failing media never costing the call. */
class AudioJournalMediaTest {
    private val stats = AudioStats(
        AudioDirection("opus", 48000, 20, 100, 0),
        AudioDirection("opus", 48000, 20, 99, 1),
        MicLevel.NORMAL,
    )

    private class FakeCall(var reply: AudioStats? = null) : MediaCall {
        var closes = 0
        var why: EndReason? = null
        var statsReads = 0
        var throwOnReport = false
        var muteCalls = 0
        override fun createOffer(gatherTimeoutMs: Long, onSdp: (String?) -> Unit) = onSdp("sdp")
        override fun setRemoteAnswer(sdp: String, onDone: (Boolean) -> Unit) = onDone(true)
        override fun setMuted(muted: Boolean) { muteCalls++ }
        override fun audioStats(onStats: (AudioStats?) -> Unit) { statsReads++; onStats(reply) }
        override fun endedBecause(reason: EndReason) { why = reason }
        override fun closeReporting(onStats: (AudioStats?) -> Unit) {
            if (throwOnReport) throw IllegalStateException("boom")
            closes++
            onStats(reply) // worst case: a media that reports on EVERY close, so only the decorator's own guard keeps it to one line
        }
        override fun close() { closes++ }
    }

    private class Rig(reply: AudioStats?) {
        val call = FakeCall(reply)
        var listener: MediaListener? = null
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0
        val store = InMemoryPushJournalStore()
        val journal = PushJournal(store, clockMs = { 1_000L }, zone = { TimeZone.getTimeZone("UTC") })
        val port = MediaPort { l -> listener = l; call }
        val scheduler = Scheduler { d, t -> tasks += d to t; Cancellable { cancelled++ } }
        fun media(j: PushJournal? = journal) = AudioJournalMedia(port, j, "talk", "gso-1760000000000-12-a1b2c3d4e5f6", scheduler)
        fun lines() = journal.lines()
    }

    @Test
    fun `connect schedules one read 2 s later and writes one start line`() {
        val r = Rig(stats)
        r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        assertTrue(r.lines().isEmpty(), "nothing before the audio connects")
        r.listener!!.onConnected()
        assertEquals(listOf(2_000L), r.tasks.map { it.first })
        r.tasks.single().second()
        assertEquals(1, r.lines().size)
        assertTrue(r.lines().single().contains(" audio start talk "), r.lines().single())
        assertTrue(r.lines().single().contains("sent=100 updrop=0 recv=99 downdrop=1 mic=normal"))
    }

    @Test
    fun `RATE LIMIT - a flapping connection schedules one read, and a second close writes no second end line`() {
        val r = Rig(stats)
        val call = r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        repeat(5) { r.listener!!.onDisconnected(); r.listener!!.onConnected() }
        assertEquals(1, r.tasks.size, "one start read however many connects")
        r.tasks.single().second()
        r.tasks.single().second() // even a repeated timer fire is one line per read, and the timer fires once
        call.close()
        call.close()
        call.closeReporting {}
        val ends = r.lines().filter { it.contains(" audio end ") }
        assertEquals(1, ends.size, r.lines().toString())
    }

    @Test
    fun `a call that ends before the 2 s read writes the end line only, and cancels the timer`() {
        val r = Rig(stats)
        val call = r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        r.listener!!.onConnected()
        call.close()
        r.tasks.single().second() // a timer that fires anyway after the end writes nothing
        assertEquals(1, r.cancelled)
        assertEquals(1, r.lines().size)
        assertTrue(r.lines().single().contains(" audio end "))
    }

    @Test
    fun `the end line carries the reason the session gave and whether the person muted`() {
        val r = Rig(stats.copy(mic = MicLevel.SILENT))
        val call = r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        call.setMuted(true)
        assertEquals(1, r.call.muteCalls, "the mute still reaches the media")
        call.endedBecause(EndReason.TIME_CAP)
        call.close()
        assertEquals(EndReason.TIME_CAP, r.call.why, "the reason also reaches the media")
        val l = r.lines().single()
        assertTrue(l.endsWith("mic=silent muted=yes why=time_cap"), l)
    }

    @Test
    fun `a call that never connected still gets an end line, with the reason`() {
        val r = Rig(AudioStats())
        val call = r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        call.endedBecause(EndReason.FAILED)
        call.close()
        assertTrue(r.lines().single().endsWith("sent=? updrop=? recv=? downdrop=? mic=? why=failed"), r.lines().single())
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun `a media that throws while reporting is still closed and costs only the numbers`() {
        val r = Rig(stats)
        r.call.throwOnReport = true
        val call = r.media().open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        call.endedBecause(EndReason.USER_HANGUP)
        call.close()
        assertEquals(1, r.call.closes, "closed through the plain close")
        assertTrue(r.lines().single().contains("sent=? ") && r.lines().single().endsWith("why=user_hangup"), r.lines().single())
    }

    @Test
    fun `the listener still gets every event, and the audio journal never adds a line for them`() {
        val r = Rig(stats)
        val seen = mutableListOf<String>()
        r.media().open(object : MediaListener {
            override fun onConnected() { seen += "c" }
            override fun onDisconnected() { seen += "d" }
            override fun onFailed() { seen += "f" }
        })
        r.listener!!.onConnected(); r.listener!!.onDisconnected(); r.listener!!.onFailed()
        assertEquals(listOf("c", "d", "f"), seen)
        assertTrue(r.lines().isEmpty())
    }

    @Test
    fun `with no journal the media is returned untouched`() {
        val r = Rig(stats)
        val call = r.media(null).open(object : MediaListener { override fun onConnected() {}; override fun onDisconnected() {}; override fun onFailed() {} })
        assertTrue(call === r.call)
        assertFalse(r.tasks.isNotEmpty())
    }
}
