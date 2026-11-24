package com.meda.portico.core.live

import com.meda.portico.core.content.CallScreen
import com.meda.portico.core.content.CallerModel
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.LiveCommands
import com.meda.portico.core.ha.LiveRtsp
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-713 S2: the caller's first camera is live on the ring and in-call screens; the rules those screens add to Home's live plan, the
 * hand-off between the two screens, and the fence around all of it.
 */
class CallerLiveTest {
    private val main = "rtsp://192.168.1.6:8554/gate_intercom"
    private val sub = "rtsp://192.168.1.6:8554/gate_intercom_sub"
    private val rtsp = LiveRtsp(main, sub)

    private fun cam(id: String, available: Boolean = true, hint: LiveRtsp? = null) = ContentCamera(id, id, null, available, null, false, hint)

    /** A document with one page camera (a document with nothing to draw is "empty" and reconciles nothing) and the caller [cams]. */
    private fun profile(vararg cams: ContentCamera) = ContentProfile(
        "", "r", listOf(cam("p-1")), emptyList(),
        callers = mapOf("ep" to ContentCaller("Gate", "door", cams.toList(), emptyList(), emptyList())),
    )

    private fun view(p: ContentProfile, have: Set<String> = emptySet(), screen: CallScreen = CallScreen.RING, link: LinkState = LinkState.CONNECTED): CallerView =
        CallerModel.build(p, "ep", screen, link, ContentSupport.YES, emptySet(), have)!!

    private val direct = LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.DIRECT)

    private class MemStore : LiveStore {
        var text: String? = null
        override fun load() = text
        override fun save(text: String): Boolean {
            this.text = text
            return true
        }
    }

    private fun plan(g: LiveGlobal, v: CallerView, memory: LiveMemory = LiveMemory(MemStore()), visible: Set<String> = v.cameras.map { it.id }.toSet()) =
        LivePlan.plan(g, CallerLive.tiles(v.cameras, { memory.effective(it.id, it.live) }, visible))

    // ---- the first-camera rule -------------------------------------------------------------

    @Test
    fun `the first AVAILABLE camera is the live one, in the caller's order`() {
        assertEquals("c-2", CallerLive.firstAvailableId(listOf(cam("c-1", available = false), cam("c-2"), cam("c-3"))))
        assertEquals("c-1", CallerLive.firstAvailableId(listOf(cam("c-1"), cam("c-2"))))
        assertEquals("c-1", CallerLive.firstAvailableId(listOf(cam("c-1"), cam("c-1", available = false), cam("c-2"))), "a repeated id counts once, as the first")
        assertNull(CallerLive.firstAvailableId(listOf(cam("c-1", available = false))))
        assertNull(CallerLive.firstAvailableId(emptyList()))
    }

    @Test
    fun `on both call screens only the first available camera defaults to live, the others are stills`() {
        val p = profile(cam("c-1", available = false), cam("c-2"), cam("c-3"))
        for (screen in CallScreen.entries) {
            assertEquals(mapOf("c-1" to false, "c-2" to true, "c-3" to false), view(p, screen = screen).cameras.associate { it.id to it.live }, "$screen")
        }
        assertTrue(view(profile(cam("c-1", available = false))).cameras.none { it.live }, "no available camera, no live one")
    }

    @Test
    fun `the first camera streams and the others only offer the chip`() {
        val v = view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2"))
        val p = plan(direct, v)
        assertEquals(setOf(LiveKey("c-1", CameraSize.BIG)), p.wanted, "one stream: the first camera, Big (main stream)")
        assertTrue(p.tiles.getValue("c-2").chip && !p.tiles.getValue("c-2").wantsLive)
        assertEquals(ChipLook.OFFER, TileLiveUi.of(p.tiles.getValue("c-2"), null, null, "--", false, false).chip)
    }

    // ---- not before the still is drawn ----------------------------------------------------

    @Test
    fun `a tile is usable for live only once its still is drawn and it is on screen`() {
        val have = setOf("c-1")
        val v = view(profile(cam("c-1")), have = emptySet())
        assertEquals(PictureState.LOADING, v.cameras.single().picture)
        val early = plan(direct, v)
        assertEquals(emptySet<LiveKey>(), early.wanted, "the still is not there yet: nothing starts")
        assertFalse(early.tiles.getValue("c-1").chip, "and no chip yet")
        val drawn = view(profile(cam("c-1")), have)
        assertEquals(setOf(LiveKey("c-1", CameraSize.BIG)), plan(direct, drawn).wanted)
        assertEquals(emptySet<LiveKey>(), plan(direct, drawn, visible = emptySet()).wanted, "not on screen (the ring layout scrolls): nothing starts")
        val offline = view(profile(cam("c-1")), have, link = LinkState.WAITING_TO_RETRY)
        assertEquals(emptySet<LiveKey>(), plan(direct, offline).wanted, "a dropped link shows no picture, so no live either")
        val unavailable = view(profile(cam("c-1", available = false)), have)
        assertEquals(emptySet<LiveKey>(), plan(direct, unavailable).wanted)
    }

    @Test
    fun `the arming delay holds everything back until the screen has been up for a while`() {
        val arm = LiveArm(800)
        assertFalse(arm.ready(10_000), "never armed")
        arm.arm(10_000)
        assertFalse(arm.ready(10_000))
        assertFalse(arm.ready(10_799))
        assertTrue(arm.ready(10_800))
        assertFalse(arm.ready(9_000), "a clock that went backwards is not ready")
        arm.disarm()
        assertFalse(arm.ready(99_999), "paused: not ready until armed again")
        arm.arm(50_000)
        assertFalse(arm.ready(50_100), "armed again: the delay starts over")
    }

    // ---- the routing table (the same as Home's) -----------------------------------------------

    @Test
    fun `routing - direct is WebRTC with the c- id, the tailnet with a hint is RTSP main, no hint says not set up, an unknown path says nothing`() {
        val v = view(profile(cam("c-1", hint = rtsp), cam("c-2")), have = setOf("c-1", "c-2"))
        val atHome = plan(direct, v)
        assertEquals(setOf(LiveKey("c-1", CameraSize.BIG)), atHome.wanted)
        assertFalse(atHome.wanted.single().rtsp)

        val away = plan(direct.copy(path = LinkPath.TAILNET), v)
        val key = LiveKey("c-1", CameraSize.BIG, "gate_intercom")
        assertEquals(setOf(key), away.wanted, "the Big tile pulls the main stream")
        assertEquals(main, away.urls.getValue(key))

        val noHint = plan(direct.copy(path = LinkPath.TAILNET), view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2")))
        assertEquals(emptySet<LiveKey>(), noHint.wanted)
        assertTrue(noHint.tiles.getValue("c-1").notSetUp)
        assertEquals(LiveWhy.NOT_SET_UP, TileLiveUi.of(noHint.tiles.getValue("c-1"), null, null, "--", false, false).notice)

        val unknown = plan(direct.copy(path = null), v)
        assertEquals(emptySet<LiveKey>(), unknown.wanted)
        val ui = TileLiveUi.of(unknown.tiles.getValue("c-1"), null, null, "--", false, false)
        assertNull(ui.notice, "an unknown path attempts nothing and says nothing")

        val buildWithoutRtsp = plan(direct.copy(path = LinkPath.TAILNET, rtspReady = false), v)
        assertTrue(buildWithoutRtsp.tiles.getValue("c-1").away)
    }

    @Test
    fun `the switch, the pause, the background and a dropped link close the caller's stream like Home's`() {
        val v = view(profile(cam("c-1")), have = setOf("c-1"))
        for (g in listOf(direct.copy(switchOn = false), direct.copy(shown = false), direct.copy(paused = true), direct.copy(connected = false))) {
            assertEquals(emptySet<LiveKey>(), plan(g, v).wanted, g.toString())
        }
    }

    // ---- the viewer's choice --------------------------------------------------------------------

    @Test
    fun `a choice is remembered per camera id and only while it differs from the default`() {
        val store = MemStore()
        val memory = LiveMemory(store)
        val v = view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2"))
        val first = v.cameras[0]
        val second = v.cameras[1]
        assertTrue(memory.effective(first.id, first.live))
        assertFalse(memory.effective(second.id, second.live))

        assertFalse(memory.toggle(first.id, first.live), "chip on the live camera: it goes to a still")
        assertEquals(1, memory.count())
        assertEquals(emptySet<LiveKey>(), plan(direct, v, memory).wanted)
        assertTrue(memory.toggle(second.id, second.live), "chip on a still camera: it goes live")
        assertEquals(2, memory.count())
        assertEquals(setOf(LiveKey("c-2", CameraSize.BIG)), plan(direct, v, memory).wanted)

        assertTrue(memory.toggle(first.id, first.live), "back to the default")
        assertFalse(memory.toggle(second.id, second.live))
        assertEquals(0, memory.count(), "a choice equal to the default is not kept")

        // it survives a restart
        memory.toggle(first.id, first.live)
        val again = LiveMemory(store)
        assertFalse(again.effective(first.id, first.live))
        assertEquals(1, again.count())
    }

    @Test
    fun `when the caller's first camera changes, an old choice no longer applies`() {
        val memory = LiveMemory(MemStore())
        val before = view(profile(cam("c-1"), cam("c-2")))
        memory.toggle("c-1", before.cameras[0].live) // c-1 was the live one; the viewer chose a still
        assertFalse(memory.effective("c-1", true))
        val after = view(profile(cam("c-1", available = false), cam("c-2")))
        assertFalse(after.cameras[0].live)
        assertTrue(after.cameras[1].live, "c-2 is the first available camera now")
        assertEquals(false, memory.effective("c-1", after.cameras[0].live))
        assertTrue(memory.effective("c-2", after.cameras[1].live), "no choice was ever made for c-2")
        // and a choice made against the old default does not carry over to the new one
        memory.toggle("c-2", false) // c-2 was a still camera; the viewer made it live
        val third = view(profile(cam("c-2"), cam("c-1")))
        assertTrue(memory.effective("c-2", third.cameras[0].live), "the default is live now, the same as the choice")
        assertEquals(2, memory.count(), "both kept until a document reconcile")
        assertTrue(memory.reconcile(profile(cam("c-2"), cam("c-1"))), "both choices were made against a default that is not the default now")
        assertEquals(0, memory.count())
    }

    @Test
    fun `reconcile keeps caller choices that still hold and forgets a camera that left`() {
        val memory = LiveMemory(MemStore())
        memory.toggle("c-1", true) // still, against default live
        memory.toggle("c-2", false) // live, against default still
        assertFalse(memory.reconcile(profile(cam("c-1"), cam("c-2"))), "both still hold")
        assertEquals(2, memory.count())
        assertTrue(memory.reconcile(profile(cam("c-1"), cam("c-9"))), "c-2 left the document")
        assertEquals(1, memory.count())
        assertFalse(memory.effective("c-1", true))
        assertFalse(memory.reconcile(ContentProfile("", "r", emptyList(), emptyList())), "an empty document forgets nothing")
        assertEquals(1, memory.count())
    }

    // ---- ring -> in-call: one session ---------------------------------------------------------------

    /** What the shared live code of the two screens is, in the model: a manager over real sessions on fake ports. */
    private class Shared {
        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val log = FakeLiveLog()
        val peers = ArrayList<FakePeer>()
        val faults = ArrayList<String>()
        val manager = LiveManager(onFault = { at, e -> faults += "$at ${e.javaClass.simpleName}" }) { key ->
            LiveSession(key, { l -> FakePeer(clock, l).also { peers += it } }, wire, clock, log)
        }
        private var ids = 0
        private val answered = HashSet<FakeLiveWire.Open>()

        fun playAll() {
            for (o in wire.opens.filter { it !in answered }) {
                answered += o
                o.sink.onReply(LiveCommands.Reply.Opened("live-${++ids}", "main"))
            }
            for (p in peers) p.listener.onFirstFrame()
        }
    }

    @Test
    fun `answering hands the running stream from the ring screen to the in-call screen without a second session`() {
        val clock = FakeLiveClock()
        var made = 0
        var destroyed = 0
        val shared = Shared()
        val lease = LiveLease(make = { made++; shared }, destroy = { destroyed++; it.manager.stopAll(StopWhy.BACKGROUND) }, clock = clock, graceMs = 3_000)
        val ring = Any()
        val inCall = Any()
        val v = view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2"))

        // the ring screen: resume, plan, the video plays
        val a = lease.claim(ring)
        a.manager.reconcile(plan(direct, v))
        a.playAll()
        assertEquals(1, shared.wire.opens.size)
        assertEquals(LiveSession.State.PLAYING, a.manager.session(LiveKey("c-1", CameraSize.BIG))!!.state)

        // Answer: the ring screen pauses, the in-call screen resumes (it claims at once), the ring screen's late stop changes nothing
        lease.release(ring)
        clock.advance(400)
        val b = lease.claim(inCall)
        assertSame(a, b, "the same live code: one manager, one set of sessions")
        lease.release(ring)
        b.manager.reconcile(plan(direct, view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2"), screen = CallScreen.IN_CALL)))
        clock.advance(60_000)
        assertEquals(1, shared.wire.opens.size, "no second offer for the same camera and size")
        assertEquals(emptyList<String>(), shared.wire.closes, "and the running one was not closed")
        assertEquals(1, made)
        assertEquals(0, destroyed)

        // the call ends: the in-call screen pauses; the stream closes once, within the grace
        lease.release(inCall)
        clock.advance(2_999)
        assertEquals(0, destroyed)
        clock.advance(2)
        assertEquals(1, destroyed)
        assertEquals(listOf("live-1"), shared.wire.closes, "live_close exactly once")
        assertEquals(0, shared.manager.count())
        assertTrue(shared.log.lines.any { it.startsWith("stop c-1|big background") })
    }

    @Test
    fun `a screen that goes off, or a rotation, behaves the same way`() {
        val clock = FakeLiveClock()
        var made = 0
        var destroyed = 0
        val lease = LiveLease(make = { ++made }, destroy = { destroyed++ }, clock = clock, graceMs = 3_000)
        val a = Any()
        val b = Any()
        lease.claim(a)
        lease.release(a)
        assertEquals(1, lease.claim(b), "a rotation: the new activity resumes within the grace and gets the same resource")
        clock.advance(10_000)
        assertEquals(0, destroyed)
        lease.release(a) // the old instance's late callback: not the owner
        clock.advance(10_000)
        assertEquals(0, destroyed, "a release by a screen that no longer owns it schedules nothing")
        lease.release(b)
        clock.advance(3_000)
        assertEquals(1, destroyed, "screen off: closed after the grace")
        assertNull(lease.current())
        assertEquals(2, lease.claim(a), "the next ring builds a new one")
        assertEquals(1, destroyed)
    }

    @Test
    fun `a claim during the grace cancels the teardown for good`() {
        val clock = FakeLiveClock()
        var destroyed = 0
        val lease = LiveLease(make = { Any() }, destroy = { destroyed++ }, clock = clock, graceMs = 3_000)
        val a = Any()
        val b = Any()
        lease.claim(a)
        lease.release(a)
        clock.advance(2_900)
        lease.claim(b)
        clock.advance(60_000)
        assertEquals(0, destroyed)
        assertEquals(0, clock.pending(), "no timer is left behind")
    }

    @Test
    fun `a ring that ends closes the stream even if the screen stays up`() {
        val shared = Shared()
        val v = view(profile(cam("c-1")), have = setOf("c-1"))
        shared.manager.reconcile(plan(direct, v))
        shared.playAll()
        // the screen's own rule says the call is over: shown=false closes everything
        shared.manager.reconcile(plan(direct.copy(shown = false), v))
        assertEquals(listOf("live-1"), shared.wire.closes)
        assertEquals(0, shared.manager.count())
    }

    // ---- isolation --------------------------------------------------------------------------------------

    @Test
    fun `an engine that throws never reaches the caller and costs only its own tile`() {
        val log = FakeLiveLog()
        val faults = ArrayList<String>()
        val clock = FakeLiveClock()
        val wire = FakeLiveWire()
        val manager = LiveManager(onFault = { at, e -> faults += "$at ${e.javaClass.simpleName}" }) { key ->
            if (key.cameraId == "c-1") throw IllegalStateException("no decoder") // the engine breaks for the first camera
            LiveSession(key, { l -> FakePeer(clock, l) }, wire, clock, log)
        }
        val v = view(profile(cam("c-1"), cam("c-2")), have = setOf("c-1", "c-2"))
        val memory = LiveMemory(MemStore())
        memory.toggle("c-2", false) // the viewer makes the second one live too
        manager.reconcile(plan(direct, v, memory)) // does not throw
        assertEquals(listOf("create IllegalStateException"), faults)
        assertEquals(setOf(LiveKey("c-2", CameraSize.BIG)), manager.keys(), "the other tile still streams")
        manager.reconcile(plan(direct, v, memory))
        assertEquals(2, faults.size, "the next pass tries again (and reports; LiveFaults de-duplicates for the Journal)")
    }

    @Test
    fun `a session that throws on start is not kept, and one that throws on stop does not stop the others stopping`() {
        val stopped = ArrayList<String>()
        class Boom(val key: LiveKey, val failStart: Boolean, val failStop: Boolean) : LiveRun {
            override var state = LiveSession.State.IDLE
            override val why: LiveWhy? = null
            override val fpsLabel = "--"
            override fun start() {
                if (failStart) throw IllegalArgumentException("start")
                state = LiveSession.State.PLAYING
            }

            override fun stop(reason: StopWhy) {
                stopped += key.cameraId
                if (failStop) throw IllegalStateException("stop")
            }
        }
        val faults = ArrayList<String>()
        val manager = LiveManager(onFault = { at, _ -> faults += at }) { k -> Boom(k, failStart = k.cameraId == "a", failStop = k.cameraId == "b") }
        val ka = LiveKey("a", CameraSize.BIG)
        val kb = LiveKey("b", CameraSize.BIG)
        val kc = LiveKey("c", CameraSize.BIG)
        manager.reconcile(LivePlanResult(setOf(ka, kb, kc), emptyMap(), StopWhy.CLOSED))
        assertEquals(setOf(kb, kc), manager.keys(), "a was never kept")
        assertTrue("start" in faults)
        assertTrue(stopped.contains("a"), "and the half-started one was stopped")
        manager.stopAll(StopWhy.BACKGROUND)
        assertEquals(0, manager.count())
        assertTrue(stopped.containsAll(listOf("b", "c")), "c is stopped although b's stop threw")
        assertTrue("stop" in faults)
    }

    @Test
    fun `live faults are reported once per place and class and the block's failure is swallowed`() {
        val log = FakeLiveLog()
        val faults = LiveFaults(log)
        var ran = 0
        for (i in 1..5) assertFalse(faults.run("sync") { ran++; throw IllegalStateException("x") })
        assertEquals(5, ran)
        assertEquals(listOf("fault sync IllegalStateException"), log.lines, "one Journal line, not five")
        assertFalse(faults.run("sync") { throw UnsatisfiedLinkError("libjingle") }, "an Error is fenced too")
        assertEquals(2, log.lines.size)
        assertFalse(faults.run("chip") { throw IllegalStateException("x") })
        assertEquals(3, log.lines.size, "another place is another line")
        assertTrue(faults.run("sync") { })
        assertEquals(7, faults.value("a", 7) { 7 })
        assertEquals(9, faults.value("b", 9) { throw RuntimeException() })
        // a Journal that throws costs nothing either
        val bad = LiveFaults(object : LiveLog {
            override fun started(key: LiveKey, stream: String?, ttffMs: Long) {}
            override fun failed(key: LiveKey, why: LiveWhy, retry: Int, willRetry: Boolean) {}
            override fun stopped(key: LiveKey, why: StopWhy, playedMs: Long) {}
            override fun fault(at: String, error: String) = throw IllegalStateException("journal")
        })
        assertFalse(bad.run("x") { throw RuntimeException() })
    }

    @Test
    fun `a lease whose resource refuses to close reports it and does not throw into the screen`() {
        val clock = FakeLiveClock()
        val faults = ArrayList<String>()
        val lease = LiveLease(make = { Any() }, destroy = { throw IllegalStateException("x") }, clock = clock, graceMs = 100, onFault = { at, _ -> faults += at })
        val a = Any()
        lease.claim(a)
        lease.release(a)
        clock.advance(200)
        assertEquals(listOf("teardown"), faults)
        assertNull(lease.current())
        assertNotNull(lease.claim(a), "and the next claim builds a fresh one")
    }
}
