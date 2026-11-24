package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.76: which channel a door ring uses, and the call window's ringing rules (fakes for the player and the DND state). */
class RingWindowAlertTest {
    private val direct = RingScreenPolicy.Decision.DIRECT_OVERLAY
    private val fsiOnly = RingScreenPolicy.Decision.FULL_SCREEN_INTENT_ONLY

    @Test fun `the quiet channel only for a direct launch that is issued`() {
        assertEquals(Channel.RING_WINDOW, RingAlertChannel.forRing(Channel.RING, direct, launchIssued = true))
        assertEquals(Channel.RING, RingAlertChannel.forRing(Channel.RING, direct, launchIssued = false), "no launch, no quiet: the ring would be silent")
        assertEquals(Channel.RING, RingAlertChannel.forRing(Channel.RING, fsiOnly, launchIssued = true), "locked / screen off keeps the high channel and its sound")
        assertEquals(Channel.RING, RingAlertChannel.forRing(Channel.RING, fsiOnly, launchIssued = false))
        // other channels are never touched
        for (c in Channel.values().filter { it != Channel.RING }) assertEquals(c, RingAlertChannel.forRing(c, direct, true))
    }

    @Test fun `the quiet channel is quiet - LOW, no DND bypass`() {
        assertEquals(Importance.LOW, Channel.RING_WINDOW.importance)
        assertFalse(Channel.RING_WINDOW.bypassDnd)
        assertTrue(Channel.RING.bypassDnd)
    }

    @Test fun `DND silences the window unless the ring channel bypasses it, a blocked channel always`() {
        assertFalse(RingSoundPolicy.silenced(1, channelBypassesDnd = false, channelBlocked = false), "DND off")
        assertFalse(RingSoundPolicy.silenced(0, false, false), "unknown filter = not DND")
        for (f in 2..4) {
            assertTrue(RingSoundPolicy.silenced(f, channelBypassesDnd = false, channelBlocked = false), "DND $f without bypass")
            assertFalse(RingSoundPolicy.silenced(f, channelBypassesDnd = true, channelBlocked = false), "DND $f with bypass")
        }
        assertTrue(RingSoundPolicy.silenced(1, channelBypassesDnd = true, channelBlocked = true))
    }

    private class FakeAlert : RingAlert {
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
    }

    private fun controller(alert: FakeAlert, owned: Set<String> = setOf("c1"), silenced: () -> Boolean = { false }) =
        RingSoundController(alert, { it in owned }, silenced)

    @Test fun `starts once per ring while the window shows it ringing and owns it`() {
        val a = FakeAlert()
        val c = controller(a)
        c.onWindow("c1", ringing = true)
        c.onWindow("c1", ringing = true) // every refresh
        c.onWindow("c1", ringing = true)
        assertEquals(1, a.starts)
        assertTrue(c.isPlaying)
    }

    @Test fun `stops when the ring is answered or ends, once, and a later refresh does not restart it`() {
        val a = FakeAlert()
        val c = controller(a)
        c.onWindow("c1", true)
        c.onWindow("c1", false)
        c.onWindow("c1", false)
        assertEquals(1, a.stops)
        assertFalse(c.isPlaying)
        c.onWindow("c1", true) // the same call never rings again in this window
        assertEquals(1, a.starts)
    }

    @Test fun `stop is idempotent and silent when nothing plays`() {
        val a = FakeAlert()
        val c = controller(a)
        c.stop()
        c.reset()
        assertEquals(0, a.stops)
        c.onWindow("c1", true)
        c.stop()
        c.stop()
        assertEquals(1, a.stops)
    }

    @Test fun `no sound when the ring is not the window's to ring - the loud channel rings it`() {
        val a = FakeAlert()
        controller(a, owned = emptySet()).onWindow("c1", true)
        assertEquals(0, a.starts)
        controller(a).onWindow(null, true)
        assertEquals(0, a.starts)
    }

    @Test fun `no sound under DND without bypass, and the decision is taken once per ring`() {
        val a = FakeAlert()
        var dnd = true
        val c = controller(a, silenced = { dnd })
        c.onWindow("c1", true)
        dnd = false
        c.onWindow("c1", true)
        assertEquals(0, a.starts, "DND was on when the ring began")
        assertFalse(c.isPlaying)
    }

    @Test fun `a second ring replaces the first - the first stops, the second starts`() {
        val a = FakeAlert()
        val c = controller(a, owned = setOf("c1", "c2"))
        c.onWindow("c1", true)
        c.onWindow("c2", true)
        assertEquals(2, a.starts)
        assertEquals(1, a.stops)
        c.reset()
        assertEquals(2, a.stops)
    }

    @Test fun `ownership - claimed rings are the window's, released ones are not, and a claimed ring with no window is the fallback case`() {
        val o = RingSoundOwnership()
        assertFalse(o.owns("c1"))
        o.claim("c1")
        assertTrue(o.owns("c1"))
        assertTrue(o.shouldFallBack("c1"), "quiet channel chosen, no window yet")
        o.windowOpened("c1")
        assertTrue(o.isOpen("c1"))
        assertFalse(o.shouldFallBack("c1"), "the window came up")
        o.windowClosed("c1")
        assertTrue(o.shouldFallBack("c1"))
        o.release("c1")
        assertFalse(o.owns("c1"))
        assertFalse(o.shouldFallBack("c1"), "a released ring is never put back on the loud channel")
        assertFalse(o.shouldFallBack("never-claimed"))
    }

    @Test fun `the journal lines`() {
        assertEquals("ring screen: banner suppressed (quiet channel)", RingScreenPolicy.QUIET_LINE)
        assertTrue(RingScreenPolicy.BANNER_RESTORED_LINE.startsWith("ring screen: "))
    }
}
