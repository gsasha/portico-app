package com.meda.portico.core.panel

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PanelLayoutTest {
    @Test
    fun noCapOutsidePanelMode() {
        assertEquals(0, PanelLayout.maxTileHeightDp(false, 600))
        assertEquals(0, PanelLayout.stripDp(false))
    }

    @Test
    fun aBigCameraAndItsButtonsFitALandscapeTablet() {
        val screen = 600
        val tile = PanelLayout.maxTileHeightDp(true, screen)
        assertEquals(screen - PanelLayout.HEADER_DP - PanelLayout.STRIP_DP - PanelLayout.BLOCK_CHROME_DP, tile)
        assertTrue(tile + PanelLayout.HEADER_DP + PanelLayout.STRIP_DP + PanelLayout.BLOCK_CHROME_DP <= screen)
    }

    @Test
    fun aVeryShortWindowKeepsAUsableTile() {
        assertEquals(PanelLayout.MIN_TILE_DP, PanelLayout.maxTileHeightDp(true, 200))
    }

    @Test
    fun stripIsPanelOnly() {
        assertEquals(PanelLayout.STRIP_DP, PanelLayout.stripDp(true))
    }

    @Test
    fun systemBarsHiddenInPanelModeAndInFullscreenVideo() {
        assertTrue(PanelLayout.systemBarsHidden(true, false))
        assertTrue(PanelLayout.systemBarsHidden(false, true))
        assertFalse(PanelLayout.systemBarsHidden(false, false))
    }
}
