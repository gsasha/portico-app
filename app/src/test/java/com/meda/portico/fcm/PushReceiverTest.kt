package com.meda.portico.fcm

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Source
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.MissedReason
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.SilentReason
import com.meda.portico.core.push.SocketEvents
import com.meda.portico.core.push.Decision
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import com.meda.portico.store.PrefsDoorsCacheStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The messaging service's decision path, minus Android: an FCM data map -> PushMessage ->
 * Decision.receive on the PERSISTED stores -> the ring-switch gate -> the OutcomeHandler.
 */
class PushReceiverTest {

    class RecordingHandler : OutcomeHandler {
        val calls = ArrayList<String>()
        val outcomes = ArrayList<Outcome>()
        var throwOnRing = false
        override fun onRing(outcome: Outcome.Ring) { calls += "ring"; outcomes += outcome; if (throwOnRing) error("boom") }
        override fun onDismiss(outcome: Outcome.Dismiss) { calls += "dismiss"; outcomes += outcome }
        override fun onMissed(outcome: Outcome.Missed) { calls += "missed"; outcomes += outcome }
        override fun onSilent(outcome: Outcome.Silent) { calls += "silent"; outcomes += outcome }
        override fun onQuiet(outcome: Outcome.Quiet) { calls += "quiet"; outcomes += outcome }
    }

    private val door = "01J9ENTRYGATE"
    private val t = 1_760_000_000_000L
    private val callsPrefs = FakeSharedPreferences()
    private val statePrefs = FakeSharedPreferences().also {
        // A previous process cached portico/doors; THIS process is cold (a push woke it).
        PrefsDoorsCacheStore(it).save(listOf(Door("gate-sip.example", door)))
    }
    private val doors = DoorsCache(PrefsDoorsCacheStore(statePrefs))
    private val store = PrefsCallStore(callsPrefs)
    private val handler = RecordingHandler()
    private var wakes = 0
    private var ringOn = true
    private val logs = ArrayList<String>()
    private val log = HaLog { level, msg -> logs += "$level $msg" }
    private var doorRings = 0
    private val dispatcher = OutcomeDispatcher(handler, { wakes++ }, { ringOn }, log, onDoorRing = { doorRings++ })
    private val receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log)

    private fun ring(call: String = "c1", exp: Long = t + 45_000) =
        mapOf("pv" to "2", "kind" to "ring", "door_ref" to door, "call_id" to call, "seq" to "1", "t" to "$t", "exp" to "$exp")
    private fun answered(call: String = "c1") =
        mapOf("pv" to "2", "kind" to "answered", "door_ref" to door, "call_id" to call, "seq" to "2", "t" to "${t + 5_000}", "by" to "other")
    private fun ended(call: String = "c1") =
        mapOf("pv" to "2", "kind" to "ended", "door_ref" to door, "call_id" to call, "seq" to "3", "t" to "${t + 9_000}")

    @Test
    fun `ring for a known door (cold process, persisted cache) rings and wakes the socket once`() {
        val o = receiver.onMessage(ring(), t + 1_000)
        assertTrue(o is Outcome.Ring, "$o")
        assertEquals(listOf("ring"), handler.calls)
        assertEquals(1, wakes)
        assertEquals(CallState.RINGING, store.get(CallKey(door, "c1"))?.state, "recorded before the handler ran")
    }

    @Test
    fun `answered after ring dismisses, without waking the socket again`() {
        receiver.onMessage(ring(), t + 1_000)
        receiver.onMessage(answered(), t + 6_000)
        assertEquals(listOf("ring", "dismiss"), handler.calls)
        assertEquals(1, wakes)
    }

    @Test
    fun `ended after ring is a missed call`() {
        receiver.onMessage(ring(), t + 1_000)
        receiver.onMessage(ended(), t + 10_000)
        assertEquals(listOf("ring", "missed"), handler.calls)
        assertEquals(MissedReason.ENDED_UNANSWERED, (handler.outcomes[1] as Outcome.Missed).reason)
    }

    @Test
    fun `a duplicate ring is silent`() {
        receiver.onMessage(ring(), t + 1_000)
        receiver.onMessage(ring(), t + 2_000)
        assertEquals(listOf("ring", "silent"), handler.calls)
        assertEquals(SilentReason.STALE, (handler.outcomes[1] as Outcome.Silent).reason)
    }

    @Test
    fun `unknown door is silent - no ring, no wake, nothing recorded`() {
        receiver.onMessage(ring() + ("door_ref" to "01J9SOMEOTHERDOOR"), t + 1_000)
        assertEquals(listOf("silent"), handler.calls)
        assertEquals(SilentReason.UNKNOWN_DOOR, (handler.outcomes.single() as Outcome.Silent).reason)
        assertEquals(0, wakes)
    }

    @Test
    fun `late ring (past exp + 15 s) is a missed call, no ring, no wake`() {
        receiver.onMessage(ring(exp = t + 45_000), t + 45_000 + 15_001)
        assertEquals(listOf("missed"), handler.calls)
        assertEquals(MissedReason.LATE_RING, (handler.outcomes.single() as Outcome.Missed).reason)
        assertEquals(0, wakes)
    }

    @Test
    fun `ring inside the skew allowance still rings`() {
        receiver.onMessage(ring(exp = t + 45_000), t + 45_000 + 15_000)
        assertEquals(listOf("ring"), handler.calls)
    }

    @Test
    fun `kind test posts the quiet notification, never a ring`() {
        receiver.onMessage(mapOf("pv" to "2", "kind" to "test", "t" to "$t"), t)
        assertEquals(listOf("quiet"), handler.calls)
        assertEquals(0, wakes)
    }

    @Test
    fun `unknown pv and garbage are silent`() {
        receiver.onMessage(ring() + ("pv" to "3"), t)
        receiver.onMessage(mapOf("from" to "x"), t)
        assertEquals(listOf("silent", "silent"), handler.calls)
    }

    @Test
    fun `ring switch off - nothing alerts, nothing wakes, but the store records the call`() {
        ringOn = false
        receiver.onMessage(ring(), t + 1_000)
        receiver.onMessage(answered(), t + 6_000)
        receiver.onMessage(ring("c2", exp = t), t + 30_000) // late -> Missed, suppressed too
        assertEquals(emptyList<String>(), handler.calls)
        assertEquals(0, wakes)
        assertEquals(CallState.ANSWERED, store.get(CallKey(door, "c1"))?.state)
        assertTrue(logs.any { "ring for door calls on this phone is off" in it }, "$logs")
    }

    @Test
    fun `a door ring that alerts outranks an outgoing call once - a repeat of the same ring does not`() {
        receiver.onMessage(ring(), t + 1_000)
        assertEquals(1, doorRings)
        // Same call again (duplicate / post-reconnect snapshot): decided Silent, so a live house call is left alone.
        val sock = Decision.receive(store, SocketEvents.ringing(door, "c1", t + 2_000), t + 2_000, doors.knownDoorRefs())
        dispatcher.dispatch(sock)
        assertEquals(1, doorRings)
    }

    @Test
    fun `a ring that does not alert never outranks an outgoing call`() {
        ringOn = false
        receiver.onMessage(ring(), t + 1_000)
        ringOn = true
        receiver.onMessage(ring("c2", exp = t), t + 30_000) // late -> Missed, not a ring
        receiver.onMessage(answered("c1"), t + 6_000)
        assertEquals(0, doorRings)
    }

    @Test
    fun `a throwing door-outranks hook still lets the ring reach the handler`() {
        val d = OutcomeDispatcher(handler, { wakes++ }, { true }, log, onDoorRing = { error("boom") })
        d.dispatch(Outcome.Ring(door, "c9", t + 60_000, CallRecord(door, "c9", CallState.RINGING, 1, t, Source.WS, t)))
        assertEquals(listOf("ring"), handler.calls)
    }

    @Test
    fun `ring switch off - a later socket ring for the same call stays silent (recorded)`() {
        ringOn = false
        receiver.onMessage(ring(), t + 1_000)
        ringOn = true
        val o = Decision.receive(store, SocketEvents.ringing(door, "c1", t + 2_000), t + 2_000, doors.knownDoorRefs())
        assertEquals(Outcome.Silent(SilentReason.STALE), o)
    }

    @Test
    fun `a socket ring dispatched through the same dispatcher does not wake the socket`() {
        val o = Decision.receive(store, SocketEvents.ringing(door, "c7", t), t, doors.knownDoorRefs())
        dispatcher.dispatch(o)
        assertEquals(listOf("ring"), handler.calls)
        assertEquals(0, wakes)
    }

    @Test
    fun `a throwing handler never escapes (the service must not crash) and the wake still happened`() {
        handler.throwOnRing = true
        receiver.onMessage(ring(), t + 1_000)
        assertEquals(1, wakes)
        assertTrue(logs.any { it.startsWith("ERROR") && "IllegalStateException" in it }, "$logs")
    }

    @Test
    fun `priority is journalled, and a demoted message says so`() {
        receiver.onMessage(ring(), t + 1_000, PushReceiver.PRIORITY_HIGH, PushReceiver.PRIORITY_HIGH)
        receiver.onMessage(ring("c2"), t + 1_000, PushReceiver.PRIORITY_NORMAL, PushReceiver.PRIORITY_HIGH)
        assertTrue(logs.any { "c1" in it && "priority=high" in it }, "$logs")
        assertTrue(logs.any { "c2" in it && "DEMOTED" in it }, "$logs")
    }
}
