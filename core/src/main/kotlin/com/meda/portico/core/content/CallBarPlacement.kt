package com.meda.portico.core.content

/**
 * Where the in-call controls row (the call bar) goes on Home: directly below the buttons of the block that holds the call's own
 * Talk or call button, instead of at the bottom of the screen. Pure; [HomeActivity]/[BlockViews] only draw the answer.
 */
object CallBarPlacement {
    /** A button of the call's own: a Talk or call button whose call is under way (the ones drawn green with a wave). */
    fun isCallTile(t: ButtonTile): Boolean =
        (t.kind == "talk" || t.kind == "call") &&
            (t.state == ButtonState.CALLING || t.state == ButtonState.TALK_CONNECTING || t.state == ButtonState.TALK_IN_CALL || t.state == ButtonState.RINGING_IN)

    /** Index (in the block's button order) of the button the bar belongs under, or null when this block has none. */
    fun indexIn(buttons: List<ButtonTile>): Int? = buttons.indexOfFirst { isCallTile(it) }.takeIf { it >= 0 }

    /** The first block of the page that holds such a button: (block index, button index), or null (the bar then stays at the bottom of the screen). */
    fun find(blocks: List<BlockView>): Pair<Int, Int>? {
        blocks.forEachIndexed { b, block -> indexIn(block.buttons)?.let { return b to it } }
        return null
    }
}
