package com.meda.portico.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-713 stage S1, checked without a phone (working directory = app/): the LIVE chip, the fps label and the notice on a Home tile, the
 * Settings switch, and the rules that keep live video VIDEO ONLY, off by one switch and closed when Home stops. What a phone shows
 * (time to first frame, the decoder, the look of the chip) is for the proof sitting.
 */
class LiveResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val activity by lazy { src("home/HomeActivity.kt") }
    private val liveFiles by lazy { File("src/main/java/com/meda/portico/live").listFiles()!!.filter { it.extension == "kt" } }
    private val liveText by lazy { liveFiles.joinToString("\n") { it.readText() } }

    private fun str(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no string $name")

    private fun between(text: String, from: String, to: String) = text.substringAfter(from).substringBefore(to)

    // ---- the words ----

    @Test
    fun `the chip says LIVE with a red dot while it plays, and a dim Live otherwise`() {
        // 0.6.50: the icon is a glyph drawable in a fixed slot, never a character of the text (LiveChipGlyphTest)
        assertEquals("LIVE", str("live_chip_label"))
        assertTrue(tiles.contains("ChipLook.ON -> drawChip(chip, R.string.live_chip_label"))
        assertTrue(File("src/main/java/com/meda/portico/home/LiveChipGlyph.kt").readText().contains("paint.color = 0xFFFF3B30.toInt()"), "the dot is red")
    }

    @Test
    fun `the notice says why, with the away words exactly as decided`() {
        assertEquals("Live video away from home is not available yet", str("live_notice_away"))
        for (k in listOf("nolive", "toomany", "decoder", "connect", "server")) assertTrue(str("live_notice_$k").startsWith("Live unavailable here: "), k)
        val map = between(tiles, "private fun noticeText", "/** Draws the live words")
        for (w in listOf("AWAY", "NOT_SET_UP", "NO_LIVE", "TOO_MANY", "DECODER", "CONNECT", "SERVER")) assertTrue(map.contains("LiveWhy.$w ->"), w)
    }

    @Test
    fun `the fps rides in the LIVE chip (one pill), as the core text only`() {
        assertTrue(tiles.contains("LiveChipGlyphs.trail(ui.chip, v.canRetry, ui.notice, ui.fps), ui.fps)"), "the text is :core's (-- / N fps), in the chip trail slot")
        assertTrue(tiles.contains("ChipLook.ON -> drawChip(chip, R.string.live_chip_label, R.string.live_chip_desc_on, Color.WHITE)"))
        assertFalse(tiles.contains("var fps: TextView") || tiles.contains("v.fps"), "no separate fps marker")
        assertTrue(!strings.contains("name=\"live_fps"), "no second wording of it in the resources")
    }

    // ---- the tile ----

    @Test
    fun `the chip is the live switch and a tap on the tile stays big or small`() {
        assertTrue(tiles.contains("chip?.setOnClickListener { listener?.onLiveChip(tile) }"))
        assertTrue(tiles.contains("fun onLiveChip(tile: CameraTile) {}"), "ring and in-call listeners need not know it")
        assertTrue(tiles.contains("v.frame.setOnClickListener { handle(v, g.activate()) }"), "the tile's own tap is untouched")
        assertTrue(activity.contains("override fun onLiveChip(tile: CameraTile) = liveChip(tile)"))
        val chip = between(activity, "private fun liveChip", "// ---- drawing")
        assertTrue(chip.contains("graph.liveChoices.toggle(tile.id, tile.live)"), "remembered by LiveMemory, only when it differs from the document")
        assertTrue(chip.contains("liveHome.views[tile.id]?.key?.let { liveHome.restart(it) }"), "a tile that gave up asks again")
        assertFalse(chip.contains("setTileSize") || chip.contains("graph.sizes"))
    }

    @Test
    fun `the renderer sits above the still and below the words, and nothing paints a background on it`() {
        assertTrue(tiles.contains("v.frame.addView(r, 0, "), "0.6.49: index 0, BELOW the still, which covers the hole until a frame is drawn")
        assertFalse(tiles.contains("r.setBackgroundColor") || tiles.contains("renderer?.setBackgroundColor") || tiles.contains("setZOrderOnTop"))
        assertFalse(tiles.contains("hiddenVideo"), "0.6.49: no 1x1 view that grows at the first frame (that resize was the blank frame)")
        assertTrue(tiles.contains("v.frame.addView(r, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))"), "full size from the start, VISIBLE always")
        assertTrue(tiles.contains("RendererCommon.ScalingType.SCALE_ASPECT_FILL"), "centre-crop, like the still")
        assertTrue(liveText.indexOf("setBackgroundColor") < 0, "none in the live package either")
        val words = between(tiles, "var notice: TextView? = null", "box.addView(frame")
        assertTrue(words.indexOf("notice = ") < words.indexOf("chip = "), "notice, chip: drawn over the video in that order")
    }

    @Test
    fun `no video view is ever INVISIBLE or GONE, because a SurfaceView that is not VISIBLE has no surface and never draws its first frame`() {
        val code = tiles.lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }
            .joinToString("\n") { it.substringBefore(" // ") }
        assertFalse(code.contains("INVISIBLE"), "StillTiles must not use INVISIBLE at all")
        for (line in code.lines().filter { it.contains("GONE") || it.contains("visibility") }) {
            // the only visibility writes are the live words (notice, fps label, chip) and the plain-text label; never a video view
            assertFalse(Regex("""\b(r|renderer|rtsp|t|view)\b\??\.""").containsMatchIn(line.substringBefore("visibility")), "a video view's visibility is touched: $line")
        }
        val show = between(tiles, "private fun showRenderer", "private fun restoreStill")
        assertTrue(show.contains("v.videoShown && v.rendered"), "shown only once a frame has been drawn, and not while the tile is zoomed or the stream is down")
        assertTrue(show.contains("image.animate().alpha(0f)") && show.contains("restoreStill(v)"), "the still fades out over a drawn video and is back at once otherwise; the renderer is never resized")
    }

    @Test
    fun `a pinch shows the still, the renderers die with the grid and with Home`() {
        assertTrue(tiles.contains("v.videoShown = ui.video"))
        assertTrue(tiles.contains("v.gesture?.zoomEnabled = tile.picture == PictureState.PICTURE || ui.video"))
        assertTrue(between(tiles, "fun reset()", "/** Adds one block").contains("releaseRenderers()"))
        assertTrue(between(tiles, "fun rebuild(", "/** Forgets every tile").contains("releaseRenderers()"))
        assertTrue(tiles.contains("r.release()"))
    }

    @Test
    fun `a tile that plays asks for no stills`() {
        assertTrue(activity.contains("liveHome.views[it.id]?.ui?.video != true"))
        assertTrue(activity.contains("StillWidth.forCamera(sizes[it], stillTiles.zoomOf(it), callSizes[it], panelTiles.zoomOf(it))"), "the still request itself is unchanged")
    }

    // ---- when it runs ----

    @Test
    fun `Home stop releases the renderers then hands the sessions to the 30 s background grace`() {
        val stop = between(activity, "override fun onStop()", "// ---- state")
        assertTrue(stop.indexOf("started = false") in 0 until stop.indexOf("stillTiles.releaseRenderers()"))
        assertTrue(stop.indexOf("stillTiles.releaseRenderers()") < stop.indexOf("graph.homeLiveGrace.leave("), "the views go first, then the sessions get their 30 s (0.6.73; HomeLiveGrace stops them on expiry)")
        assertTrue(stop.indexOf("graph.homeLiveGrace.leave(") < stop.indexOf("super.onStop()"))
        assertTrue(activity.contains("graph.homeLive.release(this)"), "and the live thread ends with the last holder (HomeLiveHost: Home or the ring pre-warm)")
    }

    @Test
    fun `the plan reads the switch, the pause, the link and the path, and the path only from the graph`() {
        val sync = between(activity, "private fun syncLive", "/** A tap on a tile's LIVE chip")
        for (needle in listOf("graph.liveVideoEnabled()", "shown = started", "graph.isPaused()", "linkState() == LinkState.CONNECTED", "path = graph.linkPath", "stillTiles.visibleIds()", "graph.liveChoices.effective(t.id, t.live)")) {
            assertTrue(sync.contains(needle), needle)
        }
        val g = src("AppGraph.kt")
        assertTrue(g.contains("val settings = settingsCache") || g.contains("val s = settingsCache ?: return null"), "never opens Settings; stills while they are not open")
        assertTrue(g.contains("var linkPath: LinkPath? = null"))
    }

    @Test
    fun `the live code never reaches into the connection internals or the call paths`() {
        for (banned in listOf("FallbackConnector", "ConnectTimeline", "connlog", "PathRecheck", "TailnetController", "AnswerSession", "TalkSession", "HouseMedia", "WebRtcMedia", "TailnetAwayMedia")) {
            assertFalse(liveText.contains(banned), banned)
        }
        assertFalse(liveText.contains("\"portico/"), "the command names are core's constants")
        val wire = src("live/LinkLiveWire.kt")
        for (needle in listOf("link.openStream(", "LiveCommands.LIVE,", "link.dropStream(", "link.sendQuiet(type, body())")) assertTrue(wire.contains(needle), needle)
    }

    // ---- video only ----

    @Test
    fun `live video is video only, the microphone is never opened and no permission is added`() {
        for (banned in listOf("RECORD_AUDIO", "AudioRecord", "createAudioSource", "createAudioTrack", "AudioTrack", "OfferToReceiveAudio", "MEDIA_TYPE_AUDIO", "MODE_IN_COMMUNICATION", "AudioManager", "requestPermissions", "checkSelfPermission")) {
            assertFalse(liveText.contains(banned), "the live package must not mention $banned")
        }
        val peer = src("live/WebRtcLivePeer.kt")
        assertTrue(peer.contains("MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO"))
        assertTrue(peer.contains("RtpTransceiver.RtpTransceiverDirection.RECV_ONLY"))
        assertEquals(1, Regex("addTransceiver\\(").findAll(peer).count(), "one transceiver, the video one")
        assertTrue(peer.contains("\"OfferToReceiveVideo\", \"true\""))
        assertFalse(peer.contains("addTrack("), "nothing is sent")
        assertTrue(src("live/LiveVideoEngine.kt").contains("module.setMicrophoneMute(true)"))
        // no permission was added for it (MergedManifestTest pins the merged set; this pins the app's own, as of 0.6.15)
        val declared = Regex("""uses-permission[^/]*android:name="android\.permission\.([A-Z_]+)"""").findAll(File("src/main/AndroidManifest.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "POST_NOTIFICATIONS", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "RECEIVE_BOOT_COMPLETED", "USE_FULL_SCREEN_INTENT", "CAMERA", "RECORD_AUDIO",
                "MODIFY_AUDIO_SETTINGS", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "CHANGE_NETWORK_STATE", "WAKE_LOCK", "SYSTEM_ALERT_WINDOW", "VIBRATE",
            ),
            declared,
        )
    }

    @Test
    fun `the peer is disposed off the live thread and the factory after every peer`() {
        val peer = src("live/WebRtcLivePeer.kt")
        assertTrue(between(peer, "private fun closeNow()", "/** [SdpObserver]").contains("LiveVideoEngine.teardown.execute"))
        val engine = src("live/LiveVideoEngine.kt")
        assertTrue(between(engine, "fun release()", "companion object").contains("teardown.execute"), "the same single thread, so in order")
        assertTrue(engine.contains("Executors.newSingleThreadExecutor"))
    }

    // ---- Settings ----

    @Test
    fun `Settings has a video row per network class, the old switch is only the starting value`() {
        assertEquals(listOf("Big video", "Small video", "Stills", "Off"), listOf("video_live_big", "video_live_small", "video_stills", "video_off").map { str(it) })
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        for (c in listOf("wifi", "mobile", "roaming")) assertTrue(layout.contains("android:id=\"@+id/setup_video_$c\""), c)
        assertFalse(layout.contains("setup_live_video_switch"))
        val s = src("store/Settings.kt")
        assertEquals(1, Regex("""getBoolean\("live_video", true\)""").findAll(s).count())
        assertFalse(s.contains("getBoolean(\"live_video\", false)"))
        assertTrue(s.contains("NetworkPolicy.defaults(stayConnected, liveVideo)"), "an unset row is the 0.6.57 behaviour")
        val setup = src("setup/SetupActivity.kt")
        assertTrue(setup.contains("s.setVideo(cls, it)") && setup.contains("onNetworkChoicesChanged()"))
        assertFalse(setup.contains("s.liveVideo ="))
    }

    // ---- the Journal ----

    @Test
    fun `the sessions log to the Journal`() {
        assertTrue(src("live/HomeLiveHost.kt").contains("LiveHome(app, signaling, log, guards)") && activity.contains("graph.homeLive.acquire(this)"))
        assertTrue(src("live/LiveHome.kt").contains("LiveSession(key, peers, wire, clock, log, onChange = ::publish)"))
    }
}
