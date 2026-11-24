package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Camera sizes and pinch zoom (DEV-658, B6), checked without a device (working directory = app/): the plumbing is
 * wired to `:core`'s rules, the words exist, the screens B6 must not touch are untouched. Every rule itself (tap vs
 * pinch vs pan, the sizes, the widths) is a JVM test in `:core`; the pixels, the matrix and the gestures are for a phone.
 */
class SizesZoomResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val activity by lazy { src("home/HomeActivity.kt") }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val touch by lazy { src("home/TileTouch.kt") }
    private val graph by lazy { src("AppGraph.kt") }
    private val sizeMemorySource by lazy { File("../core/src/main/kotlin/com/meda/portico/core/content/SizeMemory.kt").readText() }

    /** The text between `from` and the next [until] after it. */
    private fun between(text: String, from: String, until: String): String = text.substringAfter(from).substringBefore(until)

    // ---- the banner (item 6) -------------------------------------------------------------

    @Test
    fun `the no-content banner points at the Portico panel in the sidebar, not at the Configure flow E7 removed`() {
        val text = strings.getValue("home_banner_no_content")
        assertTrue(text.contains("sidebar"), text)
        assertTrue(text.contains("Portico"), text)
        for (gone in listOf("Configure", "Choose what a phone shows", ">")) assertFalse(text.contains(gone), "stale: $gone")
        assertFalse(File("../README.md").readText().contains("Choose what a phone shows"), "the README says the same")
    }

    // ---- the menu ------------------------------------------------------------------------

    @Test
    fun `the menu has no Reset camera sizes any more - the per-camera gear menu and the resize menu are the way back`() {
        assertFalse(activity.contains("MENU_RESET_SIZES") || activity.contains("resetSizes"))
        assertFalse("home_menu_reset_sizes" in strings || "home_sizes_reset" in strings || "home_sizes_reset_none" in strings)
        assertFalse(strings.getValue("home_menu_description").contains("reset"))
        assertFalse(Regex("fun clear").containsMatchIn(sizeMemorySource), "SizeMemory.clear served only that item")
    }

    // ---- the tap shows the strip; the resize menu sets and remembers the size (DEV-768 P3) -------------

    @Test
    fun `a tap no longer flips the size - it shows the strip, and the resize menu sets the size by the server's default, remembers it and sends nothing`() {
        assertFalse(activity.contains("toggleSize") || tiles.contains("onToggleSize"), "tap-to-resize is dropped")
        val set = between(activity, "override fun setTileSize(tile: CameraTile, size: CameraSize)", "override fun streamChanged()")
        assertTrue(set.contains("graph.sizes.set(pageId, tile.id, tile.defaultSize, size)"))
        assertFalse(set.contains("signaling") || set.contains("requestContent") || set.contains("runAction"), "nothing is sent (offline too)")
        val redraw = between(activity, "override fun redraw()", "override fun press(")
        assertTrue(redraw.contains("render()"), "instantly re-laid out")
        assertTrue(redraw.contains("stills.reset()"), "the new size has its own still width, and every other tile is repainted")
        assertTrue(activity.contains("override fun onTap(tile: CameraTile) = viewport.onTap(tile)"))
        assertTrue(activity.contains("selectedPageId, graph.sizes, graph.talk.state(), graph.doors.all, station())"), "the model reads the memory")
    }

    @Test
    fun `the memory is reconciled only after a real answer from HA`() {
        val listener = between(activity, "private val contentListener", "private val tick")
        assertTrue(listener.contains("if (graph.content.answered) graph.sizes.reconcile(graph.content.profile)"))
        assertTrue(graph.contains("val sizes: SizeMemory = SizeMemory(PrefsSizeStore("))
    }

    // ---- pinch zoom ----------------------------------------------------------------------

    @Test
    fun `a tile is placed by a matrix inside a box that never changes, with no double tap anywhere`() {
        assertTrue(tiles.contains("if (interactive) ImageView.ScaleType.MATRIX else ImageView.ScaleType.CENTER_CROP"), "ring and in-call tiles keep centre crop")
        assertTrue(tiles.contains("ZoomMath.placement(sw, sh, w, h, g.scale, g.tx, g.ty)"))
        assertTrue(tiles.contains("v.image.imageMatrix = matrix"))
        for (text in listOf(tiles, touch)) {
            assertFalse(Regex("(?<!Scale)GestureDetector").containsMatchIn(text) || text.contains("onDoubleTap"),"a tap while zoomed resets, so double tap is not used")
        }
        val zoomPath = between(tiles, "private fun applyMatrix", "/** Puts each tile's picture")
        assertFalse(zoomPath.contains("layoutParams") || zoomPath.contains("scaleX") || zoomPath.contains("pivot"), "the viewport (the tile) never changes")
        assertTrue(touch.contains("ScaleGestureDetector(") && touch.contains("isQuickScaleEnabled = false"))
        assertTrue(touch.contains("gesture.down(") && touch.contains("gesture.up(") && touch.contains("gesture.cancel()") && touch.contains("gesture.pointerDown()") && touch.contains("gesture.pointerUp("))
        assertTrue(touch.contains("requestDisallowInterceptTouchEvent(true)"), "the scroll view must not take a pinch or a pan")
        assertTrue(touch.contains("if (gesture.zoomed) v.parent?.requestDisallowInterceptTouchEvent(true)"))
        assertTrue(touch.contains("if (ev.pointerCount == 1) gesture.move(ev.x, ev.y)"), "with two fingers the detector drives the picture")
    }

    @Test
    fun `only Home's tiles are interactive - the ring and in-call screens are the plain column`() {
        assertTrue(tiles.contains("tile(tile, interactive = false)"), "rebuild is the call screens' path")
        assertTrue(tiles.contains("tile(tiles[i], interactive = true)"), "addGrid is Home's path")
        for (f in listOf("call/InCallActivity.kt")) {
            val text = src(f)
            assertFalse(text.contains("listener") || text.contains("TileTouch") || text.contains("resetZoom"), f)
            assertTrue(text.contains("graph.requestSnapshot(it)"), "$f asks for no width")
        }
    }

    @Test
    fun `zoom ends on a page change, on leaving the screen and on a size flip, and is not saved anywhere`() {
        assertTrue(between(activity, "private fun selectPage", "private fun showMenu").contains("stillTiles.resetZoom()"))
        assertTrue(between(activity, "override fun onStop()", "// ---- state").contains("stillTiles.resetZoom()"))
        assertTrue(tiles.contains("takeIf { it.size == tile.size }"), "a tile at a new size starts at 1x")
        assertTrue(tiles.contains("fun resetZoom()"))
        for (f in File("src/main/java/com/meda/portico/store").listFiles()!!.filter { it.name.endsWith(".kt") }) {
            assertFalse(f.readText().contains("TileGesture") || f.readText().contains("zoom", ignoreCase = true), "${f.name} stores no zoom")
        }
    }

    @Test
    fun `no tile with a picture missing can be zoomed`() {
        assertTrue(tiles.contains("v.gesture?.zoomEnabled = tile.picture == PictureState.PICTURE"))
    }

    // ---- the page swipe ------------------------------------------------------------------

    @Test
    fun `the page swipe asks the guard - not while zoomed, not with a second finger, still only watching`() {
        val swipe = between(activity, "override fun dispatchTouchEvent", "/** A tap on a tile")
        assertTrue(swipe.contains("swipeGuard.down(onStrip || viewport.isFullscreen(), stillTiles.anyZoomed()"))
        assertTrue(swipe.contains("MotionEvent.ACTION_POINTER_DOWN -> swipeGuard.pointerDown()"))
        assertTrue(swipe.contains("if (swipeGuard.up())"))
        assertTrue(swipe.contains("MotionEvent.ACTION_CANCEL -> swipeGuard.cancel()"))
        assertTrue(swipe.contains("return super.dispatchTouchEvent(ev)"), "the activity still never consumes a touch")
        assertFalse(activity.contains("swipeActive"))
    }

    // ---- stills --------------------------------------------------------------------------

    @Test
    fun `the width follows the tile's size and zoom, a width change only resets that camera's schedule, and decode is sampled`() {
        assertTrue(activity.contains("StillWidth.forCamera(sizes[it], stillTiles.zoomOf(it), callSizes[it], panelTiles.zoomOf(it))"))
        assertTrue(activity.contains("override fun onWidthChanged(cameraId: String) = stills.reset(cameraId)"))
        assertTrue(tiles.contains("if (StillWidth.changes(tile.size, v.bandZoom, g.scale))"))
        assertTrue(tiles.contains("inSampleSize = ImageSampling.sampleSize("))
        assertTrue(tiles.contains("inJustDecodeBounds = true"))
        assertTrue(tiles.contains("catch (_: OutOfMemoryError)"), "a failed decode leaves the old picture")
        assertTrue(graph.contains("signaling.post { if (!it.requestSnapshot(cameraId, width)) content.endSnapshot(cameraId) }"))
        assertTrue(graph.contains("if (!content.beginSnapshot(cameraId)) return false"), "still one request in flight per camera")
    }

    // ---- accessibility -------------------------------------------------------------------

    @Test
    fun `each tile has TalkBack actions for the size and the zoom, and says its state`() {
        for (a in listOf("home_tile_action_controls", "home_tile_action_zoom_in", "home_tile_action_zoom_out", "home_tile_action_reset_zoom", "home_tile_hint_reset_zoom")) {
            assertTrue(a in strings, a)
            assertTrue(tiles.contains("R.string.$a"), a)
            assertFalse(strings.getValue(a).contains("%"), a)
        }
        assertTrue(Regex("ViewCompat\\.addAccessibilityAction\\(v\\.frame").findAll(tiles).count() == 5, "resize (3 in a loop), stream pick (in a loop), zoom in, zoom out, reset zoom")
        assertTrue(tiles.contains("ViewCompat.replaceAccessibilityAction(v.frame, AccessibilityActionCompat.ACTION_CLICK"), "the double tap says what it will do")
        assertTrue(tiles.contains("v.frame.setOnClickListener { handle(v, g.activate()) }"), "TalkBack's click is the tap")
        for (d in listOf("home_tile_desc_big", "home_tile_desc_small", "home_tile_desc_zoomed")) {
            assertEquals(1, Regex("%1\\\$s").findAll(strings.getValue(d)).count(), d)
            assertTrue(tiles.contains("R.string.$d"), d)
        }
        assertEquals("%1\$s, big", strings["home_tile_desc_big"])
        assertEquals("%1\$s, zoomed", strings["home_tile_desc_zoomed"])
        assertTrue(tiles.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"), "the label is not read twice")
    }

    // ---- what B6 must not touch ----------------------------------------------------------

    @Test
    fun `B6 touches no call logic, no Talk and no wire command`() {
        for (f in File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = f.readText()
            assertFalse(text.contains("\"portico/dial\""), f.name)
        }
        // D-20261121-c98a: HomeActivity (a tap) and AppGraph (the panel's Call phones request, see PanelCallWiringTest) are the only callers.
        assertEquals(listOf("AppGraph.kt", "HomeActivity.kt"), File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("place.press(") }.map { it.name }.sorted().toList())
        val commands = File("../core/src/main/kotlin/com/meda/portico/core/ha/ContentCommands.kt").readText()
        assertTrue(commands.contains("const val SNAPSHOT = \"portico/content/snapshot\""))
        assertEquals(1, Regex("\"portico/content/snapshot\"").findAll(commands).count(), "the same one snapshot command")
    }

    @Test
    fun `this release is 0,6,6-alpha, versionCode 61710 (derived), recorded in the build file only`() {
        val gradle = File("build.gradle.kts").readText()
        assertEquals(1, Regex("val porticoVersionName = ").findAll(gradle).count())
        assertTrue(Regex("""val porticoVersionName = "\d+\.\d+\.\d+-alpha"""").containsMatchIn(gradle)) // not pinned to one version: every bump used to break this
        assertTrue(File("../scripts/version-guard-test.sh").readText().contains("expect_code 0.6.17-alpha 61710"))
    }

    // ---- shrinking a camera must not drop what follows it (0.5.5-alpha) ---------------------

    @Test
    fun `a resize rebuilds the grid after the tile's own touch is over, never inside it, and repaints every tile`() {
        // The memory is set now; the teardown of the views that are receiving this very ACTION_UP is posted.
        val set = between(activity, "override fun setTileSize(tile: CameraTile, size: CameraSize)", "override fun streamChanged()")
        assertFalse(set.substringBefore("redraw()").contains("render()"), "no rebuild (removeAllViews) inside the tapped tile's touch dispatch")
        val posted = between(activity, "override fun redraw()", "override fun press(").substringAfter("blocks.post")
        assertTrue(posted.contains("stills.reset()"), "every camera of the page is due, not only the resized one")
        assertTrue(posted.indexOf("stills.reset()") < posted.indexOf("render()"))
        assertTrue(posted.contains("render()") && posted.contains("blocks.requestLayout()") && posted.contains("requestPicturesIfDue()"))
        assertTrue(posted.contains("isFinishing || isDestroyed"), "the post can outlive the screen")
    }

    @Test
    fun `every rebuild of Home's blocks asks for a fresh layout of the whole container`() {
        val rebuild = between(activity, "private fun rebuild(view: HomeView)", "private fun rebuildTabs")
        assertTrue(rebuild.contains("blockViews.rebuild(blocks, view.blocks, ::onPress)"))
        assertTrue(rebuild.substringAfter("blockViews.rebuild").contains("blocks.requestLayout()"))
    }
}
