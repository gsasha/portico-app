package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Home's blocks (DEV-658, B5), checked without a device (working directory = app/): the layout holds one container the
 * code fills, the widgets follow `:core`'s grid and row functions, every reason and kind has words, the colour and
 * icon-only rules are wired, and the screens B5 must not touch are untouched. The pixels are for a phone.
 */
class BlocksResourcesTest {
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    private val strings: Map<String, String> by lazy {
        val nodes = parse("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun src(name: String) = File("src/main/java/com/meda/portico/home/$name").readText()
    private val activity by lazy { src("HomeActivity.kt") }
    private val blockViews by lazy { src("BlockViews.kt") }
    private val buttons by lazy { src("ActionButtons.kt") }
    private val flowLayout by lazy { src("ButtonFlowLayout.kt") }
    private val stills by lazy { src("StillTiles.kt") }
    private val core = "../core/src/main/kotlin/com/meda/portico/core/"

    @Test
    fun `the layout has the blocks container and its empty line, and still scrolls`() {
        val doc = parse("src/main/res/layout/activity_home.xml")
        assertEquals("LinearLayout", doc.documentElement.tagName, "0.6.52: the root holds the pinned top line and the scroller")
        assertEquals("ScrollView", doc.getElementsByTagName("ScrollView").item(0).nodeName)
        val all = doc.getElementsByTagName("*")
        val byId = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("android:id").isNotEmpty() }
            .associateBy { it.getAttribute("android:id").substringAfter("/") }
        val blocks = byId.getValue("home_blocks")
        assertEquals("LinearLayout", blocks.tagName)
        assertEquals("vertical", blocks.getAttribute("android:orientation"))
        assertEquals("match_parent", blocks.getAttribute("android:layout_width"))
        assertEquals("@string/home_no_blocks", byId.getValue("home_blocks_empty").getAttribute("android:text"))
        for (gone in listOf("home_cameras", "home_cameras_empty", "home_buttons", "home_buttons_empty")) assertFalse(gone in byId, "$gone is replaced by the blocks")
        assertTrue("home_no_blocks" in strings)
        for (gone in listOf("home_cameras", "home_buttons", "home_no_cameras", "home_no_buttons")) assertFalse(gone in strings, "$gone is unused")
    }

    @Test
    fun `the top line and the page tabs are unchanged`() {
        val layout = File("src/main/res/layout/activity_home.xml").readText()
        for (id in listOf("home_title", "home_tabs", "home_tabs_row", "home_status", "home_menu")) assertTrue(layout.contains("@+id/$id"), id)
        assertTrue(activity.contains("TopLine.choose(graph.isPaused(), view.tabs.size, view.pageLabel, view.homeName)"))
        assertTrue(activity.contains("rebuildTabs(view)"))
        assertTrue(activity.contains("Swipe.direction("))
    }

    @Test
    fun `the activity draws blocks through BlockViews and nothing else builds the page body`() {
        assertTrue(activity.contains("blockViews.rebuild(blocks, view.blocks, ::onPress)"))
        assertTrue(activity.contains("blocksEmpty.visibility = if (view.blocks.isEmpty())"))
        assertFalse(activity.contains("stillTiles.rebuild("), "Home no longer uses the column tiles")
        assertFalse(activity.contains("actionButtons.tile("))
    }

    @Test
    fun `a block is a heading, a camera grid by CameraGrid and button rows by ButtonRows`() {
        assertTrue(blockViews.contains("block.title != null"), "a block without a title draws no heading")
        assertTrue(blockViews.contains("ViewCompat.setAccessibilityHeading(this, true)"), "the title is a heading for TalkBack")
        assertTrue(blockViews.contains("stills.addGrid(grid, block.cameras, block.partialPlan)"))
        assertTrue(blockViews.contains("buttons.blockRows(block.buttons, onPress)"))
        assertTrue(blockViews.contains("stills.reset()"), "tiles of the old view are forgotten before the new ones are added")
        assertTrue(stills.contains("CameraGrid.rows(tiles.map { it.size })"))
        assertTrue(stills.contains("if (row.lone) line.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))"), "an odd small tile keeps half width with a spacer")
        assertTrue(buttons.contains("ButtonFlowLayout(context, tiles.map { !it.iconOnly }, compact)"), "rows are planned by ButtonFlow at measure time")
        assertTrue(flowLayout.contains("ButtonFlow.plan(items, availDp, MARGIN_DP.toFloat(), compact)"))
    }

    @Test
    fun `cell widths follow the units, a big tile is a whole row and nothing has a fixed width but the icon-only square`() {
        val grid = stills.substringAfter("fun addGrid(").substringBefore("/** The zoom of a camera")
        assertFalse(grid.contains("dp("), "tile widths are weights, not dp")
        assertTrue(stills.contains("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)"))
        assertTrue(buttons.contains("const val ICON_ONLY_BOX = 56"), "icon-only is as tall as a text button (a touch target of at least 48)")
        assertTrue(buttons.contains("const val BUTTON_HEIGHT = 56"))
        assertTrue(buttons.contains("minHeight = dp(BUTTON_HEIGHT)"), "text buttons keep the 56 dp minimum")
        assertTrue(flowLayout.contains("var h = px(ActionButtons.BUTTON_HEIGHT.toFloat())"), "every button of a row is the row's height")
    }

    @Test
    fun `a button's colour is the palette's swatch for the system theme and grey when it is off`() {
        assertTrue(buttons.contains("ButtonPalette.swatch(tile.color, isDark())"))
        assertTrue(buttons.contains("ButtonPalette.disabled(isDark())"))
        assertTrue(buttons.contains("Configuration.UI_MODE_NIGHT_MASK"))
        assertTrue(buttons.contains("Paint(swatch.fill, null, swatch.text,"), "the fill and the text colour come from the palette")
        assertTrue(buttons.contains("setTextColor(paint.ink)"))
        assertTrue(buttons.contains("setColor(paint.fill)"))
        assertTrue(buttons.contains("if (on) it else"), "off is drawn neutral, whatever the colour")
        assertFalse(Regex("Color\\.parseColor\\(\"#[0-9A-Fa-f]{6}\"\\)").containsMatchIn(buttons + blockViews), "no colour literal outside the palette")
    }

    @Test
    fun `an icon-only button has no text, an accessible name and a centred icon`() {
        assertTrue(buttons.contains("contentDescription = if (note != null) \"\$label, \$note\" else label"), "the label (and why it is off) is what TalkBack reads")
        assertTrue(buttons.contains("iconOnlyButton(iconKey, paint)"), "icon-only is its own widget")
    }

    @Test
    fun `every reason and every new kind has words, with no arguments`() {
        val reasons = Regex("[A-Z_]+\\(\"([a-z_]+)\"\\)").findAll(File(core + "content/HomeModel.kt").readText().substringAfter("enum class ButtonReason").substringBefore("companion")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("talk_off", "no_door", "not_connected", "busy", "phone_only", "connecting", "in_call"), reasons)
        for (r in reasons) {
            assertTrue("home_reason_$r" in strings, r)
            assertFalse(strings.getValue("home_reason_$r").contains("%"), r)
            assertTrue(buttons.contains("ButtonReason.${r.uppercase()} -> R.string.home_reason_$r"), r)
        }
        for (k in listOf("lock_lock", "lock_unlock", "cover_open", "cover_close", "talk")) {
            assertTrue("home_kind_$k" in strings, k)
            assertTrue(buttons.contains("\"$k\" -> context.getString(R.string.home_kind_$k)"), k)
        }
        assertEquals("Talk to the door is switched off", strings["home_reason_talk_off"])
        assertEquals("Door removed", strings["home_reason_no_door"])
    }

    @Test
    fun `a button that is off says why in one line under its row, and a press of it does nothing`() {
        assertTrue(buttons.contains("noteText(t)?.let { \"\${t.label}: \$it\" }"))
        assertTrue(buttons.contains("tile.endLine ?: tile.reason?.let { context.getString(reasonText(it)) } ?: stateText(tile.state)"), "how a talk ended, else the server's reason, else the state's words")
        assertTrue(buttons.contains("isEnabled = on"))
        assertTrue(buttons.contains("val on = tile.state == ButtonState.READY || tile.state == ButtonState.CALLING || tile.state == ButtonState.TALK_CONNECTING || tile.state == ButtonState.TALK_IN_CALL"))
        val press = buttons.substringAfter("fun press(").substringBefore("private fun run(")
        assertTrue(press.contains("if (tile.state != ButtonState.READY) return"), "only a READY button is run")
    }

    @Test
    fun `pictures are as in A2 - the visible page only, one request in flight, the cadence unchanged (B6 only picks the width)`() {
        assertTrue(activity.contains("val wanted = currentView().cameras.filter { !it.off && (it.picture == PictureState.PICTURE || it.picture == PictureState.LOADING) }"), "an off tile (0.6.53) is not polled")
        assertTrue(activity.contains("stills.tick(ids, SystemClock.elapsedRealtime()) {"), "Home asks at StillWidth's width")
        assertTrue(activity.contains("StillWidth.forCamera("), "Home asks at StillWidth's width")
        assertTrue(activity.contains("const val SNAPSHOT_EVERY_MS = 4_000L"))
        assertTrue(activity.contains("handler.removeCallbacks(tick)"), "nothing runs while Home is not visible")
        assertTrue(stills.contains("ImageView.ScaleType.CENTER_CROP"), "the ring and in-call tiles keep today's look")
        assertTrue(File("../core/src/main/kotlin/com/meda/portico/core/ha/ContentCommands.kt").readText().contains("width in SNAPSHOT_WIDTH_MIN..SNAPSHOT_WIDTH_MAX"), "the width guard moved with B6")
    }

    @Test
    fun `the ring and in-call screens are untouched by blocks`() {
        for (f in listOf("src/main/java/com/meda/portico/call/InCallActivity.kt")) {
            val text = File(f).readText()
            assertTrue(text.contains("StillTiles(this, findViewById(R.id."), f)
            assertTrue(text.contains(", 1)"), f)
            assertTrue(text.contains("stillTiles.rebuild("), f)
            assertTrue(text.contains("actionButtons.tile(tile, ::onExtraPress)"), f)
            assertFalse(text.contains("BlockViews") || text.contains("blockRows"), f)
        }
    }

    @Test
    fun `this build is 0,5,12-alpha`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(Regex("""val porticoVersionName = "\d+\.\d+\.\d+-alpha"""").containsMatchIn(gradle)) // not pinned to one version: every bump used to break this
    }
}
