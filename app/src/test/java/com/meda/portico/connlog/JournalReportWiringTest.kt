package com.meda.portico.connlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Journal reporter's wiring (DEV-713 / DEV-705), checked from the sources without a device (working directory = app/): ONE hook from
 * the Journal, the connection log's own queue, sender and switch, flushes on the connlog executor, and nothing in the live or audio
 * engines. What the phone sends is for a phone.
 */
class JournalReportWiringTest {
    private val main = File("src/main/java/com/meda/portico")
    private val graph by lazy { File(main, "AppGraph.kt").readText() }
    private fun count(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()
    private val reporterBlock by lazy { graph.substringAfter("private val journalReporter = JournalReporter(").substringBefore("\n    )\n") }

    @Test
    fun `the Journal has exactly one hook to the reporter`() {
        assertEquals(1, count(graph, "lineObserver"), "one assignment, no other reader of the Journal's lines")
        assertTrue(graph.contains("pushJournal.lineObserver = journalReporter::offer"))
        assertEquals(1, count(graph, "JournalReporter("), "one reporter")
    }

    @Test
    fun `the reporter uses the connection log's queue, sender and switch - no second of any`() {
        assertEquals(1, count(graph, "ConnLogSender("))
        assertEquals(1, count(graph, "ConnLogQueue("))
        assertEquals(1, count(graph, "ConnLogCommands.REPORT"), "one place puts a connlog report on the socket")
        assertTrue(reporterBlock.contains("enabled = { connLogEnabled }"), "the connlog switch is the reporter's switch")
        assertTrue(reporterBlock.contains("sink = ::enqueueConnLog"))
        val enqueue = graph.substringAfter("private fun enqueueConnLog(json: String) {").substringBefore("\n    }\n")
        assertTrue(enqueue.contains("if (connLogEnabled)") && enqueue.contains("connLogQueue.add(json)") && enqueue.contains("connSender.kick()"))
        assertTrue(graph.contains("sink = ::enqueueConnLog,\n    )"), "the connection reports take the same door")
    }

    @Test
    fun `a flush runs on the connlog executor and never on the thread that wrote the line`() {
        assertTrue(reporterBlock.contains("schedule = { delayMs, task -> connLogExec.schedule(task, delayMs, TimeUnit.MILLISECONDS) }"))
        assertFalse(reporterBlock.contains("runBlocking") || reporterBlock.contains(".get()") || reporterBlock.contains(".await("))
        assertTrue(reporterBlock.contains("onFault = { pushJournal.journalReportFault(it) }"))
    }

    @Test
    fun `switching the connection log off also forgets what the reporter holds`() {
        val setter = graph.substringAfter("var connLogEnabled: Boolean").substringBefore("private val connSender")
        assertTrue(setter.contains("connLogQueue.clear()") && setter.contains("journalReporter.clear()"))
    }

    @Test
    fun `no other app source touches the reporter or the Journal hook, and the live and audio engines are untouched`() {
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "AppGraph.kt" }
            .filter { val t = it.readText(); t.contains("JournalReporter") || t.contains("lineObserver") || t.contains("journalReporter") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), users)
        val core = File("../core/src/main/kotlin/com/meda/portico/core")
        val coreUsers = core.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { val t = it.readText(); t.contains("JournalReporter") || t.contains("lineObserver") }
            .map { it.name }.toList().sorted()
        assertEquals(listOf("JournalReporter.kt", "PushJournal.kt"), coreUsers)
    }
}
