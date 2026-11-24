package com.meda.portico.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.meda.portico.core.content.ButtonFlow

/**
 * A block's buttons as rows that fill the width: each button is as compact as it can be (its natural single-line width, or
 * half the row for a long label, which then wraps to two lines), as many as fit share a row, and `:core`'s [ButtonFlow]
 * stretches the rows to the full width with the labelled buttons as equal as possible. Every button in a row has the row's
 * height, at least [ActionButtons.BUTTON_HEIGHT] dp. Drawing only: the plan is `:core`'s, the buttons are [ActionButtons]'.
 * [labelled] says per child (in order) whether it has a label; an icon-only one keeps [ActionButtons.ICON_ONLY_BOX] dp.
 */
class ButtonFlowLayout(context: Context, private val labelled: List<Boolean>, private val compact: Boolean = false) : ViewGroup(context) {
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density + 0.5f).toInt()

    private class Placed(val index: Int, val width: Int)
    private class RowBox(val cells: List<Placed>, val height: Int, val addonAfter: Boolean = false)

    private var rows: List<RowBox> = emptyList()
    private var tail = false

    /** The extra full-width row (the in-call controls) and the button (child index) whose row it goes directly below; null: none. */
    private var addon: View? = null
    private var addonAfterChild = -1

    /** Puts [view] (not one of the buttons) as a full-width row directly below the row of button [afterChild]; [view] must have no parent. */
    fun setAddon(view: View, afterChild: Int) {
        clearAddon()
        addon = view
        addonAfterChild = afterChild
        addView(view)
    }

    /** Takes the addon out again (it can then be put elsewhere). */
    fun clearAddon() {
        addon?.let { removeView(it) }
        addon = null
        addonAfterChild = -1
    }

    private fun addonShown() = addon?.takeIf { it.visibility != GONE }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val total = MeasureSpec.getSize(widthSpec)
        val availDp = total / density
        val items = ArrayList<ButtonFlow.Item>()
        for (i in labelled.indices) {
            val child = getChildAt(i)
            if (!labelled[i]) {
                items.add(ButtonFlow.Item(ActionButtons.ICON_ONLY_BOX.toFloat(), false))
            } else {
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val natural = child.measuredWidth / density
                val cap = (availDp / 2f - 2 * MARGIN_DP).coerceAtLeast(ActionButtons.ICON_ONLY_BOX.toFloat())
                items.add(ButtonFlow.Item(minOf(natural, cap), true))
            }
        }
        val plan = ButtonFlow.plan(items, availDp, MARGIN_DP.toFloat(), compact)
        val margin = px(MARGIN_DP.toFloat())
        var y = 0
        val built = ArrayList<RowBox>()
        for (row in plan) {
            val cells = row.indices.mapIndexed { k, i -> Placed(i, px(row.widthsDp[k])) }
            var h = px(ActionButtons.BUTTON_HEIGHT.toFloat())
            for (c in cells) {
                val child = getChildAt(c.index)
                child.measure(MeasureSpec.makeMeasureSpec(c.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                h = maxOf(h, child.measuredHeight)
            }
            for (c in cells) {
                getChildAt(c.index).measure(MeasureSpec.makeMeasureSpec(c.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
            val below = addonShown()?.takeIf { a -> cells.any { it.index == addonAfterChild } }
            below?.measure(MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            built.add(RowBox(cells, h, below != null))
            y += h + 2 * margin + (below?.measuredHeight ?: 0)
        }
        // A child index outside the plan (should not happen): the addon still gets a place, at the end.
        tail = false
        val rest = addonShown()
        if (rest != null && built.none { it.addonAfter }) {
            rest.measure(MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            y += rest.measuredHeight
            tail = true
        }
        rows = built
        setMeasuredDimension(total, y)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val margin = px(MARGIN_DP.toFloat())
        var y = 0
        for (row in rows) {
            // compact rows (fullscreen landscape) are centred; stretched rows fill the width, so their start is 0 either way
            var x = if (compact) ((r - l) - row.cells.sumOf { it.width + 2 * margin }).coerceAtLeast(0) / 2 else 0
            for (c in row.cells) {
                getChildAt(c.index).layout(x + margin, y + margin, x + margin + c.width, y + margin + row.height)
                x += c.width + 2 * margin
            }
            y += row.height + 2 * margin
            if (row.addonAfter) addonShown()?.let { a ->
                a.layout(0, y, a.measuredWidth, y + a.measuredHeight)
                y += a.measuredHeight
            }
        }
        if (tail) addonShown()?.let { a -> a.layout(0, y, a.measuredWidth, y + a.measuredHeight) }
    }

    companion object {
        const val MARGIN_DP = 4
    }
}
