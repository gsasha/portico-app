package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AwayTalkMediaTest {
    private class Events : MediaListener {
        var connected = 0
        var failed = 0
        var disconnected = 0
        override fun onConnected() { connected++ }
        override fun onDisconnected() { disconnected++ }
        override fun onFailed() { failed++ }
    }

    private class Rig(prefer: Boolean = false) {
        val rtc = FakeTalkMedia()
        val away = FakeAway(prefer)
        val sched = FakeScheduler()
        val events = Events()
        val media = AwayTalkMedia(rtc, away, sched, fallbackAfterMs = 4_000)
        fun open(): TalkLeg = media.open("out-1", "front", events)
    }

    @Test
    fun `at home WebRTC is the path and no frame path is built`() {
        val r = Rig()
        val leg = r.open()
        assertEquals(1, r.rtc.opens)
        assertTrue(r.away.paths.isEmpty())
        r.rtc.legs[0].listener.onConnected()
        assertEquals(1, r.events.connected)
        r.sched.advance(60_000)
        assertTrue(r.away.paths.isEmpty(), "a connected WebRTC leg never falls back")
        r.rtc.legs[0].listener.onFailed() // a failure after connecting is the session's, as before
        assertEquals(1, r.events.failed)
        assertTrue(r.away.paths.isEmpty())
        leg.close()
        assertEquals(1, r.rtc.legs[0].closes)
    }

    @Test
    fun `on the tailnet frames are used from the start and WebRTC is never opened`() {
        val r = Rig(prefer = true)
        val leg = r.open()
        assertEquals(0, r.rtc.opens)
        val path = r.away.paths.single()
        assertEquals("out-1" to "front", path.started)
        assertEquals(0, r.events.connected)
        path.listener.onConnected()
        assertEquals(1, r.events.connected)
        assertEquals("pref_frames_open", leg.stage())
        leg.close()
        assertEquals(1, path.closes)
    }

    @Test
    fun `a WebRTC failure before connecting moves the leg to frames once`() {
        val r = Rig()
        val leg = r.open()
        r.rtc.legs[0].listener.onFailed()
        assertEquals(0, r.events.failed, "the session is not told: the leg is recovering")
        assertEquals(1, r.rtc.legs[0].closes)
        val path = r.away.paths.single()
        assertEquals("out-1" to "front", path.started)
        path.listener.onConnected()
        assertEquals(1, r.events.connected)
        assertEquals("fb_rtc_failed_frames_open", leg.stage())
        r.rtc.legs[0].listener.onConnected() // a late callback from the retired peer connection
        assertEquals(1, r.events.connected)
    }

    @Test
    fun `no WebRTC connection after the fallback time moves the leg to frames`() {
        val r = Rig()
        val leg = r.open()
        r.sched.advance(3_999)
        assertTrue(r.away.paths.isEmpty())
        r.sched.advance(1)
        assertEquals(1, r.away.paths.size)
        assertEquals(1, r.rtc.legs[0].closes)
        assertEquals("fb_timeout_frames_opening", leg.stage())
    }

    @Test
    fun `a refused channel fails the leg once, with the code`() {
        val r = Rig(prefer = true)
        val leg = r.open()
        r.away.paths[0].onResult!!(AnswerErrors.fromCode("not_claimer"))
        assertEquals(1, r.events.failed)
        assertEquals("frames_not_claimer", leg.lastFailure())
        r.away.paths[0].listener.onFailed()
        assertEquals(1, r.events.failed)
    }

    @Test
    fun `frames that cannot be built fail the leg`() {
        val r = Rig(prefer = true)
        r.away.failOpen = true
        val leg = r.open()
        assertEquals(1, r.events.failed)
        assertEquals("frames_open_threw", leg.lastFailure())
    }

    @Test
    fun `WebRTC that cannot be built tries frames`() {
        val r = Rig()
        r.rtc.failOpen = true
        r.open()
        assertEquals(0, r.events.failed)
        assertEquals(1, r.away.paths.size)
    }

    @Test
    fun `a leg closed while recovering opens nothing more and the timer is gone`() {
        val r = Rig()
        val leg = r.open()
        leg.close()
        r.sched.advance(60_000)
        assertTrue(r.away.paths.isEmpty())
        r.rtc.legs[0].listener.onFailed()
        assertTrue(r.away.paths.isEmpty())
        assertEquals(0, r.events.failed)
    }

    @Test
    fun `close after frames closes the frame path and is idempotent`() {
        val r = Rig(prefer = true)
        val leg = r.open()
        leg.close()
        leg.close()
        assertEquals(1, r.away.paths[0].closes)
        assertNull(leg.lastFailure())
        assertFalse(r.events.failed > 0)
    }
}
