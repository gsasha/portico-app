package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-764 (app fixes): a press before the link has doors/calls subscribed waits, then goes; it never waits past the bound. */
class PatientCommandPortTest {
    private val fake = FakeCommands()
    private val sched = FakeScheduler()
    private var resubscribed = 0
    private val notes = mutableListOf<String>()
    private val port = PatientCommandPort(
        fake, sched, { sched.now }, maxWaitMs = 4_000, pollMs = 250,
        onNotSubscribed = { resubscribed++ }, note = { _, what, _ -> notes += what },
    )
    private val replies = mutableListOf<CommandReply>()

    private fun press(type: String = "portico/answer") = port.send(type, obj(), { replies += it })

    @Test
    fun `a press while the socket is not ready waits, then goes when it is`() {
        fake.connected = false
        press()
        sched.advance(1_000)
        assertTrue(replies.isEmpty(), "still waiting, nothing shown to the person")
        fake.connected = true
        sched.advance(250)
        assertEquals(1, fake.count("portico/answer"))
        fake.last("portico/answer").ok()
        assertEquals(1, replies.size)
        assertTrue(replies[0] is CommandReply.Reply)
        assertEquals(listOf("wait"), notes)
    }

    @Test
    fun `a link that never gets ready ends in the same refusal, once, within the bound`() {
        fake.connected = false
        press()
        sched.advance(3_000)
        assertTrue(replies.isEmpty())
        sched.advance(1_500)
        assertEquals(listOf(CommandReply.NotSent), replies)
        sched.advance(10_000)
        assertEquals(1, replies.size)
        assertEquals(listOf("wait", "gaveup"), notes)
    }

    @Test
    fun `not_subscribed from the server is tried again, and the link is asked to subscribe once`() {
        press()
        fake.last("portico/answer").error("not_subscribed")
        assertTrue(replies.isEmpty())
        assertEquals(1, resubscribed)
        sched.advance(250)
        assertEquals(2, fake.count("portico/answer"))
        fake.sent[1].error("not_subscribed")
        sched.advance(250)
        assertEquals(1, resubscribed, "asked once per command")
        fake.sent[2].ok()
        assertEquals(1, replies.size)
        assertTrue((replies[0] as CommandReply.Reply).result.success)
    }

    @Test
    fun `not_subscribed that never clears is shown after the bound`() {
        press()
        repeat(40) {
            if (replies.isEmpty()) {
                fake.sent.last { !it.answered }.error("not_subscribed")
                sched.advance(250)
            }
        }
        assertEquals(1, replies.size)
        assertEquals("not_subscribed", (replies[0] as CommandReply.Reply).result.error?.code)
        assertTrue(sched.now <= 4_250, "waited ${sched.now} ms")
    }

    @Test
    fun `any other refusal, a loss, and hangup are never delayed`() {
        press()
        fake.last("portico/answer").error("already_answered")
        assertEquals(1, replies.size)
        press("portico/answer")
        fake.sent.last().lost()
        assertEquals(2, replies.size)
        fake.connected = false
        press("portico/hangup")
        assertEquals(listOf(CommandReply.NotSent), replies.drop(2))
        assertEquals(0, sched.pending)
    }

    private fun lostPort(wanted: () -> Boolean = { true }) = PatientCommandPort(
        fake, sched, { sched.now }, maxWaitMs = 4_000, pollMs = 250, lostWaitMs = 8_000,
        note = { _, what, _ -> notes += what }, stillWanted = { wanted() },
    )

    @Test
    fun `a command whose socket died is sent again after the reconnect while the ring is still up`() {
        val p = lostPort()
        p.send("portico/answer", obj(), { replies += it })
        fake.sent.last().lost()
        assertTrue(replies.isEmpty(), "the person is not told yet")
        fake.connected = false
        sched.advance(5_000) // the link is rebuilding: not sent, tried again, well past the 4 s NotSent bound
        assertTrue(replies.isEmpty())
        fake.connected = true
        sched.advance(250)
        assertEquals(2, fake.count("portico/answer"))
        fake.sent.last().ok()
        assertTrue((replies.single() as CommandReply.Reply).result.success)
        assertEquals(listOf("reconnect"), notes.filter { it != "wait" })
    }

    @Test
    fun `a lost command is handed on when the ring ended, or the link never comes back in time`() {
        var ringing = true
        val p = lostPort { ringing }
        p.send("portico/answer", obj(), { replies += it })
        fake.connected = false
        fake.sent.last().lost()
        ringing = false
        sched.advance(250)
        assertEquals(listOf(CommandReply.NotSent), replies, "the ring ended: the last outcome is handed on, nothing is resent")
        replies.clear()
        ringing = true
        p.send("portico/answer", obj(), { replies += it })
        sched.advance(9_000)
        assertEquals(listOf(CommandReply.NotSent), replies)
        assertTrue(sched.now <= 20_000)
    }

    @Test
    fun `DEV-811 a press with the socket down for 6 s goes when it returns, the ring still up`() {
        val p = lostPort()
        fake.connected = false
        p.send("portico/answer", obj(), { replies += it })
        sched.advance(6_000)
        assertTrue(replies.isEmpty(), "still waiting past the old 4 s bound")
        fake.connected = true
        sched.advance(250)
        assertEquals(1, fake.count("portico/answer"))
        fake.last("portico/answer").ok()
        assertTrue((replies.single() as CommandReply.Reply).result.success)
    }

    @Test
    fun `DEV-811 a socket down for 9 s gives up with NotSent at about 8 s`() {
        val p = lostPort()
        fake.connected = false
        p.send("portico/answer", obj(), { replies += it })
        sched.advance(7_500)
        assertTrue(replies.isEmpty())
        sched.advance(500)
        assertEquals(listOf(CommandReply.NotSent), replies)
        fake.connected = true
        sched.advance(2_000)
        assertEquals(0, fake.count("portico/answer"))
    }

    @Test
    fun `DEV-811 a socket down when the ring has ended gives up at 4 s`() {
        var ringing = true
        val p = lostPort { ringing }
        fake.connected = false
        p.send("portico/answer", obj(), { replies += it })
        sched.advance(3_000)
        ringing = false
        sched.advance(1_500)
        assertEquals(listOf(CommandReply.NotSent), replies)
        assertTrue(sched.now <= 4_500)
    }

    @Test
    fun `a hangup lost with its socket is sent again, a plain hangup never waits`() {
        val p = lostPort()
        p.send("portico/hangup", obj(), { replies += it })
        fake.sent.last().lost()
        assertTrue(replies.isEmpty())
        sched.advance(250)
        assertEquals(2, fake.count("portico/hangup"))
        fake.sent.last().ok()
        assertEquals(1, replies.size)
        replies.clear()
        fake.connected = false
        p.send("portico/hangup", obj(), { replies += it })
        assertEquals(listOf(CommandReply.NotSent), replies)
    }
}
