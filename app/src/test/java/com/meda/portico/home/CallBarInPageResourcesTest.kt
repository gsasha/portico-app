package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 0.6.47-alpha: the in-call controls row is drawn in the Home page directly below the row of the call's own Talk button (not at the
 * bottom of the screen), and the screen scrolls it fully into view. Which button is `:core`'s CallBarPlacementTest; these pin the
 * wiring a source read can see (working directory = app/). The picture itself is for a phone.
 */
class CallBarInPageResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val flow by lazy { src("home/ButtonFlowLayout.kt") }
    private val blockViews by lazy { src("home/BlockViews.kt") }
    private val home by lazy { src("home/HomeActivity.kt") }
    private val host by lazy { src("call/CallBarHost.kt") }

    @Test
    fun `the flow lays the controls row full width directly after the row of the call button, and skips it while gone`() {
        assertTrue(flow.contains("fun setAddon(view: View, afterChild: Int)"))
        assertTrue(flow.contains("cells.any { it.index == addonAfterChild }"), "below the row that holds the button")
        assertTrue(flow.contains("it.visibility != GONE"), "no room while no call")
        assertTrue(flow.contains("for (i in labelled.indices)"), "the addon child is never planned as a button")
    }

    @Test
    fun `the anchor comes from core, and is released before the body is replaced`() {
        assertTrue(blockViews.contains("CallBarPlacement.indexIn(block.buttons)"))
        val rebuild = blockViews.substring(blockViews.indexOf("fun rebuild("))
        assertTrue(rebuild.indexOf("releaseAnchor()") in 0 until rebuild.indexOf("container.removeAllViews()"))
        assertTrue(home.contains("blockViews.releaseAnchor()"), "the roster page also replaces the body")
    }

    @Test
    fun `Home places the bar after every render and when the host installs it, else docks it at the bottom`() {
        assertTrue(home.contains("paintStation()\n        placeCallBar()"))
        assertTrue(host.contains("(activity as? HomeActivity)?.placeCallBar()"))
        assertTrue(home.contains("CallBarHost.dock(this)"))
        assertTrue(host.contains("fun dock(activity: Activity)"))
        assertTrue(host.contains("generateSequence(bar as View?)"), "a bar inside the page is still hosted, not installed twice")
    }

    @Test
    fun `the screen scrolls the whole bar into view when it appears or moves`() {
        assertTrue(home.contains("ScrollReveal.delta("), "the amount is core's, pure and tested")
        assertTrue(home.contains("scroll.offsetDescendantRectToMyCoords(bar, r)"), "from the bar's real position in the ScrollView")
        assertTrue(home.contains("scroll.smoothScrollBy(0, d)"), "explicit scroll, not requestRectangleOnScreen")
        assertFalse(home.contains("requestRectangleOnScreen"))
        assertTrue(home.contains("longArrayOf(0L, 350L, 1200L)"), "again after the tiles above have settled")
        assertTrue(home.contains("scrollBarIntoView(bar) // newly put in the page"))
        assertTrue(home.contains("addOnLayoutChangeListener(View.OnLayoutChangeListener"))
        assertFalse(home.contains("fullScroll"), "scroll just enough, never jump to the end")
    }

    @Test
    fun `a call starting or ending redraws only the buttons, never the camera tiles`() {
        assertTrue(home.contains("PageUpdate.onlyButtonsChanged(shown, view) && blockViews.rebuildButtons(view.blocks, ::onPress)"))
        val rb = blockViews.substring(blockViews.indexOf("fun rebuildButtons("), blockViews.indexOf("fun rebuild(container"))
        assertFalse(rb.contains("stills.reset()") || rb.contains("removeAllViews"), "the tiles and their streams are left alone")
        assertTrue(rb.contains("releaseAnchor()"))
    }

    @Test
    fun `the call button has no wave pattern, is green in a call with a white icon, and has a green icon when idle`() {
        val ab = src("home/ActionButtons.kt")
        assertFalse(ab.contains("SoundWaveDrawable") || ab.contains("LayerDrawable"), "no wave pattern")
        assertTrue(ab.contains("inCall && (tile.kind == \"talk\" || tile.kind == \"call\") -> Paint(CALL_GREEN, null, Color.WHITE, 0x33FFFFFF)"), "in a call: green fill, white icon")
        assertTrue(ab.contains("Paint(basePaint.fill, basePaint.outline, CALL_GREEN, basePaint.ripple)"), "idle: the icon is green")
        assertTrue(ab.contains("isCallKind && !inCall && on"))
    }

    @Test
    fun `the top line is pinned above the scroller, not inside it (0_6_52)`() {
        val xml = File("src/main/res/layout/activity_home.xml").readText()
        val root = xml.substringAfter("?>").substringAfter("-->")
        assertTrue(root.trimStart().startsWith("<LinearLayout"), "the root is a plain vertical box, not the ScrollView")
        val headerAt = xml.indexOf("android:id=\"@+id/home_header\"")
        val scrollAt = xml.indexOf("<ScrollView")
        assertTrue(headerAt in 0 until scrollAt, "the header precedes the ScrollView as its sibling")
        val scrollTag = xml.substring(scrollAt, xml.indexOf(">", scrollAt))
        assertTrue(scrollTag.contains("android:id=\"@+id/home_scroll\"") && scrollTag.contains("android:layout_height=\"0dp\"") && scrollTag.contains("android:layout_weight=\"1\""), "the scroller takes the rest")
        assertFalse(xml.substring(scrollAt).contains("@+id/home_header"), "nothing of the header inside the scroll content")
        for (id in listOf("home_title", "home_tabs", "home_status", "home_menu")) assertTrue(xml.indexOf("@+id/$id") < scrollAt, "$id is in the pinned line")
        assertTrue(xml.substring(scrollAt).contains("android:paddingTop=\"8dp\""), "the 8 dp gap under the line moved into the scroll content")
        // no code treats the header as scrolled content; the reveal maths stays in the ScrollView's own coordinates
        assertFalse(home.contains("scrollTo(0, 0)") || home.contains("fullScroll"))
        assertTrue(home.contains("scroll.offsetDescendantRectToMyCoords(bar, r)") && home.contains("scroll.height - scroll.paddingTop - scroll.paddingBottom"))
        // fullscreen overlay is added over the whole content, so it still covers the pinned line
        assertTrue(src("home/ViewportController.kt").contains("activity.addContentView(root"))
    }

    @Test
    fun `Home's camera grid runs edge to edge, the rest of the page keeps its padding`() {
        val xml = File("src/main/res/layout/activity_home.xml").readText()
        assertFalse(xml.contains("android:padding=\"16dp\""), "0.6.49: no side padding on the page root (negative margins were clipped by the block box)")
        assertTrue(xml.contains("android:layout_marginStart=\"16dp\""), "the other children carry their own side margin")

        // 0.6.50: the header bar spans the full width on a tonal surface, compact, with the 48 dp menu button still the touch target
        val header = xml.substringAfter("android:id=\"@+id/home_header\"").substringBefore("<TextView")
        assertTrue(header.contains("android:layout_width=\"match_parent\"") && !header.contains("layout_marginStart") && !header.contains("layout_marginEnd"), "full width, no outer side margin")
        assertTrue(header.contains("android:background=\"@color/home_header_bg\"") && header.contains("android:paddingTop=\"2dp\"") && header.contains("android:paddingBottom=\"2dp\""))
        assertTrue(xml.contains("android:id=\"@+id/home_menu\"\n            android:layout_width=\"48dp\"\n            android:layout_height=\"48dp\""))
        assertTrue(File("src/main/res/values/colors.xml").readText().contains("home_header_bg") && File("src/main/res/values-night/colors.xml").readText().contains("home_header_bg"), "light and dark")
        assertTrue(blockViews.contains("const val PAGE_PAD_DP = 16"))
        assertFalse(blockViews.contains("leftMargin = -dp("), "no negative margins")
        assertTrue(blockViews.contains("setPadding(dp(PAGE_PAD_DP), dp(8), dp(PAGE_PAD_DP), dp(4))") && blockViews.contains("leftMargin = dp(PAGE_PAD_DP); rightMargin = dp(PAGE_PAD_DP)"), "headings and button rows keep the inset")
        val tiles = src("home/StillTiles.kt")
        assertTrue(tiles.contains("edgeCell(left = k > 0, right = !last)"), "no outer side margin, a 2 dp gap between two tiles")
        assertTrue(tiles.contains("const val GAP_DP = 2"))
    }

    @Test
    fun `every camera tile box has 12 dp rounded corners that clip the video surface too`() {
        val a = src("home/AspectFrameLayout.kt")
        assertTrue(a.contains("const val CORNER_DP = 12"))
        assertTrue(a.contains("outline.setRoundRect("))
        assertTrue(a.contains("clipToOutline = true"))
        assertTrue(src("home/StillTiles.kt").contains("AspectFrameLayout(context)"), "all tiles are built on it")
        // 0.6.49: a video SurfaceView is composited outside the view tree, so the outline cannot round it: corners are masked above it.
        val m = src("home/CornerMaskView.kt")
        assertTrue(m.contains("Path.FillType.EVEN_ODD") && m.contains("addRoundRect") && m.contains("colorBackground"))
        assertTrue(src("home/StillTiles.kt").contains("if (!fullscreen) frame.addView(CornerMaskView(context, AspectFrameLayout.CORNER_DP)"))
    }

    @Test
    fun `the still stays on top until the first video frame, then fades out, so the switch has no blank frame`() {
        val tiles = src("home/StillTiles.kt")
        assertTrue(tiles.contains("v.frame.addView(r, 0, "), "the renderer is below the still")
        assertTrue(tiles.contains("v.image.animate().alpha(0f)"))
        assertTrue(tiles.contains("val show = v.videoShown && v.rendered"), "only after a frame was drawn")
    }

    @Test
    fun `the LIVE chip is one pill with the fps, 1_5x smaller than before, and a small tile gets a smaller one`() {
        val tiles = src("home/StillTiles.kt")
        assertTrue(tiles.contains("const val CHIP_SP = 8f"), "12 sp / 1.5")
        assertTrue(tiles.contains("const val SMALL_CHIP = 0.78f"), "about 22 percent smaller on a small tile")
        assertTrue(tiles.contains("val small = interactive && tile.size != CameraSize.BIG"))
        assertTrue(tiles.contains("styleLive(it, CHIP_SP * k, Gravity.CENTER)"))
        assertTrue(tiles.contains("const val CHIP_INSET_DP = 8"), "inset from the tile edge")
    }

    @Test
    fun `fullscreen in landscape lays the buttons out compact, portrait stays stretched`() {
        val vc = src("home/ViewportController.kt")
        assertTrue(vc.contains("ORIENTATION_LANDSCAPE"))
        assertTrue(vc.contains("blockRows(buttons, host::press, compact = landscape)"))
        assertTrue(src("home/ActionButtons.kt").contains("compact: Boolean = false"), "the page's own rows are unchanged")
        assertTrue(src("home/ButtonFlowLayout.kt").contains("ButtonFlow.plan(items, availDp, MARGIN_DP.toFloat(), compact)"))
    }

    @Test
    fun `the size chooser is icons in a panel that opens upward, with the old words as descriptions`() {
        val vc = src("home/ViewportController.kt")
        assertFalse(vc.contains("PopupMenu"), "the text popup is gone")
        for (d in listOf("ic_view_small", "ic_view_big", "ic_view_fullscreen", "ic_view_fullscreen_exit")) {
            assertTrue(vc.contains("R.drawable.$d"), d)
            assertTrue(File("src/main/res/drawable/$d.xml").readText().contains("<vector"), "$d is a vector drawable")
        }
        assertTrue(vc.contains("R.string.home_resize_small -> ") || vc.contains("-> R.string.home_resize_small"), "descriptions keep the old labels")
        assertTrue(vc.contains("R.string.home_stream_auto_plain") && vc.contains("list = true"), "0.6.53: the video-size menu is a TEXT list; Auto is a word, not an icon")
        assertTrue(vc.contains("R.string.home_stream_stills") && vc.contains("R.string.home_stream_off") && vc.contains("StreamLabels") || vc.contains("item.quality"), "rows are named by resolution, plus stills and off")
        val menu = src("home/IconMenu.kt")
        assertTrue(menu.contains("cell.contentDescription = e.description"))
        assertTrue(menu.contains("var y = ay - h") && menu.contains("if (y < top) y = ay + ah") && menu.contains("coerceAtMost(bottom - h)"), "above the button, flipped below when no room, kept on screen")
    }

    @Test
    fun `the icon menu goes above the button, flips below when it must, and stays on screen`() {
        // anchor at (900, 1000) 100x100, panel 150x300, window 1080x2000
        assertEquals(850 to 700, IconMenu.place(900, 1000, 100, 100, 150, 300, 0, 0, 1080, 2000), "above, right edges aligned")
        assertEquals(850 to 150, IconMenu.place(900, 50, 100, 100, 150, 300, 0, 0, 1080, 2000), "no room above: flips below")
        assertEquals(0 to 100, IconMenu.place(0, 100, 100, 100, 150, 300, 0, 0, 1080, 400), "clamped inside the window both ways")
    }
}
