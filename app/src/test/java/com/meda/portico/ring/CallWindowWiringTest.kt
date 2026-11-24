package com.meda.portico.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 0.6.75 / 0.6.76: source-pattern pins of the call window (the Android side cannot run on the JVM; working directory = app/). The view-model is
 * `:core`'s CallWindowModelTest; these pin that the activity only renders it, that both ring paths open it, that Answer hands the call to Home and
 * closes the window, that the window has no in-call phase, and that it rings (and stops) itself when it owns the ring.
 */
class CallWindowWiringTest {
    private fun read(p: String) = File(p).readText()
    private val window by lazy { read("src/main/java/com/meda/portico/ring/RingCallActivity.kt") }
    private val notifier by lazy { read("src/main/java/com/meda/portico/ring/RingNotifier.kt") }
    private val launcher by lazy { read("src/main/java/com/meda/portico/ring/DirectRingLauncher.kt") }
    private val alert by lazy { read("src/main/java/com/meda/portico/ring/RingWindowAlert.kt") }
    private val manifest by lazy { read("src/main/AndroidManifest.xml") }
    private val code by lazy { window.lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }.joinToString("\n") }

    /** The source of a function from its signature to the next member at the same indent. */
    private fun fn(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "missing: $signature")
        val rest = text.substring(start + signature.length)
        val end = Regex("""\n    (private |override |internal |public )?(fun |val |var |companion |class |object )""").find(rest)?.range?.first ?: rest.length
        return signature + rest.substring(0, end)
    }

    @Test
    fun `the manifest declares the window with the lock-screen, task and recents flags`() {
        val a = manifest.substringAfter("""android:name=".ring.RingCallActivity"""").substringBefore("/>")
        for (needle in listOf(
            """android:exported="false"""", """android:excludeFromRecents="true"""", """android:launchMode="singleTask"""",
            """android:taskAffinity="com.meda.portico.callwindow"""", """android:showWhenLocked="true"""", """android:turnScreenOn="true"""",
            """android:theme="@style/Theme.Portico.CallWindow"""",
        )) assertTrue(needle in a, needle)
        val theme = read("src/main/res/values/themes.xml").substringAfter("Theme.Portico.CallWindow").substringBefore("</style>")
        assertTrue("Theme.AppCompat.NoActionBar" in read("src/main/res/values/themes.xml").substringBefore("Theme.Portico.Scan").substringAfter("Theme.Portico.CallWindow").substringBefore(">"), "a dark AppCompat theme, not DayNight")
        assertTrue("windowBackground" in theme && "statusBarColor" in theme && "navigationBarColor" in theme, "dark and edge to edge")
        assertTrue("""<uses-permission android:name="android.permission.VIBRATE" />""" in manifest, "the window vibrates itself")
    }

    @Test
    fun `the window is over the lock screen with the screen on and never asks for the unlock by itself`() {
        assertTrue("setShowWhenLocked(true)" in window && "setTurnScreenOn(true)" in window)
        assertTrue("FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON" in window, "the pre-8.1 flags")
        assertTrue("FLAG_KEEP_SCREEN_ON" in window)
        assertTrue("WindowCompat.setDecorFitsSystemWindows(window, false)" in window, "edge to edge")
        assertEquals(1, Regex("requestDismissKeyguard").findAll(code).count(), "only a Ring page button asks, never a launch, Answer or Decline")
        assertTrue("requestDismissKeyguard" in fn(window, "private fun whenUnlocked("))
        assertFalse("FLAG_DISMISS_KEYGUARD" in code || "FLAG_SECURE" in code)
        assertEquals(1, Regex("whenUnlocked \\{").findAll(code).count(), "the one caller is the Ring page's button press")
        assertTrue("DoorUnlockRule.needsUnlock" in code)
    }

    @Test
    fun `both ring paths open the window - the full-screen intent and the direct launch share one intent`() {
        val pi = fn(notifier, "private fun showIntent(")
        assertTrue("target: Class<*> = RingCallActivity::class.java" in pi && "Intent(context, target)" in pi)
        assertTrue("putExtra(RingCallActivity.EXTRA_DOOR, doorRef)" in pi && "putExtra(RingCallActivity.EXTRA_CALL, callId)" in pi, "the ring's extras: door ref and call id")
        // (a) the notification: full-screen and content are PendingIntents on that intent
        assertTrue("activityPi(slot, PiAction.FULL_SCREEN, RingCallActivity::class.java, RingCallActivity.ACTION_SHOW, doorRef, callId)" in notifier)
        assertTrue("setFullScreenIntent(full, true)" in notifier && "setContentIntent(full)" in notifier)
        assertTrue("showIntent(" in fn(notifier, "private fun activityPi("))
        // (b) the direct launch: the same showIntent, handed to the launcher, which starts it unchanged
        assertTrue("showIntent(RingCallActivity.ACTION_SHOW, doorRef, callId)" in notifier.substringAfter("launcher.launch("))
        assertTrue("app.startActivity(Intent(ringScreen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))" in launcher)
        // the CallStyle notification is still posted as before, with Decline a broadcast to the receiver
        assertTrue("Notification.CallStyle.forIncomingCall(person, decline, answer)" in notifier)
        assertTrue("RingReceiver.ACTION_DECLINE" in notifier)
        // one window per ring: the action and extras the window reads are the ones the notifier sends
        assertEquals("com.meda.portico.ring.SHOW", Regex("ACTION_SHOW = \"(.*?)\"").find(window)!!.groupValues[1])
        assertTrue("override fun onNewIntent" in window && "setIntent(intent)" in window)
    }

    @Test
    fun `house rings keep their own screen`() {
        val house = read("src/main/java/com/meda/portico/ring/HouseRingNotifier.kt")
        assertTrue("Intent(context, HouseRingActivity::class.java)" in house && "RingCallActivity" !in house)
    }

    @Test
    fun `the window only renders the view-model - what to show or that it is done is core's CallWindowModel_step`() {
        val refresh = fn(window, "private fun refresh()")
        assertTrue("CallWindowModel.step(call, answer," in refresh && "graph.stationCall()" in refresh)
        assertTrue("is CallWindowStep.Finish -> close(step.reason)" in refresh)
        assertTrue("catch (_: Exception)" in refresh, "a failure while drawing never ends or blocks the call")
        // the Ring page content: core's one resolver, the same as Home's ring panel
        assertTrue("RingPage.content(" in fn(window, "private fun ringPage("))
        assertTrue("RingPage.content(" in read("../core/src/main/kotlin/com/meda/portico/core/content/StationTab.kt").substringAfter("object StationPanel").substringBefore("object RingPage"), "Home's panel uses the same resolver")
        // the existing camera and button views, and the live plan of the ring screens
        assertTrue("StillTiles(this, findViewById(R.id.cw_stills), 1)" in window && "CallerLiveController(graph, stillTiles" in window && "actionButtons.tile(tile, ::onButton)" in window)
        assertTrue("ActionButtons.press(this, graph, tile" in window && "PressRoute.of(tile) != PressRoute.ACTION" in window)
    }

    @Test
    fun `Answer closes the window and starts Home with the call - the ring's own intent, into the app's task`() {
        val answer = fn(window, "private fun answerPressed()")
        assertTrue("startActivity(homeIntent(v.doorRef, v.callId))" in answer)
        assertTrue("close(CloseReason.ANSWERED)" in answer)
        assertTrue(answer.indexOf("startActivity(") < answer.indexOf("close(CloseReason.ANSWERED)"), "Home is started first: the lock screen or the launcher never shows in between")
        assertTrue("catch (e: Exception)" in answer && "close(CloseReason.ANSWERED)" !in answer.substringAfter("catch (e: Exception)"), "a hand-off that cannot start leaves the window up")
        val home = fn(window, "private fun homeIntent(")
        assertTrue("Intent(this, HomeActivity::class.java)" in home && "setAction(HomeActivity.ACTION_ANSWER)" in home)
        assertTrue("putExtra(HomeActivity.EXTRA_DOOR, doorRef)" in home && "putExtra(HomeActivity.EXTRA_CALL, callId)" in home, "door_ref / call_id")
        for (flag in listOf("FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_CLEAR_TOP", "FLAG_ACTIVITY_SINGLE_TOP", "FLAG_ACTIVITY_REORDER_TO_FRONT")) assertTrue(flag in home, flag)
        // the answer press itself stays the coordinator's one call, made by Home on that intent (ScreensResourcesTest pins the single press)
        assertEquals(0, Regex("graph\\.answer\\.press\\(").findAll(code).count(), "the window never presses by itself")
        assertTrue("graph.answer.press(d, c)" in read("src/main/java/com/meda/portico/home/HomeActivity.kt"))
        assertTrue("CallWindowModel.ANSWERED_HOME_LINE" in read("src/main/java/com/meda/portico/home/HomeActivity.kt"))
        // and the notification's Answer opens Home directly, never the window
        assertTrue("activityPi(slot, PiAction.ANSWER, HomeActivity::class.java, HomeActivity.ACTION_ANSWER, doorRef, callId)" in notifier)
        assertFalse("ACTION_ANSWER" in code.replace("HomeActivity.ACTION_ANSWER", ""), "the window has no Answer action of its own any more")
    }

    @Test
    fun `Decline calls the shared handler`() {
        val decline = fn(window, "private fun declinePressed()")
        assertTrue("graph.ring.stopRing(v.doorRef, v.callId," in decline, "Decline: the same stopRing the notification's Decline (RingReceiver) and the call bar use")
        assertTrue("ring.stopRing(door, call, why)" in read("src/main/java/com/meda/portico/ring/RingReceiver.kt"))
        assertTrue("close(CloseReason.DECLINED)" in decline)
    }

    @Test
    fun `the window has no in-call phase - no End call, route, volume, timer or Connected`() {
        for (gone in listOf("CallActions", "hangUpAll", "AudioControlsView", "audioControls", "renderTimer", "timerTick", "connectedAtMs", "askMic", "onMicResult", "RECORD_AUDIO", "STREAM_VOICE_CALL", "cw_end", "cw_timer", "cw_audio", "CallWindowStatus.CONNECT", "CallWindowPhase.CONNECTING", "CallWindowPhase.IN_CALL")) {
            assertFalse(gone in code, "RingCallActivity still has $gone")
        }
        val l = read("src/main/res/layout/activity_call_window.xml")
        for (gone in listOf("cw_end", "cw_timer", "cw_audio", "view_audio_controls")) assertFalse(gone in l, gone)
        val strings = read("src/main/res/values/strings.xml")
        for (gone in listOf("cw_label_in_call", "cw_status_connecting", "cw_status_need_mic", "cw_status_connected", "cw_status_muted", "\"cw_end\"")) assertFalse(gone in strings, gone)
        val model = read("../core/src/main/kotlin/com/meda/portico/core/callwindow/CallWindowModel.kt")
        assertTrue("enum class CallWindowPhase { RINGING, FAILED }" in model)
    }

    @Test
    fun `no stuck window - every end closes it, Back closes it, and it removes its own task`() {
        val refresh = fn(window, "private fun refresh()")
        assertTrue("close(step.reason)" in refresh)
        // listening from creation to destruction: a window that is not in front still closes itself when its ring is over
        val create = fn(window, "override fun onCreate(")
        assertTrue("graph.ring.addChangeListener" in create && "graph.answer.addListener" in create, "the ring ending (answered elsewhere, expired) and the answer starting")
        assertTrue("removers.forEach { it() }" in fn(window, "override fun onDestroy()"))
        assertTrue("onBackPressedDispatcher.addCallback(" in create && "close(CloseReason.BACK)" in create)
        assertTrue("close(CloseReason.DECLINED)" in fn(window, "private fun declinePressed()"))
        val close = fn(window, "private fun close(")
        assertTrue("finishAndRemoveTask()" in close && "if (closing) return" in close)
        assertTrue("CallWindowModel.closedLine(reason)" in close)
        assertTrue("CallWindowModel.OPENED_LINE" in create)
        assertTrue("FAILED_LINGER_MS" in window, "a failed answer is closed by a timer as well as its button")
        assertTrue("window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in close)
    }

    @Test
    fun `the window plays the ring channel's own sound and vibration in a loop and stops it on every way out`() {
        // the alert reads what the user set on Android's page for the ring channel
        assertTrue("getNotificationChannel(Channel.RING.id)" in alert)
        for (needle in listOf("ch.sound", "audioAttributes", "shouldVibrate()", "vibrationPattern", "RingtoneManager.getRingtone(", "r.play()", "canBypassDnd()", "currentInterruptionFilter", "RingSoundPolicy.silenced(")) assertTrue(needle in alert, needle)
        assertTrue("isLooping = true" in alert && "createWaveform(pattern, 0)" in alert, "loops until stopped")
        assertTrue("ringtone?.stop()" in alert && "cancel()" in alert)
        assertFalse("setStream" in alert || "STREAM_RING" in alert, "the channel's own audio attributes (the alarm stream) are used")
        // the window runs it through the core controller
        assertTrue("RingSoundController(alert, RingWindowSignals.ownership::owns, alert::silenced)" in window)
        assertTrue("sound.onWindow(step.view.callId, step.view.phase == CallWindowPhase.RINGING)" in fn(window, "private fun refresh()"), "ringing only")
        // stops: answer / decline / any close, destruction, a volume key
        assertTrue("sound.reset()" in fn(window, "private fun close("))
        assertTrue("sound.reset()" in fn(window, "override fun onDestroy()"))
        assertTrue("sound.stop()" in fn(window, "override fun onKeyDown("))
        // the window says it is up (the notifier's watchdog) and that it is gone
        assertTrue("RingWindowSignals.ownership.windowOpened(c)" in fn(window, "private fun markOpen("))
        assertTrue("windowClosed(it)" in fn(window, "override fun onDestroy()"))
    }

    @Test
    fun `the layout has the round Answer and Decline, an empty-safe middle and scrolls`() {
        val l = read("src/main/res/layout/activity_call_window.xml")
        for (id in listOf("cw_name", "cw_label", "cw_status", "cw_stills", "cw_buttons", "cw_answer", "cw_decline", "cw_close", "cw_scroll")) assertTrue("""@+id/$id"""" in l, id)
        assertTrue("<ScrollView" in l, "the middle scrolls")
        assertTrue("@drawable/call_round_green" in l && "@drawable/call_round_red" in l)
        assertTrue("@drawable/ic_mdi_phone\"" in l && "@drawable/ic_mdi_phone_hangup" in l)
        assertEquals("""<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">""", read("src/main/res/drawable/call_round_green.xml").lines().first { it.startsWith("<shape") })
    }

    @Test
    fun `the call bar host does not add a bar to the window - it has its own controls`() {
        assertFalse("RingCallActivity" in read("src/main/java/com/meda/portico/call/CallBarHost.kt").substringAfter("override fun onActivityStarted").substringBefore("override fun onActivityResumed"))
    }
}

/** the owner 2026-11-21 (D-20261121-2918): Answer from the lock screen needs no unlock; a Setup control; the door rule is untouched. */
class AnswerWithoutUnlockWiringTest {
    private fun read(p: String) = File(p).readText()
    private val locked by lazy { read("src/main/java/com/meda/portico/call/LockedAnswer.kt") }
    private val window by lazy { read("src/main/java/com/meda/portico/ring/RingCallActivity.kt") }
    private val home by lazy { read("src/main/java/com/meda/portico/home/HomeActivity.kt") }

    @Test
    fun `the window and Home route the answer by the lock policy`() {
        assertTrue("LockedAnswer.decide(this, graph, v.callId" in window.substringAfter("private fun answerPressed()").substringBefore("private fun homeIntent"))
        assertTrue("LockedAnswer.decide(this, graph, c," in home)
        assertTrue("AnswerLockPolicy.route(locked, setting)" in locked)
    }

    @Test
    fun `the locked route opens the show-when-locked in-call screen after pressing, and never dismisses the keyguard`() {
        val open = locked.substringAfter("fun openInCall(").substringBefore("private fun journal")
        assertTrue(open.indexOf("graph.answer.press(doorRef, callId)") in 0 until open.indexOf("InCallActivity::class.java"))
        assertTrue("FLAG_ACTIVITY_NEW_TASK" in open)
        assertEquals(1, Regex("requestDismissKeyguard").findAll(locked).count(), "only the setting-off route asks for the unlock")
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue("""android:showWhenLocked="true"""" in manifest.substringAfter("""android:name=".call.InCallActivity"""").substringBefore("/>"))
    }

    @Test
    fun `the Journal says whether the answer ran locked and what it waited for, and the cfg line carries the setting`() {
        for (f in listOf("\"ans-lock\"", "\"locked\" to locked", "\"set\" to setting", "\"route\" to route.word", "\"waited\"", "\"ans-unlock\"")) assertTrue(f in locked, f)
        assertTrue("answerWithoutUnlock = callAudio.answerWithoutUnlock()" in read("src/main/java/com/meda/portico/AppGraph.kt"))
        assertTrue("\"ans-fgs\"" in read("src/main/java/com/meda/portico/call/CallService.kt"))
    }

    @Test
    fun `Setup has the control and the caller's buttons keep the unlock rule`() {
        assertTrue("setup_answer_nolock" in read("src/main/res/layout/activity_setup.xml"))
        assertTrue("setAnswerWithoutUnlock" in read("src/main/java/com/meda/portico/setup/SetupActivity.kt"))
        assertTrue("DoorUnlockRule.needsUnlock" in window)
        assertTrue("DoorUnlockRule.needsUnlock" in read("src/main/java/com/meda/portico/call/InCallActivity.kt"))
    }
}

/** the owner 2026-11-21 (0.6.96): Unlock while the call goes on; door buttons confirm with an app-level prompt while locked; the door name in the title. */
class InCallUnlockWiringTest {
    private fun read(p: String) = File(p).readText()
    private val incall by lazy { read("src/main/java/com/meda/portico/call/InCallActivity.kt") }
    private val unlock by lazy { read("src/main/java/com/meda/portico/call/InCallUnlock.kt") }
    private val bio by lazy { read("src/main/java/com/meda/portico/call/DoorBiometric.kt") }

    @Test
    fun `the screen has an Unlock control and asks the system, once, without touching the call`() {
        assertTrue("call_unlock" in read("src/main/res/layout/activity_call.xml"))
        assertTrue("InCallLock.unlockControlVisible(" in incall)
        assertTrue("requestDismissKeyguard" in unlock)
        assertEquals(0, Regex("requestDismissKeyguard").findAll(incall.substringBefore("private fun whenUnlocked")).count(), "the screen asks through InCallUnlock")
        val auto = incall.substringAfter("private fun maybeAutoUnlock()").substringBefore("private fun requestUnlock")
        assertTrue("InCallLock.autoRequest(" in auto)
        assertTrue(auto.indexOf("lastAutoCallId = callId") in 0 until auto.indexOf("requestUnlock(InCallLock.Source.AUTO)"), "marked before the request, so it never repeats")
        // nothing in the request or the switch presses, answers or hangs up
        for (src in listOf(unlock, incall.substringAfter("private fun requestUnlock").substringBefore("private fun doorTitle"))) {
            assertFalse(".press(" in src); assertFalse("hangUp" in src); assertFalse("setMuted" in src)
        }
    }

    @Test
    fun `a success opens Home with no Answer action, then closes the screen, cancel and error stay`() {
        val open = incall.substringAfter("private fun openFullApp").substringBefore("/** The door's name")
        assertTrue("HomeActivity::class.java" in open)
        assertFalse("ACTION_ANSWER" in open); assertFalse("setAction" in open); assertFalse("EXTRA_DOOR" in open)
        assertTrue("InCallLock.afterUnlock(r) == InCallLock.AfterUnlock.OPEN_FULL_APP" in incall)
        assertTrue("fun takeRing" in read("src/main/java/com/meda/portico/home/HomeActivity.kt") && "if (i.action != ACTION_SHOW && i.action != ACTION_ANSWER) return" in read("src/main/java/com/meda/portico/home/HomeActivity.kt"), "a plain launch of Home answers nothing")
    }

    @Test
    fun `the caller's door buttons go through the biometric decision while locked, and the old rule stays as the fallback`() {
        val press = incall.substringAfter("private fun onExtraPress").substringBefore("// ---- the owner 2026-11-21: Unlock")
        assertTrue("InCallLock.doorPress(locked)" in press)
        assertTrue("DoorBiometric.prompt(" in press)
        assertTrue("InCallLock.afterBio(r)" in press)
        assertTrue("RUN_ACTION -> run()" in press && "ASK_KEYGUARD -> whenUnlocked(run)" in press)
        assertTrue("DoorUnlockRule.needsUnlock" in incall)
        assertTrue("ActionButtons.press(this, graph, tile)" in press, "the existing path, its confirm dialog included")
        // the biometric prompt never asks for the keyguard
        assertFalse("requestDismissKeyguard" in bio); assertFalse("KeyguardManager" in bio)
        assertTrue("setAllowedAuthenticators" in bio && "InCallLock.bioAuth(Build.VERSION.SDK_INT)" in bio)
    }

    @Test
    fun `the Journal says every step`() {
        for (f in listOf("\"unlock-req\"", "\"unlock-res\"", "\"src\" to source.word", "\"r\" to r.word", "\"ms\"", "\"set\" to setting", "\"locked\"")) assertTrue(f in unlock, f)
        for (f in listOf("\"bio-prompt\"", "\"bio-result\"")) assertTrue(f in bio, f)
        for (f in listOf("\"door-press\"", "\"unlock-app\"", "\"title\"")) assertTrue(f in incall, f)
    }

    @Test
    fun `Setup has Unlock when answering and the cfg line carries it`() {
        assertTrue("setup_unlock_answer" in read("src/main/res/layout/activity_setup.xml"))
        assertTrue("setUnlockWhenAnswering" in read("src/main/java/com/meda/portico/setup/SetupActivity.kt"))
        assertTrue("unlockWhenAnswering = callAudio.unlockWhenAnswering()" in read("src/main/java/com/meda/portico/AppGraph.kt"))
    }

    @Test
    fun `the title comes from the document's block, not the driver name`() {
        assertTrue("doorTitle(s, graph)" in incall)
        assertFalse("CallUi.doorName(s, graph.doors)" in incall)
        assertTrue("CallDoorTitle.blockTitleFor(" in incall)
    }
}
