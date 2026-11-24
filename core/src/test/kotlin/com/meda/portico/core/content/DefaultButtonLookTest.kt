package com.meda.portico.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The default (no colour) button look: every colour pair it introduces is recomputed here, for the AppCompat DayNight
 * light and dark themes (page #FAFAFA with text 87% black; page #303030 with white text) and for the palette's own pages
 * (#FFFFFF, #121212), with the WCAG 2.x ratio the palette tests use.
 */
class DefaultButtonLookTest {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun argb(hex: Long): Int = hex.toInt()

    /** name, page, primary text colour (ARGB). */
    private val themes = listOf(
        Triple("appcompat light", argb(0xFFFAFAFA), argb(0xDE000000)),
        Triple("appcompat dark", argb(0xFF303030), argb(0xFFFFFFFF)),
        Triple("palette light page", ButtonPalette.PAGE_LIGHT, argb(0xDE000000)),
        Triple("palette dark page", ButtonPalette.PAGE_DARK, argb(0xFFFFFFFF)),
    )

    @Test
    fun `the icon on the fill is at least 4,5 and the outline on the page at least 3, in every theme`() {
        for ((name, page, text) in themes) {
            val look = DefaultButtonLook.look(page, text, enabled = true)
            assertTrue(ratio(look.icon, look.fill) >= 4.5, "$name icon:fill ${ratio(look.icon, look.fill)}")
            assertTrue(ratio(look.outline, page) >= 3.0, "$name outline:page ${ratio(look.outline, page)}")
        }
    }

    @Test
    fun `the fill is tonal, a visible step from the page but never the text colour`() {
        for ((name, page, text) in themes) {
            val look = DefaultButtonLook.look(page, text, enabled = true)
            assertNotEquals(page, look.fill, name)
            assertTrue(ratio(look.fill, page) > 1.04, "$name fill is distinguishable from the page: ${ratio(look.fill, page)}")
            assertTrue(ratio(look.fill, page) < 3.0, "$name fill stays tonal (the outline carries the 3:1): ${ratio(look.fill, page)}")
        }
    }

    @Test
    fun `a button that is off is the same look at lower strength and every colour is opaque`() {
        for ((name, page, text) in themes) {
            val on = DefaultButtonLook.look(page, text, enabled = true)
            val off = DefaultButtonLook.look(page, text, enabled = false)
            assertTrue(ratio(off.icon, page) < ratio(on.icon, page), "$name off icon is dimmer")
            assertTrue(ratio(off.outline, page) < ratio(on.outline, page), "$name off outline is dimmer")
            assertTrue(ratio(off.icon, page) > 1.5, "$name off icon is still there")
            for (c in listOf(on.fill, on.outline, on.icon, off.fill, off.outline, off.icon)) assertEquals(0xFF, (c ushr 24) and 0xFF, name)
        }
    }

    @Test
    fun `blend and flatten agree with known values`() {
        assertEquals(argb(0xFFFFFFFF), DefaultButtonLook.blend(argb(0xFF000000), argb(0xFFFFFFFF), 0f))
        assertEquals(argb(0xFF000000), DefaultButtonLook.blend(argb(0xFF000000), argb(0xFFFFFFFF), 1f))
        val mid = DefaultButtonLook.blend(argb(0xFF000000), argb(0xFFFFFFFF), 0.5f)
        assertTrue((mid and 0xFF) in 127..128 && ((mid shr 8) and 0xFF) in 127..128 && ((mid shr 16) and 0xFF) in 127..128)
        // 87% black on white is #212121 (the AppCompat light primary text)
        assertEquals(argb(0xFF212121), DefaultButtonLook.flatten(argb(0xDE000000), argb(0xFFFFFFFF)))
        assertEquals(argb(0xFFFFFFFF), DefaultButtonLook.flatten(argb(0xFFFFFFFF), argb(0xFF303030)))
    }

    @Test
    fun `the ripple is the ink at a translucent alpha`() {
        val look = DefaultButtonLook.look(argb(0xFFFAFAFA), argb(0xDE000000), enabled = true)
        assertEquals(0x29, (look.ripple ushr 24) and 0xFF)
        assertEquals(DefaultButtonLook.flatten(argb(0xDE000000), argb(0xFFFAFAFA)) and 0x00FFFFFF, look.ripple and 0x00FFFFFF)
    }
}
