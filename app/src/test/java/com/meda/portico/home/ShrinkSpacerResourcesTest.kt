package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * "When I minimize a camera, the items following it disappear" (0.5.4 to 0.5.6): the cause, and the 0.5.7-alpha journal.
 * The cause (a half-width spacer measured to the whole screen height, see StillTiles.spacer) is Android's own measure
 * arithmetic, which no JVM test can run; what is pinned here is (1) the arithmetic itself, copied from `View.getDefaultSize`,
 * (2) that no spacer in the screens' code can ask for a WRAP_CONTENT height again, (3) the journal's wiring.
 */
class ShrinkSpacerResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val blockViews by lazy { src("home/BlockViews.kt") }
    private val activity by lazy { src("home/HomeActivity.kt") }
    private val buttons by lazy { src("home/ActionButtons.kt") }

    // MeasureSpec modes and View.getDefaultSize, as the framework has them (a plain View's onMeasure is getDefaultSize(min, spec)).
    private val unspecified = 0
    private val exactly = 1 shl 30
    private val atMost = 2 shl 30

    private fun defaultSize(size: Int, mode: Int, specSize: Int): Int = when (mode) {
        unspecified -> size
        else -> specSize // AT_MOST and EXACTLY: the whole spec size
    }

    @Test
    fun `a plain View with WRAP_CONTENT height takes ALL the room an AT_MOST spec offers, which is what ate the rows after a shrunken tile`() {
        // First pass of Home's ScrollView (child UNSPECIFIED): the spacer is 0 high, everything looks right ...
        assertEquals(0, defaultSize(0, unspecified, 0))
        // ... but when fillViewport re-measures the content EXACTLY screen-high (content now shorter than the screen),
        // the blocks get AT_MOST(what is left): the empty spacer claims it all and its row grows to the end of the screen.
        assertEquals(900, defaultSize(0, atMost, 900))
    }

    @Test
    fun `the half-width spacer has a one pixel height, never WRAP_CONTENT, in both tile grids`() {
        val params = tiles.substringAfter("private fun spacerCell()").lineSequence().first()
        assertTrue(params.contains("LinearLayout.LayoutParams(0, 1, 1f)"), params)
        assertFalse(params.contains("WRAP_CONTENT"))
        assertTrue(tiles.contains("if (row.lone) line.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))"))
        assertTrue(tiles.contains("row.addView(spacer(), spacerCell())"))
        // No other empty View goes into a row with the tiles' own cell() (a WRAP_CONTENT height).
        assertFalse(Regex("addView\\(View\\(context\\), cell\\(\\)\\)").containsMatchIn(tiles))
        assertEquals(1, Regex("(?<![A-Za-z])View\\(context\\)").findAll(tiles).count(), "the one empty View is spacer()")
    }

    @Test
    fun `the buttons need no spacer, the flow stretches every row to the full width`() {
        assertFalse(buttons.contains("spare"))
    }

    @Test
    fun `each block is built on its own, a failure is journalled and logged, and the rest are still drawn`() {
        val rebuild = blockViews.substringAfter("fun rebuild(")
        assertTrue(rebuild.contains("for (block in blocks) {"))
        val loop = rebuild.substringAfter("for (block in blocks) {")
        assertTrue(loop.contains("val failure = try {") && loop.contains("} catch (e: Exception) {"))
        assertTrue(loop.contains("container.addView(box"), "the block is added inside its own try")
        assertTrue(loop.substringAfter("val failure = try {").substringBefore("} catch (e: Exception) {").contains("container.addView(box"))
        val handler = loop.substringAfter("} catch (e: Exception) {").substringBefore("stats.add(")
        assertTrue(handler.contains("LayoutJournal.describe(e)") && handler.contains("Log.e("), "never swallowed silently")
        assertFalse(handler.contains("throw") || handler.contains("return"), "the loop goes on to the next block")
        assertTrue(rebuild.contains("LayoutJournal.add(line)"), "one journal line after every rebuild")
        assertTrue(rebuild.contains("block.cameraRows.size") && rebuild.contains("grid.childCount") && rebuild.contains("rows.childCount"))
    }

    @Test
    fun `Home journals the heights after the next layout pass and the layout log has no copy action`() {
        assertTrue(activity.contains("journalMeasured()"))
        val measured = activity.substringAfter("private fun journalMeasured()").substringBefore("/** One text per page in the top line")
        assertTrue(measured.contains("addOnGlobalLayoutListener") && measured.contains("removeOnGlobalLayoutListener(this)") && measured.contains("LayoutJournal.measuredLine("))
        assertFalse(activity.contains("copyLayoutLog") || activity.contains("MENU_LAYOUT_LOG"), "APP-01: no copy log action")
    }

    @Test
    fun `the toggle's comment no longer blames the redraw timing`() {
        assertFalse(activity.contains("likely (not proven: no emulator) cause"))
        assertTrue(activity.contains("StillTiles.spacer"))
    }
}
