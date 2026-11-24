package com.meda.portico.ring

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.Source
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.store.FakeSharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pause clears every ring that is up on this phone, with its backstop alarm, and nothing else. */
class RingPauseTest {
    private val t = 1_760_000_000_000L
    private val events = ArrayList<String>()
    private val poster = object : RingPoster {
        override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) { events += "post ${spec.kind} slot=$slot" }
        override fun cancelRing(slot: Int) { events += "cancel $slot" }
        override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) { events += "arm $slot" }
        override fun cancelBackstop(slot: Int) { events += "disarm $slot" }
    }
    private val lines = ArrayList<String>()
    private val ringState = RingState(FakeSharedPreferences())
    private val handler = AndroidOutcomeHandler(
        poster, ringState, DoorSlots(ringState), { PermissionState() }, { true }, { null }, { t }, HaLog { l, m -> lines += "$l $m" },
    )

    private fun ring(door: String, call: String) =
        handler.onRing(Outcome.Ring(door, call, t + 60_000, CallRecord(door, call, CallState.RINGING, 1, t, Source.FCM, t)))

    @Test
    fun `stopAllRings cancels every door's ring and backstop, and forgets them`() {
        ring("01J9ENTRYGATE", "gso-1760000000000000000-1-a")
        ring("01J9ENTRYFRONT", "gso-1760000000000000000-1-b")
        events.clear()
        assertEquals(2, handler.stopAllRings("paused"))
        assertEquals(setOf("cancel 0", "disarm 0", "cancel 1", "disarm 1"), events.toSet())
        assertFalse(handler.isRinging("01J9ENTRYGATE", "gso-1760000000000000000-1-a"))
        assertFalse(handler.isRinging("01J9ENTRYFRONT", "gso-1760000000000000000-1-b"))
        assertTrue(lines.none { "gso-1760000000000000000-1-a" in it }, "call ids are masked: $lines")
    }

    @Test
    fun `nothing ringing - nothing to cancel`() {
        assertEquals(0, handler.stopAllRings("paused"))
        assertTrue(events.isEmpty())
    }
}
