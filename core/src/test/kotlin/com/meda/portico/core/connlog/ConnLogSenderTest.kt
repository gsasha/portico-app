package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnLogSenderTest {
    private class Mem : ConnLogStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private var now = 1_000_000L
    private val queue = ConnLogQueue(Mem())
    private val sent = mutableListOf<JsonObject>()
    private var pendingReply: ((ConnLogReply) -> Unit)? = null
    private var socketReady = true
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var enabled = true

    private val sender = ConnLogSender(
        queue,
        send = { body, onReply -> if (!socketReady) false else { sent.add(body); pendingReply = onReply; true } },
        schedule = { d, t -> scheduled.add(d to t) },
        enabled = { enabled },
        clockMs = { now },
    )

    private fun rep(id: String) = """{"v":1,"id":"$id"}"""
    private fun fill(n: Int) = repeat(n) { queue.add(rep("r$it")) }
    private fun reply(r: ConnLogReply) { val p = pendingReply!!; pendingReply = null; p(r) }
    private fun idsSent(i: Int) = sent[i]["reports"]!!.jsonArray.map { it.toString().substringAfter("\"id\":\"").substringBefore("\"") }

    @Test
    fun `nothing queued - nothing sent`() {
        sender.kick()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a kick sends the oldest batch, one at a time, and an ok reply removes exactly it`() {
        fill(25)
        sender.kick(); sender.kick()
        assertEquals(1, sent.size)
        assertEquals("portico/connlog/report", ConnLogSender.COMMAND)
        assertEquals((0 until 20).map { "r$it" }, idsSent(0))
        reply(ConnLogReply.Ok(stored = 19, duplicates = 1, rejected = 0))
        assertEquals(5, queue.size())
        assertEquals(19, sender.sentCount)
        assertEquals(now, sender.lastSentAtMs)
        assertEquals(ConnLogSender.MIN_GAP_MS, scheduled.filter { it.first != ConnLogSender.ANSWER_TIMEOUT_MS }.single().first) // the rest follows after the minimum gap
    }

    @Test
    fun `the next batch waits for the minimum gap`() {
        fill(25)
        sender.kick(); reply(ConnLogReply.Ok(20, 0, 0))
        sender.kick()
        assertEquals(1, sent.size)
        assertTrue(scheduled.isNotEmpty())
        now += ConnLogSender.MIN_GAP_MS
        sender.kick()
        assertEquals(2, sent.size)
        assertEquals((20 until 25).map { "r$it" }, idsSent(1))
    }

    @Test
    fun `a rejected report is removed too - it would never be accepted`() {
        fill(2)
        sender.kick(); reply(ConnLogReply.Ok(stored = 1, duplicates = 0, rejected = 1))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `an error backs off 15 minutes, doubling to 6 hours, and keeps the reports`() {
        fill(1)
        sender.kick(); reply(ConnLogReply.Error("rate_limited"))
        assertEquals(1, queue.size())
        now += 14 * 60_000L; sender.kick(); assertEquals(1, sent.size)
        now += 2 * 60_000L; sender.kick(); assertEquals(2, sent.size)
        reply(ConnLogReply.Error("unauthorized"))
        now += 29 * 60_000L; sender.kick(); assertEquals(2, sent.size)
        now += 2 * 60_000L; sender.kick(); assertEquals(3, sent.size)
        assertEquals(15L * 60_000, ConnLogSender.backoff(1))
        assertEquals(30L * 60_000, ConnLogSender.backoff(2))
        assertEquals(6L * 3600_000, ConnLogSender.backoff(20))
    }

    @Test
    fun `unknown_command switches the sender off for this process`() {
        fill(3)
        sender.kick(); reply(ConnLogReply.Error("unknown_command"))
        assertTrue(sender.isUnsupported)
        now += 24 * 3600_000L; sender.kick()
        assertEquals(1, sent.size)
        assertEquals(3, queue.size())
    }

    @Test
    fun `too_large halves the batch and tries again`() {
        fill(25)
        sender.kick(); reply(ConnLogReply.Error("too_large"))
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(10, idsSent(1).size)
    }

    @Test
    fun `a lost socket is not a failure - the next connect kicks again`() {
        fill(1)
        sender.kick(); reply(ConnLogReply.Lost)
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(2, sent.size)
    }

    @Test
    fun `a socket that is not ready sends nothing and costs nothing`() {
        fill(1); socketReady = false
        sender.kick(); sender.kick()
        assertTrue(sent.isEmpty())
        socketReady = true; sender.kick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `switched off - nothing is sent`() {
        fill(1); enabled = false
        sender.kick()
        assertTrue(sent.isEmpty())
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `a throwing send never reaches the caller and leaves the sender usable`() {
        fill(1)
        var calls = 0
        val s = ConnLogSender(queue, send = { _, _ -> calls++; throw IllegalStateException("socket") }, schedule = { _, _ -> }, clockMs = { now })
        s.kick(); s.kick()
        assertEquals(2, calls)
    }

    // ---- the review's findings ----

    @Test
    fun `a rejected batch is bisected and a single refused report is dropped, not retried for ever`() {
        fill(4)
        sender.kick(); reply(ConnLogReply.Error("invalid_format")) // 4 -> 2
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(2, idsSent(1).size)
        reply(ConnLogReply.Error("invalid_format")) // 2 -> 1
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(1, idsSent(2).size)
        reply(ConnLogReply.Error("too_large")) // one report that is itself refused: dropped
        assertEquals(3, queue.size())
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(1, idsSent(3).size)
        assertTrue(idsSent(3).single() !in idsSent(2))
    }

    @Test
    fun `a success restores the full batch size after a bisection`() {
        fill(25)
        sender.kick(); reply(ConnLogReply.Error("too_large"))
        now += ConnLogSender.MIN_GAP_MS; sender.kick(); assertEquals(10, idsSent(1).size)
        reply(ConnLogReply.Ok(10, 0, 0))
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(15, idsSent(2).size) // the remaining 15, no longer capped at 10
    }

    @Test
    fun `a call nobody answers does not stick the sender`() {
        fill(2)
        sender.kick()
        assertEquals(1, sent.size)
        val firstReply = pendingReply!!
        val timeout = scheduled.single { it.first == ConnLogSender.ANSWER_TIMEOUT_MS }.second
        now += ConnLogSender.ANSWER_TIMEOUT_MS
        timeout()
        now += ConnLogSender.MIN_GAP_MS; sender.kick()
        assertEquals(2, sent.size)
        firstReply(ConnLogReply.Ok(2, 0, 0)) // a late answer to the first call: its flight is over, so it changes nothing
        assertEquals(2, queue.size())
        reply(ConnLogReply.Ok(2, 0, 0))
        assertEquals(0, queue.size())
    }

    @Test
    fun `waiting for the back-off schedules one timer, however often it is kicked`() {
        fill(1)
        sender.kick(); reply(ConnLogReply.Error("rate_limited"))
        repeat(20) { sender.kick() }
        assertEquals(1, scheduled.count { it.first != ConnLogSender.ANSWER_TIMEOUT_MS })
    }
}
