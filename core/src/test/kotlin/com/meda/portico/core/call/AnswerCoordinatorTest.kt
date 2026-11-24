package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.CallState
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.InMemoryCallStore
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.SocketEvents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app-level answer path: the press gate, the ring, the shared store, the service and the sweep, with a direct executor. */
class AnswerCoordinatorTest {

    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private val active = InMemoryActiveCallStore()
    private val store = InMemoryCallStore()
    private val doors = DoorsCache(InMemoryDoorsCacheStore(listOf(Door("gate", REF, driver = "gate_sip_observer", name = "Gate"))))
    private var mic = true
    private var paused = false
    private var now = 1_000_000L
    private val ringStops = mutableListOf<String>()
    private var serviceStarts = 0
    private var serviceStops = 0

    private val coordinator = AnswerCoordinator(
        newSession = { callId, doorId, hooks, onChange ->
            AnswerSession(callId, doorId, cmds, SerialMedia(media) { it() }, audio, { mic }, sched, active, { now }, hooks, AnswerConfig(), onChange = onChange)
        },
        callStore = store,
        doors = doors,
        sweep = AnswerSweep(cmds, active),
        post = { it() },
        stopRing = { door, call, why -> ringStops += "$door/$call/$why" },
        serviceStart = { serviceStarts++ },
        serviceStop = { serviceStops++ },
        paused = { paused },
        wallClockMs = { now },
        beforeBegin = { beforeHook() },
    )
    private val order = mutableListOf<String>()
    private var beforeHook: () -> Unit = { order += "outgoing-ended" }

    private fun ring(call: String = CALL) {
        val msg = SocketEvents.ringing(REF, call, now)
        Decision.receive(store, msg, now, setOf(REF))
    }

    private fun state(call: String = CALL) = store.get(CallKey(REF, call))?.state

    private fun connect() {
        coordinator.press(REF, CALL)
        val m = media.calls.last()
        m.offerCb!!("v=0 offer")
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to "v=0 answer"))
        m.answerCb!!(true)
        m.listener.onConnected()
    }

    @Test
    fun `a ring-only door offers no answer and a press starts nothing`() {
        doors.replaceAll(listOf(Door("gate", REF, driver = "ha_event", name = "Front door", answerable = false)))
        ring()
        assertTrue(coordinator.isRingOnly(REF))
        assertFalse(coordinator.canAnswer(REF, CALL))
        coordinator.press(REF, CALL)
        assertEquals(0, media.calls.size)
        assertEquals(CallState.RINGING, state())
    }

    @Test
    fun `a ring alone answers nothing`() {
        ring()
        coordinator.onCallEvent(CallEvent.Ringing("gate", REF, CALL))
        coordinator.onLinkReady()
        assertTrue(cmds.sent.isEmpty())
        assertTrue(media.calls.isEmpty())
        assertEquals(0, serviceStarts)
        assertEquals(Phase.IDLE, coordinator.state().snapshot.phase)
        assertTrue(coordinator.canAnswer(REF, CALL), "the button is offered; only a press answers")
    }

    @Test
    fun `a press silences the ring, translates the door ref to its door id and starts the path`() {
        ring()
        coordinator.press(REF, CALL)
        assertEquals(listOf("$REF/$CALL/answer pressed"), ringStops)
        assertEquals(1, media.calls.size)
        assertEquals(1, serviceStarts, "the foreground service starts once the microphone is allowed")
        assertEquals(CallKey(REF, CALL), coordinator.state().key)
        media.calls.single().offerCb!!("v=0 offer")
        assertEquals("gate", (cmds.last("portico/answer").body["door_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `no microphone yet - the service waits and the state asks for the microphone`() {
        mic = false
        ring()
        coordinator.press(REF, CALL)
        assertEquals(Phase.AWAITING_MIC, coordinator.state().snapshot.phase)
        assertEquals(0, serviceStarts)
        assertTrue(media.calls.isEmpty())
        coordinator.onMicResult(true)
        assertEquals(1, serviceStarts)
        assertEquals(Phase.PREPARING, coordinator.state().snapshot.phase)
    }

    @Test
    fun `the store says answered before the claim goes out, so HA's own answered event stays silent`() {
        ring()
        coordinator.press(REF, CALL)
        media.calls.single().offerCb!!("v=0 offer")
        assertEquals(CallState.ANSWERED, state())
        // HA's `answered` for the phone's own claim, then the call's end: neither alerts.
        val answered = Decision.receive(store, SocketEvents.answered(REF, CALL, now), now, setOf(REF))
        assertTrue(answered is Outcome.Silent, answered.toString())
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        val ended = Decision.receive(store, SocketEvents.ended(REF, CALL, now), now, setOf(REF))
        assertTrue(ended is Outcome.Silent, ended.toString())
    }

    @Test
    fun `already_answered - ring dismissed, store updated, no second claim, the button is gone`() {
        ring()
        coordinator.press(REF, CALL)
        media.calls.single().offerCb!!("v=0 offer")
        cmds.last("portico/answer").error("already_answered")
        val snap = coordinator.state().snapshot
        assertEquals("Someone else already answered this call", snap.failure!!.message)
        assertEquals(CallState.ANSWERED, state())
        assertEquals(2, ringStops.size, "stopped at the press and again for the refusal")
        assertEquals(1, serviceStops)
        assertFalse(coordinator.canAnswer(REF, CALL))
        coordinator.press(REF, CALL)
        assertEquals(1, cmds.count("portico/answer"), "no retry")
        assertEquals(1, media.calls.size)
        assertEquals(Phase.ENDED, coordinator.state().snapshot.phase)
    }

    @Test
    fun `a claim that was never sent leaves the call ringing, so its end is still a missed call`() {
        ring()
        cmds.connected = false
        coordinator.press(REF, CALL)
        media.calls.single().offerCb!!("v=0 offer")
        assertEquals(AnswerFailure.Kind.NOT_CONNECTED, coordinator.state().snapshot.failure!!.kind)
        assertEquals(CallState.RINGING, state(), "nobody answered")
        assertEquals(listOf("$REF/$CALL/answer pressed"), ringStops, "the ring stays silenced on this phone")
        assertTrue(coordinator.canAnswer(REF, CALL))
        val ended = Decision.receive(store, SocketEvents.ended(REF, CALL, now), now, setOf(REF))
        assertFalse(ended is Outcome.Silent, ended.toString())
    }

    @Test
    fun `device_busy may be tried again, and only by a new press`() {
        ring()
        coordinator.press(REF, CALL)
        media.calls.single().offerCb!!("v=0 offer")
        cmds.last("portico/answer").error("device_busy")
        assertEquals(AnswerFailure.Kind.DEVICE_BUSY, coordinator.state().snapshot.failure!!.kind)
        sched.advance(60_000)
        assertEquals(1, cmds.count("portico/answer"), "nothing retries by itself")
        assertTrue(coordinator.canAnswer(REF, CALL))
        coordinator.press(REF, CALL)
        assertEquals(2, media.calls.size)
        media.calls.last().offerCb!!("v=0 offer")
        assertEquals(2, cmds.count("portico/answer"))
    }

    @Test
    fun `a call that is not ringing any more is refused without touching the microphone`() {
        ring()
        Decision.receive(store, SocketEvents.answered(REF, CALL, now), now, setOf(REF))
        coordinator.press(REF, CALL)
        assertEquals("Someone else already answered this call", coordinator.state().snapshot.failure!!.message)
        assertTrue(media.calls.isEmpty())
        assertTrue(cmds.sent.isEmpty())
        assertTrue(ringStops.isEmpty())
    }

    @Test
    fun `an ended or missed call is refused as ended`() {
        ring()
        Decision.receive(store, SocketEvents.ended(REF, CALL, now), now, setOf(REF))
        coordinator.press(REF, CALL)
        assertEquals(AnswerFailure.Kind.CALL_ENDED, coordinator.state().snapshot.failure!!.kind)
        assertTrue(media.calls.isEmpty())
    }

    @Test
    fun `a paused phone never answers`() {
        ring()
        paused = true
        assertFalse(coordinator.canAnswer(REF, CALL))
        coordinator.press(REF, CALL)
        assertEquals(AnswerFailure.Kind.PAUSED, coordinator.state().snapshot.failure!!.kind)
        assertTrue(media.calls.isEmpty())
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `a second press for the same live call is the same answer, another call is refused`() {
        ring()
        coordinator.press(REF, CALL)
        coordinator.press(REF, CALL)
        assertEquals(1, media.calls.size)
        ring("gso-other")
        assertFalse(coordinator.canAnswer(REF, "gso-other"))
        coordinator.press(REF, "gso-other")
        assertEquals(1, media.calls.size)
    }

    @Test
    fun `an unknown door is refused by the session without opening the microphone`() {
        Decision.receive(store, SocketEvents.ringing("entry-unknown", CALL, now), now, setOf("entry-unknown"))
        coordinator.press("entry-unknown", CALL)
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, coordinator.state().snapshot.failure!!.kind)
        assertTrue(media.calls.isEmpty())
        assertTrue(ringStops.isEmpty(), "the ring is left alone for a door the cache does not know")
        assertFalse(coordinator.canAnswer("entry-unknown", CALL), "and Answer is not offered")
    }

    @Test
    fun `every end path stops the service and releases the claim`() {
        ring()
        connect()
        assertEquals(0, serviceStops)
        coordinator.hangUp()
        assertEquals(1, serviceStops)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertEquals(1, audio.leaves)
    }

    @Test
    fun `remote end is routed to the live session`() {
        ring()
        connect()
        coordinator.onCallEvent(CallEvent.Ended("gate", REF, CALL, false))
        assertEquals(EndReason.REMOTE_HANGUP, coordinator.state().snapshot.endReason)
        assertFalse("portico/hangup" in cmds.types())
    }

    @Test
    fun `the app stopping releases a live call, and does nothing when there is none`() {
        coordinator.appStopping()
        assertTrue(cmds.sent.isEmpty())
        ring()
        connect()
        coordinator.appStopping()
        assertEquals(EndReason.APP_STOPPED, coordinator.state().snapshot.endReason)
        assertEquals("portico/hangup", cmds.sent.last().type)
    }

    @Test
    fun `after a restart the first authenticated socket releases a call the dead process left`() {
        active.save(ActiveCall("gate", "gso-old", 1))
        coordinator.onLinkReady()
        val h = cmds.sent.single()
        assertEquals("portico/hangup", h.type)
        h.ok()
        assertNull(active.value)
    }

    @Test
    fun `a live call is not swept away by its own socket coming back`() {
        ring()
        connect()
        coordinator.onLinkReady()
        assertEquals(0, cmds.count("portico/hangup"))
        assertEquals(Phase.IN_CALL, coordinator.state().snapshot.phase)
    }

    @Test
    fun `listeners get the current state at once and every change, and can be removed`() {
        val seen = mutableListOf<Phase>()
        val remove = coordinator.addListener { seen += it.snapshot.phase }
        assertEquals(listOf(Phase.IDLE), seen)
        ring()
        coordinator.press(REF, CALL)
        assertTrue(Phase.PREPARING in seen)
        remove()
        val n = seen.size
        media.calls.single().offerCb!!("v=0 offer")
        assertEquals(n, seen.size)
    }

    @Test
    fun `generation counts attempts so a screen asks for the microphone once each`() {
        mic = false
        ring()
        coordinator.press(REF, CALL)
        val g1 = coordinator.state().generation
        coordinator.onMicResult(false)
        coordinator.press(REF, CALL)
        assertTrue(coordinator.state().generation > g1)
    }

    @Test
    fun `the call-ui controls follow the phase`() {
        fun c(p: Phase, f: AnswerFailure? = null, muted: Boolean = false, at: Long? = null) =
            CallUi.controls(AnswerSnapshot(p, if (p == Phase.ENDED) EndReason.FAILED else null, f, muted, at))
        assertTrue(c(Phase.PREPARING).hangUp && c(Phase.PREPARING).hangUpIsCancel)
        assertTrue(c(Phase.IN_CALL, at = 1).hangUp && !c(Phase.IN_CALL, at = 1).hangUpIsCancel)
        assertTrue(c(Phase.IN_CALL, at = 1).mute && c(Phase.IN_CALL, at = 1).timer)
        assertFalse(c(Phase.CONNECTING).mute)
        assertTrue(c(Phase.ENDED, AnswerErrors.micDenied()).openSettings)
        assertTrue(c(Phase.ENDED, AnswerErrors.micDenied()).retry)
        assertFalse(c(Phase.ENDED, AnswerErrors.fromCode("already_answered")).retry)
        assertTrue(c(Phase.ENDED).close && !c(Phase.ENDED).hangUp)
        assertEquals(1L to 5L, CallUi.elapsed(0, 65_000))
        assertEquals(0L to 0L, CallUi.elapsed(10_000, 0))
        val ended = CallUiState(CallKey(REF, CALL), AnswerSnapshot(Phase.ENDED, EndReason.USER_HANGUP), 1)
        assertTrue(CallUi.shouldFinish(ended))
        assertFalse(CallUi.shouldFinish(ended.copy(snapshot = AnswerSnapshot(Phase.ENDED, EndReason.FAILED, AnswerErrors.callEnded()))))
        assertTrue(CallUi.shouldFinish(CallUiState(null, AnswerSnapshot(Phase.IDLE), 0)))
    }

    @Test
    fun `SerialMedia re-posts every callback onto the executor`() {
        val queue = ArrayDeque<() -> Unit>()
        val serial = SerialMedia(media) { queue.addLast(it) }
        val events = mutableListOf<String>()
        val call = serial.open(object : MediaListener {
            override fun onConnected() { events += "c" }
            override fun onDisconnected() { events += "d" }
            override fun onFailed() { events += "f" }
        })
        var sdp: String? = "unset"
        call.createOffer(1) { sdp = it }
        media.calls.single().offerCb!!("v=0")
        media.calls.single().listener.onConnected()
        assertEquals("unset", sdp)
        assertTrue(events.isEmpty())
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals("v=0", sdp)
        assertEquals(listOf("c"), events)
    }

    companion object {
        const val REF = "entry-gate"
        const val CALL = "gso-0123456789"
    }

    @Test
    fun `an answer press ends any outgoing call first and is never refused for it`() {
        ring()
        coordinator.press(REF, CALL)
        assertEquals(listOf("outgoing-ended"), order)
        assertEquals(1, serviceStarts, "the answer began and started its service after the outgoing call was settled")
        assertNull(coordinator.state().snapshot.failure)
    }

    @Test
    fun `a refused press does not touch the outgoing call`() {
        paused = true
        ring()
        coordinator.press(REF, CALL)
        assertTrue(order.isEmpty())
    }

    @Test
    fun `answer after a live outgoing call - the outgoing call finishes first, then the answer begins`() {
        val events = mutableListOf<String>()
        val pcmds = FakeCommands()
        val place = PlaceCoordinator(
            newSession = { req, hooks, onChange -> PlaceSession(req, pcmds, FakePlaceMedia(), FakeAudio(), { true }, FakeScheduler(), { 1L }, hooks, onChange = onChange) },
            post = { it() },
            serviceStart = { events += "place-start" },
            serviceStop = { events += "place-stop" },
            paused = { false },
            otherCallLive = {
                val st = coordinator.state()
                st.key != null && st.snapshot.phase != Phase.IDLE && st.snapshot.phase != Phase.ENDED
            },
        )
        beforeHook = { place.endForDoorAnswer() }
        place.press(PlaceRequest("talk-kitchen", "Kitchen tablet"))
        pcmds.last("portico/content/run").ok(granted())
        assertTrue(place.state().isLive)

        ring()
        assertTrue(coordinator.canAnswer(REF, CALL), "an outgoing call never makes the door's Answer unavailable")
        coordinator.press(REF, CALL)

        val out = place.state()
        assertFalse(out.isLive)
        assertEquals(PlaceFailure.Kind.DOOR_RINGING, out.snapshot.failure!!.kind)
        assertEquals(1, pcmds.count("portico/house/hangup"), "a house call ends with the house hang-up (its id is hso-)")
        assertNull(coordinator.state().snapshot.failure, "the answer was not refused")
        assertEquals(1, serviceStarts)
        assertEquals(listOf("place-start", "place-stop"), events)
        // the place hooks ran before the answer started: its start is the last service change
        assertTrue(coordinator.state().snapshot.phase != Phase.ENDED)
    }
}
