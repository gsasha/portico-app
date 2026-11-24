package com.meda.portico.core.content

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ButtonFlowTest {
    private fun t(w: Float) = ButtonFlow.Item(w, true)
    private val icon = ButtonFlow.Item(56f, false)

    private fun total(r: ButtonFlow.Row) = r.widthsDp.sum() + 8f * r.indices.size

    private fun check(items: List<ButtonFlow.Item>, avail: Float): List<ButtonFlow.Row> {
        val rows = ButtonFlow.plan(items, avail)
        assertEquals(items.indices.toList(), rows.flatMap { it.indices }, "order kept, each once")
        for (r in rows) {
            assertEquals(avail, total(r), 0.01f, "row fills the width: $r")
            r.indices.forEachIndexed { k, i -> assertTrue(r.widthsDp[k] >= minOf(items[i].minDp, avail - 8f) - 0.01f) }
        }
        return rows
    }

    /** Open door, Talk (icon), Call (icon), Side Door: the sample set. */
    private val sample = listOf(t(110f), icon, icon, t(104f))

    @Test fun `sample set at 360 dp is one row at the minimum`() {
        val rows = check(sample, 360f)
        assertEquals(1, rows.size)
        val w = rows[0].widthsDp
        // 110 + 104 + 2 x 56 + 4 x 8 = 358 of 360: the labels are at their minimum, nothing to stretch to equality.
        assertTrue(w[0] >= 110f && w[3] >= 104f)
        assertEquals(56f, w[1], 0.01f)
        assertEquals(56f, w[2], 0.01f)
        println("360: $w")
    }

    @Test fun `sample set at 411 and 800 dp`() {
        val w411 = check(sample, 411f)[0].widthsDp
        assertEquals(w411[0], w411[3], 0.01f)
        println("411: $w411")
        println("800: " + check(sample, 800f)[0].widthsDp)
        assertEquals(1, check(sample, 800f).size)
    }

    @Test fun `narrow screen wraps into rows with evened widths`() {
        val items = listOf(t(150f), t(150f), t(150f), t(150f))
        val rows = check(items, 360f)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), rows.map { it.indices })
        assertEquals(rows[0].widthsDp[0], rows[1].widthsDp[1], 0.01f)
    }

    @Test fun `split chosen to even widths across rows`() {
        // greedy would give [A B C][D]; the even split is [A B][C D] when both fit.
        val items = listOf(t(60f), t(60f), t(60f), t(200f))
        val rows = check(items, 340f)
        assertEquals(2, rows.size)
        val w = rows.flatMap { r -> r.widthsDp }
        assertTrue(w.max() - w.min() < 80f)
    }

    @Test fun `icons only row shares the width`() {
        val rows = check(listOf(icon, icon), 360f)
        assertEquals(rows[0].widthsDp[0], rows[0].widthsDp[1], 0.01f)
    }

    @Test fun `button wider than the screen is cut to the row`() {
        val rows = check(listOf(t(900f)), 300f)
        assertEquals(292f, rows[0].widthsDp[0], 0.01f)
    }

    @Test fun `empty and many buttons`() {
        assertTrue(ButtonFlow.plan(emptyList(), 360f).isEmpty())
        check(List(20) { t(90f + it) }, 411f)
    }

    @Test
    fun `compact keeps the same rows but every button at its own minimum width, nothing stretched`() {
        val items = listOf(t(110f), icon, icon, t(104f))
        val stretched = ButtonFlow.plan(items, 700f)
        val compact = ButtonFlow.plan(items, 700f, compact = true)
        assertEquals(stretched.map { it.indices }, compact.map { it.indices }, "the same rows")
        val flat = compact.flatMap { r -> r.indices.zip(r.widthsDp) }
        for ((i, w) in flat) assertEquals(items[i].minDp, w, 0.01f, "button $i keeps its width")
        assertTrue(compact.all { total(it) < 700f - 20f }, "the row is not filled")
        assertEquals(700f, total(stretched.single()), 0.01f, "the default still fills the width")
    }
}
