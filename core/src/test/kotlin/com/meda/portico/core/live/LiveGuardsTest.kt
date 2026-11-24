package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.ha.LiveRtsp
import com.meda.portico.core.push.InMemoryPushJournalStore
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** DEV-713 S2: the thermal guard and the decoder-instance guard, as pure policy over fakes, and what they do to the live plan. */
class LiveGuardsTest {
    private val direct = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT)
    private fun tile(id: String, size: CameraSize = CameraSize.BIG, wants: Boolean = true, onScreen: Boolean = true, rtsp: LiveRtsp? = null) =
        LiveTileIn(id, size, true, wants, onScreen, rtsp)

    // ---- thermal policy ----------------------------------------------------------------------

    @Test
    fun `hot from SEVERE up, cool only at LIGHT or NONE, MODERATE changes nothing`() {
        // (status, hot afterwards)
        val table = listOf(
            0 to false, 1 to false, 2 to false, // rising through the band: not yet
            3 to true, // SEVERE
            2 to true, // cooling to MODERATE: still paused
            3 to true, 4 to true, 5 to true, 6 to true,
            2 to true,
            1 to false, // LIGHT: resumed
            2 to false, // heating to MODERATE: not paused yet
            0 to false,
            4 to true, // straight to CRITICAL
            0 to false,
            -1 to false, // a number Android does not define changes nothing
        )
        val log = FakeLiveLog()
        val guard = ThermalGuard(log)
        for ((status, hot) in table) {
            guard.onStatus(status)
            assertEquals(hot, guard.hot, "status $status")
        }
    }

    @Test
    fun `an undefined status keeps whatever state there was`() {
        val g = ThermalGuard()
        g.onStatus(3)
        g.onStatus(-1)
        assertTrue(g.hot)
        g.onStatus(0)
        g.onStatus(-7)
        assertFalse(g.hot)
    }

    @Test
    fun `one Journal line per transition and none for a repeat`() {
        val log = FakeLiveLog()
        val g = ThermalGuard(log)
        for (s in listOf(0, 1, 2, 3, 3, 4, 2, 3, 1, 0, 1)) g.onStatus(s)
        assertEquals(listOf("thermal severe hot=true", "thermal light hot=false"), log.lines, "SEVERE flapping with MODERATE is one pause, then one resume")
    }

    @Test
    fun `a source feeds the guard, is attached once, and detaches`() {
        val log = FakeLiveLog()
        val g = ThermalGuard(log)
        var listeners = 0
        var stopped = 0
        var push: (Int) -> Unit = {}
        val source = ThermalSource { on ->
            listeners++
            push = on
            on(0) // Android tells the current status when it is registered
            return@ThermalSource { stopped++ }
        }
        g.attach(source)
        g.attach(source)
        assertEquals(1, listeners, "attached once")
        assertFalse(g.hot)
        push(3)
        assertTrue(g.hot)
        push(1)
        assertFalse(g.hot)
        g.detach()
        g.detach()
        assertEquals(1, stopped)
    }

    @Test
    fun `a source that throws leaves live running and is not retried`() {
        val log = FakeLiveLog()
        val g = ThermalGuard(log)
        var tries = 0
        val broken = ThermalSource { tries++; throw SecurityException("no") }
        g.attach(broken)
        g.attach(broken)
        assertEquals(1, tries)
        assertFalse(g.hot)
        assertEquals(listOf("fault thermal SecurityException"), log.lines)
    }

    // ---- decoder cap policy ------------------------------------------------------------------

    @Test
    fun `the cap is the phone's instances minus one, at least one, with a safe guess when the phone does not say`() {
        val table = listOf(1 to 1, 2 to 1, 3 to 2, 4 to 3, 8 to 7, 16 to 15, 64 to 63)
        for ((max, cap) in table) assertEquals(cap, DecoderCap.cap(max), "max $max")
        for (unsaid in listOf(null, 0, -1, Int.MIN_VALUE)) assertEquals(3, DecoderCap.cap(unsaid), "unsaid $unsaid")
        assertEquals(Int.MAX_VALUE - 1, DecoderCap.cap(Int.MAX_VALUE), "no overflow")
    }

    @Test
    fun `the phone guards ask the decoder once and attach the thermal source on first use`() {
        var asked = 0
        var attached = 0
        val thermal = ThermalGuard()
        val g = PhoneGuards(thermal, ThermalSource { attached++; { } }, { asked++; 4 })
        assertEquals(0, asked)
        assertEquals(0, attached)
        assertEquals(3, g.maxStreams())
        assertEquals(3, g.maxStreams())
        assertEquals(1, asked, "the probe runs once")
        assertFalse(g.thermalHot())
        assertFalse(g.thermalHot())
        assertEquals(1, attached)
        thermal.onStatus(3)
        assertTrue(g.thermalHot())
    }

    @Test
    fun `a decoder probe that throws gives the safe default and one Journal line`() {
        val log = FakeLiveLog()
        val g = PhoneGuards(ThermalGuard(), null, { throw IllegalStateException("codec list") }, log)
        assertEquals(3, g.maxStreams())
        assertEquals(3, g.maxStreams())
        assertEquals(listOf("fault decoder-cap IllegalStateException"), log.lines)
        assertFalse(g.thermalHot(), "no source (below API 29): never hot")
    }

    // ---- what the guards do to the plan ----------------------------------------------------------

    @Test
    fun `a hot phone puts every tile on its still with the notice, on every route`() {
        val tiles = listOf(tile("a"), tile("b", wants = false), tile("c", CameraSize.SMALL))
        for (g in listOf(direct, direct.copy(path = LinkPath.TAILNET))) {
            val p = LivePlan.plan(g.copy(thermalHot = true), listOf(tile("a", rtsp = LiveRtsp("rtsp://192.168.1.6:8554/a")), tile("b", wants = false), tile("c", CameraSize.SMALL)))
            assertEquals(emptySet<LiveKey>(), p.wanted, "${g.path}")
            val a = TileLiveUi.of(p.tiles.getValue("a"), null, null, "--", true, false)
            assertEquals(ChipLook.PENDING, a.chip)
            assertEquals(LiveWhy.HOT, a.notice)
            assertFalse(a.video)
            assertNull(a.fps)
            val b = TileLiveUi.of(p.tiles.getValue("b"), null, null, "--", true, false)
            assertEquals(ChipLook.OFFER, b.chip, "a tile set to still has nothing to say about heat")
            assertNull(b.notice)
            assertEquals(StopWhy.THERMAL, p.reasonFor(LiveKey("a", CameraSize.BIG)))
        }
        assertEquals(3, tiles.size)
    }

    @Test
    fun `a hot phone closes running streams with why=thermal and they return when it has cooled`() {
        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val log = FakeLiveLog()
        val peers = ArrayList<FakePeer>()
        val manager = LiveManager { key -> LiveSession(key, { l -> FakePeer(clock, l).also { peers += it } }, wire, clock, log) }
        val tiles = listOf(tile("a"), tile("b", CameraSize.SMALL))
        manager.reconcile(LivePlan.plan(direct, tiles))
        wire.opens.forEachIndexed { i, o -> o.sink.onReply(LiveCommands.Reply.Opened("live-$i", "main")) }
        peers.forEach { it.listener.onFirstFrame() }
        assertEquals(2, manager.count())

        manager.reconcile(LivePlan.plan(direct.copy(thermalHot = true), tiles))
        assertEquals(0, manager.count())
        assertEquals(listOf("live-0", "live-1"), wire.closes.sorted(), "each stream's live_close, once")
        assertTrue(log.lines.count { it.contains(" thermal ") } == 2, log.lines.toString())
        manager.reconcile(LivePlan.plan(direct.copy(thermalHot = true), tiles))
        assertEquals(2, wire.closes.size, "still hot: nothing more is sent, nothing is retried")
        assertEquals(2, wire.opens.size)

        manager.reconcile(LivePlan.plan(direct, tiles)) // cooled
        assertEquals(2, manager.count())
        assertEquals(4, wire.opens.size, "back to live")
    }

    @Test
    fun `tiles over the decoder limit stay stills with their own notice`() {
        val tiles = listOf(tile("a"), tile("b", CameraSize.SMALL), tile("c"))
        val p = LivePlan.plan(direct.copy(maxStreams = 2), tiles)
        assertEquals(setOf(LiveKey("a", CameraSize.BIG), LiveKey("b", CameraSize.SMALL)), p.wanted, "served in order")
        val c = TileLiveUi.of(p.tiles.getValue("c"), null, null, "--", false, false)
        assertEquals(LiveWhy.PHONE_LIMIT, c.notice)
        assertEquals(ChipLook.PENDING, c.chip)
        assertTrue(p.tiles.getValue("c").capped)
        assertFalse(p.tiles.getValue("a").capped)
        assertEquals(StopWhy.CLOSED, p.reasonFor(LiveKey("c", CameraSize.BIG)))
    }

    @Test
    fun `the limit counts only tiles that would stream, RTSP and WebRTC alike, and is never below one`() {
        // a tile completely off screen or set to still uses no decoder
        val p = LivePlan.plan(direct.copy(maxStreams = 1), listOf(tile("a", onScreen = false), tile("b", wants = false), tile("c"), tile("d")))
        assertEquals(setOf(LiveKey("c", CameraSize.BIG)), p.wanted)
        assertTrue(p.tiles.getValue("d").capped)
        assertFalse(p.tiles.getValue("a").capped)
        assertFalse(p.tiles.getValue("b").capped)
        // nonsense limits still allow one
        for (n in listOf(0, -3)) assertEquals(1, LivePlan.plan(direct.copy(maxStreams = n), listOf(tile("a"), tile("b"))).wanted.size, "limit $n")
        // RTSP streams count the same
        val rtsp = LiveRtsp("rtsp://192.168.1.6:8554/a")
        val away = LivePlan.plan(direct.copy(path = LinkPath.TAILNET, maxStreams = 1), listOf(tile("a", rtsp = rtsp), tile("b", rtsp = LiveRtsp("rtsp://192.168.1.6:8554/b"))))
        assertEquals(1, away.wanted.size)
        assertEquals(1, away.urls.size, "the capped tile's address is not handed on")
        // no limit by default: no product cap
        assertEquals(20, LivePlan.plan(direct, (1..20).map { tile("t$it") }).wanted.size)
    }

    @Test
    fun `thermal wins over the limit and both give way to the switch`() {
        val tiles = listOf(tile("a"), tile("b"))
        val hotAndLimited = LivePlan.plan(direct.copy(thermalHot = true, maxStreams = 1), tiles)
        assertTrue(hotAndLimited.tiles.values.all { it.thermal && !it.capped })
        assertEquals(emptyMap<String, TilePlan>(), LivePlan.plan(direct.copy(thermalHot = true, switchOn = false), tiles).tiles)
    }

    // ---- the Journal -------------------------------------------------------------------------------

    @Test
    fun `the thermal and fault lines are short plain words`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("01:02:03.456 live thermal paused status=severe", PushJournalFormat.liveThermalLine(3_723_456L, utc, true, "severe"))
        assertEquals("01:02:03.456 live thermal resumed status=light", PushJournalFormat.liveThermalLine(3_723_456L, utc, false, "light"))
        assertEquals("01:02:03.456 live fault at=sync err=IllegalStateException", PushJournalFormat.liveFaultLine(3_723_456L, utc, "sync", "IllegalStateException"))
        assertEquals("01:02:03.456 live fault at=? err=?", PushJournalFormat.liveFaultLine(3_723_456L, utc, "192.168.1.6", "boom: rtsp://u:p@h/x"), "no message, address or credential can reach a line")
        for (w in LiveWhy.entries) assertTrue(Regex("[a-z]+").matches(w.word), w.name)
        for (w in StopWhy.entries) assertTrue(Regex("[a-z]+").matches(w.word), w.name)
        assertEquals("other", ThermalGuard.word(99))
    }

    @Test
    fun `the session log writes them to the journal`() {
        val utc = TimeZone.getTimeZone("UTC")
        val j = PushJournal(InMemoryPushJournalStore(), { 3_723_456L }, { utc })
        val g = ThermalGuard(j.liveLog)
        g.onStatus(3)
        g.onStatus(2)
        g.onStatus(0)
        j.liveLog.fault("sync", "IllegalStateException")
        assertEquals(
            listOf(
                "01:02:03.456 live thermal paused status=severe",
                "01:02:03.456 live thermal resumed status=none",
                "01:02:03.456 live fault at=sync err=IllegalStateException",
            ),
            j.lines(),
        )
    }
}
