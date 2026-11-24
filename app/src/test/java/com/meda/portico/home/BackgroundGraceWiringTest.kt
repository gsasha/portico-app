package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.73: leaving the screen hands the live sessions to HomeLiveGrace (30 s) instead of closing them; coming back cancels (the rule itself: core's BackgroundGraceTest). */
class BackgroundGraceWiringTest {
    private val home = File("src/main/java/com/meda/portico/home/HomeActivity.kt").readText()
    private val grace = File("src/main/java/com/meda/portico/live/HomeLiveGrace.kt").readText()
    private val caller = File("src/main/java/com/meda/portico/live/CallerLiveController.kt").readText()

    private fun body(src: String, fn: String): String {
        val i = src.indexOf("override fun $fn(")
        val next = src.indexOf("\n    override fun ", i + 1).let { if (it < 0) src.length else it }
        return src.substring(i, next)
    }

    @Test
    fun `onStop schedules the grace after releasing the renderers and no longer stops the sessions itself`() {
        val s = body(home, "onStop")
        assertTrue(Regex("""stillTiles\.releaseRenderers\(\)\s+if \(!isChangingConfigurations\) graph\.homeLiveGrace\.leave\(""").containsMatchIn(s))
        assertFalse(s.contains("liveHome.stop()"), "the immediate stop is gone")
    }

    @Test
    fun `a configuration change is not a background`() {
        assertTrue(body(home, "onStop").contains("!isChangingConfigurations"))
    }

    @Test
    fun `onStart cancels the pending stop before the first plan`() {
        val s = body(home, "onStart")
        assertTrue(Regex("""graph\.homeLiveGrace\.back\(\)[^\n]*\n\s+started = true""").containsMatchIn(s))
    }

    @Test
    fun `onDestroy does not stop the sessions, the grace lease and the 3 s holder bound them`() {
        val s = body(home, "onDestroy")
        assertFalse(s.contains("liveHome.stop()"))
        assertTrue(s.contains("graph.homeLive.release(this)"))
    }

    @Test
    fun `the grace lives in the graph, holds a lease, stops once on expiry and journals each step`() {
        assertTrue(grace.contains("graph.homeLive.acquire(this)"))
        assertTrue(grace.contains("main.postDelayed(expiry, OffscreenGrace.GRACE_MS)"))
        assertTrue(grace.contains("home?.stop()"))
        assertTrue(Regex("""BackgroundGrace\.Step\.START""").containsMatchIn(grace))
        assertTrue(grace.contains("BackgroundGrace.Step.CANCEL") && grace.contains("BackgroundGrace.Step.EXPIRE"))
        assertTrue(grace.contains("graph.homeLive.release(this)"))
    }

    @Test
    fun `a ring or in-call screen ends the grace at once and the grace never touches the caller's own LiveHome`() {
        assertTrue(caller.contains("graph.homeLiveGrace.endNow()"))
        assertFalse(grace.contains("callerLive"))
    }
}
