package com.meda.portico.core.connlog

import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.push.PushJournalFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * The Journal items of `portico/connlog/report` against portico-ha's recorded cases (`connlog journal ...` in the vendored `ws_v3.json`):
 * what the app's reporter builds from the lines of a case goes through the REAL queue and sender and must be the case's request, and
 * HA's recorded answer must be what the sender handles. The rules are the server's own: the lines the case says HA keeps are exactly
 * the lines the reporter keeps.
 */
class JournalReporterContractTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private class Mem : ConnLogStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private var now = 0L
    private val queue = ConnLogQueue(Mem())
    private val sent = mutableListOf<JsonObject>()
    private var pendingReply: ((ConnLogReply) -> Unit)? = null
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var nextId = "jrn-0001"
    private var reporterTasks = mutableListOf<() -> Unit>()

    private val sender = ConnLogSender(
        queue,
        send = { body, onReply -> sent.add(body); pendingReply = onReply; true },
        schedule = { d, t -> scheduled.add(d to t) },
        clockMs = { now },
    )

    private val reporter = JournalReporter(
        appVersion = "0.6.16-alpha",
        enabled = { true },
        sink = { queue.add(it); sender.kick() },
        schedule = { _, t -> reporterTasks.add(t) },
        clockMs = { now },
        zone = { utc },
        newId = { nextId },
    )

    private fun case(name: String) = WsContract.case(name)

    @Suppress("UNCHECKED_CAST")
    private fun journalItem(c: WsContract.Case, index: Int = 0): Map<String, Any?> = (c.request["reports"] as List<Map<String, Any?>>)[index]

    @Suppress("UNCHECKED_CAST")
    private fun linesOf(item: Map<String, Any?>) = (item["lines"] as List<Map<String, Any?>>).map { (it["t"] as Number).toLong() to it["line"] as String }

    private fun result(c: WsContract.Case): HaInbound.Result = HaProtocol.parse(WsContract.encode(c.receive.first())).single() as HaInbound.Result

    /** Offers a case's lines the way the Journal writes them (a time-of-day prefix) and runs the flush. */
    private fun feed(lines: List<Pair<Long, String>>) {
        now = lines.maxOf { it.first } + 500
        for ((t, line) in lines) reporter.offer(PushJournalFormat.time(t, utc) + " " + line)
        reporterTasks.toList().also { reporterTasks.clear() }.forEach { it() }
    }

    private fun wire(c: WsContract.Case): JsonElement = Json.parseToJsonElement(WsContract.encode(c.request))

    @Test
    fun `the three lines of the recorded case become exactly the recorded request, through the real queue and sender`() {
        val c = case("connlog journal report: phone, three lines")
        feed(linesOf(journalItem(c)))
        assertEquals(1, sent.size)
        val expected = (wire(c) as JsonObject).filterKeys { it != "type" }
        assertEquals(JsonObject(expected), sent.single(), "the body the sender puts on the socket")
        assertEquals(c.type, ConnLogSender.COMMAND)
        // HA's recorded answer is handled: the item leaves the queue
        pendingReply!!(ConnLogReplies.of(result(c)))
        assertTrue(queue.isEmpty())
        assertEquals(ConnLogReply.Ok(1, 0, 0, journalLines = 3, journalDropped = 0), ConnLogReplies.of(result(c)))
    }

    @Test
    fun `the lines the recorded server keeps are exactly the lines the reporter keeps`() {
        val c = case("connlog journal report: phone, hostile lines dropped and counted")
        val offered = linesOf(journalItem(c))
        assertEquals(7, offered.size)
        feed(offered)
        @Suppress("UNCHECKED_CAST")
        val entries = ((case("connlog journal get: admin, kind journal").receive.first()["result"] as Map<String, Any?>)["journal"] as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        val keptByServer = linesOf(entries.first { it["id"] == "jrn-0002" })
        val item = Json.parseToJsonElement(queue.batch().single().json).jsonObject
        val keptByApp = item["lines"]!!.jsonArray.map { it.jsonObject["t"]!!.jsonPrimitive.content.toLong() to it.jsonObject["line"]!!.jsonPrimitive.content }
        assertEquals(keptByServer, keptByApp)
        assertEquals(2, keptByApp.size)
        // the reply the case recorded says the server dropped 5: one of them ("hold ...") the app never offers, four the app drops itself
        assertEquals(4, reporter.droppedInvalid)
        assertEquals(ConnLogReply.Ok(1, 0, 0, journalLines = 2, journalDropped = 5), ConnLogReplies.of(result(c)))
    }

    @Test
    fun `an item the server cannot use is something the app never builds`() {
        val c = case("connlog journal report: phone, nothing acceptable in it (rejected)")
        feed(linesOf(journalItem(c, 0)))
        assertTrue(sent.isEmpty() && queue.isEmpty(), "hold and net lines are not offered to HA at all")
        assertEquals(0, reporter.droppedInvalid)
        // ... and if one arrived anyway, HA's `rejected` answer is final: the item is removed, not retried
        queue.add("""{"kind":"journal","id":"jrn-0003","lines":[{"t":1,"line":"hold x"}]}""")
        sender.kick()
        pendingReply!!(ConnLogReplies.of(result(c)))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a connection report and a Journal item share one message`() {
        val c = case("connlog journal report: phone, a connection report and an item in one message")
        @Suppress("UNCHECKED_CAST")
        val reports = c.request["reports"] as List<Map<String, Any?>>
        queue.add(WsContract.encode(reports[0]))
        feed(linesOf(reports[1]))
        assertEquals(1, sent.size)
        // the queue sent the report that was waiting and the item behind it in one body: two items of `reports`
        assertEquals(listOf("rep-0101", "jrn-0001"), sent.single()["reports"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val kinds = sent.single()["reports"]!!.jsonArray.map { it.jsonObject["kind"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, "journal"), kinds)
        assertFalse(sent.single()["reports"]!!.jsonArray.last().jsonObject.containsKey("v"))
        pendingReply!!(ConnLogReplies.of(result(c)))
        assertTrue(queue.isEmpty())
    }

    // ---- an old server ---------------------------------------------------------------------------------------------------------

    @Test
    fun `an old server rejects the item and the sender counts it as sent`() {
        queue.add("""{"kind":"journal","id":"abc123def456","app":"0.6.16-alpha","lines":[{"t":1761000000000,"line":"live stop cam=a…(1) size=big why=hidden secs=1"}]}""")
        sender.kick()
        pendingReply!!(ConnLogReply.Ok(stored = 0, duplicates = 0, rejected = 1)) // an old plugin: no `v`, so it is not a report
        assertTrue(queue.isEmpty(), "never retried: it would be rejected again")
        assertEquals(0, sender.sentCount)
        now += ConnLogSender.MIN_GAP_MS
        queue.add("""{"kind":"journal","id":"abc123def457","lines":[{"t":1,"line":"live stop x"}]}""")
        sender.kick()
        assertEquals(2, sent.size, "the next item goes out after the minimum gap, not after a back-off")
    }

    @Test
    fun `a plugin without the command switches the whole connlog off for the process, journal items included`() {
        queue.add("""{"kind":"journal","id":"abc123def456","lines":[{"t":1,"line":"live stop x"}]}""")
        sender.kick()
        pendingReply!!(ConnLogReply.Error("unknown_command"))
        assertTrue(sender.isUnsupported)
        queue.add("""{"kind":"journal","id":"abc123def457","lines":[{"t":1,"line":"live stop y"}]}""")
        now += 10 * 3600_000L
        sender.kick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `rate_limited keeps the items and backs off like any report`() {
        queue.add("""{"kind":"journal","id":"abc123def456","lines":[{"t":1,"line":"live stop x"}]}""")
        sender.kick()
        pendingReply!!(ConnLogReply.Error("rate_limited"))
        assertEquals(1, queue.size())
        now += 14 * 60_000L
        sender.kick()
        assertEquals(1, sent.size)
        now += 2 * 60_000L
        sender.kick()
        assertEquals(2, sent.size)
    }

    @Test
    fun `the items share the connection log's bounded queue, oldest first`() {
        repeat(ConnLogQueue.MAX_REPORTS + 5) { queue.add("""{"kind":"journal","id":"j$it","lines":[{"t":1,"line":"live stop x"}]}""") }
        assertEquals(ConnLogQueue.MAX_REPORTS, queue.size())
        assertEquals(5, queue.dropped())
        assertEquals("j5", queue.batch(1).single().id)
    }

    @Test
    fun `the contract carries every journal case and the app lists the command`() {
        val names = WsContract.cases.map { it.name }.filter { it.startsWith("connlog journal") }
        assertEquals(22, names.size)
        assertTrue("portico/connlog/report" in WsContract.appCommands)
    }
}
