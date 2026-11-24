package com.meda.portico.core.ring

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DoorUnlockRuleTest {
    @Test fun lockedDoorActionNeedsUnlock() { assertTrue(DoorUnlockRule.needsUnlock(true, RingPress.DOOR_ACTION)) }
    @Test fun lockedAnswerOrStopNeedsNone() { assertFalse(DoorUnlockRule.needsUnlock(true, RingPress.CALL_HANDLING)) }
    @Test fun unlockedNeverNeedsIt() {
        for (p in RingPress.values()) assertFalse(DoorUnlockRule.needsUnlock(false, p))
    }
}
