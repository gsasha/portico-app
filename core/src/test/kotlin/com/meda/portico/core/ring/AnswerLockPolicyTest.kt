package com.meda.portico.core.ring

import com.meda.portico.core.ring.AnswerLockPolicy.Route
import com.meda.portico.core.call.CallAudioConfig
import com.meda.portico.core.call.KvStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnswerLockPolicyTest {
    @Test fun `unlocked is always home, whatever the setting`() {
        assertEquals(Route.HOME, AnswerLockPolicy.route(false, true))
        assertEquals(Route.HOME, AnswerLockPolicy.route(false, false))
    }

    @Test fun `locked with the setting on connects over the lock with no wait`() {
        assertEquals(Route.IN_CALL_OVER_LOCK, AnswerLockPolicy.route(true, true))
        assertEquals("none", AnswerLockPolicy.waitedWord(Route.IN_CALL_OVER_LOCK))
    }

    @Test fun `locked with the setting off waits for the unlock`() {
        assertEquals(Route.UNLOCK_FIRST, AnswerLockPolicy.route(true, false))
        assertEquals("unlock", AnswerLockPolicy.waitedWord(Route.UNLOCK_FIRST))
    }

    @Test fun `answering never loosens the door rule`() {
        // the setting is about Answer only: a door button still needs the unlock whenever the phone is locked
        assertTrue(DoorUnlockRule.needsUnlock(true, RingPress.DOOR_ACTION))
        assertFalse(DoorUnlockRule.needsUnlock(true, RingPress.CALL_HANDLING))
    }

    private class Mem : KvStore {
        val m = HashMap<String, Long>()
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) {}
        override fun remove(key: String) { m.remove(key) }
    }

    @Test fun `the setting defaults on and round-trips`() {
        val c = CallAudioConfig(Mem(), { 0L }, "x")
        assertTrue(c.answerWithoutUnlock())
        c.setAnswerWithoutUnlock(false)
        assertFalse(c.answerWithoutUnlock())
        c.setAnswerWithoutUnlock(true)
        assertTrue(c.answerWithoutUnlock())
    }
}
