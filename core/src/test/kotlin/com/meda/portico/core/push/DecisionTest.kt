package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The extra cases of P2 plan §5.1-2 and the v1.1 decisions (Q5, Q6), beyond the contract file. */
class DecisionTest {
    private val door = "01JABCDEFGHJKMNPQRSTVWXYZ0"
    private val known = setOf(door)
    private val call = "gso-1759700000000000000-1-a1b2c3"
    private val t0 = 1_759_700_000_000L
    private val exp = t0 + 45_000

    private val store = InMemoryCallStore()

    private fun ring(callId: String = call, t: Long = t0, e: Long = exp, doorRef: String = door) = mapOf(
        "pv" to "2", "kind" to "ring", "door_ref" to doorRef, "call_id" to callId,
        "seq" to "1", "t" to t.toString(), "exp" to e.toString(),
    )

    private fun answered(callId: String = call) = mapOf(
        "pv" to "2", "kind" to "answered", "door_ref" to door, "call_id" to callId,
        "seq" to "2", "t" to (t0 + 5_000).toString(), "by" to "other",
    )

    private fun ended(callId: String = call) = mapOf(
        "pv" to "2", "kind" to "ended", "door_ref" to door, "call_id" to callId,
        "seq" to "3", "t" to (t0 + 9_000).toString(),
    )

    private fun push(data: Map<String, String>, now: Long, doors: Set<String> = known): Outcome =
        Decision.receive(store, PushMessage.parse(data), now, doors)

    private fun ws(msg: PushMessage, now: Long): Outcome = Decision.receive(store, msg, now, known)

    private fun state(callId: String = call) = store.get(CallKey(door, callId))?.state

    // ---- expiry, skew --------------------------------------------------------------------------

    @Test
    fun ringAtExactlyExpPlusSkewRings() {
        assertEquals(15_000L, Contract.SKEW_MS) // spec §4.3, pinned literally
        val o = push(ring(), now = exp + Contract.SKEW_MS)
        assertInstanceOf(Outcome.Ring::class.java, o)
        assertEquals(exp + Contract.SKEW_MS, (o as Outcome.Ring).ringUntilMs)
        assertEquals(CallState.RINGING, state())
    }

    @Test
    fun ringOneMsPastExpPlusSkewIsMissedAndRecorded() {
        val o = push(ring(), now = exp + Contract.SKEW_MS + 1)
        assertEquals(MissedReason.LATE_RING, (o as Outcome.Missed).reason)
        assertEquals(CallState.MISSED, state())
    }

    @Test
    fun aDuplicateLateRingPostsMissedOnlyOnce() {
        push(ring(), now = exp + 60_000)
        assertEquals(Outcome.Silent(SilentReason.STALE), push(ring(), now = exp + 61_000).withoutUpdate())
    }

    @Test
    fun answeredOrEndedAfterALateRingStaySilent() {
        push(ring(), now = exp + 60_000)
        assertEquals("silent", push(answered(), now = exp + 61_000).contractName)
        assertEquals("silent", push(ended(), now = exp + 62_000).contractName)
        assertEquals(CallState.ENDED, state())
    }

    // ---- duplicates, ordering ------------------------------------------------------------------

    @Test
    fun duplicateRingIsSilent() {
        assertEquals("ring", push(ring(), t0 + 1_000).contractName)
        assertEquals(Outcome.Silent(SilentReason.STALE), push(ring(), t0 + 2_000).withoutUpdate())
    }

    @Test
    fun outOfOrderEndedAnsweredRingStaysSilent() {
        assertEquals(Outcome.Silent(SilentReason.ENDED_NO_RING), push(ended(), t0 + 10_000).withoutUpdate())
        assertEquals(Outcome.Silent(SilentReason.STALE), push(answered(), t0 + 10_500).withoutUpdate())
        assertEquals(Outcome.Silent(SilentReason.STALE), push(ring(), t0 + 11_000).withoutUpdate())
        assertEquals(CallState.ENDED, state())
    }

    @Test
    fun endedThenLateRingIsSilentNotMissed() {
        push(ended(), t0 + 10_000)
        // Past exp + skew too: the terminal record wins over the late-ring "Missed call".
        assertEquals("silent", push(ring(), exp + 60_000).contractName)
    }

    @Test
    fun answeredByAnotherPhoneDismissesTheRing() {
        push(ring(), t0 + 1_000)
        val o = push(answered(), t0 + 6_000)
        assertInstanceOf(Outcome.Dismiss::class.java, o)
        assertEquals(CallState.ANSWERED, state())
        assertEquals("silent", push(ended(), t0 + 9_500).contractName)
        assertEquals(CallState.ENDED, state())
    }

    @Test
    fun endedWhileRingingIsMissedUnanswered() {
        push(ring(), t0 + 1_000)
        val o = push(ended(), t0 + 9_500) as Outcome.Missed
        assertEquals(MissedReason.ENDED_UNANSWERED, o.reason)
    }

    @Test
    fun aSecondCallOnTheSameDoorRings() {
        push(ring(), t0 + 1_000)
        push(ended(), t0 + 9_500)
        val second = "gso-1759700000000000000-2-d4e5f6"
        assertEquals("ring", push(ring(second, t0 + 20_000, t0 + 65_000), t0 + 21_000).contractName)
    }

    // ---- v1.1 (Q5, Q6) -------------------------------------------------------------------------

    @Test
    fun unknownDoorRefIsSilentAndNotRecorded() {
        val other = "01JZZZZZZZZZZZZZZZZZZZZZZZ"
        assertEquals(Outcome.Silent(SilentReason.UNKNOWN_DOOR), push(ring(doorRef = other), t0 + 1_000))
        assertNull(store.get(CallKey(other, call)))
    }

    @Test
    fun anEmptyDoorCacheSilencesEveryRing() {
        // Q5 (a): silent, the app keeps the cache fresh. Recorded here so the risk stays visible.
        assertEquals(Outcome.Silent(SilentReason.UNKNOWN_DOOR), push(ring(), t0 + 1_000, doors = emptySet()))
    }

    @Test
    fun endedWithNoRingSeenIsSilentAndRecorded() {
        // v1.1 (Q6), also contract case "ended with no ring seen"; this one checks the store too.
        val o = push(ended(), t0 + 10_000)
        assertEquals(SilentReason.ENDED_NO_RING, (o as Outcome.Silent).reason)
        assertEquals(CallState.ENDED, state())
    }

    @Test
    fun testKindIsAQuietNotificationAndTouchesNoCall() {
        // v1.1 (Q6): `test` posts a quiet notification, never a ring (contract case "test").
        val o = push(mapOf("pv" to "2", "kind" to "test", "t" to "1759700000000"), t0)
        assertEquals(Outcome.Quiet(t0), o)
        assertEquals("quiet", o.contractName)
        assertEquals(0, store.size)
    }

    @Test
    fun unknownPvIsSilent() {
        val o = push(ring() + ("pv" to "3"), t0 + 1_000)
        assertEquals(Outcome.Silent(SilentReason.IGNORED), o)
        assertEquals(0, store.size)
    }

    @Test
    fun malformedAndUnknownKindAreSilent() {
        assertEquals("silent", push(ring() - "call_id", t0).contractName)
        assertEquals("silent", push(ring() + ("kind" to "reboot"), t0).contractName)
        assertEquals("silent", push(ring() - "exp", t0).contractName)
        assertEquals(0, store.size)
    }

    // ---- both paths, one store -----------------------------------------------------------------

    @Test
    fun socketRingThenPushRingRingsOnce() {
        assertEquals("ring", ws(SocketEvents.ringing(door, call, t0 + 200), t0 + 200).contractName)
        assertEquals(Outcome.Silent(SilentReason.STALE), push(ring(), t0 + 1_000).withoutUpdate())
        assertEquals(Source.WS, store.get(CallKey(door, call))?.source)
    }

    @Test
    fun pushRingThenSocketRingDoesNotPostAgain() {
        assertEquals("ring", push(ring(), t0 + 1_000).contractName)
        assertEquals("silent", ws(SocketEvents.ringing(door, call, t0 + 1_200), t0 + 1_200).contractName)
        assertEquals(Source.FCM, store.get(CallKey(door, call))?.source)
    }

    @Test
    fun socketRingIsNeverLateAndGetsTheDefaultWindowCap() {
        val now = exp + 10 * 60_000 // a socket ring has no exp; the clock alone cannot make it late
        val o = ws(SocketEvents.ringing(door, call, now), now) as Outcome.Ring
        assertEquals(now + Contract.DEFAULT_RING_WINDOW_S * 1000L + Contract.SKEW_MS, o.ringUntilMs)
    }

    @Test
    fun socketAnsweredDismissesAPushRingAndLatePushAnsweredIsSilent() {
        push(ring(), t0 + 1_000)
        assertInstanceOf(Outcome.Dismiss::class.java, ws(SocketEvents.answered(door, call, t0 + 5_100), t0 + 5_100))
        assertEquals("silent", push(answered(), t0 + 6_000).contractName)
    }

    @Test
    fun g1FallbackIdleEndsTheDoorsRingingCall() {
        ws(SocketEvents.ringing(door, call, t0 + 200), t0 + 200)
        val ended = SocketEvents.idle(store, door, t0 + 9_000)
        assertEquals(SocketEvents.ended(door, call, t0 + 9_000), ended)
        assertEquals("missed", ws(ended!!, t0 + 9_000).contractName)
        // A second idle has nothing ringing to end; a late push `ended` is silent.
        assertNull(SocketEvents.idle(store, door, t0 + 9_500))
        assertEquals("silent", push(ended(), t0 + 10_000).contractName)
    }

    @Test
    fun g1FallbackWithNothingRingingIsNull() {
        assertNull(SocketEvents.idle(store, door, t0))
        push(ring(), t0 + 1_000)
        push(answered(), t0 + 6_000)
        assertNull(SocketEvents.idle(store, door, t0 + 9_000))
    }

    private fun Outcome.withoutUpdate(): Outcome = if (this is Outcome.Silent) copy(update = null) else this

    // ---- P3-C: a dialled call never rings, by push either ----------------------------------------------------

    private val dialled = "out-0123456789abcdef"

    @Test
    fun aPushedRingForADialledCallIsSilentAndRecordsNothing() {
        val o = push(ring(dialled), now = t0 + 1_000)
        assertEquals(Outcome.Silent(SilentReason.OUTBOUND_CALL), o)
        assertNull(state(dialled), "no record: no later message can turn it into a Missed call")
    }

    @Test
    fun aLateRingAnAnswerAndAnEndForADialledCallAreAllSilent() {
        assertEquals(Outcome.Silent(SilentReason.OUTBOUND_CALL), push(ring(dialled), now = exp + 60_000), "late: not a Missed call")
        assertEquals(Outcome.Silent(SilentReason.OUTBOUND_CALL), push(answered(dialled), now = t0 + 6_000))
        assertEquals(Outcome.Silent(SilentReason.OUTBOUND_CALL), push(ended(dialled), now = t0 + 9_000), "not a Missed call")
        assertNull(state(dialled))
    }

    @Test
    fun aDialledCallAndARealOneOnTheSameDoorAreIndependent() {
        assertInstanceOf(Outcome.Ring::class.java, push(ring(), now = t0 + 1_000))
        assertEquals(Outcome.Silent(SilentReason.OUTBOUND_CALL), push(ring(dialled), now = t0 + 1_500))
        assertEquals(CallState.RINGING, state(), "the real ring is untouched")
        assertInstanceOf(Outcome.Dismiss::class.java, push(answered(), now = t0 + 6_000))
    }

    @Test
    fun anIdThatOnlyContainsOutDashStillRings() {
        assertInstanceOf(Outcome.Ring::class.java, push(ring("gso-out-1"), now = t0 + 1_000))
    }
}
