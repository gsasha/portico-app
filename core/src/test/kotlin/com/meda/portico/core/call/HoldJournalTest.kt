package com.meda.portico.core.call

import com.meda.portico.core.call.HoldHarness.Companion.A
import com.meda.portico.core.call.HoldHarness.Companion.B
import com.meda.portico.core.call.HoldHarness.Companion.C
import com.meda.portico.core.call.HoldHarness.Companion.ENT
import com.meda.portico.core.call.HoldHarness.Companion.GATE
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * M7: the push and ring journal tells the hold, switch and resume story (hold requested / applied / refused / rolled back,
 * switch, resume, hold limit, a held call ended, audio focus) - and stays a pure observer: the same flows run through the real
 * coordinator with the journal off, on, with a store that throws and with a clock that throws, and everything observable is
 * identical. `HoldConfig.HOLD_ENABLED` is forced true here, in tests only; with it off nothing is journaled at all.
 */
class HoldJournalTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** One rig: the hold harness with a journal on [store] whose clock is the harness's own wall clock. */
    private class Rig(enabled: Boolean, val store: PushJournalStore?, clockThrows: Boolean = false) {
        lateinit var h: HoldHarness
        val journal = store?.let {
            PushJournal(it, if (clockThrows) ({ error("clock") }) else ({ h.now }), { TimeZone.getTimeZone("UTC") })
        }

        init {
            h = HoldHarness(enabled, journal = journal)
        }

        fun lines(): List<String> = ((store as? InMemoryPushJournalStore)?.text ?: "").lines().drop(1).filter { it.isNotEmpty() && !it.contains(" ui ans-") } // DEV-764: the press/refusal diagnostics are not the hold story

        /** The lines without their time of day. */
        fun words(): List<String> = lines().map { it.substringAfter(' ') }
    }

    private fun on(store: PushJournalStore? = InMemoryPushJournalStore()) = Rig(true, store)

    private val gateA = "door=gate-r…(8) call=gso-aa…(12)"
    private val entB = "door=entran…(12) call=gso-bb…(12)"

    // ---- the story -------------------------------------------------------------------------------------------------

    @Test
    fun `a second answer on top of a live call, then a switch back, then a resume - every step in order with shortened ids`() {
        val r = on()
        val h = r.h
        h.connect(GATE, A)
        assertEquals(emptyList<String>(), r.lines(), "a plain answer says nothing about hold")
        h.connectOnHold(ENT, B)
        assertEquals(
            listOf(
                "00:16:40.000 hold requested $gateA",
                "00:16:40.000 hold applied $gateA",
                "00:16:40.000 hold switched $entB",
            ),
            r.lines(),
        )
        h.now += 30_000
        h.coordinator.resume(A)
        assertEquals(
            listOf(
                "00:17:10.000 hold requested $entB",
                "00:17:10.000 hold applied $entB",
                "00:17:10.000 hold switched $gateA ms=30000",
            ),
            r.lines().drop(3),
        )
        // B (held) is ended by the person; A is live. Then A hangs up and B is resumed as a fresh single call: no live call.
        h.coordinator.hangUp()
        h.now += 1_000
        h.coordinator.resume(B)
        assertEquals(
            listOf("00:17:11.000 hold resumed $entB ms=1000"),
            r.lines().drop(6),
        )
    }

    @Test
    fun `refusals and the rollback are journaled and say why`() {
        // the live call cannot be held to answer a second ring
        run {
            val r = on()
            val a = r.h.connect(GATE, A)
            a.failHold = true
            r.h.ring(ENT, B)
            r.h.coordinator.holdAndAnswer(ENT, B)
            assertEquals(listOf("hold requested $gateA", "hold refused $gateA"), r.words())
        }
        // the live call cannot be held for a switch
        run {
            val r = on()
            r.h.connect(GATE, A)
            val b = r.h.connectOnHold(ENT, B)
            val before = r.lines().size
            b.failHold = true
            r.h.coordinator.resume(A)
            assertEquals(
                listOf("hold requested $entB", "hold refused $entB", "hold resume-refused $gateA result=hold_failed"),
                r.words().drop(before),
            )
        }
        // the held call will not come back: the live one is put back, and the journal says it worked
        run {
            val r = on()
            val a = r.h.connect(GATE, A)
            r.h.connectOnHold(ENT, B)
            val before = r.lines().size
            a.failHold = true
            r.h.coordinator.resume(A)
            assertEquals(
                listOf(
                    "hold requested $entB", "hold applied $entB", "hold rolled-back $entB n=1",
                    "hold resume-refused $gateA result=resume_failed",
                ),
                r.words().drop(before),
            )
        }
        // not held: only the call id is known, shortened
        run {
            val r = on()
            r.h.connect(GATE, A)
            r.h.coordinator.resume("gso-zzzzzzzz")
            assertEquals(listOf("hold resume-refused call=gso-zz…(12) result=not_held"), r.words())
        }
        // the live call is still connecting
        run {
            val r = on()
            r.h.connect(GATE, A)
            r.h.connectOnHold(ENT, B)
            r.h.ring(GATE, C)
            r.h.coordinator.hangUp() // B ends; A is held; nothing live
            r.h.coordinator.press(GATE, C) // C begins (connecting)
            val before = r.lines().size
            r.h.coordinator.resume(A)
            assertEquals(listOf("hold resume-refused $gateA result=live_not_connected"), r.words().drop(before))
        }
    }

    @Test
    fun `the hold limit is journaled with how long the call was held`() {
        val r = on()
        val h = r.h
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val before = r.lines().size
        h.now += HoldConfig.HOLD_LIMIT_MS
        h.sched.advance(HoldConfig.HOLD_LIMIT_MS + 1)
        assertEquals(listOf("hold limit $gateA result=hold_timeout ms=120000"), r.words().drop(before))
    }

    @Test
    fun `a held call ended by the other side, and one ended by the person, are journaled with the reason`() {
        val r = on()
        val h = r.h
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val before = r.lines().size
        h.now += 4_000
        h.coordinator.onCallEvent(CallEvent.Ended("gate-dev", GATE, A, inferred = false))
        assertEquals(listOf("hold held-ended $gateA result=remote_hangup ms=4000"), r.words().drop(before))
        // a live call that ends is not a held end
        h.coordinator.hangUp()
        assertEquals(before + 1, r.lines().size)

        val r2 = on()
        r2.h.connect(GATE, A)
        r2.h.connectOnHold(ENT, B)
        val before2 = r2.lines().size
        r2.h.coordinator.endHeld(A)
        assertEquals(listOf("hold held-ended $gateA result=user_hangup ms=0"), r2.words().drop(before2))
    }

    @Test
    fun `audio focus lost and regained are journaled once each, with how many calls are open`() {
        val r = on()
        val h = r.h
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        val before = r.lines().size
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN) // nothing was lost: nothing to say
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK) // ducking is ignored
        assertEquals(listOf("hold focus-lost n=2", "hold focus-regained n=2"), r.words().drop(before))
    }

    @Test
    fun `audio focus with no call at all says nothing`() {
        val r = on()
        r.h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS)
        r.h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        assertEquals(emptyList<String>(), r.lines())
    }

    @Test
    fun `nothing in the hold lines is more than enum words, numbers, times and shortened ids`() {
        val r = on()
        flowSwitch(r.h)
        val r2 = on()
        flowEnds(r2.h) // a second rig, for the end reasons
        val all = r.lines() + r2.lines()
        assertTrue(all.size >= 15, "the flows journaled the story")
        assertTrue(all.any { it.contains("result=remote_hangup") } && all.any { it.contains("result=hold_timeout") })
        for (l in all) {
            assertTrue(Regex("""\d\d:\d\d:\d\d\.\d\d\d hold [a-z-]+( door=\S+…\(\d+\))?( call=\S+…\(\d+\))?( result=[a-z_]+)?( ms=\d+)?( n=\d+)?""").matches(l), l)
            for (id in listOf(GATE, ENT, A, B, C)) assertFalse(l.contains(id), "$id in $l")
        }
    }

    // ---- inert with the gate off -----------------------------------------------------------------------------------

    @Test
    fun `with hold off no flow journals a single line, and the store is never even written`() {
        val store = InMemoryPushJournalStore()
        val r = Rig(false, store)
        val h = r.h
        h.connect(GATE, A)
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B) // refused: you are already on a call
        h.coordinator.press(ENT, B)
        h.coordinator.resume(A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        h.coordinator.setMuted(true)
        h.coordinator.hangUp()
        assertTrue((store.text ?: "").lines().none { " hold " in it }, "no hold line is written with hold off") // DEV-764: the press diagnostics do write
    }

    @Test
    fun `the gate itself is still compiled off`() {
        assertFalse(HoldConfig.HOLD_ENABLED)
        assertNotNull(HoldHarness(false).coordinator)
    }

    // ---- GOLDEN: the journal is a pure observer --------------------------------------------------------------------

    /** A call-by-call account of one flow: what every press returned, and everything observable about the answer path. */
    private fun account(h: HoldHarness, flow: (HoldHarness, MutableList<String>) -> Unit): List<String> {
        val out = mutableListOf<String>()
        flow(h, out)
        out += h.transcript()
        out += "sessions=" + h.coordinator.sessionsState()
        out += "now=${h.now} ringOffer=${h.coordinator.ringOffer(ENT, "gso-zzzz9999")} quiet=${h.coordinator.thirdRingIsQuiet()}"
        return out
    }

    private fun switchFlow(h: HoldHarness, out: MutableList<String>) {
        val a = h.connect(GATE, A)
        h.ring(ENT, B)
        out += "offer=" + h.coordinator.ringOffer(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        val b = h.finishConnecting(B)
        h.now += 7_000
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS_TRANSIENT)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        h.ring(GATE, C)
        h.coordinator.holdAndAnswer(GATE, C) // both in use
        out += "third quiet=" + h.coordinator.thirdRingIsQuiet()
        a.failHold = true
        h.coordinator.resume(A) { out += "resume A (A cannot come back) -> $it" }
        a.failHold = false
        b.failHold = true
        h.coordinator.resume(A) { out += "resume A (B cannot be held) -> $it" }
        b.failHold = false
        h.now += 5_000
        h.coordinator.resume(A) { out += "resume A -> $it" }
        h.coordinator.resume("gso-zzzzzzzz") { out += "resume unknown -> $it" }
        h.now += HoldConfig.HOLD_LIMIT_MS
        h.sched.advance(HoldConfig.HOLD_LIMIT_MS + 1) // B hits the hold limit
        h.coordinator.setMuted(true)
        h.coordinator.hangUp()
    }

    private fun flowSwitch(h: HoldHarness) = switchFlow(h, mutableListOf())

    private fun endsFlow(h: HoldHarness, out: MutableList<String>) {
        h.connect(GATE, A)
        h.connectOnHold(ENT, B)
        h.now += 2_000
        h.coordinator.onCallEvent(CallEvent.Ended("gate-dev", GATE, A, inferred = false)) // the held call's door hangs up
        h.coordinator.resume(A) { out += "resume ended A -> $it" }
        h.ring(GATE, C)
        h.coordinator.holdAndAnswer(GATE, C)
        h.finishConnecting(C)
        h.coordinator.endHeld(B)
        h.coordinator.hangUpAll()
    }

    private fun flowEnds(h: HoldHarness) = endsFlow(h, mutableListOf())

    private fun failedHoldFlow(h: HoldHarness, out: MutableList<String>) {
        val a = h.connect(GATE, A)
        a.failHold = true
        h.ring(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        out += "state=" + h.coordinator.state().snapshot.failure?.kind
        h.coordinator.hangUp()
    }

    /** Every hold entry point pressed with the gate off: each is refused or ignored, as before M7. */
    private fun offFlow(h: HoldHarness, out: MutableList<String>) {
        h.connect(GATE, A)
        h.ring(ENT, B)
        out += "offer=" + h.coordinator.ringOffer(ENT, B)
        h.coordinator.holdAndAnswer(ENT, B)
        out += "refused=" + h.coordinator.state().snapshot.failure?.kind
        h.coordinator.press(ENT, B)
        h.coordinator.resume(A) { out += "resume -> $it" }
        h.coordinator.endHeld(A)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.LOSS)
        h.coordinator.onAudioFocusChange(AudioFocusPolicy.GAIN)
        h.coordinator.setMuted(true)
        h.coordinator.onCallEvent(CallEvent.Ended("ent-dev", ENT, B, inferred = false))
        h.coordinator.hangUp()
        h.ring(GATE, C)
        h.coordinator.press(GATE, C)
        h.finishConnecting(C)
        h.coordinator.hangUp()
    }

    private val flowsOn: List<(HoldHarness, MutableList<String>) -> Unit> = listOf(::switchFlow, ::endsFlow, ::failedHoldFlow)
    private val flowsOff: List<(HoldHarness, MutableList<String>) -> Unit> = listOf(::offFlow)

    private fun golden(enabled: Boolean): List<List<String>> {
        val results = (if (enabled) flowsOn else flowsOff).map { flow ->
            val base = account(Rig(enabled, null).h, flow)
            val good = Rig(enabled, InMemoryPushJournalStore())
            val withJournal = account(good.h, flow)
            val throwingStore = object : PushJournalStore {
                override fun read(): String? = error("read")
                override fun write(text: String): Boolean = error("write")
            }
            val broken = account(Rig(enabled, throwingStore).h, flow)
            val badClock = account(Rig(enabled, InMemoryPushJournalStore(), clockThrows = true).h, flow)
            assertEquals(base, withJournal, "a journal changes nothing the answer path shows")
            assertEquals(base, broken, "a journal whose store throws on every call changes nothing either")
            assertEquals(base, badClock, "a journal whose clock throws changes nothing either")
            if (enabled) assertTrue(good.lines().isNotEmpty(), "the flow really journaled (the comparison is not vacuous)") else assertEquals(emptyList<String>(), good.lines())
            base
        }
        return results
    }

    @Test
    fun `GOLDEN - with hold on, the answer path is identical with the journal off, on, with a throwing store and a throwing clock`() {
        val g = golden(true)
        assertTrue(g.any { r -> r.any { it.contains("resume A -> SWITCHED") } }, "the flows really switched")
        assertTrue(g.any { r -> r.any { it.contains("-> RESUME_FAILED") } } && g.any { r -> r.any { it.contains("-> HOLD_FAILED") } })
        assertTrue(g.any { r -> r.any { it.contains("HOLD_TIMEOUT") } })
    }

    @Test
    fun `GOLDEN - with hold off, the answer path is identical and the journal stays empty`() {
        val g = golden(false)
        assertTrue(g.single().any { it == "resume -> DISABLED" } && g.single().any { it.startsWith("refused=ANOTHER_CALL") }, "the flow really pressed every entry point")
    }

    @Test
    fun `GOLDEN - the whole story of the switch flow, as the journal tells it`() {
        val r = on()
        switchFlow(r.h, mutableListOf())
        val kinds = r.words().map { it.split(' ')[1] }
        assertEquals(
            listOf(
                "requested", "applied", "switched", // B on top of A
                "focus-lost", "focus-regained",
                "requested", "applied", "rolled-back", "resume-refused", // A cannot come back
                "requested", "refused", "resume-refused", // B cannot be held
                "requested", "applied", "switched", // A live again, B held
                "resume-refused", // unknown call
                "limit", // B was held too long
            ),
            kinds,
        )
    }
}
