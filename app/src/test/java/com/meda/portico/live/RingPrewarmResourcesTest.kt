package com.meda.portico.live

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The ring pre-warm's wiring, checked without a phone (working directory = app/). The rules are `:core`'s RingPrewarmTest. */
class RingPrewarmResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val activity by lazy { src("home/HomeActivity.kt") }
    private val glue by lazy { src("live/RingPrewarmGlue.kt") }
    private val graph by lazy { src("AppGraph.kt") }

    @Test
    fun `the pre-warm listens to the ring state from the process start and fences everything`() {
        assertTrue(graph.contains("ringPrewarm.attach()"))
        assertTrue(glue.contains("graph.ring.addChangeListener { main.post { faults.run(\"prewarm-ring\") { onRingChanged() } } }"))
    }

    @Test
    fun `Home takes the pre-warm's LiveHome and hands over before its first plan`() {
        assertTrue(activity.contains("graph.homeLive.acquire(this)"))
        assertTrue(activity.indexOf("graph.ringPrewarm.onHomeStarted()") in 0 until activity.indexOf("started = true"))
        assertTrue(activity.contains("graph.ringPrewarm.onHomeStopped()"))
        assertTrue(activity.contains("t.id in prewarmed"), "the handed-over cameras stay on screen until the tiles are laid out")
        assertTrue(activity.indexOf("render() // before the live plan") in 0 until activity.indexOf("syncLive()\n            handler.postDelayed(this, TICK_MS)"))
    }

    @Test
    fun `the pre-warm picks its streams exactly as Home does and closes them itself only before the hand-off`() {
        assertTrue(glue.contains("RingPrewarm.keyFor"))
        assertTrue(glue.contains("graph.liveChoices.effective(it.id, it.live)"), "the same live-or-still rule as Home's tiles")
        assertTrue(glue.contains("if (!r.handedOff) {"))
        assertTrue(glue.contains("RingPrewarm.stopWhy("))
    }
}
