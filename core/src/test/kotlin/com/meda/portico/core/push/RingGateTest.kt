package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** P2 plan §3.6, §7 Q2: the local "ring for door calls on this phone" switch, at the handler boundary. */
class RingGateTest {
    private val rec = CallRecord("door", "call", CallState.RINGING, 1, 0, Source.FCM, 0)
    private val ring = Outcome.Ring("door", "call", 60_000, rec)
    private val dismiss = Outcome.Dismiss("door", "call", rec.copy(state = CallState.ANSWERED, seq = 2))
    private val missed = Outcome.Missed("door", "call", MissedReason.LATE_RING, rec.copy(state = CallState.MISSED))
    private val silent = Outcome.Silent(SilentReason.STALE)
    private val quiet = Outcome.Quiet(1L)

    @Test
    fun `switch on - every outcome reaches the handler`() {
        for (o in listOf(ring, dismiss, missed, silent, quiet)) assertEquals(true, RingGate.shouldHandle(o, ringOnThisPhone = true), "$o")
    }

    @Test
    fun `switch off - no door-call alert of any kind (ring, answered elsewhere, missed)`() {
        for (o in listOf(ring, dismiss, missed)) assertEquals(false, RingGate.shouldHandle(o, ringOnThisPhone = false), "$o")
    }

    @Test
    fun `switch off - silent and the test notification still reach the handler`() {
        // Silent posts nothing anyway; `test` is not a door call (Q6: a quiet notification).
        assertEquals(true, RingGate.shouldHandle(silent, ringOnThisPhone = false))
        assertEquals(true, RingGate.shouldHandle(quiet, ringOnThisPhone = false))
    }

    @Test
    fun `switch off - the store is still written (gating happens after Decision receive)`() {
        val store = InMemoryCallStore()
        val msg = PushMessage.Ring("door", "call", 1, 1_000, 46_000)
        val outcome = Decision.receive(store, msg, 2_000, setOf("door"))
        assertEquals(false, RingGate.shouldHandle(outcome, ringOnThisPhone = false))
        assertEquals(CallState.RINGING, store.get(CallKey("door", "call"))?.state, "recorded, so a later socket ring stays silent")
    }
}
