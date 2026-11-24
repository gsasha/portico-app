package com.meda.portico.core.live

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.live.LiveSession.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plan driving real sessions over fake ports: the screen's rules end in commands (or in none). */
class LiveManagerTest {
    private val home = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT)

    private class Bench {
        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val log = FakeLiveLog()
        val peers = ArrayList<FakePeer>()
        val manager = LiveManager { key -> LiveSession(key, { l -> FakePeer(clock, l).also { peers += it } }, wire, clock, log) }
        private var ids = 0

        /** Answers the newest unanswered offer so the session is playing. */
        fun playAll() {
            for (o in wire.opens.filter { !answered.contains(it) }) {
                answered += o
                o.sink.onReply(LiveCommands.Reply.Opened("live-${++ids}", "main"))
            }
            for (p in peers) p.listener.onFirstFrame()
        }

        private val answered = HashSet<FakeLiveWire.Open>()
    }

    private fun tile(id: String, size: CameraSize = CameraSize.BIG, wants: Boolean = true, onScreen: Boolean = true) = LiveTileIn(id, size, true, wants, onScreen)

    @Test
    fun `a re-layout of the grid reconciles to the same set and opens and closes nothing`() {
        val b = Bench()
        val tiles = listOf(tile("gate"), tile("lawn", CameraSize.SMALL))
        b.manager.reconcile(LivePlan.plan(home, tiles))
        b.playAll()
        val before = b.manager.keys().associateWith { b.manager.session(it) }
        for (i in 1..5) b.manager.reconcile(LivePlan.plan(home, tiles.reversed()))
        assertEquals(2, b.wire.opens.size, "no reopen")
        assertEquals(emptyList<String>(), b.wire.closes, "no close")
        for ((k, s) in before) assertSame(s, b.manager.session(k))
        assertTrue(b.manager.keys().all { b.manager.session(it)!!.state == State.PLAYING })
    }

    @Test
    fun `scrolling a tile out of view closes it with its live_close and back into view opens a new one`() {
        val b = Bench()
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"))))
        b.playAll()
        val first = b.manager.session(LiveKey("gate", CameraSize.BIG))
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate", onScreen = false))))
        assertEquals(1, b.wire.closes.size)
        assertEquals(0, b.manager.count())
        assertTrue(b.log.lines.last().startsWith("stop gate|big hidden"))
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"))))
        assertEquals(2, b.wire.opens.size)
        assertFalse(first === b.manager.session(LiveKey("gate", CameraSize.BIG)))
    }

    @Test
    fun `leaving the screen, the pause and the switch close every stream and send one live_close each`() {
        for ((g, word) in listOf(home.copy(shown = false) to "background", home.copy(paused = true) to "paused", home.copy(switchOn = false) to "switch", home.copy(connected = false) to "closed")) {
            val b = Bench()
            b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"), tile("lawn", CameraSize.SMALL))))
            b.playAll()
            assertEquals(2, b.wire.opens.size)
            b.manager.reconcile(LivePlan.plan(g, listOf(tile("gate"), tile("lawn", CameraSize.SMALL))))
            assertEquals(0, b.manager.count(), word)
            assertEquals(2, b.wire.closes.size, "$word: a live_close for each of the two sessions")
            assertEquals(2, b.peers.count { it.closed == 1 }, word)
            assertEquals(2, b.log.lines.count { it.startsWith("stop") && it.contains(" $word ") }, word)
        }
    }

    @Test
    fun `with the switch off no live command is ever sent, whatever the tiles say`() {
        val b = Bench()
        val off = home.copy(switchOn = false)
        for (i in 1..10) b.manager.reconcile(LivePlan.plan(off, listOf(tile("gate"), tile("lawn", CameraSize.SMALL, wants = true))))
        assertEquals(0, b.wire.opens.size)
        assertEquals(0, b.wire.ices.size)
        assertEquals(0, b.wire.closes.size)
        assertEquals(0, b.peers.size, "not even a peer connection")
        assertEquals(0, b.clock.pending())
    }

    @Test
    fun `on the tailnet no attempt is made`() {
        val b = Bench()
        b.manager.reconcile(LivePlan.plan(home.copy(path = LinkPath.TAILNET), listOf(tile("gate"))))
        assertEquals(0, b.peers.size)
        assertEquals(0, b.wire.opens.size)
    }

    @Test
    fun `a size change closes the old stream and opens the new one`() {
        val b = Bench()
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate", CameraSize.BIG))))
        b.playAll()
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate", CameraSize.SMALL))))
        assertEquals(listOf(CameraSize.BIG, CameraSize.SMALL), b.wire.opens.map { it.size })
        assertEquals(1, b.wire.closes.size)
        assertEquals(setOf(LiveKey("gate", CameraSize.SMALL)), b.manager.keys())
    }

    @Test
    fun `a session that gave up stays until a tap restarts it or the tile leaves and returns`() {
        val b = Bench()
        val key = LiveKey("gate", CameraSize.BIG)
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"))))
        b.wire.opens.last().sink.onReply(LiveCommands.Reply.Refused("live_unavailable"))
        assertEquals(State.FAILED, b.manager.session(key)!!.state)
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"))))
        b.clock.advance(60_000)
        assertEquals(1, b.wire.opens.size, "the plan does not retry a tile that gave up")
        assertTrue(b.manager.restart(key))
        assertEquals(2, b.wire.opens.size)
        assertFalse(b.manager.restart(LiveKey("nope", CameraSize.BIG)))
        // a running session is not "restarted"
        assertFalse(b.manager.restart(key))
        // off screen and back: a fresh session
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate", onScreen = false))))
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"))))
        assertEquals(3, b.wire.opens.size)
    }

    @Test
    fun `stopAll ends every session`() {
        val b = Bench()
        b.manager.reconcile(LivePlan.plan(home, listOf(tile("gate"), tile("lawn"))))
        b.playAll()
        b.manager.stopAll(StopWhy.BACKGROUND)
        assertEquals(0, b.manager.count())
        assertEquals(2, b.wire.closes.size)
        b.manager.stopAll(StopWhy.BACKGROUND)
        assertEquals(2, b.wire.closes.size)
    }
}
