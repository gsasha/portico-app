package com.meda.portico.home

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.meda.portico.core.content.BlockView
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.CallBarPlacement
import com.meda.portico.core.content.LayoutJournal

/**
 * Home's body (DEV-658): the selected page's blocks as widgets, in order. Per block: a heading (its title; a heading for
 * TalkBack), the camera grid ([StillTiles.addGrid]) and the button rows ([ActionButtons.blockRows]). Drawing only: which
 * blocks, tiles and rows, and every state, is `:core` (`HomeModel`, `CameraGrid`, `ButtonRows`).
 */
class BlockViews(private val context: Context, private val stills: StillTiles, private val buttons: ActionButtons) {

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** 0.6.49: Home's page root has NO side padding (activity_home.xml): the camera grid is full width, everything else carries this side margin itself. */
        const val PAGE_PAD_DP = 16
    }

    /**
     * The button flow that holds the call's own Talk/call button of the last [rebuild] and that button's child index (null: none on
     * this page). The in-call controls row ([com.meda.portico.call.CallBarView]) is put directly below that button's row
     * ([ButtonFlowLayout.setAddon]); `:core`'s [CallBarPlacement] decides which button.
     */
    var callAnchor: Pair<ButtonFlowLayout, Int>? = null
        private set

    /** Frees the controls row from the flow it sits in (before the page body is replaced by something else). */
    fun releaseAnchor() {
        callAnchor?.first?.clearAddon()
        callAnchor = null
    }

    /** The box of each block of the last [rebuild] (null: that block failed to build), and the button rows inside it. */
    private val boxes = ArrayList<LinearLayout?>()
    private val rowsOf = HashMap<LinearLayout, LinearLayout>()

    private fun rowsParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); leftMargin = dp(PAGE_PAD_DP); rightMargin = dp(PAGE_PAD_DP) }

    private fun buildRows(block: BlockView, onPress: (ButtonTile) -> Unit): LinearLayout {
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val built = buttons.blockRows(block.buttons, onPress)
        val at = if (callAnchor == null) CallBarPlacement.indexIn(block.buttons) else null
        if (at != null) (built.firstOrNull() as? ButtonFlowLayout)?.let { callAnchor = it to at }
        for (row in built) rows.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return rows
    }

    /**
     * 0.6.48: replaces ONLY the button rows of each block, keeping the camera grids (their views, renderers and streams) untouched: a
     * call starting or ending changes just the buttons, and rebuilding the tiles for it made them flicker. Call only when `:core`'s
     * PageUpdate says only the buttons changed. False (nothing done) when the last [rebuild] cannot be updated in place.
     */
    fun rebuildButtons(blocks: List<BlockView>, onPress: (ButtonTile) -> Unit): Boolean {
        if (blocks.size != boxes.size || boxes.any { it == null }) return false
        releaseAnchor()
        blocks.forEachIndexed { i, block ->
            val box = boxes[i] ?: return false
            rowsOf.remove(box)?.let { box.removeView(it) }
            if (block.buttons.isNotEmpty()) {
                val rows = buildRows(block, onPress)
                rowsOf[box] = rows
                box.addView(rows, rowsParams())
            }
        }
        return true
    }

    /**
     * Replaces everything in [container] with [blocks]. Call only when the view changed. Each block is built on its own:
     * one that throws is journalled ([LayoutJournal], and logcat; never swallowed silently) and the blocks after it are
     * still drawn. One journal line is written at the end.
     */
    fun rebuild(container: LinearLayout, blocks: List<BlockView>, onPress: (ButtonTile) -> Unit) {
        releaseAnchor() // the controls row is a child of the old flow: it must be free before the tree goes
        container.removeAllViews()
        stills.reset()
        boxes.clear()
        rowsOf.clear()
        val stats = ArrayList<LayoutJournal.BlockStat>()
        for (block in blocks) {
            var gridChildren = 0
            var buttonRows = 0
            val boxesBefore = boxes.size
            val failure = try {
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                if (block.title != null) {
                    box.addView(TextView(context).apply {
                        text = block.title
                        TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
                        ViewCompat.setAccessibilityHeading(this, true)
                        setPadding(dp(PAGE_PAD_DP), dp(8), dp(PAGE_PAD_DP), dp(4))
                    }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                if (block.cameras.isNotEmpty()) {
                    val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    stills.addGrid(grid, block.cameras, block.partialPlan)
                    gridChildren = grid.childCount
                    // 0.6.49: the camera tiles run edge to edge simply because nothing above them pads or clips (no negative margins: the 0.6.48 ones were clipped by the box).
                    box.addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                if (block.buttons.isNotEmpty()) {
                    val rows = buildRows(block, onPress)
                    buttonRows = rows.childCount
                    rowsOf[box] = rows
                    box.addView(rows, rowsParams())
                }
                boxes.add(box)
                container.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                null
            } catch (e: Exception) {
                LayoutJournal.describe(e).also { Log.e("Portico", "home: a block failed to build: $it", e) }
            }
            if (failure != null) { while (boxes.size > boxesBefore) boxes.removeAt(boxes.size - 1); boxes.add(null) } // a block that failed: no partial update is possible
            stats.add(LayoutJournal.BlockStat(LayoutJournal.sizes(block.cameras), block.buttons.size, block.cameraRows.size, gridChildren, buttonRows, failure))
        }
        val line = LayoutJournal.rebuildLine(stats)
        LayoutJournal.add(line)
        LayoutJournal.reportFailure(stats) // DEV-762 A5: a failed block also goes to the Journal, bounded
        Log.i("Portico", line)
    }
}
