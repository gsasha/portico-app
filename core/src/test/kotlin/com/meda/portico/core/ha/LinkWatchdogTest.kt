package com.meda.portico.core.ha

import com.meda.portico.core.ha.LinkWatchdog.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-764 aftermath: a stalled link is found in seconds, a quiet healthy one costs one ping. */
class LinkWatchdogTest {
    private val w = LinkWatchdog().also { it.reset(0) }

    @Test
    fun `a link that is talking is never probed`() {
        for (t in 1..100) {
            w.onRx(t * 1_000L)
            assertEquals(Verdict.NONE, w.decide(t * 1_000L + 500, active = false))
        }
    }

    @Test
    fun `idle silence pings at 30 s, active silence at 10 s`() {
        assertEquals(Verdict.NONE, w.decide(29_999, false))
        assertEquals(Verdict.PING, w.decide(30_000, false))
        assertEquals(Verdict.NONE, w.decide(9_999, true))
        assertEquals(Verdict.PING, w.decide(10_000, true))
    }

    @Test
    fun `an unanswered ping is a dead link after 8 s`() {
        w.onPingSent(10_000)
        assertTrue(w.pingOutstanding)
        assertEquals(Verdict.NONE, w.decide(17_999, true))
        assertEquals(Verdict.DEAD, w.decide(18_000, true))
    }

    @Test
    fun `the pong, or any frame, clears the ping`() {
        w.onPingSent(10_000)
        w.onRx(12_000)
        assertFalse(w.pingOutstanding)
        assertEquals(Verdict.NONE, w.decide(21_999, true))
        assertEquals(Verdict.PING, w.decide(22_000, true))
    }

    @Test
    fun `a command with no sign of life for 3 s asks at once, and the deadline shrinks to 4 s`() {
        w.onRx(1_000)
        w.onCommandSent(2_000)
        assertEquals(Verdict.NONE, w.decide(4_999, false))
        assertEquals(Verdict.PING, w.decide(5_000, false))
        w.onPingSent(5_000)
        assertEquals(Verdict.NONE, w.decide(8_999, false))
        assertEquals(Verdict.DEAD, w.decide(9_000, false))
    }

    @Test
    fun `a reply to the command proves the link and a new socket starts clean`() {
        w.onCommandSent(1_000)
        w.onRx(1_500)
        assertFalse(w.commandWaiting)
        w.onPingSent(2_000)
        w.reset(50_000)
        assertFalse(w.pingOutstanding)
        assertEquals(Verdict.NONE, w.decide(50_100, false))
    }

    @Test
    fun `after an Answer press the link is asked every 2 s and one missed ping is dead`() {
        w.onRx(1_000)
        w.answerPressed(1_000)
        assertEquals(Verdict.NONE, w.decide(2_999, false))
        assertEquals(Verdict.PING, w.decide(3_000, false))
        w.onPingSent(3_000)
        assertEquals(Verdict.NONE, w.decide(4_999, false))
        assertEquals(Verdict.DEAD, w.decide(5_000, false))
        w.onRx(4_000) // the pong came in time instead
        assertEquals(Verdict.NONE, w.decide(5_999, false))
    }

    @Test
    fun `the fast window ends at 15 s, edges included`() {
        w.answerPressed(10_000)
        w.onRx(24_900)
        assertTrue(w.isFast(24_999))
        w.onPingSent(24_000) // a ping out for 0.9 s at 24.9 s: still the 2 s fast deadline, not dead
        assertEquals(Verdict.NONE, w.decide(24_900, false))
        w.onRx(24_950)
        assertFalse(w.isFast(25_000))
        assertEquals(Verdict.NONE, w.decide(25_100, false), "at 15.1 s the normal rule: silent 150 ms, nothing to do")
        w.onPingSent(25_100)
        assertEquals(Verdict.NONE, w.decide(30_000, false), "a ping out for 4.9 s is no longer dead (8 s rule)")
        assertEquals(Verdict.DEAD, w.decide(33_100, false))
    }

    @Test
    fun `the fast window survives the reset of a new socket`() {
        w.answerPressed(1_000)
        w.reset(2_000)
        assertTrue(w.isFast(3_000))
        assertEquals(Verdict.PING, w.decide(4_000, false))
    }

    @Test
    fun `a ring starts the fast rule and its end stops it`() {
        w.onRx(1_000)
        assertEquals(null, w.fastReason(1_000))
        w.ringStarted(1_000)
        assertEquals("ring", w.fastReason(1_001))
        assertEquals(Verdict.PING, w.decide(3_000, false), "2 s of silence during a ring")
        w.onPingSent(3_000)
        assertEquals(Verdict.DEAD, w.decide(5_000, false))
        w.ringEnded()
        assertEquals(null, w.fastReason(5_001))
    }

    @Test
    fun `an Answer press outlives the ring for 15 s and wins the reason`() {
        w.ringStarted(1_000)
        w.answerPressed(10_000)
        assertEquals("answer", w.fastReason(10_001))
        w.ringEnded() // answering ends the ring
        assertEquals("answer", w.fastReason(24_999))
        assertEquals(null, w.fastReason(25_000))
    }

    @Test
    fun `a ring that never says it ended cannot keep the fast rule on past the cap`() {
        w.ringStarted(0)
        assertEquals("ring", w.fastReason(LinkWatchdog.RING_CAP_MS - 1))
        assertEquals(null, w.fastReason(LinkWatchdog.RING_CAP_MS))
    }

    @Test
    fun `a ring start asks for proof only of a link quiet for a second, and not twice`() {
        w.onRx(10_000)
        assertFalse(w.needsProof(10_999))
        assertTrue(w.needsProof(11_000))
        w.onPingSent(11_000)
        assertFalse(w.needsProof(12_500), "a ping is already out")
    }

    @Test
    fun `the ring window survives the replacement socket`() {
        w.ringStarted(1_000)
        w.reset(2_000)
        assertEquals("ring", w.fastReason(3_000))
    }

    // ---- DEV-811: the link race (mobile or tailnet links) ----

    @Test
    fun `strict rule on Wi-Fi - the default decide never offers a race`() {
        w.ringStarted(0)
        w.onPingSent(10_000)
        assertEquals(Verdict.NONE, w.decide(11_999, true))
        assertEquals(Verdict.DEAD, w.decide(12_000, true))
    }

    @Test
    fun `mobile in the fast rule - a race at 2 s, dead at 6 s`() {
        w.ringStarted(0)
        w.onPingSent(10_000)
        assertEquals(Verdict.NONE, w.decide(11_999, true, race = true))
        assertEquals(Verdict.RACE, w.decide(12_000, true, race = true))
        assertEquals(Verdict.NONE, w.decide(12_500, true, race = true), "offered once per silence")
        assertEquals(Verdict.NONE, w.decide(15_999, true, race = true))
        assertEquals(Verdict.DEAD, w.decide(16_000, true, race = true))
    }

    @Test
    fun `mobile idle - a race at the normal deadline, dead 4 s later`() {
        w.onPingSent(10_000)
        assertEquals(Verdict.NONE, w.decide(17_999, true, race = true))
        assertEquals(Verdict.RACE, w.decide(18_000, true, race = true))
        assertEquals(Verdict.NONE, w.decide(21_999, true, race = true))
        assertEquals(Verdict.DEAD, w.decide(22_000, true, race = true))
    }

    @Test
    fun `any frame after a race was offered clears the ping and arms a new race`() {
        w.ringStarted(0)
        w.onPingSent(10_000)
        assertEquals(Verdict.RACE, w.decide(12_000, true, race = true))
        w.onRx(12_100)
        assertEquals(Verdict.NONE, w.decide(12_200, true, race = true))
        w.onPingSent(20_000)
        assertEquals(Verdict.RACE, w.decide(22_000, true, race = true))
    }

    @Test
    fun `mayRace false - the long mobile deadline stays, only the race offer is withheld`() {
        w.ringStarted(0)
        w.onPingSent(10_000)
        assertEquals(Verdict.NONE, w.decide(12_000, true, race = true, mayRace = false))
        assertEquals(Verdict.NONE, w.decide(15_999, true, race = true, mayRace = false))
        assertEquals(Verdict.DEAD, w.decide(16_000, true, race = true, mayRace = false))
    }

    @Test
    fun `a ring that starts when an idle ping is 6 to 8 s old still gets its race before the link is called dead`() {
        w.onPingSent(10_000) // idle ping, the normal deadline is 8 s
        assertEquals(Verdict.NONE, w.decide(16_500, true, race = true))
        w.ringStarted(16_500) // the fast rule switches on with the ping already 6.5 s old
        assertEquals(Verdict.RACE, w.decide(16_600, true, race = true))
        assertEquals(Verdict.DEAD, w.decide(16_700, true, race = true))
    }

    @Test
    fun `a swapped-in watchdog inherits the ring and Answer windows`() {
        val next = LinkWatchdog().also { it.reset(100) }
        w.ringStarted(10)
        w.answerPressed(20)
        next.copyWindowFrom(w)
        assertEquals("answer", next.fastReason(30))
        w.ringEnded()
        val third = LinkWatchdog().also { it.copyWindowFrom(w) }
        assertEquals("answer", third.fastReason(30))
        assertTrue(third.fastReason(30 + LinkWatchdog.FAST_WINDOW_MS + 1) == null)
    }
}
