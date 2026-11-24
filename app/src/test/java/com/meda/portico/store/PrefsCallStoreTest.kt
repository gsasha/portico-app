package com.meda.portico.store

import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.SocketEvents
import com.meda.portico.core.push.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PrefsCallStoreTest {
    private val prefs = FakeSharedPreferences()
    private fun rec(call: String, state: CallState, door: String = "door", src: Source = Source.FCM) =
        CallRecord(door, call, state, state.seq, 1_000, src, 2_000)

    @Test
    fun `put applies CallStore merge - a record never moves to a lower seq`() {
        val s = PrefsCallStore(prefs)
        s.put(rec("c1", CallState.ENDED))
        s.put(rec("c1", CallState.RINGING, src = Source.WS))
        assertEquals(CallState.ENDED, s.get(CallKey("door", "c1"))?.state)
        s.put(rec("c2", CallState.RINGING))
        s.put(rec("c2", CallState.MISSED))
        assertEquals(CallState.MISSED, s.get(CallKey("door", "c2"))?.state, "RINGING may become MISSED (same seq)")
    }

    @Test
    fun `every put is one synchronous commit, never apply`() {
        val s = PrefsCallStore(prefs)
        s.put(rec("c1", CallState.RINGING))
        s.put(rec("c1", CallState.ANSWERED))
        assertEquals(2, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test
    fun `durable - a new instance on the same prefs (a new process) sees the records`() {
        PrefsCallStore(prefs).put(rec("c1", CallState.RINGING))
        assertEquals(CallState.RINGING, PrefsCallStore(prefs).get(CallKey("door", "c1"))?.state)
    }

    @Test
    fun `bounded to 64 - the least recently written goes first`() {
        val s = PrefsCallStore(prefs)
        for (i in 0 until 70) s.put(rec("c$i", CallState.RINGING))
        assertNull(s.get(CallKey("door", "c5")))
        assertEquals(CallState.RINGING, s.get(CallKey("door", "c6"))?.state)
        assertEquals(CallState.RINGING, s.get(CallKey("door", "c69"))?.state)
    }

    @Test
    fun `latestFor is the door's most recently written record`() {
        val s = PrefsCallStore(prefs)
        s.put(rec("a", CallState.RINGING, door = "d1"))
        s.put(rec("b", CallState.RINGING, door = "d1"))
        s.put(rec("x", CallState.RINGING, door = "d2"))
        assertEquals("b", s.latestFor("d1")?.callId)
        assertNull(s.latestFor("d3"))
    }

    @Test
    fun `a failed commit is reported, and the process still deduplicates`() {
        prefs.commitResult = false
        var failures = 0
        val s = PrefsCallStore(prefs) { failures++ }
        s.put(rec("c1", CallState.RINGING))
        assertEquals(1, failures)
        assertEquals(CallState.RINGING, s.get(CallKey("door", "c1"))?.state)
    }

    @Test
    fun `atomically is process-wide - a second instance (the socket path) cannot interleave`() {
        val push = PrefsCallStore(prefs)
        val socket = PrefsCallStore(prefs) // another instance, as a second code path would hold
        val events = Collections.synchronizedList(ArrayList<String>())
        val aInside = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val a = thread {
            push.atomically {
                events += "A in"
                aInside.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
                events += "A out"
            }
        }
        assertTrue(aInside.await(5, TimeUnit.SECONDS))
        val b = thread { socket.atomically { events += "B in" } }
        Thread.sleep(200)
        assertEquals(listOf("A in"), events.toList(), "B entered while A held the lock")
        releaseA.countDown()
        a.join(5_000)
        b.join(5_000)
        assertEquals(listOf("A in", "A out", "B in"), events.toList())
    }

    @Test
    fun `push and socket racing on the same call - exactly one Ring, every time`() {
        val known = setOf("door")
        repeat(100) { i ->
            val p = FakeSharedPreferences()
            val pushStore = PrefsCallStore(p)
            val socketStore = PrefsCallStore(p)
            val barrier = CyclicBarrier(2)
            val outcomes = Collections.synchronizedList(ArrayList<Outcome>())
            val t1 = thread {
                barrier.await()
                outcomes += Decision.receive(pushStore, PushMessage.Ring("door", "call$i", 1, 1_000, 46_000), 2_000, known)
            }
            val t2 = thread {
                barrier.await()
                outcomes += Decision.receive(socketStore, SocketEvents.ringing("door", "call$i", 2_000), 2_000, known)
            }
            t1.join(5_000)
            t2.join(5_000)
            assertEquals(1, outcomes.count { it is Outcome.Ring }, "iteration $i: $outcomes")
        }
    }
}
