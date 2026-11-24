package com.meda.portico.home

import com.meda.portico.core.content.Presence
import com.meda.portico.core.content.RosterStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a host with no emulator and no eyes can still check about the ROSTER PAGE (M7 3b-A): every state has words, the rows are
 * large and one TalkBack sentence, Home draws them only when `:core` says the page is selected, the wiring of the events, and that
 * the manifest gained NOTHING - no permission, no component - so with house calls off the APK asks for what 0.5.12 asked for.
 * Working directory = app/.
 */
class RosterResourcesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun text(path: String) = File(path).readText()
    private fun src(p: String) = text("src/main/java/com/meda/portico/$p")
    private fun strings() = text("src/main/res/values/strings.xml")

    private fun stringNames(): Set<String> = Regex("""<string name="([a-z0-9_]+)"""").findAll(strings()).map { it.groupValues[1] }.toSet()

    @Test
    fun `every string the roster views read is defined`() {
        val views = src("home/RosterViews.kt")
        val used = Regex("""R\.string\.(roster_[a-z0-9_]+)""").findAll(views).map { it.groupValues[1] }.toSet()
        assertTrue(used.size >= 15, "the views read the roster strings ($used)")
        val defined = stringNames()
        for (s in used) assertTrue(s in defined, "R.string.$s is not in strings.xml")
    }

    @Test
    fun `every RosterStatus and every Presence has words and a dot in the views - none falls to a default`() {
        val views = src("home/RosterViews.kt")
        for (s in RosterStatus.entries) assertTrue("RosterStatus.${s.name} ->" in views, "the status ${s.name} has no string")
        for (p in Presence.entries) assertTrue("Presence.${p.name}" in views, "the presence ${p.name} has no dot")
        assertFalse(Regex("""else\s*->""").containsMatchIn(views.substringAfter("private fun statusText")), "statusText has no else: a new status must be worded")
    }

    @Test
    fun `the words say what the roster means in plain English`() {
        val s = strings()
        assertTrue(">Do not disturb<" in s, "the Do not disturb text of the roster")
        assertTrue(">Not reachable<" in s)
        assertTrue(">On a call<" in s)
        assertTrue(">No phone is set to receive house calls<" in s, "Everyone's empty-set words (15.14)")
        assertTrue(">Everyone (%1\$d phones)<" in s && ">Everyone (1 phone)<" in s)
    }

    @Test
    fun `a Call button is at least 48 dp, a row at least 64, and the dot is hidden from TalkBack while the name and words are one sentence`() {
        val v = src("home/RosterViews.kt")
        val heights = Regex("""minHeight = dp\((\d+)\)""").findAll(v).map { it.groupValues[1].toInt() }.toList()
        assertTrue(heights.isNotEmpty() && heights.all { it >= 48 }, "button heights $heights")
        assertTrue(Regex("""minimumHeight = dp\((\d+)\)""").findAll(v).all { it.groupValues[1].toInt() >= 64 })
        assertTrue("IMPORTANT_FOR_ACCESSIBILITY_NO" in v, "the dot is decoration")
        assertTrue("setScreenReaderFocusable(this, true)" in v && "R.string.roster_row_desc" in v, "name and words are one stop")
        assertTrue("R.string.roster_call_desc" in v, "the button says whom it calls")
    }

    @Test
    fun `colour is never the only signal - the dot's state is also words and a filled or hollow shape`() {
        val v = src("home/RosterViews.kt")
        assertTrue("\"●\"" in v && "\"○\"" in v)
        assertTrue(Regex("""RosterStatus\.[A-Z_]+ -> R\.string""").findAll(v).count() >= RosterStatus.entries.size)
    }

    @Test
    fun `Home draws roster rows only for the selected roster page, and a press is a press of the row's action id`() {
        val h = src("home/HomeActivity.kt")
        val rebuild = h.substringAfter("private fun rebuild(view: HomeView)").substringBefore("/** After the next layout pass")
        assertTrue("view.rosterSelected && roster != null" in rebuild, "rows only when the roster page is the selected one")
        assertTrue(rebuild.indexOf("rosterViews.rebuild") < rebuild.indexOf("blockViews.rebuild"), "the roster branch comes first, the blocks are the else")
        val press = h.substringAfter("private fun onRosterCall")
        assertTrue("graph.place.press(PlaceRequest(row.actionId, row.name))" in press, "the press places by action id and name, nothing else")
        assertTrue("RowState.DISABLED -> Unit" in press, "a disabled row sends nothing")
        assertFalse("content.markRunning" in press || "runAction" in press, "a roster row is not a plain button press")
    }

    @Test
    fun `the link's house events reach the placing session and the house ring coordinator`() {
        val g = src("AppGraph.kt")
        val sink = g.substringAfter("houseSink = HouseEventSink {").substringBefore("},")
        assertTrue("place.onHouseCall(it)" in sink && "houseRing.onHouseCall(it)" in sink)
        assertEquals(1, Regex("""HouseEventSink\s*\{""").findAll(g).count(), "one sink")
    }

    @Test
    fun `the manifest gained no permission - the same ones as 0_5_12 (3b-B adds one activity, only a house ring can start)`() {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        fun names(tag: String): List<String> {
            val out = ArrayList<String>()
            fun walk(e: Element) {
                if (e.tagName == tag) out += e.getAttributeNS(androidNs, "name")
                val kids = e.childNodes
                for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(::walk)
            }
            walk(root)
            return out
        }
        assertEquals(
            listOf(
                "android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.USE_FULL_SCREEN_INTENT",
                "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", "android.permission.CHANGE_NETWORK_STATE", "android.permission.WAKE_LOCK", "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.VIBRATE",
            ),
            names("uses-permission"),
            "no new permission (and so no new prompt) with house calls off",
        )
        assertEquals(
            listOf(".MainActivity", ".home.HomeActivity", ".setup.SetupActivity", ".setup.ScanActivity", ".ring.RingCallActivity", ".ring.HouseRingActivity", ".call.InCallActivity", ".call.OutgoingCallActivity"),
            names("activity"),
        )
        assertEquals(listOf(".call.CallService", ".stay.ConnectionService", ".fcm.PorticoMessagingService"), names("service"))
    }

    @Test
    fun `no new layout file - the roster page is built in code inside Home's existing scroller`() {
        assertEquals(
            setOf("activity_call.xml", "activity_call_window.xml", "activity_home.xml", "activity_ring.xml", "activity_scan.xml", "activity_setup.xml", "view_audio_controls.xml"),
            File("src/main/res/layout").list()!!.toSet(),
        )
    }
}
