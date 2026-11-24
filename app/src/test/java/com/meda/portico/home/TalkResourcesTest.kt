package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Talk to the door, the app's side (DEV-658 B7b), checked without a device (working directory = app/). The state machine is
 * tested in `:core`; here the glue that cannot run on this host is pinned by its source: what a press can reach, that the
 * plain run path refuses a Talk id, that the call screens are untouched, that the words and the TalkBack hooks exist. The
 * pixels, the dialog and TalkBack itself are for a phone.
 */
class TalkResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun main(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val activity by lazy { main("home/HomeActivity.kt") }
    private val buttons by lazy { main("home/ActionButtons.kt") }
    private val graph by lazy { main("AppGraph.kt") }
    private val core = "../core/src/main/kotlin/com/meda/portico/core/"

    private fun between(text: String, from: String, to: String): String {
        val i = text.indexOf(from)
        assertTrue(i >= 0, from)
        return text.substring(i, text.indexOf(to, i + from.length).also { assertTrue(it >= 0, to) })
    }

    private fun appSources() = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun coreSources() = File("../core/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---- what a press can reach -----------------------------------------------------------------------------

    @Test
    fun `only a READY Talk button starts a talk, only Home presses, and the other states send nothing`() {
        val press = between(activity, "private fun pressTalk(tile: ButtonTile)", "/** The microphone dialog")
        assertTrue(press.contains("ButtonState.READY -> graph.talk.press(TalkRequest(tile.id, tile.label, tile.door))"))
        assertTrue(press.contains("ButtonState.TALK_CONNECTING, ButtonState.TALK_IN_CALL -> CallActions.hangUpAll(graph)"))
        assertTrue(press.contains("else -> Unit"), "an off button, an offline one, anything else: nothing is sent")
        assertEquals(1, Regex("graph\\.talk\\.press\\(").findAll(activity).count())
        assertEquals(listOf("HomeActivity.kt"), appSources().filter { it.readText().contains("talk.press(") }.map { it.name })
        assertTrue(activity.contains("PressRoute.TALK_START -> return whenUnlocked { pressTalk(tile) }") && activity.contains("PressRoute.TALK_STOP -> return pressTalk(tile)"), "a Talk press never reaches the plain run path")
    }

    @Test
    fun `the plain run path refuses a Talk id, before anything is marked or sent`() {
        val run = between(graph, "fun runAction(actionId: String): Boolean {", "signaling.post")
        val guard = run.indexOf("if (content.action(actionId)?.isTalk == true) return false")
        val mark = run.indexOf("content.markRunning(actionId)")
        assertTrue(guard in 0 until mark, "the Talk refusal comes first")
        // and the plain press helper only ever runs a READY button
        val press = buttons.substringAfter("fun press(").substringBefore("private fun run(")
        assertTrue(press.contains("if (tile.state != ButtonState.READY) return"))
    }

    @Test
    fun `the coordinator is built with the document's own gate and nothing starts a session but it`() {
        val talk = between(graph, "val talk: TalkCoordinator = TalkCoordinator(", "log = log,\n    )")
        assertTrue(talk.contains("TalkGate.ready(action, HomeModel.usable(linkNow(), content.support), GateCall.of(action, content.profile.roster, doors.all))"))
        assertEquals(listOf("AppGraph.kt"), appSources().filter { it.readText().contains("TalkSession(") }.map { it.name })
        assertEquals(listOf("TalkCoordinator.kt"), coreSources().filter { it.readText().contains("newSession(request, hooks, { safeGate(request) })") }.map { it.name })
        assertEquals(1, Regex("TalkSession\\(").findAll(graph).count())
        // The audio is the answer path's, built only when a session opens it (after the station answered).
        assertTrue(talk.contains("TalkMediaPort { callId, doorId, listener ->"))
        assertTrue(talk.contains("AnswerPathTalkMedia(audioJournaled(\"talk\", callId, SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread)), answerCommands, AnswerConfig(), log).open(callId, doorId, listener)"))
    }

    @Test
    fun `the app's command list is unchanged - nothing dials, and talk sends only run, hangup and the offer`() {
        for (f in appSources() + coreSources()) assertFalse(f.readText().contains("\"portico/dial\""), f.name)
        val talkSources = coreSources().filter { it.name.startsWith("Talk") && it.parentFile.name == "call" }
        assertTrue(talkSources.size >= 6)
        for (f in talkSources) {
            val strings = Regex("\"(portico/[a-z_/]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()
            assertTrue(strings.isEmpty(), "${f.name} names no command literal of its own: $strings")
        }
        val commands = File(core + "call/TalkCommands.kt").readText()
        assertTrue(commands.contains("const val RUN = PlaceCommands.RUN"))
        assertTrue(commands.contains("const val HANGUP = PlaceCommands.HANGUP"))
    }

    @Test
    fun `the microphone is asked for as the call screens do - once per attempt, with a request code of its own`() {
        val ask = between(activity, "private fun askTalkMic(s: TalkUiState)", "override fun onRequestPermissionsResult")
        assertTrue(ask.contains("s.snapshot.phase != TalkPhase.AWAITING_MIC || talkMicAskedFor == s.generation"))
        assertTrue(ask.contains("requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_TALK_MIC)"))
        assertTrue(ask.contains("graph.talk.onMicResult(true)"))
        assertTrue(activity.contains("REQ_TALK_MIC -> graph.talk.onMicResult(granted)"))
        val codes = listOf("home/HomeActivity.kt", "call/InCallActivity.kt", "call/OutgoingCallActivity.kt").flatMap { Regex("REQ_[A-Z_]*MIC = (\\d+)").findAll(main(it)).map { m -> m.groupValues[1] } }
        assertEquals(codes.size, codes.toSet().size, "request codes are distinct: $codes")
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `Home follows the talk only while it is visible, and the model reads it`() {
        assertTrue(activity.contains("talkRemover = graph.talk.addListener(talkListener)"))
        assertTrue(activity.contains("talkRemover?.invoke()"))
        assertTrue(activity.contains("selectedPageId, graph.sizes, graph.talk.state(), graph.doors.all, station())"))
        assertTrue(activity.contains("runOnUiThread {\n            askTalkMic(s)\n            render()"))
    }

    @Test
    fun `the call screens, their service logic and the ring are untouched except for the hang-up and the notification target`() {
        for (f in listOf("call/InCallActivity.kt", "call/OutgoingCallActivity.kt", "ring/RingNotifier.kt", "ring/RingReceiver.kt")) {
            val text = main(f)
            assertFalse(text.contains("Talk") || text.contains("talk."), f)
        }
        // the one wiring each: the notification's Hang up, the service's title and target, and the swipe-away release
        assertTrue(main("call/CallReceiver.kt").contains("CallActions.hangUpAll"))
        assertTrue(main("call/CallBarView.kt").contains("graph.talk.hangUp()"), "the one hang-up the notification and the bar share")
        assertTrue(main("call/CallService.kt").contains("PorticoApp.graph(this).talk.appStopping()"))
        assertTrue(graph.contains("talk.hangUp()"), "pause releases a talk as it does a call")
    }

    // ---- words and TalkBack --------------------------------------------------------------------------------------

    @Test
    fun `every new state and reason has plain English words with no arguments`() {
        val expected = mapOf(
            "home_reason_connecting" to "A call from this phone is still connecting",
            "home_reason_in_call" to "A call from this phone is still going",
            "home_talk_connecting" to "Connecting to the gate. Tap to cancel.",
            "home_talk_in_call" to "In call. Tap to stop.",
            "home_talk_state_ready" to "Ready",
            "home_talk_state_connecting" to "Connecting to the gate",
            "home_talk_state_in_call" to "In call",
            "home_talk_action_cancel" to "Cancel",
            "home_talk_action_stop" to "Stop",
        )
        for ((k, v) in expected) {
            assertEquals(v, strings[k], k)
            assertFalse(v.contains("%"), k)
        }
        // B5's words for the disabled button, unchanged
        assertEquals("Talk to the door is switched off", strings["home_reason_talk_off"])
        assertEquals("Door removed", strings["home_reason_no_door"])
        assertEquals("The gate is not connected", strings["home_reason_not_connected"])
        assertEquals("The gate is busy", strings["home_reason_busy"])
        assertEquals("Talk works from a phone only", strings["home_reason_phone_only"])
        for (k in listOf("ButtonState.TALK_CONNECTING -> R.string.home_talk_connecting", "ButtonState.TALK_IN_CALL -> R.string.home_talk_in_call", "ButtonReason.CONNECTING -> R.string.home_reason_connecting", "ButtonReason.IN_CALL -> R.string.home_reason_in_call")) {
            assertTrue(buttons.contains(k), k)
        }
    }

    @Test
    fun `TalkBack hears the state, the Cancel or Stop action, and the reason a button is off`() {
        val tb = between(buttons, "private fun talkAccessibility(", "/** The system theme")
        assertTrue(tb.contains("ViewCompat.setStateDescription(button, state)"))
        assertTrue(tb.contains("ButtonState.READY -> context.getString(R.string.home_talk_state_ready)"))
        assertTrue(tb.contains("else -> note"), "an off button's state is its reason")
        assertTrue(tb.contains("ButtonState.TALK_CONNECTING -> R.string.home_talk_action_cancel"))
        assertTrue(tb.contains("ButtonState.TALK_IN_CALL -> R.string.home_talk_action_stop"))
        assertTrue(tb.contains("AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK"))
        assertTrue(buttons.contains("if (tile.kind == \"talk\" || tile.talkRoute) talkAccessibility(this, tile, note)"))
        // the label and why it is off are still what the content description says
        assertTrue(buttons.contains("contentDescription = if (note != null) \"\$label, \$note\" else label"))
    }

    @Test
    fun `an off Talk button is still a disabled button - enabled only for READY or a talk under way`() {
        val blockButton = between(buttons, "private fun blockButton(", "private fun talkAccessibility(")
        assertTrue(blockButton.contains("tile.state == ButtonState.READY || tile.state == ButtonState.CALLING || tile.state == ButtonState.TALK_CONNECTING || tile.state == ButtonState.TALK_IN_CALL"))
        assertTrue(blockButton.contains("isEnabled = on"))
        assertFalse(blockButton.contains("ButtonState.UNAVAILABLE"), "UNAVAILABLE never enables a button")
        assertFalse(blockButton.contains("ButtonState.OFFLINE"))
    }

    @Test
    fun `the call service titles and opens a talk, and the notification does not need a call screen`() {
        val service = main("call/CallService.kt")
        assertTrue(service.contains("val talking = graph.talk.state().takeIf { it.isLive }"))
        assertTrue(service.contains("if (talk) HomeActivity::class.java else if (outgoing) OutgoingCallActivity::class.java else InCallActivity::class.java"))
    }
}
