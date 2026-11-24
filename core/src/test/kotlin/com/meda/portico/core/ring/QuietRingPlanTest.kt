package com.meda.portico.core.ring

import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M7 H2 (design 15.13 failure f): a ring that comes while both door sessions are in use is posted quietly, one line, no sound. */
class QuietRingPlanTest {
    private val now = 1_760_000_000_000L
    private val rec = CallRecord("door", "call", CallState.RINGING, 1, now, Source.FCM, now)
    private val ring = Outcome.Ring("door", "call", now + 60_000, rec)

    @Test
    fun `by default a ring is exactly the loud CallStyle ring (the flag is off unless asked)`() {
        val loud = NotificationPlanner.plan(ring, now, true, PermissionState(), null)
        val explicitOff = NotificationPlanner.plan(ring, now, true, PermissionState(), null, quietRing = false)
        assertEquals(loud, explicitOff)
        val s = loud.post!!
        assertEquals(Channel.RING, s.channel)
        assertTrue(s.fullScreen && s.callStyle && s.insistent)
        assertEquals(TextKey.RING_TEXT, s.text)
    }

    @Test
    fun `a quiet ring uses the silent status channel, no full screen, no CallStyle, no insistent sound`() {
        val s = NotificationPlanner.plan(ring, now, true, PermissionState(), null, quietRing = true).post!!
        assertEquals(NoteKind.RING, s.kind)
        assertEquals(Channel.STATUS, s.channel)
        assertEquals(Importance.DEFAULT, s.channel.importance)
        assertFalse(s.fullScreen, "it must not take the screen from the call")
        assertFalse(s.callStyle)
        assertFalse(s.insistent, "no repeating ring sound")
        assertTrue(s.onlyAlertOnce)
        assertEquals(TextKey.RING_TITLE, s.title)
        assertEquals(TextKey.RING_TEXT_BOTH_BUSY, s.text)
    }

    @Test
    fun `a quiet ring still ends like any ring - the same timeout, backstop and call identity`() {
        val loud = NotificationPlanner.plan(ring, now, true, PermissionState(), null).post!!
        val quiet = NotificationPlanner.plan(ring, now, true, PermissionState(), null, quietRing = true).post!!
        assertEquals(loud.timeoutMs, quiet.timeoutMs)
        assertEquals(loud.backstopAtMs, quiet.backstopAtMs)
        assertEquals(loud.doorRef, quiet.doorRef)
        assertEquals(loud.callId, quiet.callId)
        assertTrue(quiet.ongoing)
        assertFalse(quiet.autoCancel)
    }

    @Test
    fun `a quiet ring warns about the status channel, not the ring channel`() {
        val perms = PermissionState(ringChannelEnabled = false, statusChannelEnabled = false)
        val quiet = NotificationPlanner.plan(ring, now, true, perms, null, quietRing = true)
        assertEquals(listOf(PlanWarning.STATUS_CHANNEL_BLOCKED), quiet.warnings)
    }

    @Test
    fun `the quiet flag changes nothing else - a dismiss and a missed call are the same`() {
        val dismiss = Outcome.Dismiss("door", "call", rec.copy(state = CallState.ANSWERED, seq = 2))
        assertEquals(
            NotificationPlanner.plan(dismiss, now, true, PermissionState(), null),
            NotificationPlanner.plan(dismiss, now, true, PermissionState(), null, quietRing = true),
        )
    }

    @Test
    fun `the ring switch still wins - a phone with ringing off posts nothing even when quiet`() {
        val p = NotificationPlanner.plan(ring, now, false, PermissionState(), null, quietRing = true)
        assertNull(p.post)
    }
}
