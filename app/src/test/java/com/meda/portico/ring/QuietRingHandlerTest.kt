package com.meda.portico.ring

import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.fcm.OutcomeDispatcher
import com.meda.portico.fcm.PushReceiver
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M7 H2: the ring that comes while both door sessions are in use is posted quietly (design 15.13 failure f). The same path as
 * [AndroidOutcomeHandlerTest]; `quietRing` is the coordinator's `thirdRingIsQuiet`, false for ever while hold is off.
 */
class QuietRingHandlerTest {
    private val t = 1_760_000_000_000L
    private val door = "01J9ENTRYGATEXXXXXXXXXXXXX"
    private val call = "gso-1760000000000-12-a1b2c3d4e5f6"

    private class RecordingPoster : RingPoster {
        val events = ArrayList<String>()
        val posted = ArrayList<NotificationSpec>()
        override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) {
            events += "post ${spec.kind} slot=$slot name=$doorName"
            posted += spec
        }

        override fun cancelRing(slot: Int) { events += "cancel $slot" }
        override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) { events += "arm $slot at=$atMs" }
        override fun cancelBackstop(slot: Int) { events += "disarm $slot" }
    }

    private val log = HaLog { _, _ -> }
    private val poster = RecordingPoster()
    private val ringState = RingState(FakeSharedPreferences())
    private var quiet = false
    private var quietThrows = false
    private val now = t
    private val handler = AndroidOutcomeHandler(
        poster, ringState, DoorSlots(ringState), { PermissionState() }, { true },
        { if (it == door) "Gate" else null }, { now }, log,
        quietRing = { if (quietThrows) throw IllegalStateException("boom") else quiet },
    )
    private val store = PrefsCallStore(FakeSharedPreferences())
    private val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door("gate-sip", door, driver = "gate_sip_observer", name = "Gate"))))
    private val dispatcher = OutcomeDispatcher(handler, { }, { true }, log)
    private val receiver = PushReceiver(store, doors::knownDoorRefs, dispatcher, log)

    private fun message(kind: String, seq: Int, callId: String = call, extra: Map<String, String> = emptyMap()) =
        mapOf("pv" to "2", "kind" to kind, "door_ref" to door, "call_id" to callId, "seq" to "$seq", "t" to "$t") + extra

    private fun deliver(kind: String, seq: Int, callId: String = call, extra: Map<String, String> = emptyMap()) =
        receiver.onMessage(message(kind, seq, callId, extra), now)

    private val ringExp = mapOf("exp" to "${t + 45_000}")

    @Test
    fun `the handler built without the flag rings loudly, as before`() {
        val plain = AndroidOutcomeHandler(poster, ringState, DoorSlots(ringState), { PermissionState() }, { true }, { "Gate" }, { now }, log)
        val d = OutcomeDispatcher(plain, { }, { true }, log)
        PushReceiver(store, doors::knownDoorRefs, d, log).onMessage(message("ring", 1, extra = ringExp), now)
        assertEquals(Channel.RING, poster.posted.single().channel)
        assertTrue(poster.posted.single().insistent)
    }

    @Test
    fun `with two calls in use a new ring is posted on the silent channel, still armed and still stoppable`() {
        quiet = true
        deliver("ring", 1, extra = ringExp)
        assertEquals(listOf("arm 0 at=${t + 60_000}", "post RING slot=0 name=Gate"), poster.events)
        val s = poster.posted.single()
        assertEquals(Channel.STATUS, s.channel)
        assertFalse(s.fullScreen)
        assertFalse(s.insistent)
        assertTrue(handler.isRinging(door, call), "the ring is tracked like any ring")
        poster.events.clear()
        handler.stopRing(door, call, "declined")
        assertEquals(listOf("cancel 0", "disarm 0"), poster.events)
    }

    @Test
    fun `when the flag turns off the next ring is loud again`() {
        quiet = true
        deliver("ring", 1, extra = ringExp)
        handler.stopRing(door, call, "declined")
        poster.posted.clear()
        quiet = false
        deliver("ring", 1, callId = "gso-1760000000001-13-b1b2b3b4b5b6", extra = ringExp)
        assertEquals(Channel.RING, poster.posted.single().channel)
    }

    @Test
    fun `a flag that throws never costs the ring - it rings loudly`() {
        quietThrows = true
        deliver("ring", 1, extra = ringExp)
        assertEquals(Channel.RING, poster.posted.single().channel)
    }

    @Test
    fun `answered elsewhere is the same quiet or not`() {
        quiet = true
        deliver("ring", 1, extra = ringExp)
        poster.events.clear()
        deliver("answered", 2, extra = mapOf("by" to "other"))
        assertEquals(listOf("cancel 0", "disarm 0", "post ANSWERED_ELSEWHERE slot=0 name=Gate"), poster.events)
    }
}
