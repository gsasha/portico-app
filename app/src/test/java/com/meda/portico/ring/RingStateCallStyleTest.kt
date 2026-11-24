package com.meda.portico.ring

import com.meda.portico.store.FakeSharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** PR-G: the persisted "last CallStyle was refused" flag that Setup reads. */
class RingStateCallStyleTest {
    private val prefs = FakeSharedPreferences()
    private val state = RingState(prefs)

    @Test
    fun `absent until a refusal, kept across instances, cleared by an accepted CallStyle`() {
        assertNull(state.callStyleRefusedBy())
        state.setCallStyleRefusedBy("IllegalArgumentException")
        assertEquals("IllegalArgumentException", RingState(prefs).callStyleRefusedBy(), "survives a process death")
        state.setCallStyleRefusedBy(null)
        assertNull(state.callStyleRefusedBy())
        assertEquals(setOf<String>(), prefs.all.keys, "nothing else is stored")
    }

    @Test
    fun `an unchanged value is not rewritten - no commit on every ring`() {
        state.setCallStyleRefusedBy(null)
        assertEquals(0, prefs.commits)
        state.setCallStyleRefusedBy("SecurityException")
        state.setCallStyleRefusedBy("SecurityException")
        assertEquals(1, prefs.commits)
    }
}
