package com.meda.portico.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.76: source-pattern pins of the banner suppression (the Android side cannot run on the JVM; working directory = app/). */
class QuietRingWiringTest {
    private fun read(p: String) = File(p).readText()
    private val notifier by lazy { read("src/main/java/com/meda/portico/ring/RingNotifier.kt") }
    private val channels by lazy { read("src/main/java/com/meda/portico/ring/Channels.kt") }
    private val launcher by lazy { read("src/main/java/com/meda/portico/ring/DirectRingLauncher.kt") }

    @Test
    fun `the quiet channel is created LOW with no sound and no vibration, after the stale-id cleanup, and never deleted`() {
        val make = channels.substringAfter("Channel.RING_WINDOW.id) == null").substringBefore("nm.createNotificationChannel(w)")
        assertTrue("NotificationManager.IMPORTANCE_LOW" in channels.substringAfter("val w = NotificationChannel("))
        assertTrue("w.setSound(null, null)" in make && "w.enableVibration(false)" in make)
        assertTrue(channels.indexOf("staleIds(") < channels.indexOf("Channel.RING_WINDOW.id) == null"), "created after the cleanup")
        assertEquals(1, Regex("deleteNotificationChannel").findAll(channels).count(), "the one cleanup loop, over RingChannelIds.staleIds")
        assertTrue("RingChannelIds.staleIds(nm.notificationChannels.map { it.id }, ringId)" in channels)
        assertTrue("Channel.PAUSED, Channel.CONNECTION, Channel.RING_WINDOW -> NotificationManager.IMPORTANCE_LOW" in channels)
        // the strings exist
        val strings = read("src/main/res/values/strings.xml")
        assertTrue("channel_ring_window_name" in strings && "channel_ring_window_description" in strings)
    }

    @Test
    fun `the notifier decides before posting and posts on the quiet channel only for a direct launch`() {
        val post = notifier.substringAfter("private fun postRing(").substringBefore("private fun backToLoud(")
        assertTrue("launcher?.decide()" in post)
        assertTrue("RingAlertChannel.forRing(spec.channel, decision, launchIssued = launcher != null) == Channel.RING_WINDOW" in post)
        assertTrue("val launcher = if (spec.fullScreen) direct else null" in post)
        assertTrue("RingWindowSignals.ownership.claim(callId)" in post && post.indexOf("claim(callId)") < post.indexOf("postRingNotification("), "the window owns the ring before the notification exists")
        assertTrue("launcher.journalLine(RingScreenPolicy.QUIET_LINE)" in post)
        val body = notifier.substringAfter("private fun postRingNotification(").substringBefore("private fun postStatus(")
        assertTrue("val channel = if (quiet) Channel.RING_WINDOW else spec.channel" in body && "Channels.idOf(context, channel)" in body)
        assertTrue("if (spec.insistent && !quiet)" in body, "the quiet notification has no insistent alert")
        // still one notification per ring: the same id, in every path
        assertEquals(2, Regex("nm\\.notify\\(id,").findAll(body).count(), "the plain and the CallStyle post, both with the ring's one id")
        assertTrue("RequestCodes.ringNotificationId(slot)" in body)
        assertTrue("setFullScreenIntent(full, true)" in body, "the full-screen intent stays on both channels")
    }

    @Test
    fun `a failed or silently blocked direct launch puts the ring back on the loud channel`() {
        assertTrue("onFailed = { backToLoud(spec, slot, door, doorRef, callId, launcher) }" in notifier)
        assertTrue("windowUp = { RingWindowSignals.ownership.isOpen(callId) }" in notifier)
        val back = notifier.substringAfter("private fun backToLoud(").substringBefore("private fun postRingNotification(")
        assertTrue("RingScreenPolicy.BANNER_RESTORED_LINE" in back)
        assertTrue("if (!still) return" in back, "a ring that has ended is never brought back")
        assertTrue(back.indexOf("nm.cancel(") < back.indexOf("postRingNotification("), "cancel then a fresh post alerts")
        assertTrue("quiet = false" in back)
        // the launcher: every failure path and the watchdog run the callback once
        assertEquals(3, Regex("watch\\?\\.fail\\(\\)|watch\\.fail\\(\\)").findAll(launcher).count(), "overlay failure, start failure, watchdog")
        assertTrue("WINDOW_WATCHDOG_MS" in launcher.substringAfter("app.startActivity("))
        assertTrue("if (fired) return" in launcher)
        // ending the ring releases the window's claim
        assertTrue("RingWindowSignals.ownership.release(it)" in notifier.substringAfter("override fun cancelRing(").substringBefore("private val quietCalls"))
    }

    @Test
    fun `the locked path keeps the high channel - the full-screen intent is untouched`() {
        // the only way to the quiet channel is a DIRECT_OVERLAY decision; decide() returns FULL_SCREEN_INTENT_ONLY for locked / screen off / no permission
        assertTrue("RingScreenPolicy.Decision.FULL_SCREEN_INTENT_ONLY" in notifier.substringAfter("private fun postRing(").substringBefore("val quiet"))
        assertFalse("RING_WINDOW" in read("src/main/java/com/meda/portico/ring/HouseRingNotifier.kt"), "house rings are not changed")
    }
}
