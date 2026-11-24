package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioRoutePlanTest {
    private fun d(c: RouteChoice, hs: Boolean = false, ear: Boolean = true, near: Boolean? = null) = AudioRoutePlan.decide(c, hs, ear, near)

    @Test fun autoWithHeadsetIsHeadset() {
        assertEquals(RouteDevice.HEADSET, d(RouteChoice.AUTO, hs = true, near = true))
        assertEquals(RouteDevice.HEADSET, d(RouteChoice.AUTO, hs = true, ear = false))
    }

    @Test fun bluetoothChoiceIsTheHeadsetWhileConnectedElseFallsBackToAuto() {
        assertEquals(RouteDevice.HEADSET, d(RouteChoice.BLUETOOTH, hs = true, near = true))
        assertEquals(RouteDevice.EARPIECE, d(RouteChoice.BLUETOOTH, hs = false, near = true))
        assertEquals(RouteDevice.SPEAKER, d(RouteChoice.BLUETOOTH, hs = false, near = false))
    }

    @Test fun tabletWithoutEarpieceIsSpeaker() {
        for (c in RouteChoice.values()) if (c != RouteChoice.BLUETOOTH) assertEquals(RouteDevice.SPEAKER, d(c, ear = false, near = true))
    }

    @Test fun literalChoicesBeatAConnectedHeadset() {
        assertEquals(RouteDevice.EARPIECE, d(RouteChoice.EARPIECE, near = false))
        assertEquals(RouteDevice.SPEAKER, d(RouteChoice.SPEAKER, near = true))
        assertEquals(RouteDevice.SPEAKER, d(RouteChoice.SPEAKER, hs = true))
        assertEquals(RouteDevice.EARPIECE, d(RouteChoice.EARPIECE, hs = true))
    }

    @Test fun autoFollowsProximityAndDefaultsToSpeaker() {
        assertEquals(RouteDevice.EARPIECE, d(RouteChoice.AUTO, near = true))
        assertEquals(RouteDevice.SPEAKER, d(RouteChoice.AUTO, near = false))
        assertEquals(RouteDevice.SPEAKER, d(RouteChoice.AUTO, near = null))
    }

    @Test fun panelModeIsAlwaysTheSpeaker() {
        // DEV-807 section 10: a board may report a headset that is not there; a wall panel is never held to an ear.
        for (c in RouteChoice.values()) {
            for (hs in listOf(false, true)) for (near in listOf(true, false, null)) {
                assertEquals(RouteDevice.SPEAKER, AudioRoutePlan.decide(c, hs, true, near, panelMode = true), "$c hs=$hs near=$near")
            }
        }
        assertEquals(RouteDevice.HEADSET, d(RouteChoice.AUTO, hs = true)) // and a phone is unchanged
    }

    @Test fun proximity() {
        assertTrue(AudioRoutePlan.isNear(0f, 5f))
        assertFalse(AudioRoutePlan.isNear(5f, 5f))
        assertFalse(AudioRoutePlan.isNear(8f, 8f))
        assertTrue(AudioRoutePlan.isNear(3f, 8f))
        assertFalse(AudioRoutePlan.isNear(6f, 10f))
    }

    @Test fun volumeClampAndStep() {
        assertEquals(5, AudioRoutePlan.clampVolume(9, 1, 5))
        assertEquals(1, AudioRoutePlan.clampVolume(-3, 1, 5))
        assertEquals(3, AudioRoutePlan.clampVolume(3, 1, 5))
        assertEquals(1, AudioRoutePlan.clampVolume(3, 1, 0))
        assertEquals(5, AudioRoutePlan.stepVolume(5, 1, 1, 5))
        assertEquals(1, AudioRoutePlan.stepVolume(1, -1, 1, 5))
        assertEquals(4, AudioRoutePlan.stepVolume(3, 1, 1, 5))
    }
}
