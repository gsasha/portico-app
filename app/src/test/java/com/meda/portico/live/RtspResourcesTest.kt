package com.meda.portico.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-713 S1b (working directory = app/): the away-from-home route's views, decoder and wiring, checked without a phone. What a phone
 * does (the codec, the surface, the first IDR over mobile data) is for the proof sitting; these pin the rules that keep it safe.
 */
class RtspResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val surface by lazy { src("home/RtspTileSurface.kt") }
    private val decoder by lazy { src("live/MediaCodecRtspDecoder.kt") }
    private val home by lazy { src("live/LiveHome.kt") }
    private val activity by lazy { src("home/HomeActivity.kt") }
    private val goBackend by lazy { File("src/gomobile/java/com/meda/portico/live/RtspBackends.kt").readText() }
    private val noBackend by lazy { File("src/nogomobile/java/com/meda/portico/live/RtspBackends.kt").readText() }
    private val liveFiles by lazy { File("src/main/java/com/meda/portico/live").listFiles()!!.filter { it.extension == "kt" } }
    private val liveText by lazy { (liveFiles.map { it.readText() } + goBackend + noBackend + surface).joinToString("\n") }

    private fun str(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no string $name")

    private fun between(text: String, from: String, to: String) = text.substringAfter(from).substringBefore(to)

    @Test
    fun `the new notice says the away route is not set up for this camera, and the tile words it`() {
        assertEquals("Live video away from home is not set up for this camera", str("live_notice_notsetup"))
        assertEquals("Live video away from home is not available yet", str("live_notice_away"), "the old words stay, for a build without the client")
        assertTrue(between(tiles, "private fun noticeText", "/** Draws the live words").contains("LiveWhy.NOT_SET_UP -> R.string.live_notice_notsetup"))
    }

    @Test
    fun `the video view is a plain SurfaceView that is never made invisible, because an invisible one has no surface`() {
        assertTrue(surface.contains("val view = SurfaceView(context)"))
        assertTrue(surface.contains("frame.addView(view, 0, "), "above the still (index 0), below the words")
        val code = surface.lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }.joinToString("\n")
        for (banned in listOf("view.visibility", "setZOrderOnTop", "setZOrderMediaOverlay", "setBackgroundColor", "setBackground(", "GONE", "INVISIBLE")) {
            assertFalse(code.contains(banned), "RtspTileSurface must not use $banned")
        }
        assertFalse(surface.contains("FrameLayout.LayoutParams(1, 1,"), "0.6.49: no 1x1 view that grows at the first frame")
        assertTrue(surface.contains("minOf(fw.toFloat() / videoW, fh.toFloat() / videoH)"), "shown is fitted, keeping the aspect")
        assertFalse(surface.contains("image.visibility"), "the still is faded by StillTiles (alpha), never hidden")
        assertFalse(liveText.contains("setBackgroundColor"), "nothing paints a background on video")
    }

    @Test
    fun `the tile picks the view by the stream's route and releases both kinds with the grid`() {
        assertTrue(tiles.contains("if (key.rtsp) return ensureSurface(v, key, surfaces)"))
        assertTrue(tiles.contains("v.rtsp?.show(show)"), "hidden while zoomed, like the renderer")
        val release = between(tiles, "private fun releaseRenderer", "/** The renderer sits at index")
        assertTrue(release.contains("live?.unbindSurface(key, t.view)") && release.contains("t.release()"))
        assertTrue(tiles.contains("v.frame.addView(r, 0, FrameLayout.LayoutParams("), "the WebRTC renderer is added at index 0 (below the still) at full size, VISIBLE like the RTSP one")
        assertTrue(tiles.contains("if (v.rendered) return"), "frames report for every frame; only the first matters")
    }

    @Test
    fun `the surface is drawn on under a lock and only while it is the one the codec was configured with`() {
        assertTrue(decoder.contains("synchronized(slot.lock) { r.codec.releaseOutputBuffer(i, slot.surface() === r.surface) }"))
        assertTrue(src("live/RtspSlot.kt").contains("override fun surfaceDestroyed(holder: SurfaceHolder) = setSurface(null)"))
        assertTrue(decoder.contains("slot.surface() !== r.surface"), "a surface that changed stops the codec until the next IDR")
        assertTrue(between(decoder, "} finally {", "private fun same").contains("stopCodec(running)"), "the thread always releases the codec")
    }

    @Test
    fun `the decoder never feeds before an IDR, and a lagging codec resyncs instead of tearing the picture`() {
        assertTrue(decoder.contains("if (!item.key || format == null) continue"))
        assertTrue(decoder.contains("r.codec.flush()") && decoder.contains("needKey = true"))
        assertTrue(decoder.contains("NalQueue()"), "the bounded queue of :core")
        assertTrue(decoder.contains("check(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null)"), "no H.264 decoder: the factory throws, the tile keeps its still")
        assertTrue(decoder.contains("setByteBuffer(\"csd-0\"") && decoder.contains("setByteBuffer(\"csd-1\""), "configured from the SPS and PPS")
    }

    @Test
    fun `the plan hook - the session by route, the address read where the plan is made, rtspReady from the build`() {
        val create = between(home, "private fun create(key: LiveKey)", "// ---- state of the live thread")
        assertTrue(create.contains("if (key.rtsp)") && create.contains("RtspLiveSession(key, plan?.urls?.get(key).orEmpty(), backend, rtspDecoders, clock, log, onChange = ::publish)"))
        assertTrue(create.contains("LiveSession(key, peers, wire, clock, log, onChange = ::publish)"), "the WebRTC session is as it was")
        assertTrue(home.contains("val rtspReady: Boolean get() = rtspBackend != null"))
        assertTrue(activity.contains("rtspReady = liveHome.rtspReady"))
        assertTrue(activity.contains("rtsp = t.liveRtsp"))
    }

    @Test
    fun `the Go client is reached through the node's Dial and never the forwarder, and nothing logs an address`() {
        assertTrue(goBackend.contains("Tailnet.startRtspVideo(url, sink)"))
        assertFalse(goBackend.contains("startForwarder") || goBackend.contains("StartForwarder"))
        assertTrue(noBackend.contains("RtspSourceFactory? = null"), "a build without gocore.aar has no away route")
        val go = File("../gocore/tailnet/rtspvideo.go").readText()
        assertTrue(go.contains("rtsp.Start(url, n.Dial, sink, rtsp.DefaultConfig())"))
        assertFalse(go.contains("StartForwarder(") || go.contains("newForwarder("), "the forwarder bridges one fixed target; the video takes the node's own Dial")
        val goFiles = File("../gocore/internal/rtsp").listFiles()!!.filter { it.extension == "go" && !it.name.endsWith("_test.go") }
        for (f in goFiles) {
            val t = f.readText()
            assertFalse(t.contains("\"log\"") || t.contains("fmt.Print") || t.contains("os.Stderr") || t.contains("os.Stdout"), "${f.name} writes a log")
        }
        assertTrue(goFiles.map { it.name }.containsAll(listOf("client.go", "session.go", "h264.go", "queue.go")))
        for (line in liveText.lines().filter { it.contains("Log.") || it.contains("println(") }) {
            assertFalse(Regex("""\burl\b|\bUrl\b|\burls\b""").containsMatchIn(line), "a log line mentions the address: $line")
        }
    }

    @Test
    fun `no new dependency and no new permission came with it`() {
        val gradle = File("build.gradle.kts").readText()
        for (banned in listOf("media3", "exoplayer", "androidx.media", "rtsp")) assertFalse(gradle.lowercase().contains(banned), banned)
        for (banned in listOf("RECORD_AUDIO", "AudioRecord", "AudioManager", "MODE_IN_COMMUNICATION", "AudioTrack", "requestPermissions")) {
            assertFalse(liveText.contains(banned), "the away route must not mention $banned")
        }
    }

    @Test
    fun `the away route does not touch the call paths or the connection internals`() {
        for (banned in listOf("FallbackConnector", "ConnectTimeline", "connlog", "TailnetController", "AnswerSession", "TalkSession", "HouseMedia", "TailnetAwayMedia", "PorticoLink")) {
            assertFalse(decoder.contains(banned) || surface.contains(banned) || goBackend.contains(banned) || noBackend.contains(banned), banned)
        }
    }
}
