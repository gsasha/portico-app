package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A2: no emulator draws these screens, so the tests pin what a source read can: the ids the code uses exist in the
 * layouts, Answer / Stop / Hang up keep their place above the new tiles, the new drawing can never take the old
 * buttons down with it, and nothing keeps running while a screen is not visible.
 */
class ScreensResourcesTest {
    private fun text(path: String) = File(path).readText()

    private val houseRing by lazy { text("src/main/java/com/meda/portico/ring/HouseRingActivity.kt") }
    private val inCall by lazy { text("src/main/java/com/meda/portico/call/InCallActivity.kt") }
    private val home by lazy { text("src/main/java/com/meda/portico/home/HomeActivity.kt") }
    private val ringLayout by lazy { text("src/main/res/layout/activity_ring.xml") }
    private val callLayout by lazy { text("src/main/res/layout/activity_call.xml") }
    private val homeLayout by lazy { text("src/main/res/layout/activity_home.xml") }
    private val strings by lazy { text("src/main/res/values/strings.xml") }

    private fun ids(layout: String) = Regex("android:id=\"@\\+id/(\\w+)\"").findAll(layout).map { it.groupValues[1] }.toList()

    @Test
    fun `every id the three screens use is in their layout`() {
        for ((code, layout) in listOf(houseRing to ringLayout, inCall to callLayout, home to homeLayout)) {
            val have = ids(layout).toSet()
            for (id in Regex("R\\.id\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()) assertTrue(id in have, "R.id.$id is not in its layout")
        }
    }

    @Test
    fun `the ring screen keeps Answer and Stop above the new tiles, and the call screen keeps Hang up and Mute above them`() {
        val r = ids(ringLayout)
        assertTrue(r.indexOf("ring_answer") < r.indexOf("ring_stop") && r.indexOf("ring_stop") < r.indexOf("ring_stills"), r.toString())
        assertTrue(r.indexOf("ring_stills") < r.indexOf("ring_extra") && r.indexOf("ring_extra") < r.indexOf("ring_result"), r.toString())
        val c = ids(callLayout)
        assertTrue(c.indexOf("call_hangup") < c.indexOf("call_mute") && c.indexOf("call_mute") < c.indexOf("call_stills"), c.toString())
        assertTrue(c.indexOf("call_stills") < c.indexOf("call_extra") && c.indexOf("call_extra") < c.indexOf("call_retry"), c.toString())
        assertTrue(ringLayout.contains("<ScrollView") && callLayout.contains("<ScrollView"), "every growable screen scrolls")
        assertFalse(callLayout.contains("android:background") || callLayout.contains("SurfaceView"), "stills are ImageViews, never a video surface")
    }

    @Test
    fun `the new drawing cannot take the old buttons down - every entry point swallows its own failure`() {
        for ((name, code) in listOf("InCallActivity" to inCall)) {
            val render = code.substringAfter("private fun renderCaller()").substringBefore("private fun drawCaller()")
            assertTrue(render.contains("try {") && render.contains("catch (_: Exception)"), "$name.renderCaller")
            val request = code.substringAfter("private fun requestStills()").substringBefore("private fun askForStills()")
            assertTrue(request.contains("try {") && request.contains("catch (_: Exception)"), "$name.requestStills")
        }
    }

    @Test
    fun `answering is still only the user's press - the new code never answers, hangs up or places a call`() {
        // APP-03: the ring screen is Home now. The one press is the Answer action's first delivery (a rotation re-delivers the intent);
        // the call bar's Answer button reaches it through the same intent, so there is no second path.
        assertEquals(1, Regex("answer\\.press\\(").findAll(home).count(), "one press in Home")
        // 0.6.76: the same one press, now preceded by the "answered -> home" journal line
        assertTrue(home.contains("if (i.action == ACTION_ANSWER && answerNow && d != null && c != null) {") && home.contains("                    graph.answer.press(d, c)\n") && home.contains("LockedAnswer.decide(this, graph, c,"))
        assertTrue(home.contains("CallWindowModel.ANSWERED_HOME_LINE"))
        assertTrue(home.contains("takeRing(intent, answerNow = savedInstanceState == null)") && home.contains("takeRing(intent, answerNow = true)"))
        for (code in listOf(inCall)) {
            assertFalse(code.contains("place.press("), "a call screen never places a call")
            assertFalse(code.contains("runAction(") && !code.contains("ActionButtons.press("), "buttons go through the shared press")
        }
        // the in-call hang-up is still the coordinator's, untouched by the extras
        assertTrue(inCall.contains("findViewById<Button>(R.id.call_hangup).setOnClickListener { PorticoApp.graph(this).answer.hangUp() }"))
    }

    @Test
    fun `stills and the content listener run only while the screen is visible, and the ring asks only while it rings`() {
        assertTrue(inCall.contains("handler.removeCallbacks(stillsTick)") && inCall.contains("content.unlisten(contentListener)"))
        assertTrue(inCall.substringAfter("private fun callerView()").substringBefore("private fun renderCaller()").contains("CallUi.controls(s.snapshot).hangUp"), "extras only while the call is live")
        assertTrue(inCall.contains("STILL_EVERY_MS = 1_500L"), "about every 1.5 s (O3)")
    }

    @Test
    fun `Home pages - page tabs in the top line that scroll sideways and hide for one page, a swipe that never consumes a touch`() {
        assertTrue(homeLayout.contains("<HorizontalScrollView") && homeLayout.contains("android:id=\"@+id/home_tabs\""))
        assertTrue(Regex("home_tabs\"[^>]*android:visibility=\"gone\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(homeLayout), "hidden until a document has two pages")
        val h = ids(homeLayout)
        assertTrue(h.indexOf("home_title") < h.indexOf("home_tabs") && h.indexOf("home_tabs") < h.indexOf("home_status") && h.indexOf("home_menu") < h.indexOf("home_banner"), "the tabs are in the top line, not a strip below it")
        assertTrue(Regex("home_tabs\"[^>]*android:layout_weight=\"1\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(homeLayout), "the tabs take the title's spare width")
        val swipe = home.substringAfter("override fun dispatchTouchEvent").substringBefore("private fun selectPage")
        assertTrue(swipe.contains("return super.dispatchTouchEvent(ev)"), "the swipe only watches")
        assertTrue(swipe.contains("Swipe.direction(") && swipe.contains("Pager.step("))
        assertTrue(home.contains("selectedPageId = view.selectedId") && home.contains("KEY_PAGE"), "the page survives a refresh and a rotation")
        assertTrue(home.contains("currentView().cameras.filter"), "only the visible page's cameras are fetched")
    }

    @Test
    fun `the new strings exist and take no arguments`() {
        assertTrue(Regex("<string name=\"ring_explain_with_buttons\">[^%<]*</string>").containsMatchIn(strings))
        assertTrue(ringLayout.contains("@string/ring_explain"))
    }
}
