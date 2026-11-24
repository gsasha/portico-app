package com.meda.portico.core.content

import com.meda.portico.core.ha.WsContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The palette (DEV-658): the app's [ButtonPalette] equals the vendored `palette_v1.json` (a byte copy of portico-ha's,
 * pinned below), both themes resolve, and every contrast ratio the design promises is recomputed from the values.
 */
class ButtonPaletteTest {
    companion object {
        /** sha256 of `palette_v1.json` as vendored from portico-ha `a2ad780` (PR #128, blocks B2). */
        const val PALETTE_SHA256 = "9b47dbf71092d9b093c8d885cadd311cc6c5dc855a75450f16db46df8279fc54"

        /** sha256 of `icons_v1.json` as vendored from the same commit (118 names). */
        const val ICONS_SHA256 = "fea11bcabead4dca7a9f81ac3499178e1185699de39951082c7d4f389f17ff52"
    }

    private val bytes: ByteArray by lazy { WsContract.resourceBytes("/palette_v1.json") }
    private val json: JsonObject by lazy { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }

    private fun hex(s: String): Int = (0xFF000000L or s.removePrefix("#").toLong(16)).toInt()

    @Test
    fun `the vendored palette and icon files are the pinned copies`() {
        assertEquals(PALETTE_SHA256, WsContract.sha256Of(bytes), "palette_v1.json drifted: copy contract/palette_v1.json from portico-ha and update PALETTE_SHA256 and ButtonPalette")
        assertEquals(ICONS_SHA256, WsContract.sha256Of(WsContract.resourceBytes("/icons_v1.json")), "icons_v1.json drifted: copy contract/icons_v1.json from portico-ha and update ICONS_SHA256, HomeIcons and the drawables")
    }

    @Test
    fun `the app's palette is exactly the contract file, in order`() {
        val colors = json["colors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(colors.map { it["name"]!!.jsonPrimitive.content }, ButtonPalette.ENTRIES.map { it.name })
        for ((file, mine) in colors.zip(ButtonPalette.ENTRIES)) {
            for (theme in listOf("light", "dark")) {
                val swatch = if (theme == "light") mine.light else mine.dark
                val want = file[theme]!!.jsonObject
                assertEquals(hex(want["fill"]!!.jsonPrimitive.content), swatch.fill, "${mine.name} $theme fill")
                assertEquals(hex(want["text"]!!.jsonPrimitive.content), swatch.text, "${mine.name} $theme text")
            }
        }
        val pages = json["pages"]!!.jsonObject
        assertEquals(hex(pages["light"]!!.jsonPrimitive.content), ButtonPalette.PAGE_LIGHT)
        assertEquals(hex(pages["dark"]!!.jsonPrimitive.content), ButtonPalette.PAGE_DARK)
        assertEquals(8, ButtonPalette.ENTRIES.size)
    }

    // ---- WCAG 2.x contrast, recomputed ----

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

    @Test
    fun `every text on its fill is at least 4,5 and every fill against its page at least 3, in both themes`() {
        val darkCard = hex(json["pages"]!!.jsonObject["dark_card"]!!.jsonPrimitive.content)
        for (e in ButtonPalette.ENTRIES) {
            assertTrue(ratio(e.light.text, e.light.fill) >= 4.5, "${e.name} light text:fill ${ratio(e.light.text, e.light.fill)}")
            assertTrue(ratio(e.dark.text, e.dark.fill) >= 4.5, "${e.name} dark text:fill ${ratio(e.dark.text, e.dark.fill)}")
            assertTrue(ratio(e.light.fill, ButtonPalette.PAGE_LIGHT) >= 3.0, "${e.name} light fill:page")
            assertTrue(ratio(e.dark.fill, ButtonPalette.PAGE_DARK) >= 3.0, "${e.name} dark fill:page")
            assertTrue(ratio(e.dark.fill, darkCard) >= 3.0, "${e.name} dark fill:card")
        }
    }

    @Test
    fun `the ratio helper agrees with known values`() {
        assertEquals(21.0, ratio(hex("#000000"), hex("#FFFFFF")), 0.001)
        assertEquals(1.0, ratio(hex("#777777"), hex("#777777")), 0.001)
        assertEquals(5.7, ratio(hex("#FFFFFF"), hex("#1565C0")), 0.05, "the design's blue light text:fill")
    }

    // ---- lookup ----

    @Test
    fun `a name resolves to the light or the dark swatch by theme`() {
        val blue = ButtonPalette.ENTRIES.first { it.name == "blue" }
        assertEquals(blue.light, ButtonPalette.swatch("blue", dark = false))
        assertEquals(blue.dark, ButtonPalette.swatch("blue", dark = true))
        assertNotEquals(ButtonPalette.swatch("blue", false), ButtonPalette.swatch("blue", true))
        assertEquals(hex("#1565C0"), ButtonPalette.swatch("blue", false)!!.fill)
        assertEquals(hex("#90CAF9"), ButtonPalette.swatch("blue", true)!!.fill)
        assertEquals(hex("#FFFFFF"), ButtonPalette.swatch("green", false)!!.text)
        assertEquals(hex("#0F3A14"), ButtonPalette.swatch("green", true)!!.text)
        for (e in ButtonPalette.ENTRIES) {
            assertEquals(e.light, ButtonPalette.swatch(e.name, false), e.name)
            assertEquals(e.dark, ButtonPalette.swatch(e.name, true), e.name)
        }
    }

    @Test
    fun `case and padding are tolerated, anything else is no colour`() {
        assertEquals("amber", ButtonPalette.of("  Amber ")?.name)
        for (s in listOf(null, "", "  ", "mauve", "#FF0000", "blue;", "bl ue")) {
            assertNull(ButtonPalette.of(s), "'$s'")
            assertNull(ButtonPalette.swatch(s, true))
        }
    }

    @Test
    fun `a button that is off is neutral in both themes, whatever its colour`() {
        assertNotEquals(ButtonPalette.disabled(false), ButtonPalette.disabled(true))
        for (e in ButtonPalette.ENTRIES) {
            assertNotEquals(e.light.fill, ButtonPalette.DISABLED_LIGHT.fill, e.name)
            assertNotEquals(e.dark.fill, ButtonPalette.DISABLED_DARK.fill, e.name)
        }
        assertTrue(ratio(ButtonPalette.DISABLED_LIGHT.text, ButtonPalette.DISABLED_LIGHT.fill) >= 3.0, "an off button is still readable")
        assertTrue(ratio(ButtonPalette.DISABLED_DARK.text, ButtonPalette.DISABLED_DARK.fill) >= 3.0)
    }
}
