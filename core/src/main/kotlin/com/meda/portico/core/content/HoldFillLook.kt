package com.meda.portico.core.content

/**
 * The colours of the hold-to-confirm fill (0.5.10-alpha). A translucent green layer sweeps across the button at
 * [LAYER_ALPHA]; its leading edge is a [EDGE_DP] dp bar of the OPAQUE green with a [RING_DP] dp ring of the button's own ink
 * (text colour) either side, so the progress reads on any background: the default tonal fill and the page (the green alone
 * is at least 3:1 against both, in light and dark: `HoldFillLookTest`) and every palette swatch (the ring is the swatch's
 * own text colour, already contrasted with the swatch). The green differs by theme because no single green is 3:1 against
 * both a near-white and a mid-grey page. Pure: ints are ARGB.
 */
object HoldFillLook {
    const val LAYER_ALPHA = 0.50f
    const val EDGE_DP = 3
    const val RING_DP = 1
    private val GREEN_ON_LIGHT = 0xFF2E7D32.toInt()
    private val GREEN_ON_DARK = 0xFF81C784.toInt()

    fun green(dark: Boolean): Int = if (dark) GREEN_ON_DARK else GREEN_ON_LIGHT

    /** The sweeping layer: [green] at [LAYER_ALPHA]. */
    fun layer(dark: Boolean): Int = (Math.round(LAYER_ALPHA * 255f) shl 24) or (green(dark) and 0x00FFFFFF)
}
