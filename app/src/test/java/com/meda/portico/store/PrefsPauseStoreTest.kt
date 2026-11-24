package com.meda.portico.store

import com.meda.portico.core.pause.PauseKeeper
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.core.pause.PauseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pause on its private prefs file: synchronous commits, survives a new process, fails open. */
class PrefsPauseStoreTest {
    private val prefs = FakeSharedPreferences()
    private var now = 1_760_000_000_000L

    @Test
    fun `a pause is committed (not applied) and a new process reads it back`() {
        val keeper = PauseKeeper(PrefsPauseStore(prefs), { now })
        val s = keeper.pause(PauseRules.FOUR_HOURS)
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        assertEquals(s, PrefsPauseStore(prefs).load(), "the cold process sees the same pause")
        assertTrue(PauseKeeper(PrefsPauseStore(prefs), { now }).active())
    }

    @Test
    fun `resume clears the pause and records when it ended, in one commit`() {
        val keeper = PauseKeeper(PrefsPauseStore(prefs), { now })
        keeper.pause(PauseRules.ONE_HOUR)
        now += 2 * PauseRules.HOUR
        assertEquals(now - PauseRules.HOUR, keeper.resume())
        assertEquals(2, prefs.commits)
        assertEquals(PauseState.NOT_PAUSED, PrefsPauseStore(prefs).load())
        assertEquals(now - PauseRules.HOUR, PrefsPauseStore(prefs).loadResumedAt())
    }

    @Test
    fun `a failed commit is not a pause`() {
        val failing = FakeSharedPreferences(commitResult = false)
        assertNull(PauseKeeper(PrefsPauseStore(failing), { now }).pause(PauseRules.ONE_HOUR))
    }

    @Test
    fun `FAIL OPEN - a value of the wrong type in the file means not paused`() {
        prefs.edit().putBoolean("paused", true).putString("until", "garbage").commit()
        val keeper = PauseKeeper(PrefsPauseStore(prefs), { now })
        assertFalse(keeper.active())
        assertEquals(0L, keeper.resumedAt())
    }

    @Test
    fun `an empty file is not paused and never resumed`() {
        assertEquals(PauseState.NOT_PAUSED, PrefsPauseStore(prefs).load())
        assertEquals(0L, PrefsPauseStore(prefs).loadResumedAt())
    }
}
