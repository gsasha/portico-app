package com.meda.portico.core.content

/**
 * The look of a block button that has no palette colour (0.5.9-alpha): a "tonal" button derived from the two colours the
 * app's theme already has, the page ([page], the theme's `android:colorBackground`) and the primary text colour ([text],
 * `android:textColorPrimary`). Nothing is invented: the fill is the text colour at 10% over the page, the outline the text
 * colour at 55%, the icon (and any label) the text colour itself, so light and dark themes follow for free.
 *
 * Ratios (recomputed by `DefaultButtonLookTest` for the AppCompat light and dark pages and the palette's own pages): icon
 * on fill at least 4.5, outline on page at least 3. A button that is off is the same look at [DISABLED_STRENGTH] of its
 * strength (lower alpha), which is dimmed on purpose and not held to the ratios.
 *
 * Pure: ints are ARGB; the app turns the result into a rounded drawable.
 */
object DefaultButtonLook {
    const val FILL_STRENGTH = 0.10f
    const val OUTLINE_STRENGTH = 0.55f
    const val RIPPLE_STRENGTH = 0.16f
    const val DISABLED_STRENGTH = 0.38f

    /** All opaque ARGB ints, except [ripple], the translucent colour the press ripple is drawn with. */
    data class Look(val fill: Int, val outline: Int, val icon: Int, val ripple: Int)

    /** [fg] over [bg] at [alpha] (0..1), per channel, opaque. */
    fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return Math.round(f * alpha + b * (1f - alpha)).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [argb] drawn over [page]: a text colour such as 87% black (`#DE000000`) becomes the opaque colour it shows as. */
    fun flatten(argb: Int, page: Int): Int = blend(argb, page, ((argb ushr 24) and 0xFF) / 255f)

    fun look(page: Int, text: Int, enabled: Boolean): Look {
        val pageOpaque = flatten(page, 0xFF000000.toInt())
        val ink = flatten(text, pageOpaque)
        val k = if (enabled) 1f else DISABLED_STRENGTH
        return Look(
            fill = blend(ink, pageOpaque, FILL_STRENGTH * k),
            outline = blend(ink, pageOpaque, OUTLINE_STRENGTH * k),
            icon = blend(ink, pageOpaque, k),
            ripple = ((RIPPLE_STRENGTH * 255f + 0.5f).toInt() shl 24) or (ink and 0x00FFFFFF),
        )
    }
}
