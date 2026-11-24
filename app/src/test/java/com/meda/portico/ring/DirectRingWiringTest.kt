package com.meda.portico.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.74: source-pattern pins of the experimental direct ring screen (the Android side cannot run on the JVM). */
class DirectRingWiringTest {
    private fun read(p: String) = File(p).readText()
    private val launcher by lazy { read("src/main/java/com/meda/portico/ring/DirectRingLauncher.kt") }

    @Test
    fun `the manifest declares the overlay permission`() {
        assertTrue("""<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />""" in read("src/main/AndroidManifest.xml"))
    }

    @Test
    fun `Settings has an Over other apps button that opens the overlay permission page for this package`() {
        val layout = read("src/main/res/layout/activity_setup.xml")
        assertTrue("""android:id="@+id/setup_android_overlay"""" in layout)
        val settings = layout.substringAfter("""android:id="@+id/setup_android_overlay"""").substringBefore("/>")
        assertTrue("@string/setup_android_overlay" in settings)
        assertEquals("Over other apps", Regex("<string name=\"setup_android_overlay\">(.*?)</string>").find(read("src/main/res/values/strings.xml"))!!.groupValues[1])
        val pages = read("src/main/java/com/meda/portico/setup/AndroidPages.kt")
        assertTrue("ACTION_MANAGE_OVERLAY_PERMISSION" in pages && "package:\${context.packageName}" in pages.substringAfter("fun overOtherApps"))
        assertTrue("R.id.setup_android_overlay to { AndroidPages.overOtherApps(this) }" in read("src/main/java/com/meda/portico/setup/SetupActivity.kt"))
    }

    @Test
    fun `the launcher decides by the policy and never launches when locked or without the permission`() {
        assertTrue("RingScreenPolicy.decide(can, interactive, locked, sdk)" in launcher)
        val gate = launcher.substringAfter("fun decide()").substringBefore("fun onRing")
        assertTrue("if (d != RingScreenPolicy.Decision.DIRECT_OVERLAY) note(RingScreenPolicy.notificationLine(" in gate)
        assertTrue("if (decide() == RingScreenPolicy.Decision.DIRECT_OVERLAY) main.post { launchOnMain(ringScreen, null) }" in launcher, "the house path launches only when direct")
        assertTrue("isKeyguardLocked" in launcher && "isInteractive" in launcher && "Settings.canDrawOverlays" in launcher)
        // exactly one startActivity, and it is after the overlay window exists
        assertEquals(1, Regex("app\\.startActivity\\(").findAll(launcher).count())
        assertTrue(launcher.indexOf("wm.addView") < launcher.indexOf("app.startActivity("))
    }

    @Test
    fun `the overlay window is the 1px transparent untouchable kind and is always removed`() {
        assertTrue("TYPE_APPLICATION_OVERLAY" in launcher && "FLAG_NOT_FOCUSABLE" in launcher && "FLAG_NOT_TOUCHABLE" in launcher && "PixelFormat.TRANSLUCENT" in launcher)
        assertTrue("1, 1," in launcher)
        val start = launcher.substringAfter("private fun startRingScreen").substringBefore("private fun removeOverlay")
        assertTrue("finally {" in start && "removeOverlay" in start.substringAfter("finally {"))
        assertTrue("removeOverlay()" in launcher.substringAfter("private fun launchOnMain").substringAfter("} catch (e: Throwable) {").substringBefore("failed(e)"))
        assertTrue("HARD_REMOVE_MS" in launcher.substringAfter("wm.addView"), "a hard timer removes the window whatever happens")
        assertTrue("removeViewImmediate" in launcher)
    }

    @Test
    fun `a failure is journalled and never thrown`() {
        assertTrue("RingScreenPolicy.failedLine(e.javaClass.simpleName)" in launcher)
        assertTrue("RingScreenPolicy.DIRECT_LINE" in launcher)
        // no throw statement escapes the class except the one inside the guarded launch
        assertEquals(1, Regex("\\bthrow ").findAll(launcher).count())
    }

    @Test
    fun `the house notifier posts first and then calls the launcher, the door ring decides first (0_6_76), and the lock-screen path is unchanged`() {
        val ring = read("src/main/java/com/meda/portico/ring/RingNotifier.kt")
        assertTrue("setFullScreenIntent(full, true)" in ring)
        val postRing = ring.substringAfter("private fun postRing(").substringBefore("private fun backToLoud(")
        assertTrue(postRing.indexOf("launcher?.decide()") < postRing.indexOf("postRingNotification("), "decided before posting")
        assertTrue(postRing.indexOf("postRingNotification(") < postRing.indexOf("launcher.launch("), "launched after the notification exists")
        assertTrue("direct?.onRing(" !in ring, "the door ring no longer uses the post-then-launch house path")
        val house = read("src/main/java/com/meda/portico/ring/HouseRingNotifier.kt")
        assertTrue(house.indexOf("nm?.notify(RequestCodes.HOUSE_RING_NOTIFICATION_ID, n)") < house.indexOf("direct?.onRing("))
        assertTrue(".setFullScreenIntent(full, true)" in house)
        val graph = read("src/main/java/com/meda/portico/AppGraph.kt")
        assertTrue("directRing)" in graph && "HouseRingNotifier(app, log, directRing)" in graph)
    }

    @Test
    fun `Status shows no lock-screen or overlay row`() {
        val checks = read("src/main/java/com/meda/portico/setup/SetupChecks.kt")
        assertTrue("canDrawOverlays" !in checks && "overlay" !in checks.lowercase())
    }
}
