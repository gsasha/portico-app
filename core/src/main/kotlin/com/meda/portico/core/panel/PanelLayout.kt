package com.meda.portico.core.panel

/**
 * Panel mode's screen layout rules (D-20261122-b45c, 0.6.99): a wall tablet is looked at from across the room, in landscape, with no scrolling.
 * Pure numbers; the Android glue ([com.meda.portico.panel.PanelScreen], Home's tiles, the viewport overlay) applies them.
 */
object PanelLayout {
    /** The bottom control strip of a Home or Settings screen in Panel mode: the return countdown on the left, the large Close on the right. */
    const val STRIP_DP = 64

    /** The pinned top line of Home (title, status, menu). */
    const val HEADER_DP = 56

    /** What a block needs besides its camera: top paddings, the heading, one row of buttons, the bottom padding. */
    const val BLOCK_CHROME_DP = 136

    /** A camera must never be squeezed below this, whatever the screen (a very short window): the page then scrolls as it always did. */
    const val MIN_TILE_DP = 120

    /**
     * The tallest a Big camera tile may be in Panel mode so that it AND its block's buttons fit the screen: the screen height less the header,
     * the bottom strip and the block's own chrome. The tile's width follows (16:9, centred), so the picture is shrunk, never cropped.
     * Returns 0 (no cap) when not in Panel mode.
     */
    fun maxTileHeightDp(panelMode: Boolean, screenHeightDp: Int): Int =
        if (!panelMode) 0 else (screenHeightDp - HEADER_DP - STRIP_DP - BLOCK_CHROME_DP).coerceAtLeast(MIN_TILE_DP)

    /** The bottom space (dp) a Home/Settings screen gives to the control strip: only in Panel mode. */
    fun stripDp(panelMode: Boolean): Int = if (panelMode) STRIP_DP else 0

    /** Whether the system bars (status and navigation) are hidden: always in Panel mode (swipe shows them for a moment), else only in fullscreen video. */
    fun systemBarsHidden(panelMode: Boolean, fullscreenVideo: Boolean): Boolean = panelMode || fullscreenVideo
}
