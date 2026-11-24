package com.meda.portico.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a host with no emulator and no ears can still check about HOLD AND SWITCH (M7 H2): the libwebrtc methods the hold calls
 * really exist in the pinned build, the gate is a compile-time false and nothing in the shipped code turns it on, the layouts and
 * strings the screens need are there and start hidden, the strip is one TalkBack node with large targets, and the manifest gained
 * nothing. Working directory = app/.
 */
class HoldResourcesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

    private fun text(path: String) = File(path).readText()
    private fun src(p: String) = text("src/main/java/com/meda/portico/$p")
    private fun core(p: String) = text("../core/src/main/kotlin/com/meda/portico/core/$p")

    private fun views(layout: String): Map<String, Element> {
        val out = LinkedHashMap<String, Element>()
        fun walk(e: Element) {
            e.getAttributeNS(androidNs, "id").takeIf { it.isNotEmpty() }?.let { out[it.removePrefix("@+id/")] = e }
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(::walk)
        }
        walk(xml("src/main/res/layout/$layout.xml"))
        return out
    }

    private fun Element.a(name: String): String = getAttributeNS(androidNs, name)
    private fun dp(v: String) = v.removeSuffix("dp").toInt()

    // ---- the libwebrtc calls -----------------------------------------------------------------------

    @Test
    fun `the libwebrtc methods the hold uses exist in the pinned build`() {
        val adm = Class.forName("org.webrtc.audio.JavaAudioDeviceModule")
        assertNotNull(adm.getMethod("setMicrophoneMute", Boolean::class.javaPrimitiveType))
        assertNotNull(adm.getMethod("setSpeakerMute", Boolean::class.javaPrimitiveType))
        val track = Class.forName("org.webrtc.AudioTrack")
        assertNotNull(track.getMethod("setEnabled", Boolean::class.javaPrimitiveType))
    }

    @Test
    fun `the real media implements hold with exactly those calls and never closes the connection to do it`() {
        val s = src("call/WebRtcMedia.kt")
        for (call in listOf("setMicrophoneMute(mute)", "setSpeakerMute(mute)", "track?.setEnabled(enabled)", "override fun setHeld(held: Boolean)", "gate.setHeld(held)")) {
            assertTrue(call in s, "WebRtcMedia must contain $call")
        }
        val body = s.substringAfter("override fun setHeld(held: Boolean)").substringBefore("override fun close()")
        assertFalse("pc?.close" in body || ".dispose" in body || "close()" in body.replace("closed.get()", ""), "hold never closes anything")
    }

    @Test
    fun `the frames path implements hold too`() {
        assertTrue("override fun setHeld(held: Boolean)" in core("call/FrameMedia.kt"))
    }

    // ---- the gate -----------------------------------------------------------------------------------

    @Test
    fun `the gate is a compile-time false and nothing shipped turns it on`() {
        assertTrue(Regex("""const val HOLD_ENABLED = false\b""").containsMatchIn(core("call/AnswerPorts.kt")))
        assertEquals(1, Regex("""HOLD_ENABLED\s*=""").findAll(core("call/AnswerPorts.kt")).count())
        val roots = listOf(File("src/main"), File("../core/src/main"))
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val t = f.readText()
                assertFalse(Regex("""holdEnabled\s*=\s*true""").containsMatchIn(t), "${f.path} turns the gate on")
                assertFalse(Regex("""HOLD_ENABLED\s*=\s*true""").containsMatchIn(t), "${f.path} turns the gate on")
            }
        }
        // the app builds its coordinator with the default flag
        assertFalse(Regex("""holdEnabled""").containsMatchIn(src("AppGraph.kt")), "AppGraph passes no flag: the constant decides")
    }

    // ---- the in-call layout ---------------------------------------------------------------------------

    private val holdViewsCall = listOf("call_hold_notice", "call_held_strip", "call_held_title", "call_held_detail", "call_held_reason", "call_held_switch", "call_held_end")

    @Test
    fun `every held-call view exists, every one the code looks up too, and all start hidden`() {
        val v = views("activity_call")
        for (id in holdViewsCall) assertTrue(id in v, "activity_call lacks @+id/$id")
        for (id in listOf("call_hold_notice", "call_held_strip", "call_held_reason", "call_held_end")) {
            assertEquals("gone", v.getValue(id).a("visibility"), "$id must start gone: with nothing held the screen is what it was")
        }
        val r = views("activity_ring")
        for (id in listOf("ring_oncall", "ring_hold_answer", "ring_hold_reason")) {
            assertTrue(id in r, "activity_ring lacks @+id/$id")
            assertEquals("gone", r.getValue(id).a("visibility"), "$id must start gone")
        }
    }

    @Test
    fun `hang up and mute stay above the strip, and the strip above the stills`() {
        val order = views("activity_call").keys.toList()
        assertTrue(order.indexOf("call_hangup") < order.indexOf("call_mute"))
        assertTrue(order.indexOf("call_mute") < order.indexOf("call_held_strip"))
        assertTrue(order.indexOf("call_held_strip") < order.indexOf("call_held_end"))
        assertTrue(order.indexOf("call_held_end") < order.indexOf("call_stills"))
    }

    @Test
    fun `the strip is one TalkBack node with a spoken description, and its targets are large`() {
        val v = views("activity_call")
        val strip = v.getValue("call_held_strip")
        assertEquals("true", strip.a("focusable"))
        assertEquals("true", strip.a("clickable"))
        assertEquals("noHideDescendants", strip.a("importantForAccessibility"), "one node: the children are not read apart")
        assertTrue(strip.a("contentDescription").startsWith("@string/"), "a description is set (and replaced in code with the live text)")
        assertTrue(dp(strip.a("minHeight")) >= 72)
        assertTrue(dp(v.getValue("call_held_switch").a("minHeight")) >= 56, "Switch is at least 56 dp")
        assertTrue(dp(v.getValue("call_held_end").a("minHeight")) >= 48, "End held call is at least 48 dp")
        assertTrue(dp(v.getValue("call_hangup").a("minHeight")) >= 56, "Hang up keeps its size")
        assertEquals("match_parent", v.getValue("call_held_switch").a("layout_width"), "full width, so 200 percent text does not squeeze it")
        assertEquals("wrap_content", v.getValue("call_held_switch").a("layout_height"), "and it grows with the text")
    }

    @Test
    fun `quiet lines are live regions so TalkBack reads a change once`() {
        val v = views("activity_call")
        assertEquals("polite", v.getValue("call_hold_notice").a("accessibilityLiveRegion"))
        val r = views("activity_ring")
        assertEquals("polite", r.getValue("ring_hold_reason").a("accessibilityLiveRegion"))
        assertEquals("polite", r.getValue("ring_oncall").a("accessibilityLiveRegion"))
    }

    @Test
    fun `the ring screen keeps Answer first and the new button right after it, ahead of Decline`() {
        val order = views("activity_ring").keys.toList()
        assertTrue(order.indexOf("ring_oncall") < order.indexOf("ring_answer"))
        assertTrue(order.indexOf("ring_answer") < order.indexOf("ring_hold_answer"))
        assertTrue(order.indexOf("ring_hold_answer") < order.indexOf("ring_hold_reason"))
        assertTrue(order.indexOf("ring_hold_reason") < order.indexOf("ring_stop"))
        assertTrue(dp(views("activity_ring").getValue("ring_hold_answer").a("minHeight")) >= 56)
        assertEquals("ScrollView", xml("src/main/res/layout/activity_ring.xml").tagName)
        assertEquals("ScrollView", xml("src/main/res/layout/activity_call.xml").tagName)
    }

    @Test
    fun `the held mark is an icon beside words, never colour alone`() {
        val icon = text("src/main/res/drawable/ic_hold_pause.xml")
        assertTrue("pathData" in icon)
        assertFalse("setBackgroundColor" in src("call/InCallActivity.kt"), "no background colour on anything that could hold a video surface")
    }

    // ---- strings ---------------------------------------------------------------------------------------

    private val strings: Map<String, String> by lazy {
        val nodes = xml("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    @Test
    fun `the fixed labels are plain English and take no arguments`() {
        assertEquals("Switch", strings["call_switch"])
        assertEquals("End held call", strings["call_end_held"])
        assertEquals("Decline", strings["ring_decline"])
        assertEquals("Both of your calls are in use.", strings["ring_text_both_busy"])
        for (k in listOf("call_switch", "call_end_held", "ring_decline", "ring_text_both_busy", "call_held_strip_default", "ring_hold_answer_default")) {
            assertNotNull(strings[k], k)
            assertFalse(Regex("%\\d").containsMatchIn(strings.getValue(k)), "$k takes no arguments")
        }
    }

    @Test
    fun `the old call strings are untouched`() {
        assertEquals("On a call with %1\$s", strings["call_notif_title"])
        assertEquals("Tap to open the call", strings["call_notif_text"])
        assertEquals("Hang up", strings["call_hangup"])
        assertEquals("Stop ringing on this phone", strings["ring_stop"])
        assertEquals("Answer", strings["ring_answer"])
    }

    // ---- code shapes ----------------------------------------------------------------------------------

    @Test
    fun `a notification action never holds a call by itself`() {
        // APP-03: the ring screen (and its "Hold and answer" button) is gone; a ring's Answer is a plain press on Home, and nothing in the
        // app calls holdAndAnswer, so the gated-off hold can never start from a notification either.
        val s = src("home/HomeActivity.kt")
        assertFalse("holdAndAnswer(" in s, "the notification path must not press hold-and-answer")
        assertEquals(1, Regex("answer\\.press\\(").findAll(s).count(), "one press, on the Answer action's first delivery")
    }

    @Test
    fun `hold-and-answer is pressed from a button only`() {
        // APP-03: no button calls holdAndAnswer any more (the ring screen that had it is gone; hold is gated off).
        for (f in File("src/main/java/com/meda/portico").walkTopDown().filter { it.name.endsWith(".kt") }) assertFalse(Regex("answer\\.holdAndAnswer\\(").containsMatchIn(f.readText()), f.name)
        val c = src("call/InCallActivity.kt")
        assertEquals(1, Regex("answer\\.resume\\(").findAll(c).count(), "a switch starts from switchToHeld only")
    }

    @Test
    fun `the screen stays open for a held call`() {
        val c = src("call/InCallActivity.kt")
        assertTrue("CallUi.shouldFinish(s, graph.answer.sessionsState().held != null)" in c)
    }

    @Test
    fun `the audio focus listener is real and reaches the coordinator`() {
        val r = src("call/AndroidAudioRoute.kt")
        assertFalse(Regex("""setOnAudioFocusChangeListener \{\s*\}""").containsMatchIn(r), "the empty listener is gone")
        assertTrue("onFocusChange(change)" in r)
        assertTrue("answer.onAudioFocusChange(change)" in src("AppGraph.kt"))
        // only the answer path's route has one; the outgoing and talk routes are as they were
        assertEquals(3, Regex("AndroidAudioRoute\\(app\\)").findAll(src("AppGraph.kt")).count())
    }

    @Test
    fun `one notification, the same channel and id, and no new PendingIntent`() {
        val s = src("call/CallService.kt")
        assertEquals(1, Regex("PendingIntent\\.getActivity").findAll(s).count())
        assertEquals(1, Regex("PendingIntent\\.getBroadcast").findAll(s).count())
        assertEquals(0, Regex("FLAG_MUTABLE").findAll(s).count())
        assertTrue("RequestCodes.CALL_NOTIFICATION_ID" in s)
        assertEquals("portico_call_v1", Regex("""CHANNEL_ID = "([a-z0-9_]+)"""").find(s)!!.groupValues[1], "the channel id is unchanged")
        assertTrue("HoldNoticeModel.build" in s)
        assertTrue("startForeground(RequestCodes.CALL_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)" in s)
    }

    @Test
    fun `the service is told to stop only through the coordinator, which counts sessions`() {
        val c = core("call/AnswerCoordinator.kt")
        assertTrue("if (!sessions.active().any { it !== slot })" in c)
    }

    @Test
    fun `the pause releases every call, held ones too`() {
        assertTrue("answer.hangUpAll()" in src("AppGraph.kt"))
    }

    // ---- the manifest --------------------------------------------------------------------------------

    @Test
    fun `the manifest gained no permission and no component`() {
        val m = xml("src/main/AndroidManifest.xml")
        val perms = mutableListOf<String>()
        val comps = mutableListOf<String>()
        val kids = m.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? Element ?: continue
            if (e.tagName == "uses-permission") perms += e.a("name").removePrefix("android.permission.")
            if (e.tagName == "application") {
                val appKids = e.childNodes
                for (j in 0 until appKids.length) {
                    val c = appKids.item(j) as? Element ?: continue
                    if (c.tagName in setOf("activity", "service", "receiver", "provider")) comps += c.a("name")
                }
            }
        }
        assertEquals(
            listOf(
                "INTERNET", "POST_NOTIFICATIONS", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "RECEIVE_BOOT_COMPLETED", "USE_FULL_SCREEN_INTENT",
                "CAMERA", "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE",
                "FOREGROUND_SERVICE_CONNECTED_DEVICE", "CHANGE_NETWORK_STATE", "WAKE_LOCK", "SYSTEM_ALERT_WINDOW", "VIBRATE",
            ),
            perms,
            "permissions as of main before H2",
        )
        assertEquals(
            listOf(
                ".MainActivity", ".home.HomeActivity", ".setup.SetupActivity", ".setup.ScanActivity", ".ring.RingReceiver", ".ring.RingCallActivity", ".ring.HouseRingActivity",
                ".call.InCallActivity", ".call.OutgoingCallActivity", ".call.CallService", ".stay.ConnectionService", ".call.CallReceiver",
                ".pause.PauseReceiver", ".fcm.PorticoMessagingService", ".boot.BootReceiver", "com.google.firebase.provider.FirebaseInitProvider",
            ),
            comps,
            "components as of main before H2",
        )
        val service = m.getElementsByTagName("service").let { n -> (0 until n.length).map { n.item(it) as Element }.first { it.a("name") == ".call.CallService" } }
        assertEquals("microphone", service.a("foregroundServiceType"), "the call service keeps its type")
    }
}
