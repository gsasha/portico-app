package com.meda.portico.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * APP-02: what a host with no emulator can still check about the call button, bar, chooser and notification. Working
 * directory = app/. What only a device can show (colour, the animation, layout at phone width, the bar over gesture
 * navigation) is listed in the PR.
 */
class CallBarResourcesTest {
    private val strings: Map<String, String> by lazy {
        Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>").findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()

    @Test
    fun `every string the new call surfaces use exists`() {
        for (f in listOf("call/CallBarView", "call/RouteChooserView", "call/CallService")) {
            for (m in Regex("R\\.string\\.([a-z0-9_]+)").findAll(src("$f.kt"))) assertTrue(m.groupValues[1] in strings, "$f uses R.string.${m.groupValues[1]}")
        }
    }

    @Test
    fun `the chooser words are Speaker Bluetooth Phone and the old key is gone`() {
        assertEquals("Speaker", strings["route_speaker"])
        assertEquals("Bluetooth", strings["route_bluetooth"])
        assertEquals("Phone", strings["route_phone"])
        assertTrue(strings.keys.none { it.startsWith("audio_route_") }, "the Auto/Speaker/Earpiece key's strings are replaced")
        val layout = File("src/main/res/layout/view_audio_controls.xml").readText()
        assertTrue(layout.contains("com.meda.portico.call.RouteChooserView"))
        assertFalse(src("call/CallAudioControl.kt").contains("cycleRoute"))
    }

    @Test
    fun `the call bar says Calling name and has a red end button`() {
        assertEquals("Calling %1\$s", strings["callbar_calling"])
        val bar = src("call/CallBarView.kt")
        assertTrue(bar.contains("0xFFDC2626"))
        assertTrue(bar.contains("CallActions.hangUpAll"))
    }

    @Test
    fun `the call button is green with the intercom green and has no Talk word`() {
        val buttons = src("home/ActionButtons.kt")
        assertTrue(buttons.contains("CALL_GREEN = 0xFF16A34A.toInt()"), "the intercom app's #16a34a")
        // No string for a visible 'Talk' label exists for the button: the only Talk words are descriptions and reasons.
        assertFalse(buttons.contains("\"Talk\""))
    }

    @Test
    fun `the wave stops when hidden and with remove animations and holds no wake lock`() {
        val wave = src("call/SoundWaveDrawable.kt")
        assertTrue(wave.contains("override fun setVisible"))
        assertTrue(wave.contains("ANIMATOR_DURATION_SCALE"))
        assertFalse(wave.contains("WakeLock"))
        assertFalse(src("call/CallBarView.kt").contains("WakeLock"))
    }

    @Test
    fun `the ongoing call notification is ongoing silent call-category with a Stop action and a chronometer`() {
        val svc = src("call/CallService.kt")
        assertTrue(svc.contains(".setOngoing(true)"))
        assertTrue(svc.contains("Notification.CATEGORY_CALL"))
        assertTrue(svc.contains("setUsesChronometer(true)"))
        assertTrue(svc.contains("R.string.call_notif_stop"))
        assertEquals("Stop", strings["call_notif_stop"])
        assertTrue(svc.contains("setSound(null, null)"))
        assertTrue(svc.contains("IMPORTANCE_LOW"))
        assertTrue(svc.contains("FLAG_IMMUTABLE"))
        // The ring channel (DND bypass) is not touched by the call notification.
        assertFalse(svc.contains("portico_ring"))
    }

    @Test
    fun `the duration tick survives the bar being moved in the page (0_6_49, the clock stuck at 00_00)`() {
        val bar = src("call/CallBarView.kt")
        val detach = bar.substringAfter("override fun onDetachedFromWindow()").substringBefore("\n    }")
        assertTrue(detach.contains("stopTick()") && !detach.contains("pause()"), "detaching must not clear the resumed state")
        val attach = bar.substringAfter("override fun onAttachedToWindow()").substringBefore("\n    }")
        assertTrue(attach.contains("if (resumed)") && attach.contains("startTick()"), "re-attaching restarts the tick while the screen is resumed")
        assertTrue(bar.substringAfter("fun resume()").substringBefore("fun pause()").contains("resumed = true"))
        assertTrue(bar.substringAfter("fun pause()").substringBefore("private fun stopTick").contains("resumed = false"))
        val refresh = bar.substringAfter("fun refresh()").substringBefore("/** The red button")
        assertFalse(refresh.contains("pause()"), "refresh must not clear the resumed state")
    }
}
