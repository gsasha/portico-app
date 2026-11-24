package com.meda.portico.core.content

/**
 * How a block's buttons are laid out in rows (Home): first every button as compact as it can be, so that as many as possible
 * share a row; then each row is stretched to the full width, the labelled buttons as close in width to each other as
 * possible (across rows too). Pure, in dp: the activity measures each button's natural width and draws the plan.
 *
 * A button is a [Item]: a labelled one has a minimum width ([Item.minDp], from its icon, label and padding) and stretches;
 * an icon-only one (Talk, Call) has the fixed width [Item.minDp] and keeps it, unless a row has nothing else to stretch,
 * in which case its buttons share the row equally. Each button also owns [slotMarginDp] of margin on both sides.
 */
object ButtonFlow {
    /** One button to place: [labelled] false means icon-only (fixed width). */
    data class Item(val minDp: Float, val labelled: Boolean)

    /** One row: indices into the items, in order, and the width of each button (the margins are on top of it). */
    data class Row(val indices: List<Int>, val widthsDp: List<Float>)

    /** More buttons than this are packed greedily (first fit) and then stretched, not searched for the most even split. */
    const val SEARCH_LIMIT = 16

    /** A button's minimum cannot exceed the row: a lone button wider than the screen is cut to it (its label then wraps or ellipsizes). */
    fun plan(items: List<Item>, availableDp: Float, slotMarginDp: Float = 4f, compact: Boolean = false): List<Row> {
        if (items.isEmpty()) return emptyList()
        val slot = 2 * slotMarginDp
        val room = (availableDp - slot).coerceAtLeast(1f)
        val mins = items.map { it.minDp.coerceIn(1f, room) }
        val groups = bestSplit(items, mins, availableDp, slot)
        // 0.6.49 [compact] (the fullscreen camera in landscape): the same rows, but every button keeps its own minimum width and nothing is stretched to the row.
        return groups.map { g -> Row(g, if (compact) g.map { mins[it] } else widths(g.map { items[it] }, g.map { mins[it] }, availableDp - slot * g.size)) }
    }

    private fun fits(sumMin: Float, count: Int, avail: Float, slot: Float) = sumMin + slot * count <= avail + 0.001f

    private fun greedy(mins: List<Float>, avail: Float, slot: Float): List<List<Int>> {
        val rows = ArrayList<List<Int>>()
        var row = ArrayList<Int>()
        var sum = 0f
        for (i in mins.indices) {
            if (row.isNotEmpty() && !fits(sum + mins[i], row.size + 1, avail, slot)) {
                rows.add(row); row = ArrayList(); sum = 0f
            }
            row.add(i); sum += mins[i]
        }
        if (row.isNotEmpty()) rows.add(row)
        return rows
    }

    /** The fewest rows (greedy is optimal for that, order kept); among the splits into that many rows, the most even labelled widths. */
    private fun bestSplit(items: List<Item>, mins: List<Float>, avail: Float, slot: Float): List<List<Int>> {
        val first = greedy(mins, avail, slot)
        val rowsNeeded = first.size
        if (rowsNeeded == 1 || items.size > SEARCH_LIMIT) return first
        var best = first
        var bestScore = score(first, items, mins, avail, slot)
        fun walk(start: Int, left: Int, acc: ArrayList<List<Int>>) {
            if (left == 1) {
                val g = (start until items.size).toList()
                if (!fits(g.sumOf { mins[it].toDouble() }.toFloat(), g.size, avail, slot)) return
                acc.add(g)
                val s = score(acc, items, mins, avail, slot)
                if (s < bestScore - 1e-4f) { bestScore = s; best = ArrayList(acc) }
                acc.removeAt(acc.size - 1)
                return
            }
            var sum = 0f
            for (end in start until items.size - (left - 1)) {
                sum += mins[end]
                if (!fits(sum, end - start + 1, avail, slot)) break
                acc.add((start..end).toList())
                walk(end + 1, left - 1, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        walk(0, rowsNeeded, ArrayList())
        return best
    }

    /** Spread of the labelled widths over the whole plan (max minus min); a row of icons only adds nothing. */
    private fun score(groups: List<List<Int>>, items: List<Item>, mins: List<Float>, avail: Float, slot: Float): Float {
        val labelled = ArrayList<Float>()
        for (g in groups) {
            val w = widths(g.map { items[it] }, g.map { mins[it] }, avail - slot * g.size)
            g.forEachIndexed { k, i -> if (items[i].labelled) labelled.add(w[k]) }
        }
        return if (labelled.size < 2) 0f else labelled.max() - labelled.min()
    }

    /** Widths filling [space] exactly: icons keep their width, labelled buttons share the rest by water-filling (none below its minimum). */
    fun widths(items: List<Item>, mins: List<Float>, space: Float): List<Float> {
        val out = mins.toMutableList()
        val flex = items.indices.filter { items[it].labelled }
        val grow = if (flex.isNotEmpty()) flex else items.indices.toList()
        val fixed = mins.indices.filter { it !in grow }.sumOf { mins[it].toDouble() }.toFloat()
        val budget = space - fixed
        // Level L with sum(max(min_i, L)) = budget: a button whose minimum is above an equal share keeps its minimum, the rest share what is left.
        val sorted = grow.sortedByDescending { mins[it] }
        var level = budget / grow.size
        var remaining = budget
        var n = grow.size
        for (i in sorted) {
            if (mins[i] * n > remaining) { remaining -= mins[i]; n-- } else { level = remaining / n; break }
        }
        for (i in grow) out[i] = maxOf(mins[i], level)
        return out
    }
}
