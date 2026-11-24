package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The push and ring journal's ring buffer: caps, rotation, order across process restarts, threads, a corrupt store. */
class PushJournalTest {
    private val store = InMemoryPushJournalStore()
    private fun journal(s: PushJournalStore = store) = PushJournal(s, { 1_000L }, { TimeZone.getTimeZone("UTC") })

    /** A word line with no digit-bearing long runs, `n` long at most. */
    private fun fat(i: Int, chars: Int = 280) = ("w$i " + "ab cd ".repeat(60)).take(chars)

    @Test
    fun `an empty journal reads empty and a line is kept`() {
        val j = journal()
        assertTrue(j.isEmpty())
        assertEquals("", j.text())
        assertTrue(j.add("08:00:00.000 push ring"))
        assertEquals(listOf("08:00:00.000 push ring"), j.lines())
        assertFalse(j.isEmpty())
    }

    @Test
    fun `the line cap is 1500 and the oldest go first`() {
        val j = journal()
        val cap = PushJournal.MAX_LINES
        for (i in 1..cap + 50) j.add("line $i")
        val lines = j.lines()
        assertEquals(1500, cap)
        assertEquals(cap, lines.size)
        assertEquals("line 51", lines.first())
        assertEquals("line ${cap + 50}", lines.last())
        assertEquals((51..cap + 50).map { "line $it" }, lines, "rotation keeps order")
    }

    @Test
    fun `the byte cap is 160 KB and the newest line always survives`() {
        val j = journal()
        for (i in 1..700) j.add(fat(i))
        val stored = store.text!!
        assertTrue(stored.toByteArray(Charsets.UTF_8).size <= PushJournal.MAX_BYTES, "stored ${stored.length}")
        val lines = j.lines()
        assertTrue(lines.size < 700, "the byte cap bit before the line cap: ${lines.size}")
        assertTrue(lines.last().startsWith("w700 "))
        assertTrue(lines.size > 50)
    }

    @Test
    fun `multibyte text counts in bytes, not characters`() {
        val j = journal()
        val wide = "é".repeat(PushJournal.MAX_LINE) // 2 bytes each
        for (i in 1..600) j.add("$wide")
        assertTrue(store.text!!.toByteArray(Charsets.UTF_8).size <= PushJournal.MAX_BYTES)
    }

    @Test
    fun `one line is cut at 300 characters`() {
        val j = journal()
        j.add("x y ".repeat(500))
        assertEquals(300, PushJournal.MAX_LINE)
        assertTrue(j.lines().single().length in 290..PushJournal.MAX_LINE, "${j.lines().single().length}")
    }

    @Test
    fun `order holds across process restarts, each process a fresh instance on the same store`() {
        journal().apply { add("a1"); add("a2") }          // process 1
        journal().apply { add("b1") }                      // process 2 (fresh)
        journal().apply { add("c1"); add("c2") }           // process 3
        assertEquals(listOf("a1", "a2", "b1", "c1", "c2"), journal().lines())
        assertEquals(listOf("c1", "c2"), journal().tail(2))
        assertEquals(emptyList<String>(), journal().tail(0))
    }

    @Test
    fun `the cap holds across restarts too`() {
        val per = PushJournal.MAX_LINES / 4
        for (p in 0 until 5) journal().apply { for (i in 0 until per) add("p$p-$i") }
        val lines = journal().lines()
        assertEquals(PushJournal.MAX_LINES, lines.size)
        assertEquals("p1-0", lines.first())
        assertEquals("p4-${per - 1}", lines.last())
    }

    @Test
    fun `clear empties it, durably`() {
        val j = journal()
        j.add("one")
        assertTrue(j.clear())
        assertTrue(journal().isEmpty())
        j.add("two")
        assertEquals(listOf("two"), journal().lines())
    }

    @Test
    fun `threads on two instances lose nothing and keep each thread's order`() {
        val a = journal()
        val b = journal()
        val threads = 8
        val per = 20
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        for (t in 0 until threads) pool.execute {
            start.await()
            val j = if (t % 2 == 0) a else b
            for (i in 0 until per) j.add("t$t-$i")
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val lines = journal().lines()
        assertEquals(threads * per, lines.size)
        assertEquals(lines.size, lines.toSet().size, "no line twice")
        for (t in 0 until threads) {
            assertEquals((0 until per).map { "t$t-$it" }, lines.filter { it.startsWith("t$t-") })
        }
    }

    // ---- a store that is wrong ----------------------------------------------------------------------

    @Test
    fun `garbage, a missing header and a truncated header read as empty and the next add starts clean`() {
        for (bad in listOf("", "garbage", "not a journal\nline", "pj", "{\"a\":1}", "\u0000\u0001\u0002", "pj2\nline")) {
            val s = InMemoryPushJournalStore(bad)
            val j = journal(s)
            assertEquals(emptyList<String>(), j.lines(), "[$bad]")
            assertTrue(j.add("fresh"), "[$bad]")
            assertEquals(listOf("fresh"), j.lines(), "[$bad]")
        }
    }

    @Test
    fun `a stored text from a bigger build is trimmed to the caps and its control characters dropped`() {
        val big = PushJournal.HEADER + (1..2000).joinToString("\n") { "old $it\u0007\r" }
        val j = journal(InMemoryPushJournalStore(big))
        val lines = j.lines()
        assertEquals(PushJournal.MAX_LINES, lines.size)
        assertEquals("old ${2000 - PushJournal.MAX_LINES + 1}", lines.first())
        assertEquals("old 2000", lines.last())
        assertTrue(lines.none { l -> l.any { it < ' ' } })
    }

    @Test
    fun `a store that throws never throws into the caller`() {
        val boom = object : PushJournalStore {
            override fun read(): String? = throw IllegalStateException("read")
            override fun write(text: String): Boolean = throw IllegalStateException("write")
        }
        val j = journal(boom)
        assertFalse(j.add("x"))
        assertEquals(emptyList<String>(), j.lines())
        assertFalse(j.clear())
        j.dropped("ring", "paused")
        j.failed("ring", RuntimeException("secret-token-0123456789abcdef"))
    }

    @Test
    fun `a store that cannot be written is not remembered as written`() {
        val s = object : PushJournalStore {
            var text: String? = null
            var ok = false
            override fun read() = text
            override fun write(text: String): Boolean { if (ok) this.text = text; return ok }
        }
        val j = journal(s)
        assertFalse(j.add("lost"))
        s.ok = true
        assertTrue(j.add("kept"))
        assertEquals(listOf("kept"), j.lines())
    }

    @Test
    fun `a failing clock, zone or environment probe costs nothing`() {
        val j = PushJournal(
            store,
            { error("clock") },
            { error("zone") },
            { error("env") },
        )
        j.dropped("ring", "paused")
        j.stopped("decline", "door", "call", true)
        val msg = PushMessage.Ended("door-ref-1", "call-id-1", 3, 1_000L)
        j.decided(msg, Outcome.Silent(SilentReason.STALE), 2_000L, 1) // zone throws
        assertTrue(j.isEmpty(), "nothing could be formatted, nothing was written, nothing was thrown")

        val envOnly = PushJournal(store, { 5_000L }, { TimeZone.getTimeZone("UTC") }, { error("env") })
        envOnly.decided(msg, Outcome.Silent(SilentReason.STALE), 2_000L, 1)
        assertEquals(1, envOnly.lines().size)
        assertTrue(envOnly.lines().single().contains("fg=? fsi=?"), envOnly.lines().single())
    }

    @Test
    fun `add scrubs ids, tokens, urls and control characters, whoever calls it`() {
        val j = journal()
        j.add("token fcm:APA91bGexampleexampleexample0123456789 end")
        j.add("see https://gate.example.com/path?code=ABCD1234 now")
        j.add("full call id gso-1760000000000-12-a1b2c3d4e5f6 here")
        j.add("tab\tand\nnewline")
        val text = j.text()
        assertFalse(text.contains("APA91b"), text)
        assertFalse(text.contains("example.com"), text)
        assertFalse(text.contains("a1b2c3d4e5f6"), text)
        assertFalse(text.contains("code=ABCD"), text)
        assertTrue(text.contains("<url>") && text.contains("<id>"), text)
        assertEquals(4, j.lines().size, "the embedded newline did not make a fifth line")
    }

    @Test
    fun `a hold step is one line, and a store or a clock that throws costs the line and nothing else`() {
        val j = journal()
        j.hold(HoldStep.SWITCHED, "gate-ref-1234567", "gso-1760000000000-12-a1b2c3d4e5f6", null, 1500)
        assertEquals(listOf("00:00:01.000 hold switched door=gate-r…(16) call=gso-17…(33) ms=1500"), j.lines())
        val throwingStore = object : PushJournalStore {
            override fun read(): String? = error("read")
            override fun write(text: String): Boolean = error("write")
        }
        journal(throwingStore).hold(HoldStep.FOCUS_LOST, null, null, null, null, 1)
        PushJournal(store, { error("clock") }).hold(HoldStep.FOCUS_LOST, null, null)
        assertEquals(1, j.lines().size)
    }
}
