package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 0.6.71: the "Data used by this app" table must fit any phone width (no emulator here, so the layout rules are pinned as source patterns).
 * Working directory = app/.
 */
class TrafficTableLayoutTest {
    private val src = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
    private val render: String = src.substringAfter("private fun renderTraffic()").substringBefore("/** The text last put on screen")
    private val xml = File("src/main/res/layout/activity_setup.xml").readText()

    @Test
    fun `columns are weighted, never a fixed width`() {
        assertTrue("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)" in render)
        assertTrue("0.30f" in render && "0.35f" in render)
        assertFalse("minWidth" in render, "a minimum width is what pushed Wi-Fi off the screen")
        assertFalse(Regex("""\bwidth\s*=\s*\(""").containsMatchIn(render))
        assertFalse(Regex("""LayoutParams\(\s*\(?\d+\s*\*\s*dp""").containsMatchIn(render), "no dp-sized cell widths")
    }

    @Test
    fun `the period is a full-width heading row of its own above the column headers`() {
        val heading = render.indexOf("text = label")
        assertTrue(heading > 0)
        assertTrue(render.lastIndexOf("LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT", heading) in 0 until heading)
        assertTrue(render.indexOf("setup_traffic_mobile") > heading, "Mobile / Wi-Fi headers come after the heading row")
    }

    @Test
    fun `cells are single line and shrink to fit`() {
        assertTrue("setSingleLine(true)" in render)
        assertTrue("setAutoSizeTextTypeUniformWithConfiguration" in render)
        assertTrue("minSp: Int = 11" in render && "maxSp: Int = 14" in render)
    }

    @Test
    fun `the layout holds a plain stack, the table is built in code`() {
        val tag = xml.substringAfter("android:id=\"@+id/setup_traffic_rows\"").substringBefore("/>")
        assertTrue("match_parent" in tag)
        assertFalse("stretchColumns" in xml)
    }
}
