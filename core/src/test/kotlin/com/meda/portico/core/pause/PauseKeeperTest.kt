package com.meda.portico.core.pause

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The stored pause as the app asks it: fail-open reads, the deadline noticed by whoever asks first. */
class PauseKeeperTest {

    private var now = 1_760_000_000_000L
    private val h = PauseRules.HOUR
    private val store = InMemoryPauseStore()
    private var expiredCalls = 0
    private val keeper = PauseKeeper(store, clock = { now }, onExpired = { expiredCalls++ })

    @Test
    fun `pause writes the state before it returns, and active() says so`() {
        val s = keeper.pause(PauseRules.ONE_HOUR)
        assertEquals(PauseState(true, now, now + h), s)
        assertEquals(s, store.state)
        assertTrue(keeper.active())
    }

    @Test
    fun `a pause whose write failed is not a pause`() {
        store.failWrites = true
        assertNull(keeper.pause(PauseRules.FOUR_HOURS))
        assertFalse(keeper.active())
    }

    @Test
    fun `FAIL OPEN - a store that throws on read means not paused`() {
        keeper.pause(PauseRules.UNTIL_RESUMED)
        store.throwOnRead = true
        assertFalse(keeper.active())
        assertEquals(PauseState.NOT_PAUSED, keeper.state())
        assertEquals(0L, keeper.resumedAt())
    }

    @Test
    fun `FAIL OPEN - a clock that throws means not paused`() {
        keeper.pause(PauseRules.UNTIL_RESUMED)
        val broken = PauseKeeper(store, clock = { error("no clock") })
        assertFalse(broken.active())
    }

    @Test
    fun `a corrupt stored state is not a pause, and the first asker triggers the resume`() {
        store.state = PauseState(true, now, now + 72 * h)
        assertFalse(keeper.active())
        assertEquals(1, expiredCalls)
    }

    @Test
    fun `the deadline is noticed by the first check after it, once, however many ask`() {
        keeper.pause(PauseRules.ONE_HOUR)
        now += h - 1
        assertTrue(keeper.active())
        assertEquals(0, expiredCalls)
        now += 1
        repeat(5) { assertFalse(keeper.active()) }
        assertEquals(1, expiredCalls, "one resume, not one per caller")
    }

    @Test
    fun `resume after a late-noticed deadline records the deadline as the resume time`() {
        val s = keeper.pause(PauseRules.ONE_HOUR)!!
        now += 3 * h // the phone was off
        assertEquals(s.untilMs, keeper.resume())
        assertEquals(PauseState.NOT_PAUSED, store.state)
        assertEquals(s.untilMs, keeper.resumedAt())
        assertFalse(keeper.active())
    }

    @Test
    fun `resume by hand records now, and a second resume does nothing`() {
        keeper.pause(PauseRules.UNTIL_RESUMED)
        now += 10 * 60_000
        assertEquals(now, keeper.resume())
        assertNull(keeper.resume(), "idempotent")
        assertEquals(now, keeper.resumedAt())
    }

    @Test
    fun `after a resume the expiry signal re-arms for the next pause`() {
        keeper.pause(PauseRules.ONE_HOUR)
        now += h
        keeper.active()
        keeper.resume()
        keeper.pause(PauseRules.ONE_HOUR)
        now += h
        keeper.active()
        assertEquals(2, expiredCalls)
    }

    @Test
    fun `resume of a phone that is not paused writes nothing`() {
        assertNull(keeper.resume())
        assertEquals(0, store.writes)
    }

    @Test
    fun `onPush - DROP while paused, RING otherwise, RING when the store throws`() {
        assertEquals(PushAction.RING, keeper.onPush())
        keeper.pause(PauseRules.ONE_HOUR)
        assertEquals(PushAction.DROP, keeper.onPush())
        store.throwOnRead = true
        assertEquals(PushAction.RING, keeper.onPush())
    }

    @Test
    fun `an onExpired that throws does not turn into paused`() {
        val k = PauseKeeper(store, clock = { now }, onExpired = { error("boom") })
        k.pause(PauseRules.ONE_HOUR)
        now += 2 * h
        assertFalse(k.active())
    }
}
