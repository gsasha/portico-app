package com.meda.portico.core.call

import com.meda.portico.core.push.HouseStep
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's own Do not disturb for house calls (HC-4): what it reports, and that it is sent only when it should be (M7 3b-B). */
class HouseDndTest {

    @Test
    fun `what is reported - not receiving is Do not disturb, and Android's filter counts only when the person lets Portico read it`() {
        // receive, shareOs, osDnd -> reported
        assertEquals(false, HouseDnd.reported(receiveOn = true, shareOsDnd = true, osDndOn = false))
        assertEquals(true, HouseDnd.reported(receiveOn = true, shareOsDnd = true, osDndOn = true))
        assertEquals(false, HouseDnd.reported(receiveOn = true, shareOsDnd = false, osDndOn = true), "Android's filter is read only through its switch")
        assertEquals(true, HouseDnd.reported(receiveOn = false, shareOsDnd = true, osDndOn = false), "the receive switch (default off): callers are told, not rung into a void")
        assertEquals(true, HouseDnd.reported(receiveOn = false, shareOsDnd = false, osDndOn = false))
    }

    private class Wire {
        val sent = mutableListOf<Boolean>()
        val replies = mutableListOf<(Boolean) -> Unit>()
        var canSend = true
        val send: (Boolean, (Boolean) -> Unit) -> Boolean = { dnd, done ->
            if (!canSend) false else { sent += dnd; replies += done; true }
        }
    }

    private val w = Wire()
    private val rep = HouseDndReporter(w.send)

    @Test
    fun `nothing is sent while house calls are off, however the other inputs stand`() {
        rep.evaluate(enabled = false, connectionReady = true, desired = true)
        rep.evaluate(enabled = false, connectionReady = true, desired = false)
        assertEquals(emptyList<Boolean>(), w.sent, "with the server's switch off the app is silent on the wire")
    }

    @Test
    fun `nothing is sent without a ready connection`() {
        rep.evaluate(true, connectionReady = false, desired = true)
        assertEquals(emptyList<Boolean>(), w.sent)
    }

    @Test
    fun `the first report goes out, a repeat of the same value does not, a change does`() {
        rep.evaluate(true, true, false)
        assertEquals(listOf(false), w.sent)
        w.replies.single()(true)
        assertEquals(false, rep.reportedToServer)
        rep.evaluate(true, true, false)
        assertEquals(1, w.sent.size, "the same value is not sent twice on one connection")
        rep.evaluate(true, true, true)
        assertEquals(listOf(false, true), w.sent)
    }

    @Test
    fun `one in flight at a time, and a wish that changed meanwhile is said when the answer comes`() {
        rep.evaluate(true, true, true)
        rep.evaluate(true, true, false) // changed while the first is in flight: not sent yet
        assertEquals(listOf(true), w.sent)
        w.replies[0](true)
        assertEquals(listOf(true, false), w.sent)
    }

    @Test
    fun `a refusal (not_subscribed) leaves it unsent so the next evaluation tries again`() {
        rep.evaluate(true, true, true)
        w.replies.single()(false)
        assertNull(rep.reportedToServer)
        rep.evaluate(true, true, true)
        assertEquals(listOf(true, true), w.sent)
    }

    @Test
    fun `a closed connection forgets what it told the server - the next one reports again`() {
        rep.evaluate(true, true, true)
        w.replies.single()(true)
        rep.onDisconnected()
        assertNull(rep.reportedToServer)
        rep.evaluate(true, true, true)
        assertEquals(listOf(true, true), w.sent)
    }

    @Test
    fun `a send that could not be made is not left in flight`() {
        w.canSend = false
        rep.evaluate(true, true, true)
        w.canSend = true
        rep.evaluate(true, true, true)
        assertEquals(listOf(true), w.sent)
    }

    // ---- the journal's new lines ------------------------------------------------------------------------------------

    private val store = InMemoryPushJournalStore()
    private val journal = PushJournal(store, { 1_759_700_001_000L }, { TimeZone.getTimeZone("UTC") })

    @Test
    fun `a Do not disturb report is one line with the state and no id`() {
        journal.house(HouseStep.DND, null, null, 1)
        assertTrue(journal.lines().single().endsWith(" house dnd n=1"), journal.lines().single())
    }

    @Test
    fun `the frame audio lines hold numbers, words and a shortened call id only, and pass the reporter's filter`() {
        val flow = FrameStats.Summary(sent = 120, dropped = 3, received = 118, concealed = 9, maxGapMs = 360, underruns = 4, p95GapMs = 100, qMin = 0, qMax = 5, secs = 42)
        val report = FrameReport("hso-1759700000000000000-1-a1b2c3", AudioProfile.L16, flow, 12000, 8000, 0, 0, DeviceReport("on", "on", 45), "none", "none", 40, "none")
        journal.frameAudio(report, "speaker")
        val (frames, dev) = journal.lines().takeLast(2)
        assertTrue(" frames v=2 call=hso-17…(32) codec=l16 rate=16000 secs=42 sent=120 dropped=3 recv=118 underruns=4 maxgap=360ms p95gap=100ms qmin=0 qmax=5" in frames, frames)
        assertTrue(" audio dev call=hso-17…(32) first=40ms uppeak=-9 dnpeak=-12 upclip=0 dnclip=0 readmax=45ms aec=on ns=on route=speaker why=none mark=none fail=none" in dev, dev)
        for (l in listOf(frames, dev)) {
            assertFalse("a1b2c3" in l)
            val body = l.substringAfter(' ')
            assertTrue(body.length <= com.meda.portico.core.connlog.JournalLines.MAX_LINE, "at most 240 characters: ${body.length}")
            assertTrue(com.meda.portico.core.connlog.JournalLines.check(body) is com.meda.portico.core.connlog.JournalLines.Verdict.Keep, body)
        }
        assertFalse(com.meda.portico.core.connlog.JournalLines.endsSession(dev.substringAfter(' ')), "audio dev must not end a session")
    }

    @Test
    fun `at pcmu the clip counts are n-a, and a device that fell back says why`() {
        val flow = FrameStats.Summary(sent = 1, dropped = 0, received = 1, concealed = 0, maxGapMs = 0)
        journal.frameAudio(FrameReport("hso-1759700000000000000-1-a1b2c3", AudioProfile.PCMU, flow, 100, 100, 0, 0, DeviceReport("unavailable", "failed", 3), "prep16", "temp", -1, "noframe"), "ear")
        val (frames, dev) = journal.lines().takeLast(2)
        assertTrue("codec=pcmu rate=8000" in frames, frames)
        assertTrue("first=? " in dev && "upclip=n/a dnclip=n/a" in dev && "aec=unavailable ns=failed route=ear why=prep16 mark=temp fail=noframe" in dev, dev)
        for (l in listOf(frames, dev)) assertTrue(com.meda.portico.core.connlog.JournalLines.check(l.substringAfter(' ')) is com.meda.portico.core.connlog.JournalLines.Verdict.Keep, l)
    }

    @Test
    fun `FrameStats - the first silence is not an underrun, gaps are the largest between two frames, and it is thread-safe enough to sum`() {
        var t = 0L
        val s = FrameStats { t }
        s.onConcealed()
        assertEquals(0L, s.summary().concealed)
        s.onReceived(); t = 40; s.onReceived(); t = 100; s.onReceived()
        assertEquals(60L, s.summary().maxGapMs)
        s.onConcealed()
        s.onSent(true); s.onSent(false)
        val sum = s.summary()
        assertEquals(FrameStats.Summary(sent = 1, dropped = 1, received = 3, concealed = 1, maxGapMs = 60, p95GapMs = 60, qMin = 0, qMax = 0), sum)
    }
}
