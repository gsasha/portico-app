package com.meda.portico.core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hold fill's colours, recomputed with the WCAG 2.x ratio the other palette tests use: the opaque green (the leading
 * edge) is at least 3:1 against the default tonal fill and the page in light and dark; over every palette swatch the edge's
 * ring (the swatch's own text colour) is at least 3:1 against the swatch, so the progress reads on any background.
 */
class HoldFillLookTest {
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

    /** name, dark?, page, primary text colour: the same four themes as the default look's test. */
    private val themes = listOf(
        Triple("appcompat light", false, Pair(0xFFFAFAFA.toInt(), 0xDE000000.toInt())),
        Triple("appcompat dark", true, Pair(0xFF303030.toInt(), 0xFFFFFFFF.toInt())),
        Triple("palette light page", false, Pair(ButtonPalette.PAGE_LIGHT, 0xDE000000.toInt())),
        Triple("palette dark page", true, Pair(ButtonPalette.PAGE_DARK, 0xFFFFFFFF.toInt())),
    )

    @Test
    fun `the green is at least 3 to 1 against the default fill and the page in every theme`() {
        for ((name, dark, colours) in themes) {
            val (page, text) = colours
            val look = DefaultButtonLook.look(page, text, enabled = true)
            val green = HoldFillLook.green(dark)
            assertTrue(ratio(green, look.fill) >= 3.0, "$name green:fill ${ratio(green, look.fill)}")
            assertTrue(ratio(green, page) >= 3.0, "$name green:page ${ratio(green, page)}")
        }
    }

    @Test
    fun `over every palette swatch the edge ring is at least 3 to 1 against the swatch`() {
        for (e in ButtonPalette.ENTRIES) {
            for ((mode, swatch) in listOf("light" to e.light, "dark" to e.dark)) {
                assertTrue(ratio(swatch.text, swatch.fill) >= 3.0, "${e.name} $mode ring:swatch ${ratio(swatch.text, swatch.fill)}")
            }
        }
        for (dark in listOf(false, true)) {
            val s = ButtonPalette.disabled(dark)
            // a button that is off never holds (it ignores touches), so this is only the floor for the grey
            assertTrue(ratio(s.text, s.fill) >= 3.0)
        }
    }

    @Test
    fun `the layer is the green, translucent, and the themes differ`() {
        for (dark in listOf(false, true)) {
            val layer = HoldFillLook.layer(dark)
            assertEquals(HoldFillLook.green(dark) and 0x00FFFFFF, layer and 0x00FFFFFF)
            val alpha = (layer ushr 24) and 0xFF
            assertTrue(alpha in 64..200, "translucent, not invisible: $alpha")
        }
        assertTrue(HoldFillLook.green(false) != HoldFillLook.green(true))
    }

    @Test
    fun `the layer is green in hue`() {
        for (dark in listOf(false, true)) {
            val c = HoldFillLook.green(dark)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue(g > r && g > b, "green dominates: $r,$g,$b")
        }
    }

    @Test
    fun `the edge is visible at a real size`() {
        assertTrue(HoldFillLook.EDGE_DP >= 3 && HoldFillLook.RING_DP >= 1)
    }
}
