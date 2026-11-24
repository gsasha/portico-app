package com.meda.portico.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a host with no emulator and no ears can still check about the HOUSE RING and its wiring (M7 3b-B): the strings and the manifest
 * entry exist, the settings default OFF and are shown only while house calls are on, the app wires each rule where the design says
 * (a door outranks, a push goes to the house coordinator, Do Not Disturb is read only through its switch), and that nothing here can
 * start by itself. Working directory = app/.
 */
class HouseResourcesTest {
    private fun text(path: String) = File(path).readText()
    private fun src(p: String) = text("src/main/java/com/meda/portico/$p")
    private fun core(p: String) = text("../core/src/main/kotlin/com/meda/portico/core/$p")
    private fun strings() = text("src/main/res/values/strings.xml")

    @Test
    fun `every string the house screens read is defined`() {
        val defined = Regex("""<string name="([a-z0-9_]+)"""").findAll(strings()).map { it.groupValues[1] }.toSet()
        for (f in listOf("ring/HouseRingActivity.kt", "ring/HouseRingNotifier.kt")) {
            val used = Regex("""R\.string\.([a-z0-9_]+)""").findAll(src(f)).map { it.groupValues[1] }.toSet()
            for (s in used) assertTrue(s in defined, "$f reads R.string.$s, which strings.xml lacks")
        }
        assertTrue(">%1\$s is calling<" in strings())
        assertTrue(">Someone<" in strings(), "the name for a caller the document does not name")
        assertTrue(">Answering…<" in strings())
    }

    @Test
    fun `the ring screen is a house variant of the door ring layout - Answer and Decline, the door things hidden`() {
        val a = src("ring/HouseRingActivity.kt")
        assertTrue("R.layout.activity_ring" in a, "no new layout")
        for (id in listOf("ring_hold_answer", "ring_hold_reason", "ring_oncall", "ring_stills", "ring_extra", "ring_result")) assertTrue("R.id.$id" in a, "$id is hidden")
        assertTrue("R.string.ring_decline" in a)
        assertTrue("setShowWhenLocked(true)" in a && "requestDismissKeyguard" !in a)
        assertTrue("if (savedInstanceState == null) act(intent.action)" in a, "the notification's action is honoured on its first delivery only")
        assertTrue("ring.name ?: getString(R.string.house_someone)" in a, "never an id")
    }

    @Test
    fun `the manifest declares the house ring activity - not exported, excluded from recents, over the lock screen - and nothing else`() {
        val m = text("src/main/AndroidManifest.xml")
        val block = m.substringAfter("android:name=\".ring.HouseRingActivity\"").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in block && "excludeFromRecents" in block && "showWhenLocked" in block && "singleTop" in block)
        assertEquals(0, Regex("""<intent-filter""").findAll(block).count())
        assertEquals(1, Regex("""\.ring\.HouseRingActivity""").findAll(m).count())
    }

    @Test
    fun `the notification is a call-category full-screen ring with Answer and Decline as activity intents, timed out with the ring`() {
        val n = src("ring/HouseRingNotifier.kt")
        for (needle in listOf("CATEGORY_CALL", "setFullScreenIntent(full, true)", "setTimeoutAfter", "HouseRingActivity.ACTION_ANSWER", "HouseRingActivity.ACTION_DECLINE", "PendingIntent.getActivity", "MODE_BACKGROUND_ACTIVITY_START_ALLOWED")) {
            assertTrue(needle in n, needle)
        }
        assertFalse("getBroadcast" in n, "an activity PendingIntent: a broadcast that starts an activity is a notification trampoline")
        assertTrue("ring.name ?: context.getString(R.string.house_someone)" in n)
    }

    // ---- the settings ---------------------------------------------------------------------------------------------

    @Test
    fun `receive house calls defaults OFF and reading Android's Do Not Disturb defaults ON, each with a visible switch`() {
        val plan = core("enroll/SetupPlan.kt")
        assertTrue("const val HOUSE_RECEIVE_DEFAULT = false" in plan)
        assertTrue("const val HOUSE_SHARE_DND_DEFAULT = true" in plan)
        val settings = src("store/Settings.kt")
        assertTrue("SettingsKeys.HOUSE_RECEIVE, SettingsKeys.HOUSE_RECEIVE_DEFAULT" in settings)
        val layout = text("src/main/res/layout/activity_setup.xml")
        assertTrue("setup_house_receive_switch" in layout && "setup_house_dnd_switch" in layout)
        assertTrue(">Receive house calls in Portico<" in strings())
    }

    @Test
    fun `the house switches are in a group that is gone by default and shown only while the server says house calls are on`() {
        val layout = text("src/main/res/layout/activity_setup.xml")
        val group = layout.substringAfter("android:id=\"@+id/setup_house_group\"").substringBefore("<TextView")
        assertTrue("android:visibility=\"gone\"" in group, "no new row on Setup unless house calls are on")
        val setup = src("setup/SetupActivity.kt")
        assertTrue("if (PorticoApp.graph(this).houseEnabled()) View.VISIBLE else View.GONE" in setup)
    }

    // ---- the wiring -----------------------------------------------------------------------------------------------

    @Test
    fun `a door ring withdraws a house ring, a house push goes only to the coordinator, and the roster is the one gate`() {
        val g = src("AppGraph.kt")
        val door = g.substringAfter("onDoorRing = {").substringBefore("},")
        assertTrue("place.onDoorRinging()" in door && "houseRing.onDoorRinging()" in door)
        assertTrue("houseOn = { houseEnabled() && houseReceiveOn() }" in g)
        assertTrue("onHouse = { msg, nowMs -> houseRing.onPush(msg, nowMs) }" in g)
        assertTrue("HouseGate.enabled(content.profile.roster)" in g)
        assertTrue("enabled = ::houseEnabled" in g && "receiveOn = ::houseReceiveOn" in g && "dndOn = { houseDndShared() && osDndOn() }" in g)
        assertTrue("doorBusy = ::doorBusy" in g)
        assertTrue("private fun doorBusy(): Boolean = answerLive() ||" in g, "a door call live or held, or a door ringing")
        assertTrue("houseRing.onLinkLost()" in g)
        assertTrue("houseDnd.onDisconnected()" in g)
    }

    @Test
    fun `Do Not Disturb is reported only through the reporter, which the roster gates, and the Android filter is watched only once house calls are on`() {
        val g = src("AppGraph.kt")
        assertTrue("houseDnd.evaluate(on, signaling.state == SignalingClient.State.CONNECTED, HouseDnd.reported(houseReceiveOn(), houseDndShared(), osDndOn()))" in g)
        assertEquals(1, Regex("""HouseCommands\.PRESENCE""").findAll(g).count(), "one place sends it")
        val watch = g.substringAfter("private fun houseStateChanged()")
        assertTrue("if (!on && !houseWasEnabled) return" in watch.substringBefore("if (on && !dndWatching)"), "off and was off: nothing is touched")
        assertTrue(watch.indexOf("if (on && !dndWatching)") in 0..400, "the receiver is registered only when house calls are on")
        assertTrue("ACTION_INTERRUPTION_FILTER_CHANGED" in g)
    }

    @Test
    fun `an answered house ring is the placed call's path - one call at a time, the same service and screen - never started by an event`() {
        val g = src("AppGraph.kt")
        assertTrue("answerHouse = { callId, name -> place.answerHouse(callId, name) }" in g)
        assertTrue("media = houseMedia" in g)
        val ring = core("call/HouseRingCoordinator.kt")
        assertEquals(1, Regex("""answerHouse\(""").findAll(ring.substringAfter("fun answer(")).count(), "the press is the only caller")
        assertFalse("answerHouse(" in ring.substringBefore("fun answer("), "no event, push or timer answers")
    }

    @Test
    fun `away uses the frame channel and at home WebRTC, and the frame counters go to the journal through Redact`() {
        val g = src("AppGraph.kt")
        assertTrue("AwayPathPolicy.decide(linkPath, lastKnownPath, callAudio.pathChoice()).frames" in g)
        assertTrue("pushJournal.frameAudio(r," in g)
        val fmt = core("push/PushJournalFormat.kt")
        assertTrue(Regex("""fun framesLine[\s\S]*?Redact\.prefix\(it\)""").containsMatchIn(fmt), "the call id goes through Redact.prefix")
        assertTrue(Regex("""fun houseLine[\s\S]*?Redact\.prefix\(it\)""").containsMatchIn(fmt))
    }

    @Test
    fun `the house hang-up and answer commands are sent from core only - and the manifest, layouts and permissions are what 3b-A left`() {
        assertEquals(setOf("activity_call.xml", "activity_call_window.xml", "activity_home.xml", "activity_ring.xml", "activity_scan.xml", "activity_setup.xml", "view_audio_controls.xml"), File("src/main/res/layout").list()!!.toSet())
        val m = text("src/main/AndroidManifest.xml")
        assertEquals(16, Regex("""<uses-permission""").findAll(m).count(), "13 from 3b-A plus WAKE_LOCK (DEV-705) plus SYSTEM_ALERT_WINDOW (0.6.74, optional: granted only by the owner in Settings) plus VIBRATE (0.6.76, the call window vibrates)")
    }
}
