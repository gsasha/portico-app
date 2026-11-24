package com.meda.portico.core.content

/**
 * 0.6.48: what Home must redraw when the page's view changed. A call starting or ending changes only the buttons (the Talk button's
 * state, its end line); redrawing the camera tiles for that tore down their views and live streams, which flickered. Pure.
 */
object PageUpdate {
    /**
     * True when [new] differs from [old] only in the buttons of its blocks: the same blocks (ids, titles, camera tiles) in the same
     * order, and everything else of the view equal. The camera grids can then stay as they are.
     */
    fun onlyButtonsChanged(old: HomeView?, new: HomeView): Boolean {
        if (old == null || old == new) return false
        if (old.rosterSelected || new.rosterSelected) return false
        if (old.blocks.size != new.blocks.size) return false
        for (i in old.blocks.indices) {
            val a = old.blocks[i]
            val b = new.blocks[i]
            if (a.id != b.id || a.title != b.title || a.cameras != b.cameras) return false
            if (a.buttons.isEmpty() != b.buttons.isEmpty()) return false
        }
        return old.copy(blocks = new.blocks) == new
    }
}

/** 0.6.48: how far a scroll view must move so that a bar is fully inside its viewport. Pure; the activity does the scrolling. */
object ScrollReveal {
    /**
     * [top] and [bottom] are the bar's edges (the bottom already includes the air wanted under it) relative to the viewport's top,
     * [viewport] its height. Returns the scroll amount (positive: content moves up). A bar taller than the viewport shows its top.
     */
    fun delta(top: Int, bottom: Int, viewport: Int): Int = when {
        bottom - top > viewport -> top
        bottom > viewport -> bottom - viewport
        top < 0 -> top
        else -> 0
    }
}
