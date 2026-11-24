package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.58, what a host with no device can check about the reworked Settings: Ringing, the Firebase field gone, the network cap wired. Working directory = app/. */
class SettingsReworkTest {
    private val main = "src/main/java/com/meda/portico/"
    private val setup = File(main + "setup/SetupActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_setup.xml").readText()
    private val channels = File(main + "ring/Channels.kt").readText()
    private val graph = File(main + "AppGraph.kt").readText()
    private val strings: Map<String, String> by lazy {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val n = doc.getElementsByTagName("string")
        (0 until n.length).associate { n.item(it).attributes.getNamedItem("name").nodeValue to n.item(it).textContent }
    }

    @Test
    fun `Ring control is ONE button to Android's page, with no modes, ringtone picker, state text or away-audio switch`() {
        assertEquals("Ring control (Android)", strings["setup_ring_control"])
        assertTrue("setup_ring_control" in layout)
        for (gone in listOf("setup_ring_mode", "setup_ring_sound", "setup_ring_dnd", "setup_ringing_title", "setup_away_audio_switch", "setup_ring_switch")) assertFalse(gone in layout, gone)
        for (gone in listOf("ring_mode_ring", "setup_ring_sound", "setup_ring_dnd", "setup_ringing_heading", "setup_away_audio_switch", "setup_away_audio_hint")) assertFalse(gone in strings.keys, gone)
        assertTrue("Channels.settingsIntent(this, Channel.RING)" in setup)
        assertFalse("RingtoneManager" in setup || "RingMode" in setup || "RingSoundStore" in setup || "awayCallAudio" in setup)
    }

    @Test
    fun `there is ONE fixed ring channel with the original id, and the extra ids of the earlier versions are deleted`() {
        assertTrue("fun idOf(context: Context, channel: Channel): String = channel.id" in channels)
        assertTrue("RingChannelIds.staleIds(" in channels && "deleteNotificationChannel(old)" in channels)
        assertTrue("IMPORTANCE_HIGH" in channels && "ring.setBypassDnd(Channel.RING.bypassDnd)" in channels)
        assertTrue("TYPE_RINGTONE" in channels, "the one channel keeps the default ringtone")
        assertFalse("RingSoundStore" in channels || "RingMode" in channels)
    }

    @Test
    fun `away call audio is always on`() {
        assertFalse("awayCallAudio" in graph)
        assertTrue("away = TailnetAwayMedia(" in graph)
    }

    @Test
    fun `no RadioGroup is left in Settings, the choices are ChoiceStrips`() {
        assertFalse("RadioGroup" in layout || "RadioGroup" in setup || "RadioButton" in setup)
        assertEquals(11, Regex("<com.meda.portico.setup.ChoiceStrip").findAll(layout).count()) // 11: DEV-811 added "Reconnect while ringing", then "Answer without unlocking", then "Unlock when answering"
        val strip = File(main + "setup/ChoiceStrip.kt").readText()
        assertFalse("ButtonFlow" in strip, "one row, no wrapping planner")
        assertTrue("b.isSelected = v == current" in strip && "contentDescription" in strip)
    }

    @Test
    fun `the phone has no Firebase field or google-services import any more`() {
        assertFalse("setup_google_services" in layout || "google_services" in strings.keys.joinToString())
        assertFalse("setup_google_services" in setup)
        assertTrue(strings.getValue("st_push_no_firebase").contains("Portico integration"))
    }

    @Test
    fun `no long texts in Settings`() {
        for (k in listOf("setup_house_receive_hint", "setup_house_dnd_hint", "pause_hint", "connlog_hint", "setup_scan_or_paste")) {
            assertTrue(strings.getValue(k).length <= 180, "$k is too long")
        }
        for (k in listOf("setup_stay_hint", "setup_live_video_hint", "setup_ring_switch_hint", "setup_ring_sound_hint")) assertFalse(k in strings, k)
    }

    @Test
    fun `the network cap is read by the stills, the live plan and the connection policy`() {
        assertTrue("videoVerdict().picturesOff" in graph, "no stills when the row is Off")
        assertTrue("videoVerdict().live" in graph && "videoVerdict().roamingHold" in graph)
        assertTrue("connection(netClass()) == ConnectionMode.PUSH" in graph && "networkChoices()?.anyStay" in graph)
        assertTrue("roaming.invalidate()" in graph.substringAfter("private val stayNet"), "a network change re-reads the class")
        val home = File(main + "home/HomeActivity.kt").readText()
        assertTrue("videoVerdict().picturesOff" in home && "videoVerdict().smallOnly" in home)
    }

    @Test
    fun `ChoiceStrip is a square joined single-row segmented control`() {
        val strip = File(main + "setup/ChoiceStrip.kt").readText()
        val res = "src/main/"
        val seg = File(res + "res/drawable/choice_bg.xml").readText()
        val outline = File(res + "res/drawable/choice_strip_bg.xml").readText()
        assertFalse("<corners" in seg || "<corners" in outline || "cornerRadius" in strip || "radius" in strip.lowercase(), "no rounding at all")
        assertFalse("<stroke" in seg, "a segment has no border of its own, the strip draws the outline")
        assertEquals(2, Regex("<stroke android:width=\"1dp\"").findAll(outline).count(), "one 1dp outline (enabled + disabled states)")
        assertTrue("LinearLayout" in strip && "orientation = HORIZONTAL" in strip, "a single row")
        assertTrue("SHOW_DIVIDER_MIDDLE" in strip && "setSize(px(1f), 0)" in strip, "1dp dividers between segments")
        assertTrue("LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)" in strip, "equal weights fill the width")
        assertFalse("setMargins" in strip || "margin" in strip.lowercase(), "no gaps between segments")
        assertTrue("MAX_LINES = 2" in strip && "b.maxLines = MAX_LINES" in strip && "TEXT_SP = 13f" in strip)
        assertTrue("TextUtils.TruncateAt.END" in strip && "TEXT_ALIGNMENT_CENTER" in strip)
        assertTrue("RadioButton::class.java.name" in strip && "info.isChecked" in strip && "b.contentDescription = label" in strip)
    }
}
