package com.meda.portico.core.call

import com.meda.portico.core.connlog.JournalLines
import com.meda.portico.core.content.TalkGate
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat.DiagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** DEV-764: the diagnostic lines are compact, redacted, accepted by the server's journal rules, and change nothing. */
class DiagLoggingTest {
    private val store = InMemoryPushJournalStore()
    private val journal = PushJournal(store, { 1_000L }, { TimeZone.getTimeZone("UTC") })
    private fun lines() = journal.lines()

    private fun serverKeeps(line: String): Boolean {
        val body = line.substringAfter(' ') // drop the time
        return JournalLines.check(body) is JournalLines.Verdict.Keep
    }

    @Test
    fun `a diag line carries words, numbers, yes-no and shortened ids and nothing else`() {
        journal.diag(
            "net", "link-calls", "ok" to false, "code" to "not_subscribed", "ms" to 1234L,
            "call" to DiagId("gso-0123456789abcdef0123456789"), "url" to "https://ha.example.com/x?token=abc", "none" to null, "bad key" to 1,
        )
        val l = lines().single()
        assertEquals("00:00:01.000 net link-calls ok=no code=not_subscribed ms=1234 call=gso-01…(30) url=?", l)
        assertFalse("token" in l)
        assertTrue(serverKeeps(l), l)
    }

    @Test
    fun `an unknown journal word falls back to ui and a bad event name to a question mark`() {
        journal.diag("zzz", "Bad Event")
        assertEquals("00:00:01.000 ui ?", lines().single())
    }

    @Test
    fun `a dotted id cannot make the server drop the line`() {
        journal.diag("ui", "talk-doc", "act" to DiagId("gate.talk.button-01"), "avail" to true)
        val l = lines().single()
        assertTrue(serverKeeps(l), l)
    }

    @Test
    fun `the command port logs failures with the link fields and hands the reply on unchanged`() {
        val inner = FakeCommands()
        val port = JournalingCommandPort(inner, journal, { listOf("sock" to "connected", "calls" to false) }, { 5L })
        val got = mutableListOf<CommandReply>()
        port.send("portico/answer", obj(), { got += it })
        port.send("portico/webrtc_offer", obj(), { got += it })
        inner.sent[0].error("not_subscribed")
        inner.sent[1].ok()
        port.send("portico/hangup", obj(), { got += it }) // answered by nobody: stays pending
        inner.connected = false
        port.send("portico/place", obj(), { got += it })
        assertEquals(3, got.size)
        val ls = lines()
        assertEquals(3, ls.size)
        assertTrue(ls[0].endsWith("ui cmd c=answer r=err code=not_subscribed ms=0 sock=connected calls=no"), ls[0])
        assertTrue(ls[1].endsWith("ui cmd c=webrtc-offer r=ok ms=0"), ls[1])
        assertTrue(ls[2].endsWith("ui cmd c=place r=notsent ms=0 sock=connected calls=no"), ls[2])
        ls.forEach { assertTrue(serverKeeps(it), it) }
    }

    @Test
    fun `a null journal changes nothing about the command port`() {
        val inner = FakeCommands()
        val port = JournalingCommandPort(inner, null)
        var r: CommandReply? = null
        port.send("portico/answer", obj(), { r = it })
        inner.sent.single().lost()
        assertEquals(CommandReply.Lost, r)
    }

    @Test
    fun `an answer session writes its steps with the time from the press and the same outcome as without a journal`() {
        var wall = 1_000_000L
        fun run(j: PushJournal?): Pair<List<AnswerSnapshot>, FakeCommands> {
            val cmds = FakeCommands()
            val media = FakeMedia()
            val snaps = mutableListOf<AnswerSnapshot>()
            val s = AnswerSession(
                "gso-0123456789", "gate", cmds, media, FakeAudio(), { true }, FakeScheduler(), InMemoryActiveCallStore(), { wall },
                onChange = { snaps += it }, journal = j,
            )
            s.begin()
            wall += 40
            media.calls.single().offerCb!!("v=0 offer")
            wall += 60
            cmds.last("portico/answer").error("not_subscribed")
            return snaps to cmds
        }
        val with = run(journal)
        val without = run(null)
        assertEquals(without.first.map { it.phase to it.failure?.code }, with.first.map { it.phase to it.failure?.code })
        val ls = lines().map { it.substringAfter(' ') }
        assertEquals("ui ans-begin call=gso-01…(14) ms=0 door=yes mic=yes", ls[0])
        assertEquals("ui ans-offer-ask call=gso-01…(14) ms=0 tmo=4000", ls[1])
        assertEquals("ui ans-offer call=gso-01…(14) ms=40 r=ok len=9", ls[2])
        assertEquals("ui ans-claim-send call=gso-01…(14) ms=40 sdp=yes", ls[3])
        assertTrue(ls[4].startsWith("ui ans-claim call=gso-01…(14) ms=100 r=refused-"), ls[4])
        assertTrue(ls.last().startsWith("ui ans-end call=gso-01…(14) ms=100 reason=failed code=not_subscribed"), ls.last())
        lines().forEach { assertTrue(serverKeeps(it), it) }
    }

    private fun action(kind: String, available: Boolean, reason: String? = null, gate: Boolean = false) =
        ContentAction("a", "Talk", kind, null, false, null, available, null, reason, gateCall = gate)

    @Test
    fun `TalkGate why agrees with ready for every combination and names the first cause`() {
        val gate = com.meda.portico.core.content.GateCall("door-1")
        for (kind in listOf("talk", "call", "portico_open")) for (avail in listOf(true, false)) for (reason in listOf(null, "talk_off", "in_call", "connecting", "busy"))
            for (usable in listOf(true, false)) for (g in listOf(null, gate)) {
                val a = action(kind, avail, reason)
                assertEquals(TalkGate.ready(a, usable, g), TalkGate.why(a, usable, g).word == "ready", "$kind $avail $reason $usable $g")
            }
        assertEquals("no-action", TalkGate.why(null, true).word)
        assertEquals("link-not-usable", TalkGate.why(action("talk", true), false).word)
        val off = TalkGate.why(action("talk", false, "talk_off"), true)
        assertEquals("not-available", off.word)
        assertEquals("talk_off", off.reason)
        assertEquals("under-way", TalkGate.why(action("talk", true, "in_call"), true).word)
    }
}
