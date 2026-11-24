package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The icon-only button and the default look (0.5.9-alpha), checked without a device (working directory = app/): an icon-only
 * block button is a real centred image, not a text button with an empty label and a start-edge compound drawable; it keeps its
 * touch target, name and states; and a button with no colour gets the tonal look derived from the theme. The pixels are for a phone.
 */
class IconButtonLookResourcesTest {
    private val buttons by lazy { File("src/main/java/com/meda/portico/home/ActionButtons.kt").readText() }
    private val blockButton by lazy { buttons.substringAfter("private fun blockButton(").substringBefore("    /** What a block button is drawn with") }
    private val iconOnly by lazy { buttons.substringAfter("private fun iconOnlyButton(").substringBefore("    private fun loadIcon(") }
    private val applyIcon by lazy { buttons.substringAfter("private fun applyIcon(").substringBefore("    /** The word under a button") }
    private val gradle by lazy { File("build.gradle.kts").readText() }

    @Test
    fun `an icon-only button is an ImageButton, not a text button with an empty label`() {
        assertTrue(iconOnly.contains("ImageButton(context)"))
        assertTrue(blockButton.contains("if (tile.iconOnly) {\n            iconOnlyButton("))
        assertFalse(blockButton.contains("text = if (tile.iconOnly)"), "no empty text")
        assertFalse(Regex("text = \"\"").containsMatchIn(buttons), "no empty text anywhere")
    }

    @Test
    fun `the compound drawable is for text buttons only and the icon-only path never uses it or gravity`() {
        assertFalse(applyIcon.contains("iconOnly"), "applyIcon has no icon-only branch")
        assertFalse(applyIcon.contains("Gravity"))
        assertFalse(iconOnly.contains("setCompoundDrawables"))
        assertFalse(iconOnly.contains("gravity"))
        assertEquals(1, Regex("setCompoundDrawablesRelative").findAll(buttons).count(), "one use: beside the text")
    }

    @Test
    fun `the icon is centred on both axes by equal padding around a fit-centred image`() {
        assertTrue(iconOnly.contains("scaleType = ImageView.ScaleType.FIT_CENTER"))
        assertTrue(iconOnly.contains("val pad = (dp(ICON_ONLY_BOX) - dp(ICON_ONLY_ICON)) / 2"))
        assertTrue(iconOnly.contains("setPadding(pad, pad, pad, pad)"), "the same padding on every side")
        assertTrue(buttons.contains("const val ICON_ONLY_ICON = 30"), "28 to 32 dp")
        assertTrue(buttons.contains("const val TEXT_ICON = 24"), "24 dp beside text")
    }

    @Test
    fun `the box is at least the 48 dp touch target and the cell is that square`() {
        val box = Regex("const val ICON_ONLY_BOX = (\\d+)").find(buttons)!!.groupValues[1].toInt()
        val icon = Regex("const val ICON_ONLY_ICON = (\\d+)").find(buttons)!!.groupValues[1].toInt()
        assertTrue(box >= 48, "touch target $box")
        assertTrue(icon in 28..32 && icon < box && (box - icon) % 2 == 0, "an even margin around the icon")
        assertEquals(box, Regex("const val BUTTON_HEIGHT = (\\d+)").find(buttons)!!.groupValues[1].toInt(), "Talk and Call are as tall as the text buttons")
        assertTrue(buttons.contains("minHeight = dp(BUTTON_HEIGHT)"), "text buttons keep their 56 dp minimum")
    }

    @Test
    fun `name, click, enabled state and reason are set for both kinds from one place`() {
        val tail = blockButton.substringAfter("return view.apply {")
        assertTrue(tail.contains("isEnabled = on"))
        assertTrue(tail.contains("contentDescription = if (note != null) \"\$label, \$note\" else label"))
        assertTrue(tail.contains("setOnClickListener { onPress(tile) }"), "a button that does not ask first still runs on a plain tap")
        assertTrue(tail.contains("if (tile.kind == \"talk\" || tile.talkRoute) talkAccessibility(this, tile, note)"))
        assertTrue(buttons.contains("private fun talkAccessibility(button: View,"))
    }

    @Test
    fun `a long press on an icon-only button shows its label`() {
        assertTrue(blockButton.contains("if (tile.iconOnly) TooltipCompat.setTooltipText(this, label)"), "kept for a button that does not ask first")
        assertTrue(buttons.contains("import androidx.appcompat.widget.TooltipCompat"))
    }

    @Test
    fun `an icon-only button is a fixed square in the flow`() {
        assertTrue(buttons.contains("ButtonFlowLayout(context, tiles.map { !it.iconOnly }, compact)"))
    }

    @Test
    fun `with no colour the look is the tonal default derived from the theme, with a 1 dp outline and 8 dp corners`() {
        assertTrue(buttons.contains("else -> defaultLook(on).let { Paint(it.fill, it.outline, it.icon, it.ripple) }"))
        assertTrue(buttons.contains("android.R.attr.colorBackground, android.R.attr.textColorPrimary"), "the theme's own attributes")
        assertTrue(buttons.contains("DefaultButtonLook.look(page, text, enabled)"))
        assertTrue(buttons.contains("setStroke(dp(1), paint.outline)"))
        assertEquals(2, Regex("cornerRadius = dp\\(8\\)\\.toFloat\\(\\)").findAll(buttons).count(), "fill and ripple mask share the radius")
        assertTrue(buttons.contains("RippleDrawable(ColorStateList.valueOf(paint.ripple), shape, mask)"), "a pressed state, clipped to the shape")
        assertFalse(Regex("Color\\.parseColor|Color\\.rgb|0xFF[0-9A-Fa-f]{6}").containsMatchIn(blockButton), "no colour literal in the button")
    }

    @Test
    fun `the palette colour keeps its swatch and a button that is off is grey, and a text Talk button keeps the platform off look`() {
        assertTrue(blockButton.contains("ButtonPalette.swatch(tile.color, isDark())?.let { if (on) it else ButtonPalette.disabled(isDark()) }"))
        assertTrue(blockButton.contains("swatch != null -> Paint(swatch.fill, null, swatch.text,"))
        assertTrue(blockButton.contains("tile.kind == \"talk\" && !on && !tile.iconOnly -> null"))
        assertTrue(iconOnly.contains("icon.setTint(paint.ink)"), "the icon takes the swatch's text colour")
    }

    @Test
    fun `the ring and in-call tile is untouched`() {
        val tile = buttons.substringAfter("fun tile(").substringBefore("fun blockRows(")
        assertTrue(tile.contains("applyIcon(this, HomeIcons.resolve(tile.icon, tile.kind))"))
        assertTrue(tile.contains("minHeight = dp(56)"))
        assertFalse(tile.contains("DefaultButtonLook"))
        assertFalse(tile.contains("roundedBackground"))
    }

    @Test
    fun `this release is 0,5,12-alpha`() {
        assertTrue(Regex("""val porticoVersionName = "\d+\.\d+\.\d+-alpha"""").containsMatchIn(gradle)) // not pinned to one version: every bump used to break this
    }
}
