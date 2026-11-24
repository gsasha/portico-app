package com.meda.portico.core.connlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Source guards (working directory = core/): the hand-off from the Journal cannot block, and there is one door out of it. */
class JournalReporterSourceTest {
    /** The code only: comments say what the reporter is NOT, and would trip the checks. */
    private val reporter by lazy {
        File("src/main/kotlin/com/meda/portico/core/connlog/JournalReporter.kt").readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("/**") || t.startsWith("//") }
            .joinToString("\n") { it.substringBefore(" // ") }
    }
    private val journal by lazy { File("src/main/kotlin/com/meda/portico/core/push/PushJournal.kt").readText() }

    private val offerBody by lazy { reporter.substringAfter("    fun offer(raw: String) {").substringBefore("    fun clear() {") }

    @Test
    fun `offer reaches neither the sink nor the queue nor a wait`() {
        assertFalse(offerBody.contains("sink("), "the sink does disk and JSON work: only the flush calls it")
        assertFalse(offerBody.contains("render("))
        for (blocking in listOf("Thread.sleep", ".await(", ".join(", "runBlocking", ".get()", "Future", "Queue", "store.", "File(")) {
            assertFalse(offerBody.contains(blocking), blocking)
        }
        assertTrue(offerBody.contains("schedule(delay)"), "it asks the executor for the flush")
    }

    @Test
    fun `the lock is never held across the sink or the scheduler`() {
        // the only synchronized(lock) blocks end before sink( / schedule( are called
        val flush = reporter.substringAfter("    private fun flush() {").substringBefore("    private fun render(")
        val sinkAt = flush.indexOf("sink(render(chunk))")
        assertTrue(sinkAt > 0)
        val chunk = flush.substringAfter("val chunk = synchronized(lock) {").substringBefore("if (chunk.isEmpty()) break")
        assertTrue(chunk.contains("pending.removeFirst()") && !chunk.contains("sink("))
        val beforeSink = flush.substring(0, sinkAt)
        assertEquals(1, Regex("synchronized\\(lock\\)").findAll(beforeSink).count(), "only the short take of a chunk holds the lock")
    }

    @Test
    fun `the reporter owns no socket, queue, sender or command of its own`() {
        for (second in listOf("ConnLogSender", "ConnLogQueue", "ConnLogCommands", "portico/connlog", "sendCall", "WebSocket", "HaLink", "PorticoLink")) {
            assertFalse(reporter.contains(second), second)
        }
    }

    @Test
    fun `the Journal hands each line over once, after its write and its lock`() {
        assertEquals(1, Regex("offerToObserver\\(clean\\)").findAll(journal).count())
        assertEquals(1, Regex("lineObserver\\?\\.invoke").findAll(journal).count())
        val add = journal.substringAfter("fun add(line: String): Boolean").substringBefore("    /**\n     * DEV-713 / DEV-705: the ONE place")
        assertTrue(add.indexOf("synchronized(LOCK)") in 1 until add.indexOf("offerToObserver(clean)"), "the hand-over comes after the lock is released")
        assertTrue(add.contains("} finally {"), "a failed write still offers the line")
        assertFalse(add.substringAfter("synchronized(LOCK) {").substringBefore("} finally {").contains("offerToObserver"), "not inside the lock")
        val observer = journal.substringAfter("private fun offerToObserver(").substringBefore("\n    }\n")
        assertTrue(observer.contains("catch (e: Throwable)"), "an observer's failure is swallowed")
    }
}
