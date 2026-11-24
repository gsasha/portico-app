package com.meda.portico.core.pause

import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.push.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every row of [PauseRules], on the JVM: no phone, no hour-long wait, no reboot.
 *
 * The first block is the old intercom app's `tools/PauseTable.java` (0.52.0-alpha), row for row,
 * minus the two wall-panel rows (Portico has no wall-panel role) and with the call-id rows moved
 * to Portico's id grammar (`gso-<epoch ns>-<n>-<rand>`, not `<door>-<unix seconds>`). The second
 * block is Portico's own: the corrupt-state rows, every push kind, and where a ring's call time
 * comes from on each delivery path.
 *
 * What this does NOT prove: that AlarmManager fires the auto-resume on time under Doze, that the
 * boot receiver runs, or that Setup draws what it should. Those are the platform's.
 */
class PauseRulesTest {

    private val t0 = 1_760_000_000_000L
    private val h = PauseRules.HOUR
    private val min = 60_000L

    private fun row(name: String, got: () -> Any?, want: Any?) =
        DynamicTest.dynamicTest(name) { assertEquals(want, got(), name) }

    @TestFactory
    fun `the intercom app's table`(): List<DynamicTest> {
        val oneHour = PauseRules.start(t0, PauseRules.ONE_HOUR)
        val fourHours = PauseRules.start(t0, PauseRules.FOUR_HOURS)
        val forever = PauseRules.start(t0, PauseRules.UNTIL_RESUMED)
        // What survives a dead process or a reboot: the three stored values, re-read.
        val stored = PauseState(oneHour.paused, oneHour.sinceMs, oneHour.untilMs)
        val resumed = t0 + h
        return listOf(
            // pause 1 h, then ask before and after the deadline
            row("1 h: paused right after pressing", { PauseRules.active(oneHour, t0) }, true),
            row("1 h: paused at 59 min", { PauseRules.active(oneHour, t0 + h - min) }, true),
            row("1 h: paused 1 ms before the deadline", { PauseRules.active(oneHour, t0 + h - 1) }, true),
            row("1 h: NOT paused at the deadline", { PauseRules.active(oneHour, t0 + h) }, false),
            row("1 h: NOT paused after the deadline", { PauseRules.active(oneHour, t0 + 2 * h) }, false),
            row("1 h: remaining at 15 min", { PauseRules.remaining(oneHour, t0 + 15 * min) }, 45 * min),
            row("4 h: paused at 3 h 59", { PauseRules.active(fourHours, t0 + 4 * h - min) }, true),
            row("4 h: over at 4 h", { PauseRules.active(fourHours, t0 + 4 * h) }, false),
            // until resumed
            row("until resumed: paused a week later", { PauseRules.active(forever, t0 + 168 * h) }, true),
            row("until resumed: never expires by itself", { PauseRules.expired(forever, t0 + 10_000 * h) }, false),
            row("until resumed: no alarm to arm", { PauseRules.remaining(forever, t0 + h) }, 0L),
            row("until resumed: survives a clock set backwards", { PauseRules.active(forever, t0 - 24 * h) }, true),
            // the deadline passed while the process was dead / the phone off
            row("dead process: expired when re-read 3 h later", { PauseRules.expired(stored, t0 + 3 * h) }, true),
            row("dead process: not active", { PauseRules.active(stored, t0 + 3 * h) }, false),
            row("dead process: the pause ended AT the deadline, not at boot", { PauseRules.resumedAt(stored, t0 + 3 * h) }, t0 + h),
            // boot while paused (still inside the window)
            row("boot at 20 min: still paused", { PauseRules.active(stored, t0 + 20 * min) }, true),
            row("boot at 20 min: alarm re-armed for 40 min", { PauseRules.remaining(stored, t0 + 20 * min) }, 40 * min),
            row("boot while paused until resumed: still paused", { PauseRules.active(forever, t0 + 48 * h) }, true),
            // the clock moves backwards
            row("clock 1 day back: a timed pause ends (cannot trust the clock)", { PauseRules.active(oneHour, t0 - 24 * h) }, false),
            row("clock 1 day back: resumed NOW, not at a deadline in the future", { PauseRules.resumedAt(oneHour, t0 - 24 * h) }, t0 - 24 * h),
            row("clock back but still after the start: still paused", { PauseRules.active(oneHour, t0 + 30 * min) }, true),
            row("a corrupt deadline 3 days out is not a pause", { PauseRules.active(PauseState(true, t0, t0 + 72 * h), t0 + h) }, false),
            row("a deadline before its own start is not a pause", { PauseRules.active(PauseState(true, t0, t0 - 1), t0) }, false),
            // a push while paused is dropped; while not paused it rings
            row("push while paused: DROP", { PauseRules.onPush(oneHour, t0 + min) }, PushAction.DROP),
            row("push while paused until resumed: DROP", { PauseRules.onPush(forever, t0 + min) }, PushAction.DROP),
            row("push when never paused: RING", { PauseRules.onPush(PauseState.NOT_PAUSED, t0) }, PushAction.RING),
            row("push with no stored state: RING", { PauseRules.onPush(null, t0) }, PushAction.RING),
            row("push after the 1 h deadline: RING", { PauseRules.onPush(oneHour, t0 + h + 1) }, PushAction.RING),
            // unpausing must not replay missed rings
            row("catch-up: a ring from during the pause is history", { PauseRules.replayable(t0 + h - 20_000, resumed) }, false),
            row("catch-up: a ring exactly at the resume moment is history", { PauseRules.replayable(resumed, resumed) }, false),
            row("catch-up: a ring after the resume still rings", { PauseRules.replayable(t0 + h + 5_000, resumed) }, true),
            row("catch-up: never paused lets everything through", { PauseRules.replayable(t0, 0L) }, true),
            row("call id parse", { PauseRules.callIdEpochMs("gso-1760000000000000000-1-a1b2c3") }, 1_760_000_000_000L),
            row("call id parse, no stamp", { PauseRules.callIdEpochMs("selftest") }, -1L),
            row("call id parse, garbage", { PauseRules.callIdEpochMs("gso-abc-1-x") }, -1L),
            row("catch-up: an unparseable id is not withheld", { PauseRules.replayable(PauseRules.callIdEpochMs("gso-abc-1-x"), resumed) }, true),
            // input validation
            row("a 5 h pause is refused (the UI offers 1 h, 4 h, until resumed)", {
                runCatching { PauseRules.start(t0, 5 * h) }.exceptionOrNull() is IllegalArgumentException
            }, true),
        )
    }

    @TestFactory
    fun `Portico's own rows`(): List<DynamicTest> {
        val oneHour = PauseRules.start(t0, PauseRules.ONE_HOUR)
        val forever = PauseRules.start(t0, PauseRules.UNTIL_RESUMED)
        val resumed = t0 + h
        val call = "gso-1760003600000000000-4-a1b2c3" // stamped exactly at `resumed`
        fun fcmRing(t: Long) = PushMessage.Ring("01J9ENTRYGATE", call, 1, t, t + 45_000, Source.FCM)
        fun wsRing(id: String, now: Long) = PushMessage.Ring("01J9ENTRYGATE", id, 1, now, null, Source.WS)
        return listOf(
            // the three choices
            row("the three choices are 1 h, 4 h and until resumed", { listOf(PauseRules.ONE_HOUR, PauseRules.FOUR_HOURS, PauseRules.UNTIL_RESUMED) }, listOf(h, 4 * h, 0L)),
            row("4 h is the longest timed pause", { PauseRules.MAX_TIMED_MS }, 4 * h),
            row("start 1 h stores since and until", { PauseRules.start(t0, PauseRules.ONE_HOUR) }, PauseState(true, t0, t0 + h)),
            row("start until-resumed stores until = 0", { PauseRules.start(t0, PauseRules.UNTIL_RESUMED) }, PauseState(true, t0, 0L)),
            row("a negative length is refused", { runCatching { PauseRules.start(t0, -1) }.exceptionOrNull() is IllegalArgumentException }, true),
            row("not paused: never expired", { PauseRules.expired(PauseState.NOT_PAUSED, t0) }, false),
            row("not paused: resumedAt is now", { PauseRules.resumedAt(PauseState.NOT_PAUSED, t0) }, t0),
            // corrupt state counts as expired (fails towards ringing)
            row("corrupt: until == since is expired", { PauseRules.expired(PauseState(true, t0, t0), t0) }, true),
            row("corrupt: span of 4 h + 1 ms is expired", { PauseRules.expired(PauseState(true, t0, t0 + 4 * h + 1), t0 + min) }, true),
            row("corrupt: span of exactly 4 h is a real pause", { PauseRules.active(PauseState(true, t0, t0 + 4 * h), t0 + min) }, true),
            row("corrupt: resumedAt is now, not the nonsense deadline", { PauseRules.resumedAt(PauseState(true, t0, t0 + 72 * h), t0 + min) }, t0 + min),
            row("corrupt: a pushed ring rings", { PauseRules.onPush(PauseState(true, t0, t0 - 1), t0) }, PushAction.RING),
            row("resume by hand inside the window: resumedAt is now", { PauseRules.resumedAt(oneHour, t0 + 10 * min) }, t0 + 10 * min),
            row("resume by hand of until-resumed: resumedAt is now", { PauseRules.resumedAt(forever, t0 + 30 * h) }, t0 + 30 * h),
            row("remaining when the deadline has passed: 0", { PauseRules.remaining(oneHour, t0 + 2 * h) }, 0L),
            row("remaining 1 ms before the deadline: 1", { PauseRules.remaining(oneHour, t0 + h - 1) }, 1L),
            // where a ring's call time comes from
            row("FCM ring: the call time is the push's t", { PauseRules.callTimeMs(fcmRing(t0 + 5 * min)) }, t0 + 5 * min),
            row("socket ring: the call time is the gso id's stamp", { PauseRules.callTimeMs(wsRing(call, t0 + 2 * h)) }, resumed),
            row("socket ring with an unstamped id: unknown (-1)", { PauseRules.callTimeMs(wsRing("vto-call-7", t0 + 2 * h)) }, -1L),
            row("FCM ring stamped during the pause, delivered after resume: withheld", { PauseRules.withheld(fcmRing(t0 + 50 * min), resumed) }, true),
            row("FCM ring stamped after the resume: rings", { PauseRules.withheld(fcmRing(t0 + h + 1), resumed) }, false),
            row("socket snapshot ring for a call that began during the pause: withheld", { PauseRules.withheld(wsRing("gso-1760003000000000000-1-x", t0 + h + 3_000), resumed) }, true),
            row("socket ring for a call that began after the resume: rings", { PauseRules.withheld(wsRing("gso-1760003601000000000-1-x", t0 + h + 3_000), resumed) }, false),
            row("socket ring with an unstamped id: rings (unknown is not withheld)", { PauseRules.withheld(wsRing("vto-call-7", t0 + h + 3_000), resumed) }, false),
            row("never paused: nothing is withheld", { PauseRules.withheld(fcmRing(t0), 0L) }, false),
            row("call id with a 13-digit (ms) stamp is not a gso ns stamp", { PauseRules.callIdEpochMs("gso-1760000000000-12-a1b2") }, -1L),
            row("call id with a leading sign is garbage", { PauseRules.callIdEpochMs("gso--1760000000000000000-1-x") }, -1L),
            row("null call id", { PauseRules.callIdEpochMs(null) }, -1L),
        )
    }
}
