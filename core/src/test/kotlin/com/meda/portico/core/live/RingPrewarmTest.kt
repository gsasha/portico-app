package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RingPrewarmTest {
    private fun cam(id: String, size: CameraSize = CameraSize.BIG, small: Boolean = true, ok: Boolean = true, live: Boolean = true) = PrewarmCam(id, size, small, ok, live)

    private fun input(
        switch: Boolean = true,
        paused: Boolean = false,
        roaming: Boolean = false,
        net: DefaultNet = DefaultNet.WIFI,
        path: LinkPath? = LinkPath.DIRECT,
        ringing: Boolean = true,
        already: Boolean = false,
        cams: List<PrewarmCam> = listOf(cam("doorbell")),
        up: Boolean = false,
    ) = PrewarmIn(switch, paused, roaming, net, path, ringing, already, cams, up)

    private fun skip(i: PrewarmIn) = (RingPrewarm.decide(i) as PrewarmDecision.Skip).why

    @Test
    fun wifi_ring_starts_the_live_cameras() {
        val d = RingPrewarm.decide(input(cams = listOf(cam("a"), cam("b", live = false), cam("c", ok = false), cam("d"))))
        assertEquals(listOf("a", "d"), (d as PrewarmDecision.Start).cameras.map { it.cameraId })
    }

    @Test
    fun mobile_data_does_nothing() = assertEquals(PrewarmSkip.NOT_WIFI, skip(input(net = DefaultNet.MOBILE)))

    @Test
    fun unknown_network_does_nothing() = assertEquals(PrewarmSkip.NETWORK_UNKNOWN, skip(input(net = DefaultNet.UNKNOWN)))

    @Test
    fun roaming_block_wins_even_on_wifi() = assertEquals(PrewarmSkip.ROAMING, skip(input(roaming = true)))

    @Test
    fun only_the_direct_path_is_prewarmed() {
        assertEquals(PrewarmSkip.PATH, skip(input(path = LinkPath.TAILNET)))
        assertEquals(PrewarmSkip.PATH, skip(input(path = null)))
    }

    @Test
    fun switch_pause_ring_and_once_per_ring() {
        assertEquals(PrewarmSkip.SWITCH_OFF, skip(input(switch = false)))
        assertEquals(PrewarmSkip.PAUSED, skip(input(paused = true)))
        assertEquals(PrewarmSkip.NOT_RINGING, skip(input(ringing = false)))
        assertEquals(PrewarmSkip.ALREADY, skip(input(already = true)))
        assertEquals(PrewarmSkip.SCREEN_UP, skip(input(up = true)))
    }

    @Test
    fun no_live_camera_is_nothing_to_start() {
        assertEquals(PrewarmSkip.NO_CAMERAS, skip(input(cams = emptyList())))
        assertEquals(PrewarmSkip.NO_CAMERAS, skip(input(cams = listOf(cam("a", live = false), cam("b", ok = false)))))
    }

    @Test
    fun never_more_than_the_cap_and_a_repeated_camera_counts_once() {
        val many = (1..9).map { cam("c$it") } + cam("c1")
        val d = RingPrewarm.decide(input(cams = many)) as PrewarmDecision.Start
        assertEquals(RingPrewarm.MAX_CAMERAS, d.cameras.size)
        assertEquals(listOf("c1", "c2", "c3", "c4"), d.cameras.map { it.cameraId })
    }

    @Test
    fun key_is_the_auto_stream_of_the_tile_size() {
        assertEquals(LiveKey("x", CameraSize.BIG), RingPrewarm.keyFor(cam("x", CameraSize.BIG)))
        assertEquals(LiveKey("x", CameraSize.SMALL), RingPrewarm.keyFor(cam("x", CameraSize.SMALL)))
        // a pick the camera cannot honour is Auto anyway; Auto follows the tile size
        assertEquals(LiveKey("x", CameraSize.SMALL), RingPrewarm.keyFor(cam("x", CameraSize.SMALL, small = false)))
    }

    @Test
    fun stop_reasons() {
        assertNull(RingPrewarm.stopWhy(1_000, 2_000, ringing = true))
        assertEquals(PrewarmStop.ENDED, RingPrewarm.stopWhy(1_000, 2_000, ringing = false))
        assertEquals(PrewarmStop.TIMEOUT, RingPrewarm.stopWhy(1_000, 1_000 + RingPrewarm.MAX_HOLD_MS, ringing = true))
        assertEquals(PrewarmStop.ENDED, RingPrewarm.stopWhy(1_000, 1_000 + RingPrewarm.MAX_HOLD_MS, ringing = false))
    }

    @Test
    fun lines() {
        assertEquals("ui prewarm start door=abcdef…(8) cams=2", RingPrewarm.startLine("abcdefgh", 2))
        assertTrue(RingPrewarm.firstFrameLine("camera.doorbell_main", 1234).endsWith("ms=1234"))
        assertTrue(RingPrewarm.handoffLine("camera.doorbell_main", 900, true).contains("age=900ms playing=yes"))
        assertEquals("ui prewarm stop why=ended held=3000ms", RingPrewarm.stopLine("ended", 3000))
        assertEquals("ui prewarm skip door=${com.meda.portico.core.push.Redact.prefix("d1")} why=notwifi", RingPrewarm.skipLine("d1", PrewarmSkip.NOT_WIFI))
    }

    @Test
    fun run_reports_each_first_frame_once_and_the_handoff() {
        val r = PrewarmRun("d", "c", 1_000, listOf(LiveKey("a", CameraSize.BIG)))
        assertEquals(700L, r.firstFrame("a", 1_700))
        assertNull(r.firstFrame("a", 2_000))
        assertFalse(r.handedOff)
        r.handoff()
        assertTrue(r.handedOff)
        assertEquals(1_500L, r.age(2_500))
    }

    @Test
    fun tracker_is_once_per_call_and_bounded() {
        val t = PrewarmTracker()
        assertFalse(t.has("c1"))
        t.add("c1")
        assertTrue(t.has("c1"))
        for (i in 0 until 100) t.add("x$i")
        assertTrue(t.has("x99"))
    }

    // ---- RefHolder: the pre-warm and the screen hold ONE LiveHome ----

    private class Res(val n: Int) {
        var destroyed = false
    }

    private fun holder(clock: FakeLiveClock, made: MutableList<Res>) = RefHolder({ Res(made.size + 1).also { made += it } }, { it.destroyed = true }, clock, 3_000)

    @Test
    fun screen_adopts_the_prewarm_resource_and_it_survives_the_prewarm_letting_go() {
        val clock = FakeLiveClock()
        val made = ArrayList<Res>()
        val h = holder(clock, made)
        val a = h.acquire("prewarm")
        val b = h.acquire("screen")
        assertSame(a, b)
        assertEquals(1, made.size)
        h.release("prewarm")
        clock.advance(10_000)
        assertFalse(a.destroyed)
        h.release("screen")
        clock.advance(2_999)
        assertFalse(a.destroyed)
        clock.advance(1)
        assertTrue(a.destroyed)
        assertNull(h.current())
    }

    @Test
    fun an_acquire_inside_the_grace_cancels_the_teardown_and_a_stranger_release_is_ignored() {
        val clock = FakeLiveClock()
        val made = ArrayList<Res>()
        val h = holder(clock, made)
        val a = h.acquire("p")
        h.release("stranger")
        assertEquals(1, h.holderCount())
        h.release("p")
        clock.advance(2_000)
        assertSame(a, h.acquire("s"))
        clock.advance(10_000)
        assertFalse(a.destroyed)
        h.release("s")
        clock.advance(3_000)
        assertTrue(a.destroyed)
        val c = h.acquire("again")
        assertNotNull(c)
        assertEquals(2, made.size)
    }
}
