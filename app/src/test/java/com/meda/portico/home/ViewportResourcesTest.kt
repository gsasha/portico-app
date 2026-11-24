package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DEV-768 P3 (camera viewport controls), checked without a device (working directory = app/). Every rule (menus, stream, strip timing, memory)
 * is a JVM test in `:core` (`CameraViewportTest`); here only that the glue is wired to them, that nothing is stored, and the words exist. How
 * it looks and feels is for a phone.
 */
class ViewportResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val controller by lazy { src("home/ViewportController.kt") }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val activity by lazy { src("home/HomeActivity.kt") }

    @Test
    fun `the glue asks core for every decision`() {
        for (call in listOf("CameraViewport.resizeItems(", "CameraViewport.streamMenu(", "CameraViewport.resize(", "CameraViewport.stream(", "CameraViewport.pickLine(", "CameraViewport.resizeLine(", "ViewportMemory.decode(", "ControlStrip()", "memory.retain(")) {
            assertTrue(controller.contains(call), call)
        }
    }

    @Test
    fun `partial layout is offered in panel mode only and drawn from core's plan`() {
        assertTrue(activity.contains("override fun partialAvailable(): Boolean = graph.panelMode()"))
        assertTrue(activity.contains("CameraViewport.withPartial("))
        assertTrue(controller.contains("partialOffered = host.partialAvailable()"))
        assertTrue(controller.contains("fun partialAvailable(): Boolean = false"), "the default is off: phones never see it")
        assertTrue(src("home/BlockViews.kt").contains("block.partialPlan"))
        assertTrue(File("src/main/res/drawable/ic_view_partial.xml").exists())
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("name=\"home_resize_partial\""))
    }

    @Test
    fun `the pick is in memory only - nothing in the controller or the stores writes it to disk`() {
        assertFalse(controller.contains("SharedPreferences") || controller.contains("getSharedPreferences") || controller.contains("Prefs"))
        for (f in File("src/main/java/com/meda/portico/store").listFiles()!!.filter { it.name.endsWith(".kt") }) {
            assertFalse(f.readText().contains("ViewportMemory") || f.readText().contains("StreamPick"), "${f.name} stores no pick")
        }
        assertTrue(activity.contains("outState.putString(KEY_VIEWPORT"), "a rotation keeps it, through the saved instance state only")
    }

    @Test
    fun `the live plan is given the viewport's stream, not the tile's size, and the tile's size still lays out the grid`() {
        assertTrue(activity.contains("else viewport.stream(t, view.selectedId)"), "still the viewport's stream, capped to small only by the network row")
        assertTrue(activity.contains("val sizes = wanted.associate { it.id to it.size }"), "the still width follows the layout")
    }

    @Test
    fun `fullscreen builds a second tile and never re-parents the page tile's video view`() {
        assertFalse(controller.contains("removeView(r") || controller.contains("SurfaceView"), "no view of the page's tile is moved")
        assertTrue(controller.contains("tiles.addFullscreen("))
        assertTrue(controller.contains("fun pageLiveViews(cameraId: String): LiveTileView? = if (cameraId == fullscreenCamera()) null"), "the page tile lets go of the stream first")
        assertTrue(controller.contains("blockRows(buttons, host::press, compact = landscape)"), "the block's own buttons, with their hold-to-confirm, over the video")
        assertTrue(controller.contains("elevation = 100f") && controller.contains("isClickable = true"))
    }

    @Test
    fun `the strip is last in the tile, the fps label moved bottom-left, and a tap no longer toggles anything`() {
        assertTrue(tiles.indexOf("it.setBackground(pill())\n                it.setTypeface") < tiles.indexOf("controlStrip(tile)"), "the strip is added after the chip (which now holds the fps)")
        assertTrue(tiles.contains("if (action == TileAction.SHOW_CONTROLS) listener?.onTap(v.tile)"))
        assertTrue(tiles.contains("if (interactive) strip = controlStrip(tile)"), "stills get the strip too; the ring and in-call tiles do not")
    }

    @Test
    fun `back leaves fullscreen and the page does not swipe under it`() {
        assertTrue(controller.contains("OnBackPressedCallback(false)") && controller.contains("handleOnBackPressed() = leaveFullscreen()"))
        assertTrue(activity.contains("swipeGuard.down(onStrip || viewport.isFullscreen()"))
    }

    @Test
    fun `the words exist`() {
        for (k in listOf(
            "home_strip_resize", "home_strip_settings", "home_resize_big", "home_resize_small", "home_resize_fullscreen", "home_resize_exit",
            "home_stream_title", "home_stream_auto", "home_stream_auto_plain", "home_stream_high", "home_stream_low", "home_stream_desc", "home_stream_stills", "home_stream_off", "home_picture_off",
            "home_tile_action_controls", "home_tile_action_resize", "home_tile_action_stream", "home_tile_desc_fullscreen",
        )) {
            assertTrue(k in strings, k)
        }
        assertTrue(strings.getValue("home_stream_auto").contains("%1\$s"))
        assertTrue(File("src/main/res/drawable/ic_tile_resize.xml").exists())
    }

    @Test
    fun `the fullscreen button row has no scrim or background behind it`() {
        val from = controller.indexOf("val bar = LinearLayout(activity).apply {")
        assertTrue(from > 0, "the fullscreen button row container is still built here")
        val block = controller.substring(from, controller.indexOf("root.addView(bar", from))
        for (bad in listOf("setBackground", "background =", "setBackgroundColor", "GradientDrawable", "ColorDrawable")) {
            assertFalse(block.contains(bad), "no $bad on the button row container")
        }
    }

    @Test
    fun `the fullscreen overlay has a one-tap exit button on the same exit path, with no scrim on any container`() {
        val ensure = controller.substring(controller.indexOf("private fun ensureOverlay"), controller.indexOf("private fun fillButtons"))
        assertTrue(ensure.contains("root.addView(exitButton()"), "the overlay adds the exit button")
        assertTrue(ensure.contains("Gravity.TOP or Gravity.END"), "top-right: clear of the LIVE chip (top-left) and the strip (bottom-right)")
        val fn = controller.substring(controller.indexOf("private fun exitButton"), controller.indexOf("private fun fillButtons"))
        assertTrue(fn.contains("setOnClickListener { leaveFullscreen() }"), "the same exit as Back")
        assertTrue(controller.contains("override fun handleOnBackPressed() = leaveFullscreen()"))
        assertTrue(fn.contains("R.drawable.ic_view_fullscreen_exit"))
        assertTrue(fn.contains("R.string.home_exit_fullscreen_button"))
        assertTrue(strings.getValue("home_exit_fullscreen_button") == "Exit full screen")
        assertTrue(controller.contains("EXIT_BUTTON_DP = 48"), "48 dp touch target")
        // the only background in the overlay build is the button's own disc: none on root, the picture slot or the row
        val containers = ensure.substring(0, ensure.indexOf("root.addView(exitButton()"))
        assertTrue(Regex("setBackground\\(|background =|GradientDrawable|ColorDrawable").findAll(containers).none(), "no scrim/background on a container")
        assertTrue(Regex("setBackgroundColor").findAll(containers).count() == 1, "only the root's own black")
        assertTrue(fn.contains("GradientDrawable"), "the disc belongs to the button")
    }
}
