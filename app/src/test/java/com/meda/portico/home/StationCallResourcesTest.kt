package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * APP-03, checked without a phone (working directory = app/): a call from the station has no screen of its own. The state mapping is
 * `:core`'s StationCallTest; these pin the wiring a source read can see. What a lock screen and a notification really do is for a phone.
 */
class StationCallResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val home by lazy { src("home/HomeActivity.kt") }
    private val notifier by lazy { src("ring/RingNotifier.kt") }
    private val bar by lazy { src("call/CallBarView.kt") }
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }

    private fun fn(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "missing: $signature")
        val rest = text.substring(start + signature.length)
        val end = Regex("""\n    (private |override |internal |public )?(fun |val |var |companion |class |object )""").find(rest)?.range?.first ?: rest.length
        return signature + rest.substring(0, end)
    }

    @Test
    fun `no ring screen and no other screen is opened for the call - the ring, the answer and the end are all on Home`() {
        assertFalse(File("src/main/java/com/meda/portico/ring/RingActivity.kt").exists())
        assertFalse(home.contains("InCallActivity"), "answering no longer opens the in-call screen")
        assertFalse(notifier.contains("RingActivity"))
        // 0.6.75: the ring's three PendingIntents (full-screen, content, Answer) and the direct launch open the CALL WINDOW (RingCallActivity),
        // not Home: one intent shape (the extras the ring has always carried), built in one place
        val pi = fn(notifier, "private fun showIntent(")
        assertTrue(fn(notifier, "private fun activityPi(").contains("showIntent("), "0.6.74: the PendingIntents and the direct launch share one intent")
        assertTrue(pi.contains("target: Class<*> = RingCallActivity::class.java"), "the default target is the call window")
        assertFalse(pi.contains("HomeActivity"), "0.6.75: Home is no longer the ring window")
        assertTrue(pi.contains("FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(pi.contains("putExtra(RingCallActivity.EXTRA_DOOR, doorRef)") && pi.contains("putExtra(RingCallActivity.EXTRA_CALL, callId)"))
        assertTrue(notifier.contains("setFullScreenIntent(full, true)") && notifier.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED"), "the full-screen intent and the Android 14 opt-in are kept")
        assertEquals(3, Regex("activityPi\\(").findAll(notifier).count(), "the declaration, full-screen/content and Answer")
        // 0.6.76: the Answer action opens Home (not the window), with the ring's own action and extras
        assertTrue(notifier.contains("activityPi(slot, PiAction.ANSWER, HomeActivity::class.java, HomeActivity.ACTION_ANSWER, doorRef, callId)"))
    }

    @Test
    fun `Home is over the lock screen only while a station call lasts, and never asks for the unlock by itself`() {
        val code = home.lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }.joinToString("\n")
        val take = fn(code, "private fun takeRing(")
        assertTrue(take.contains("showOverLock(true)"))
        assertTrue(take.indexOf("showOverLock(true)") < take.indexOf("answer.press("), "the window is set before anything else")
        val paint = fn(code, "private fun paintStation()")
        assertTrue(paint.contains("if (!on) showOverLock(false)"), "undone as soon as there is no station call")
        assertTrue(paint.contains("FLAG_KEEP_SCREEN_ON"))
        val over = fn(code, "private fun showOverLock(")
        assertTrue(over.contains("setShowWhenLocked(on)") && over.contains("setTurnScreenOn(on)") && over.contains("FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON"), "the pre-8.1 flags too")
        assertEquals(1, Regex("requestDismissKeyguard").findAll(code).count(), "only the press path asks, never a launch")
        assertFalse(code.contains("FLAG_DISMISS_KEYGUARD") || code.contains("FLAG_SECURE"))
        assertEquals(1, Regex("showOverLock\\(true\\)").findAll(code).count(), "only a ring intent turns it on")
    }

    @Test
    fun `answer, decline and end need no unlock, every other press does (A-02)`() {
        val guard = fn(home, "private fun whenUnlocked(")
        assertTrue(guard.contains("DoorUnlockRule.needsUnlock(km?.isKeyguardLocked ?: true, RingPress.DOOR_ACTION)"), "fails closed")
        assertTrue(guard.contains("onDismissSucceeded() = run()"))
        val press = fn(home, "private fun onPress(tile: ButtonTile)")
        assertTrue(press.contains("PressRoute.TALK_STOP -> return pressTalk(tile)"), "ending a call is not guarded")
        for (guarded in listOf("PressRoute.TALK_START -> return whenUnlocked", "PressRoute.PLACE, PressRoute.REOPEN_CALL -> return whenUnlocked", "whenUnlocked { if (tile.confirm)")) assertTrue(press.contains(guarded), guarded)
        assertTrue(fn(home, "private fun openSetup(").contains("whenUnlocked {") && home.contains("MENU_SETTINGS -> openSetup(") && home.contains("MENU_STATUS -> openSetup("), "Settings and Status need the unlock too (APP-04 entries go through openSetup)")
        assertTrue(home.contains("RowState.READY -> whenUnlocked {"), "so does a roster call")
        // the bar's Answer and Decline go through no guard: they are call handling
        assertFalse(bar.contains("whenUnlocked") || bar.contains("requestDismissKeyguard"))
    }

    @Test
    fun `Home follows the answer and the ring only while visible, asks the microphone once per attempt, and words how a call ended`() {
        assertTrue(home.contains("answerRemover = graph.answer.addListener(answerListener)") && home.contains("answerRemover?.invoke()"))
        assertTrue(home.contains("ringRemover = graph.ring.addChangeListener { runOnUiThread { render() } }") && home.contains("ringRemover?.invoke()"))
        val mic = fn(home, "private fun askAnswerMic(")
        assertTrue(mic.contains("s.snapshot.phase != Phase.AWAITING_MIC || answerMicAskedFor == s.generation"))
        assertTrue(mic.contains("requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_ANSWER_MIC)") && mic.contains("graph.answer.onMicResult(true)"))
        assertTrue(home.contains("REQ_ANSWER_MIC -> graph.answer.onMicResult(granted)"))
        val end = fn(home, "private fun announceAnswerEnd(")
        assertTrue(end.contains("announcedGeneration == s.generation"), "one line per attempt")
        assertFalse(end.contains("ENDED_USER ->"), "a hang-up the person asked for says nothing")
        assertTrue(home.contains("graph.stationCall().also { stationNow = it }"))
    }

    @Test
    fun `the call bar draws Decline and Answer while ringing, and Answer goes through Home so the microphone can be asked`() {
        assertTrue(bar.contains("graph.ring.addChangeListener { post { refresh() } }"), "a ring that starts or stops redraws at once")
        assertTrue(bar.contains("CallBars.fromRing(graph.stationCall())"))
        assertTrue(bar.contains("visibility = if (next.isShown) VISIBLE else GONE"))
        val decline = fn(bar, "private fun endOrDecline()")
        assertTrue(decline.contains("graph.ring.stopRing(ring.doorRef, ring.callId, \"declined from the call bar\")") && decline.contains("CallActions.hangUpAll(graph)"))
        val answer = fn(bar, "private fun answer()")
        assertTrue(answer.contains("setAction(HomeActivity.ACTION_ANSWER)") && answer.contains("FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertFalse(answer.contains("answer.press("), "the one press is Home's")
        assertTrue(bar.contains("answerButton.visibility = if (ringing && next.ring?.canAnswer == true) VISIBLE else GONE"), "a ring-only door offers Decline alone")
        assertTrue(src("call/CallBarHost.kt").contains("is HomeActivity, is SetupActivity -> true"))
    }

    @Test
    fun `the ringing look is the in-call green plus a pulse, and its tile does nothing`() {
        val buttons = src("home/ActionButtons.kt")
        assertTrue(buttons.contains("tile.state == ButtonState.TALK_IN_CALL || tile.state == ButtonState.RINGING_IN\n"), "enabled, so it is not drawn grey")
        assertFalse(buttons.contains("pulse"), "0.6.48: no wave and no pulse on the call button")
        assertTrue(buttons.contains("ButtonState.RINGING_IN -> R.string.home_talk_ringing"))
        assertTrue(src("call/SoundWaveDrawable.kt").contains("private val pulse: Boolean = false"), "the steady wave is the default, so an outgoing call is unchanged")
    }

    @Test
    fun `the new strings exist, and take only the name`() {
        for (name in listOf("callbar_incoming_nameless", "callbar_incoming_hint", "callbar_incoming_ring_only", "callbar_answer", "callbar_decline", "home_talk_ringing", "home_talk_state_ringing")) {
            assertTrue(Regex("<string name=\"$name\">[^%<]+</string>").containsMatchIn(strings), name)
        }
        assertTrue(Regex("<string name=\"callbar_incoming\">%1\\\$s is calling</string>").containsMatchIn(strings))
    }

    @Test
    fun `app-05 - Home follows the ring into its tab, draws the Ring page panel, and gives the tab back`() {
        val render = fn(home, "private fun render()")
        assertTrue(render.contains("tabMemory.update(stationNow, view.selectedId)") && render.contains("StationTab.pageFor("))
        assertTrue(render.indexOf("tabMemory.update") < render.indexOf("rebuild(view)"), "the tab is chosen before the page is drawn")
        assertTrue(render.contains("paintPanel()"))
        val panel = fn(home, "private fun paintPanel()")
        assertTrue(panel.contains("StationPanel.build(") && panel.contains("panelActions.tile(tile, ::onPanelPress)"))
        assertTrue(panel.contains("panel.visibility = if (next == null) View.GONE else View.VISIBLE"), "answered: build returns null and the panel is gone")
        assertFalse(home.contains("StationPanel.screenFor"), "no switch to a call-buttons panel")
        assertTrue(panel.contains("catch (_: Exception)"), "decoration never takes the call bar down")
        // a panel press is the page's press: unlock first, then ActionButtons.press, whose confirm dialog is kept
        val press = fn(home, "private fun onPanelPress(")
        assertTrue(press.contains("whenUnlocked") && press.contains("ActionButtons.press(this, graph, tile, ::say)"))
        assertFalse(press.contains("pressHeld"))
        assertTrue(home.contains("outState.putString(KEY_CALL") && home.contains("StationTabMemory(StationTabMemory.State("), "the memory survives a rotation")
        val layout = File("src/main/res/layout/activity_home.xml").readText()
        assertTrue(layout.contains("@+id/home_station_panel") && layout.contains("@+id/home_station_stills") && layout.contains("@+id/home_station_buttons"))
        assertTrue(layout.indexOf("home_station_panel") < layout.indexOf("@+id/home_blocks\""))
    }
}
