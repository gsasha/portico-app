package com.meda.portico.core.ring

import com.meda.portico.core.ring.RingScreenPolicy.Decision.DIRECT_OVERLAY
import com.meda.portico.core.ring.RingScreenPolicy.Decision.FULL_SCREEN_INTENT_ONLY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RingScreenPolicyTest {
    @Test fun unlockedInteractiveWithPermissionIsDirect() {
        assertEquals(DIRECT_OVERLAY, RingScreenPolicy.decide(true, true, false, 36))
    }

    @Test fun everyOtherCombinationKeepsTheFullScreenIntent() {
        for (can in listOf(true, false)) for (inter in listOf(true, false)) for (locked in listOf(true, false)) {
            if (can && inter && !locked) continue
            assertEquals(FULL_SCREEN_INTENT_ONLY, RingScreenPolicy.decide(can, inter, locked, 36), "can=$can interactive=$inter locked=$locked")
        }
    }

    @Test fun lockedIsNeverDirectEvenWithThePermission() {
        assertEquals(FULL_SCREEN_INTENT_ONLY, RingScreenPolicy.decide(true, true, true, 36))
        assertEquals(FULL_SCREEN_INTENT_ONLY, RingScreenPolicy.decide(true, false, true, 36))
    }

    @Test fun noPermissionIsNeverDirect() {
        assertEquals(FULL_SCREEN_INTENT_ONLY, RingScreenPolicy.decide(false, true, false, 36))
    }

    @Test fun beforeAndroid8IsNeverDirect() {
        assertEquals(FULL_SCREEN_INTENT_ONLY, RingScreenPolicy.decide(true, true, false, 25))
        assertEquals(DIRECT_OVERLAY, RingScreenPolicy.decide(true, true, false, 26))
    }

    @Test fun journalLines() {
        assertEquals("ring screen: direct (overlay)", RingScreenPolicy.DIRECT_LINE)
        assertEquals("ring screen: notification (no overlay permission)", RingScreenPolicy.notificationLine(false, true, false, 36))
        assertEquals("ring screen: notification (locked)", RingScreenPolicy.notificationLine(true, true, true, 36))
        assertEquals("ring screen: notification (screen off)", RingScreenPolicy.notificationLine(true, false, false, 36))
        assertEquals("ring screen: direct failed SecurityException", RingScreenPolicy.failedLine("SecurityException"))
        assertEquals("ring screen: direct failed Exception", RingScreenPolicy.failedLine(""))
    }
}
