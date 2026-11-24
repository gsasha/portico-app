package com.meda.portico.call

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** D-20261121-c98a: source-pattern pins of the panel's "Call phones" path (the Android side cannot run on the JVM). */
class PanelCallWiringTest {
    private fun read(p: String) = File(p).readText()
    private val graph by lazy { read("src/main/java/com/meda/portico/AppGraph.kt") }
    private val ringback by lazy { read("src/main/java/com/meda/portico/call/Ringback.kt") }
    private val outgoing by lazy { read("src/main/java/com/meda/portico/call/OutgoingCallActivity.kt") }

    @Test
    fun `no new entry point reaches the app from outside - the exported components are the ones that were`() {
        val manifest = read("src/main/AndroidManifest.xml")
        // The request comes over the authenticated socket only: no deep link, no intent filter was added for it.
        assertFalse("panel_call" in manifest.lowercase() || "call_everyone" in manifest.lowercase())
        val outgoingTag = manifest.substringAfter("OutgoingCallActivity").substringBefore("/>")
        assertFalse("android:exported=\"true\"" in outgoingTag, "the call screen stays unexported")
        assertFalse("<intent-filter" in outgoingTag)
    }

    @Test
    fun `the socket sink hands the request to the answer thread and the coordinator, nothing else`() {
        assertTrue("panelCallSink = PanelCallSink { r -> onAnswerThread { panelCall.onRequest(r) } }" in graph)
    }

    @Test
    fun `the call is pressed through the one press path - there is no second way to begin a call`() {
        assertTrue("press = { place.press(it) }" in graph)
        assertFalse("ContentCommands.run" in graph, "the app never builds a content/run by hand here")
    }

    @Test
    fun `the screen is brought forward by the ring window's own launcher, and a refusal never starts the call`() {
        val front = graph.substringAfter("private fun panelCallFront").substringBefore("private fun panelCallReply")
        assertTrue("directRing.decide() != com.meda.portico.core.ring.RingScreenPolicy.Decision.DIRECT_OVERLAY" in front)
        assertTrue("onFailed(\"screen off or no overlay permission\")" in front)
        assertTrue("directRing.launch(intent, windowUp = { outgoingScreenUp }" in front)
        assertFalse("place.press" in front, "fronting never places the call: the coordinator does, once the screen is up")
    }

    @Test
    fun `the screen reports it is up from onStart and down from onStop`() {
        assertTrue("outgoingScreenStarted()" in outgoing.substringAfter("override fun onStart()").substringBefore("override fun onResume"))
        assertTrue("outgoingScreenStopped()" in outgoing.substringAfter("override fun onStop()").substringBefore("override fun onNewIntent"))
    }

    @Test
    fun `the ringback is the platform's ringing tone on the voice-call stream, bounded, and stopped by the place state`() {
        assertTrue("ToneGenerator(AudioManager.STREAM_VOICE_CALL" in ringback && "TONE_SUP_RINGTONE" in ringback)
        assertTrue("RingbackPolicy.MAX_MS" in ringback && "removeCallbacks(cap)" in ringback)
        assertTrue("ringback.update(RingbackPolicy.wanted(panelModeOn, it))" in graph)
        assertTrue("panelVolume.restore(PanelStream.VOICE_CALL)" in graph.substringAfter("onStop = { why ->"))
    }

    @Test
    fun `the Journal lines carry the settings the path depends on (D-20261121-ee09)`() {
        val note = graph.substringAfter("private fun panelCallNote").substringBefore("val panelCall: PanelCallCoordinator")
        for (k in listOf("panelmode", "houseswitch", "overlay", "mic", "paused", "dnd", "infront", "idlemin", "callpct", "volcall", "maxcall")) {
            assertTrue("\"$k\" to" in note, k)
        }
        val tone = graph.substringAfter("private val ringback").substringBefore("onStop = { why ->")
        for (k in listOf("stream", "raised", "pct", "volcall", "maxcall", "volalarm", "dnd", "panelmode")) assertTrue("\"$k\" to" in tone, k)
        // every key obeys the journal's key rule (a-z0-9, at most 12), or the line would drop it
        for (k in Regex("\"([a-z][a-z0-9_]*)\" to").findAll(note + tone).map { it.groupValues[1] }) assertTrue(Regex("[a-z][a-z0-9]{0,11}").matches(k), k)
    }
}
