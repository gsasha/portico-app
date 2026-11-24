package com.meda.portico.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-713 S2, checked without a phone (working directory = app/): the caller's first camera live on the ring and in-call screens
 * cannot get in the way of the call. The rules are in text because no emulator can run a ringing lock-screen here: what the screen shows
 * (the chip, the SurfaceView over the keyguard, the decoder) is for a phone.
 */
class CallerLiveResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val call by lazy { src("call/InCallActivity.kt") }
    private val controller by lazy { src("live/CallerLiveController.kt") }
    private val host by lazy { src("live/CallerLiveHost.kt") }
    private val guards by lazy { src("live/AndroidLiveGuards.kt") }
    private val graph by lazy { src("AppGraph.kt") }
    private val home by lazy { src("home/HomeActivity.kt") }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val liveHome by lazy { src("live/LiveHome.kt") }
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }
    private val manifest by lazy { File("src/main/AndroidManifest.xml").readText() }

    private fun between(text: String, from: String, to: String): String {
        assertTrue(text.contains(from), "missing: $from")
        return text.substringAfter(from).substringBefore(to)
    }

    /** The source of a function from its signature to the next member at the same indent. */
    private fun fn(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "missing: $signature")
        val rest = text.substring(start + signature.length)
        val end = Regex("""\n    (private |override |internal |public )?(fun |val |var |companion |class |object )""").find(rest)?.range?.first ?: rest.length
        return signature + rest.substring(0, end)
    }

    // ---- the call comes first -------------------------------------------------------------------

    @Test
    fun `the lock-screen configuration of both screens is untouched`() {
        // APP-03: the ring screen is gone; Home takes the station call over the lock screen (HomeStationCallResourcesTest pins that).
        for ((name, text) in listOf("InCallActivity" to call)) {
            assertTrue(text.contains("setShowWhenLocked(true)") && text.contains("setTurnScreenOn(true)"), name)
            assertTrue(text.contains("FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON"), "$name: the pre-8.1 flags")
            // SEC-01: the caller's buttons (a door, a gate, a lock) need an unlock (A-02), through whenUnlocked and nowhere else; answering and the rest need none.
            val gate = fn(text, "private fun whenUnlocked(")
            // 0.6.96: the in-call screen confirms a door button with an app-level biometric prompt while locked (InCallLock.doorPress), falling back to whenUnlocked; the ring window is as it was.
            val press = fn(text, "private fun onExtraPress(")
            assertTrue(press.contains("whenUnlocked {") || (press.contains("InCallLock.doorPress(") && press.contains("whenUnlocked(run)")), "$name: the caller's buttons go through whenUnlocked or the locked-press biometric")
            val code = text.replace(gate, "").lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }.joinToString("\n")
            assertFalse(code.contains("requestDismissKeyguard"), name)
            assertFalse(code.contains("FLAG_SECURE") || code.contains("FLAG_DISMISS_KEYGUARD"), name)
        }
    }

    @Test
    fun `no live code on the answer, decline, hang-up or mute path`() {
        // APP-03: the answer, decline and ring paths are Home's now, and Home has no caller-live code on them
        for (f in listOf(fn(home, "private fun takeRing("), fn(home, "override fun onNewIntent"), fn(home, "private fun whenUnlocked("))) assertTrue(noLive(f), f.lines().first())
        assertTrue(noLive(between(call, "findViewById<Button>(R.id.call_hangup)", "findViewById<Button>(R.id.call_close)")), "hang up and mute")
        assertTrue(noLive(fn(call, "private fun show(s: CallUiState)")), "show() only redraws the caller tiles, like before")
        assertTrue(noLive(fn(call, "override fun onRequestPermissionsResult")))
    }

    /** No use of the live code in this text (a comment may say the word). */
    private fun noLive(text: String) = !text.contains("live?.") && !text.contains("CallerLive") && !text.contains("LiveHome") && !text.contains("callerLive")

    @Test
    fun `live is built in a fence, started after the call UI is up, and stopped on pause`() {
        for ((name, text) in listOf("InCallActivity" to call)) {
            val create = between(text, "live = try {", "actionButtons = ActionButtons(this)")
            assertTrue(create.contains("CallerLiveController(") && create.contains("catch (_: Throwable)") && create.contains("null"), "$name: built in a try, null on any failure")
            val resume = fn(text, "override fun onResume()")
            assertTrue(resume.indexOf("handler.post(") in 0 until resume.indexOf("live?.resumed()"), "$name: after the screen's own ticks are posted")
            assertTrue(resume.lines().filter { it.isNotBlank() }.dropLast(1).last().contains("live?.resumed()"), "$name: the last thing onResume does")
            val pause = fn(text, "override fun onPause()")
            assertTrue(pause.contains("live?.paused()") && pause.indexOf("live?.paused()") < pause.indexOf("super.onPause()"), name)
            assertFalse(text.contains("LiveHome("), "$name builds no live code of its own: it uses the shared one")
            assertTrue(text.contains("live?.playing(it.id) != true"), "$name: a playing tile asks for no stills")
        }
        assertTrue(call.contains("private fun callLiveNow(): Boolean = state?.let { CallUi.controls(it.snapshot).hangUp } == true"), "streams only while the call is live")
    }

    @Test
    fun `the controller waits for the arming delay, the drawn still and the screen, and fences every entry point`() {
        val sync = fn(controller, "private fun sync()")
        assertTrue(sync.contains("faults.run(\"sync\")") && sync.contains("if (arm.ready(clock.nowMs())) plan()"))
        val resumed = fn(controller, "fun resumed()")
        assertFalse(resumed.contains("plan()") || resumed.contains(".update("), "resume only arms a timer and takes the lease")
        assertTrue(resumed.contains("main.postDelayed(tick, CallerLiveTiming.ARM_DELAY_MS)"))
        assertTrue(host.contains("const val ARM_DELAY_MS = 800L"))
        for (entry in listOf("fun resumed()", "fun paused()", "private fun sync()", "private fun chip(tile: CameraTile)", "private fun onUi()")) {
            assertTrue(fn(controller, entry).contains("faults.run("), "$entry is fenced")
        }
        assertTrue(controller.contains("CallerLive.tiles("), "available only once the still is drawn, on screen only")
        assertTrue(controller.contains("shown = allowed() && v != null"), "the screen's own rule closes the streams when the call is over")
        assertFalse(controller.contains("graph.answer") || controller.contains("graph.talk") || controller.contains("graph.ring."), "the call is not read or touched from here")
    }

    @Test
    fun `the lease hands the live code from one screen to the other and tears it down after a grace`() {
        assertTrue(controller.contains("graph.callerLive.claim(this)") && controller.contains("graph.callerLive.release(this)"))
        val paused = fn(controller, "fun paused()")
        assertTrue(paused.indexOf("tiles.releaseRenderers()") < paused.indexOf("graph.callerLive.release(this)"), "the views go first, then the lease")
        assertTrue(host.contains("LiveLease<LiveHome>") && host.contains("destroy = { it.destroy() }") && host.contains("graceMs = GRACE_MS"))
        assertTrue(graph.contains("val callerLive: com.meda.portico.live.CallerLiveHost by lazy"), "one holder for the whole process")
        assertFalse(host.contains("graph.answer") || host.contains("AnswerCoordinator(") , "the call's audio peer connection shares nothing with it")
    }

    // ---- the tile ------------------------------------------------------------------------------------------

    @Test
    fun `the call screens' tiles get the live words and a chip tap, and Home's size toggle is not theirs`() {
        assertTrue(tiles.contains("if (interactive || live != null) {"), "chip, fps label and notice exist on a non-interactive tile when its screen gave it a hand-off")
        assertTrue(controller.contains("override fun onLiveChip(tile: CameraTile) = chip(tile)"))
        assertFalse(controller.contains("onTap(") || controller.contains("onResizeButton("), "a tap on a call tile means nothing but the chip: the viewport controls are Home's")
        assertTrue(controller.contains("tiles.live = this") && controller.contains("tiles.liveViews = "))
        assertFalse(tiles.contains("setZOrderOnTop") || tiles.contains("setZOrderMediaOverlay(true)"))
    }

    @Test
    fun `a chip tap remembers the choice only when it differs from the default, by the camera id`() {
        val chip = fn(controller, "private fun chip(tile: CameraTile)")
        assertTrue(chip.contains("graph.liveChoices.toggle(tile.id, tile.live)"))
        assertTrue(chip.contains("h.restart(key)"), "a tile that gave up asks again")
    }

    // ---- the guards ------------------------------------------------------------------------------------------

    @Test
    fun `the thermal listener is API 29 and guarded, the decoder query is the platform's, and both are read on the live thread`() {
        assertTrue(guards.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null"), "minSdk 26: no thermal listener below 29")
        assertTrue(guards.contains("addThermalStatusListener(app.mainExecutor, listener)") && guards.contains("removeThermalStatusListener(listener)") && guards.contains("onStatus(pm.currentThermalStatus)"))
        assertTrue(guards.contains("getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances"))
        assertTrue(guards.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null"))
        val update = between(liveHome, "fun update(input: LiveInput) = post {", "manager.reconcile(p)")
        assertTrue(update.contains("guards.thermalHot()") && update.contains("guards.maxStreams()"), "read inside the live thread's task, never on the main thread")
        assertTrue(liveHome.contains("LiveManager(onFault = faults::report)"))
        assertTrue(graph.contains("HomeLiveHost(app, { signaling }, pushJournal.liveLog, liveGuards)"), "Home (through the graph's shared LiveHome) has the same guards")
        assertTrue(graph.contains("val liveGuards: com.meda.portico.core.live.LiveGuards by lazy"))
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(File("build.gradle.kts").readText())!!.groupValues[1].toInt()
        assertTrue(minSdk < 29, "if minSdk reaches 29 the version guard can go")
    }

    @Test
    fun `the new notices say what was decided`() {
        fun str(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no string $name")
        assertEquals("Live paused: the phone is hot", str("live_notice_hot"))
        assertEquals("Live unavailable here: too many live videos for this phone", str("live_notice_phonelimit"))
        val map = between(tiles, "private fun noticeText", "/** Draws the live words")
        assertTrue(map.contains("LiveWhy.HOT -> R.string.live_notice_hot") && map.contains("LiveWhy.PHONE_LIMIT -> R.string.live_notice_phonelimit"))
    }

    @Test
    fun `nothing in the new live code opens the microphone or touches the audio mode or the connection internals`() {
        for ((name, text) in listOf("controller" to controller, "host" to host, "guards" to guards)) {
            for (banned in listOf("AudioRecord", "createAudioSource", "RECORD_AUDIO", "AudioManager", "MODE_IN_COMMUNICATION", "FallbackConnector", "ConnectTimeline", "connlog", "TailnetController", "AnswerSession", "TalkSession", "HouseMedia", "WebRtcMedia")) {
                assertFalse(text.contains(banned), "$name: $banned")
            }
        }
    }
}
