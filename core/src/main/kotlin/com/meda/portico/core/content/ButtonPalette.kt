package com.meda.portico.core.content

/**
 * The button colours of blocks (DEV-658): eight names, each with a fill and a text colour for the light and for the dark
 * theme. Light draws a solid fill with white text, dark a pale fill with dark text (every text:fill ratio is at least 4.5
 * and every fill:page ratio at least 3, which portico-ha's tests recompute from `contract/palette_v1.json`).
 *
 * This is the app's copy of the values; `core/src/test/resources/palette_v1.json` is the vendored contract file (a byte
 * copy of portico-ha's) and a test fails when this object and that file disagree, and another pins the file's sha256. To
 * change a colour: change it in portico-ha, copy the file here, update this object and the pin (docs/reference/websocket-contract.md, docs/user-guide/home-screen.md "Blocks and button colours").
 *
 * Pure: the app turns the returned ARGB ints into widget colours. A name this build does not know (a later server's) is
 * "no colour": the theme's default button, never a crash. Colour never carries meaning alone: the text or the icon is
 * always there, and a button that is off is drawn grey ([DISABLED_LIGHT], [DISABLED_DARK]) whatever its colour.
 */
object ButtonPalette {
    /** A fill and the text drawn on it, as opaque ARGB ints. */
    data class Swatch(val fill: Int, val text: Int)

    data class Entry(val name: String, val light: Swatch, val dark: Swatch)

    private fun rgb(hex: Int): Int = 0xFF000000.toInt() or hex

    private fun entry(name: String, lightFill: Int, darkFill: Int, darkText: Int) =
        Entry(name, Swatch(rgb(lightFill), rgb(0xFFFFFF)), Swatch(rgb(darkFill), rgb(darkText)))

    val ENTRIES: List<Entry> = listOf(
        entry("blue", 0x1565C0, 0x90CAF9, 0x0D2B4E),
        entry("green", 0x2E7D32, 0xA5D6A7, 0x0F3A14),
        entry("amber", 0x8A5A00, 0xFFD54F, 0x3D2A00),
        entry("red", 0xC62828, 0xEF9A9A, 0x5A0E0E),
        entry("purple", 0x6A1B9A, 0xCE93D8, 0x3B0F52),
        entry("teal", 0x00796B, 0x80CBC4, 0x00332D),
        entry("pink", 0xAD1457, 0xF48FB1, 0x4F0A27),
        entry("grey", 0x546E7A, 0xB0BEC5, 0x1F2A30),
    )

    /** The page colours the ratios are computed against (palette_v1.json `pages`). */
    val PAGE_LIGHT: Int = rgb(0xFFFFFF)
    val PAGE_DARK: Int = rgb(0x121212)

    /** A button that is off, whatever its colour: neutral, and still readable. */
    val DISABLED_LIGHT = Swatch(rgb(0xBDBDBD), rgb(0x616161))
    val DISABLED_DARK = Swatch(rgb(0x424242), rgb(0x9E9E9E))

    private val BY_NAME: Map<String, Entry> = ENTRIES.associateBy { it.name }

    /** The entry a stored colour names, or null: `Blue`, ` blue ` give blue; null, blank and any unknown name give none. */
    fun of(name: String?): Entry? = name?.trim()?.lowercase()?.let { BY_NAME[it] }

    /** The swatch to draw for [name] in the light or dark ([dark]) theme, or null for the default button. */
    fun swatch(name: String?, dark: Boolean): Swatch? = of(name)?.let { if (dark) it.dark else it.light }

    /** What a button that is off is drawn with in the given theme. */
    fun disabled(dark: Boolean): Swatch = if (dark) DISABLED_DARK else DISABLED_LIGHT
}
