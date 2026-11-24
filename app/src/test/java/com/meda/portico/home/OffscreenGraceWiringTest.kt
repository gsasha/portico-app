package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.50: Home feeds the plan's onScreen through OffscreenGrace and cancels every pending stop on stop/destroy (the rule itself: core's OffscreenGraceTest). */
class OffscreenGraceWiringTest {
    private val home = File("src/main/java/com/meda/portico/home/HomeActivity.kt").readText()

    @Test
    fun `onScreen goes through the grace, fullscreen bypasses it, stop and destroy clear it`() {
        assertTrue(home.contains("offscreenGrace.onScreen(t.id, t.id in visible || t.id in prewarmed, nowMs)"))
        assertTrue(home.contains("offscreenGrace.forget(t.id)"), "fullscreen hides the others at once")
        assertTrue(Regex("""started = false\s+offscreenGrace\.clear\(\)""").containsMatchIn(home), "onStop")
        assertTrue(Regex("""offscreenGrace\.clear\(\)\s+graph\.homeLive\.release""").containsMatchIn(home), "onDestroy")
    }
}
