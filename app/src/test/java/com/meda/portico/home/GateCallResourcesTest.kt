package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-692 T2, the glue that cannot run on this host, pinned by its source (working directory = app/): a call button aimed at the
 * gate starts the Talk session, and nothing but [PressRoute] decides which session a press reaches. The decision itself and the
 * whole press path are tested in `:core` (`GateCallRoutingTest`).
 */
class GateCallResourcesTest {
    private fun main(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val activity by lazy { main("home/HomeActivity.kt") }
    private val graph by lazy { main("AppGraph.kt") }
    private val buttons by lazy { main("home/ActionButtons.kt") }

    private fun between(text: String, from: String, to: String): String = text.substringAfter(from).substringBefore(to)

    @Test
    fun `a press is routed by PressRoute alone - no kind check of the activity can send a gate call to a house call`() {
        val press = between(activity, "private fun onPress(tile: ButtonTile) {", "// A block button")
        assertTrue(press.contains("when (PressRoute.of(tile)) {"))
        assertTrue(press.contains("PressRoute.PLACE, PressRoute.REOPEN_CALL -> return whenUnlocked { pressCall(tile) }"))
        assertTrue(press.contains("PressRoute.TALK_START -> return whenUnlocked { pressTalk(tile) }") && press.contains("PressRoute.TALK_STOP -> return pressTalk(tile)"))
        assertFalse(press.contains("tile.kind =="), "the kind is not read here any more")
        assertEquals(2, Regex("graph\\.place\\.press\\(").findAll(activity).count(), "pressCall, and a roster row (a door row is never drawn there)")
    }

    @Test
    fun `Home hands the model the doors cache, and the Talk gate reads the same roster and doors`() {
        assertTrue(activity.contains("selectedPageId, graph.sizes, graph.talk.state(), graph.doors.all, station())"))
        val talk = between(graph, "val talk: TalkCoordinator = TalkCoordinator(", "log = log,\n    )")
        assertTrue(talk.contains("GateCall.of(action, content.profile.roster, doors.all)"))
    }

    @Test
    fun `the Talk press reads the door from the tile, the app has one TalkSession and TalkBack treats the gate button as a talk`() {
        assertTrue(activity.contains("graph.talk.press(TalkRequest(tile.id, tile.label, tile.door))"))
        assertEquals(1, Regex("TalkSession\\(").findAll(graph).count())
        assertTrue(buttons.contains("if (tile.kind == \"talk\" || tile.talkRoute) talkAccessibility(this, tile, note)"))
    }

    @Test
    fun `the app still never dials by itself`() {
        for (f in File("src/main/java").walkTopDown().filter { it.extension == "kt" }) assertFalse(f.readText().contains("\"portico/dial\""), f.name)
    }
}
