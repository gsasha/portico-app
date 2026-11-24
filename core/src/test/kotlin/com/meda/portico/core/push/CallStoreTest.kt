package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CallStoreTest {
    private val door = "01JABCDEFGHJKMNPQRSTVWXYZ0"

    private fun rec(callId: String, state: CallState, source: Source = Source.FCM, at: Long = 0) =
        CallRecord(door, callId, state, state.seq, at, source, at)

    @Test
    fun mergeNeverLowersSeq() {
        val s = InMemoryCallStore()
        s.put(rec("c", CallState.ENDED))
        s.put(rec("c", CallState.RINGING, Source.WS))
        s.put(rec("c", CallState.ANSWERED, Source.WS))
        assertEquals(CallState.ENDED, s.get(CallKey(door, "c"))?.state)
        assertEquals(Source.FCM, s.get(CallKey(door, "c"))?.source)
    }

    @Test
    fun mergeOnEqualSeqKeepsTheFirstExceptRingingToMissed() {
        val s = InMemoryCallStore()
        s.put(rec("c", CallState.RINGING, Source.WS))
        s.put(rec("c", CallState.RINGING, Source.FCM))
        assertEquals(Source.WS, s.get(CallKey(door, "c"))?.source)
        s.put(rec("c", CallState.MISSED))
        assertEquals(CallState.MISSED, s.get(CallKey(door, "c"))?.state)
        s.put(rec("c", CallState.RINGING))
        assertEquals(CallState.MISSED, s.get(CallKey(door, "c"))?.state)
    }

    @Test
    fun boundedAtCapacityDroppingTheLeastRecentlyWritten() {
        val s = InMemoryCallStore()
        for (n in 0 until Contract.STORE_CAPACITY) s.put(rec("c$n", CallState.RINGING))
        s.put(rec("c0", CallState.ENDED)) // rewrite c0: now the newest
        s.put(rec("c64", CallState.RINGING))
        assertEquals(Contract.STORE_CAPACITY, s.size)
        assertNull(s.get(CallKey(door, "c1")), "c1 was the oldest write")
        assertNotNull(s.get(CallKey(door, "c0")))
        assertNotNull(s.get(CallKey(door, "c64")))
    }

    @Test
    fun keyIsDoorAndCall() {
        val s = InMemoryCallStore()
        s.put(rec("c", CallState.RINGING))
        assertNull(s.get(CallKey("01JOTHERDOOR00000000000000", "c")))
    }

    @Test
    fun latestForIsTheDoorsMostRecentWrite() {
        val s = InMemoryCallStore()
        s.put(rec("a", CallState.RINGING))
        s.put(rec("b", CallState.RINGING))
        s.put(CallRecord("01JOTHERDOOR00000000000000", "x", CallState.RINGING, 1, 0, Source.FCM, 0))
        assertEquals("b", s.latestFor(door)?.callId)
        assertNull(s.latestFor("01JNOSUCHDOOR0000000000000"))
    }

    @Test
    fun concurrentPathsRingOnce() {
        // The FCM thread and the socket thread receive the same ring at once: exactly one rings.
        repeat(200) { round ->
            val s = InMemoryCallStore()
            val callId = "gso-race-$round"
            val known = setOf(door)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val push = PushMessage.Ring(door, callId, 1, 1_000, 46_000)
            val sock = SocketEvents.ringing(door, callId, 1_000)
            val futures = listOf(push, sock).map { m ->
                pool.submit<Outcome> { start.await(); Decision.receive(s, m, 1_000, known) }
            }
            start.countDown()
            val outcomes = futures.map { it.get(5, TimeUnit.SECONDS) }
            pool.shutdown()
            assertEquals(1, outcomes.count { it is Outcome.Ring }, "round $round: $outcomes")
        }
    }
}
