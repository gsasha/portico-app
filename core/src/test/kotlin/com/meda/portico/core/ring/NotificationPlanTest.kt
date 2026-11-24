package com.meda.portico.core.ring

import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallRecord
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.InMemoryCallStore
import com.meda.portico.core.push.MissedReason
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.SilentReason
import com.meda.portico.core.push.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P2 plan §3.3-§3.4, spec §4.2-§4.3: each Outcome -> what the phone posts. */
class NotificationPlanTest {
    private val now = 1_760_000_000_000L
    private val key = CallKey("door", "call")
    private val rec = CallRecord("door", "call", CallState.RINGING, 1, now, Source.FCM, now)
    private val ring = Outcome.Ring("door", "call", now + 60_000, rec)
    private val dismiss = Outcome.Dismiss("door", "call", rec.copy(state = CallState.ANSWERED, seq = 2))
    private val missedEnded = Outcome.Missed("door", "call", MissedReason.ENDED_UNANSWERED, rec.copy(state = CallState.ENDED, seq = 3))
    private val missedLate = Outcome.Missed("door", "call", MissedReason.LATE_RING, rec.copy(state = CallState.MISSED))
    private val ok = PermissionState()

    private fun plan(o: Outcome, ringOn: Boolean = true, perms: PermissionState = ok, last: LastStatus? = null) =
        NotificationPlanner.plan(o, now, ringOn, perms, last)

    @Test
    fun `ring - CallStyle with full-screen intent on the HIGH ring channel, insistent, ongoing, capped twice`() {
        val p = plan(ring)
        val s = p.post!!
        assertEquals(NoteKind.RING, s.kind)
        assertEquals(Channel.RING, s.channel)
        assertEquals("portico_ring_v2", s.channel.id)
        assertEquals(Importance.HIGH, s.channel.importance)
        assertTrue(s.fullScreen && s.callStyle && s.insistent && s.ongoing)
        assertFalse(s.autoCancel || s.onlyAlertOnce)
        assertEquals(60_000L, s.timeoutMs, "setTimeoutAfter(ringUntilMs - now)")
        assertEquals(now + 60_000, s.backstopAtMs, "AlarmManager cancel at ringUntilMs")
        assertEquals(TextKey.RING_TITLE, s.title)
        assertEquals("door", s.doorRef)
        assertEquals("call", s.callId)
        assertNull(p.cancelRing)
        assertNull(p.recordStatus)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `ring whose window already passed on this clock still gets a positive timeout`() {
        val p = NotificationPlanner.plan(ring, now + 120_000, true, ok, null)
        assertEquals(NotificationPlanner.MIN_RING_TIMEOUT_MS, p.post!!.timeoutMs)
    }

    @Test
    fun `dismiss - stop the ring, Answered elsewhere on the quiet status channel for about 10 s`() {
        val p = plan(dismiss)
        assertEquals(key, p.cancelRing)
        val s = p.post!!
        assertEquals(NoteKind.ANSWERED_ELSEWHERE, s.kind)
        assertEquals(Channel.STATUS, s.channel)
        assertEquals("portico_status_v1", s.channel.id)
        assertEquals(Importance.DEFAULT, s.channel.importance, "DEFAULT, never LOW (the Silent group hides it)")
        assertEquals(10_000L, s.timeoutMs)
        assertFalse(s.fullScreen || s.callStyle || s.insistent || s.ongoing)
        assertTrue(s.autoCancel)
        assertNull(s.backstopAtMs)
        assertEquals(TextKey.ANSWERED_TITLE, s.title)
        assertEquals(LastStatus(key, NoteKind.ANSWERED_ELSEWHERE), p.recordStatus)
    }

    @Test
    fun `missed - ended unanswered stops the ring and posts a quiet Missed call that stays`() {
        val p = plan(missedEnded)
        assertEquals(key, p.cancelRing)
        val s = p.post!!
        assertEquals(NoteKind.MISSED, s.kind)
        assertEquals(Channel.STATUS, s.channel)
        assertNull(s.timeoutMs, "a missed call is kept until the user clears it")
        assertTrue(s.autoCancel)
        assertFalse(s.fullScreen || s.callStyle || s.insistent || s.ongoing)
        assertEquals(LastStatus(key, NoteKind.MISSED), p.recordStatus)
    }

    @Test
    fun `missed - a late ring never rings, posts the same quiet Missed call`() {
        val p = plan(missedLate)
        val s = p.post!!
        assertEquals(NoteKind.MISSED, s.kind)
        assertFalse(s.fullScreen)
        assertEquals(Channel.STATUS, s.channel)
        assertEquals(key, p.cancelRing, "harmless: the app cancels only if THIS call is the one ringing")
    }

    @Test
    fun `silent after terminal - re-posts this call's last status with onlyAlertOnce, nothing new`() {
        val endedAfterAnswered = Outcome.Silent(SilentReason.AFTER_TERMINAL, rec.copy(state = CallState.ENDED, seq = 3))
        val p = plan(endedAfterAnswered, last = LastStatus(key, NoteKind.ANSWERED_ELSEWHERE))
        val s = p.post!!
        assertEquals(NoteKind.ANSWERED_ELSEWHERE, s.kind)
        assertEquals(Channel.STATUS, s.channel)
        assertTrue(s.onlyAlertOnce)
        assertEquals(NotificationPlanner.DISMISS_TIMEOUT_MS, s.timeoutMs)
        assertNull(p.cancelRing)

        val afterLate = Outcome.Silent(SilentReason.AFTER_TERMINAL, rec.copy(state = CallState.ANSWERED, seq = 2))
        val q = plan(afterLate, last = LastStatus(key, NoteKind.MISSED)).post!!
        assertEquals(NoteKind.MISSED, q.kind)
        assertTrue(q.onlyAlertOnce)
    }

    @Test
    fun `silent after terminal - nothing when the last status belongs to another call or is unknown`() {
        val o = Outcome.Silent(SilentReason.AFTER_TERMINAL, rec.copy(state = CallState.ENDED, seq = 3))
        assertTrue(plan(o, last = null).postsNothing)
        assertTrue(plan(o, last = LastStatus(CallKey("door", "other"), NoteKind.ANSWERED_ELSEWHERE)).postsNothing)
    }

    @Test
    fun `every other silent posts nothing and stops nothing`() {
        for (r in SilentReason.values().filter { it != SilentReason.AFTER_TERMINAL }) {
            val o = Outcome.Silent(r, rec.copy(state = CallState.ANSWERED, seq = 2))
            assertTrue(plan(o, last = LastStatus(key, NoteKind.ANSWERED_ELSEWHERE)).postsNothing, "$r")
        }
        assertTrue(plan(Outcome.Silent(SilentReason.IGNORED)).postsNothing)
    }

    @Test
    fun `quiet test - one quiet status notification, never a ring or a full-screen intent`() {
        val p = plan(Outcome.Quiet(now))
        val s = p.post!!
        assertEquals(NoteKind.TEST, s.kind)
        assertEquals(Channel.STATUS, s.channel)
        assertFalse(s.fullScreen || s.callStyle || s.insistent || s.ongoing)
        assertNull(s.doorRef)
        assertNull(p.cancelRing)
        assertNull(p.recordStatus)
    }

    @Test
    fun `ring switch off - no door-call alert, the test notification still posts`() {
        for (o in listOf(ring, dismiss, missedEnded, missedLate)) assertTrue(plan(o, ringOn = false).postsNothing, "$o")
        assertNotNull(plan(Outcome.Quiet(now), ringOn = false).post)
    }

    @Test
    fun `warnings - each blocked piece is named for the notification it affects`() {
        val blocked = PermissionState(
            notificationsEnabled = false, ringChannelEnabled = false, ringChannelHigh = false,
            statusChannelEnabled = false, fullScreenAllowed = false,
        )
        assertEquals(
            listOf(PlanWarning.NOTIFICATIONS_BLOCKED, PlanWarning.RING_CHANNEL_BLOCKED, PlanWarning.RING_CHANNEL_LOWERED, PlanWarning.FULL_SCREEN_DENIED),
            plan(ring, perms = blocked).warnings,
        )
        assertEquals(listOf(PlanWarning.NOTIFICATIONS_BLOCKED, PlanWarning.STATUS_CHANNEL_BLOCKED), plan(dismiss, perms = blocked).warnings)
        assertEquals(listOf(PlanWarning.FULL_SCREEN_DENIED), plan(ring, perms = ok.copy(fullScreenAllowed = false)).warnings)
        assertTrue(plan(dismiss, perms = ok.copy(fullScreenAllowed = false)).warnings.isEmpty(), "a status notification needs no FSI")
    }

    @Test
    fun `the plan still posts when a permission is missing - the system drops it, the journal says why`() {
        val p = plan(ring, perms = PermissionState(notificationsEnabled = false, fullScreenAllowed = false))
        assertNotNull(p.post)
        assertTrue(p.post!!.fullScreen, "attached anyway: Android 14 downgrades a denied FSI to a heads-up")
    }

    @Test
    fun `end to end through Decision - ring, answered, ended (contract ended-after-answered stays silent)`() {
        val store = InMemoryCallStore()
        val refs = setOf("door")
        var last: LastStatus? = null
        fun step(msg: PushMessage): NotificationPlan {
            val o = Decision.receive(store, msg, now, refs)
            return NotificationPlanner.plan(o, now, true, ok, last).also { p -> p.recordStatus?.let { last = it } }
        }
        assertEquals(NoteKind.RING, step(PushMessage.Ring("door", "call", 1, now, now + 45_000)).post!!.kind)
        assertEquals(NoteKind.ANSWERED_ELSEWHERE, step(PushMessage.Answered("door", "call", 2, now, "other")).post!!.kind)
        val ended = step(PushMessage.Ended("door", "call", 3, now)).post!!
        assertEquals(NoteKind.ANSWERED_ELSEWHERE, ended.kind)
        assertTrue(ended.onlyAlertOnce, "the silent re-post: no new alert")
        assertEquals(Channel.STATUS, ended.channel)
    }
}
