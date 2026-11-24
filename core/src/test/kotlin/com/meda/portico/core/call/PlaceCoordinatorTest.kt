package com.meda.portico.core.call

import com.meda.portico.core.call.PlaceFailure.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceCoordinatorTest {
    private val cmds = FakeCommands()
    private val media = FakePlaceMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private var paused = false
    private var otherCall = false
    private var starts = 0
    private var stops = 0
    private val states = mutableListOf<PlaceUiState>()

    private val coordinator = PlaceCoordinator(
        newSession = { req, hooks, onChange -> PlaceSession(req, cmds, media, audio, { true }, sched, { 1L }, hooks, onChange = onChange) },
        post = { it() },
        serviceStart = { starts++ },
        serviceStop = { stops++ },
        paused = { paused },
        otherCallLive = { otherCall },
    )

    private val a = PlaceRequest("talk-kitchen", "Kitchen tablet")
    private val b = PlaceRequest("call-bob", "Bob's phone")

    init {
        coordinator.addListener { states += it }
    }

    @Test
    fun `nothing happens until a press - every other input is silent`() {
        coordinator.onMicResult(true)
        coordinator.onHouseCall(HouseCallEvent("hso-1", "answered", null))
        coordinator.onDoorRinging()
        coordinator.onLinkLost()
        coordinator.onLinkReady()
        coordinator.setMuted(true)
        coordinator.hangUp()
        coordinator.retry()
        coordinator.appStopping()
        assertTrue(cmds.sent.isEmpty() && media.legs.isEmpty())
        assertEquals(0, starts)
        assertEquals(PlacePhase.IDLE, coordinator.state().snapshot.phase)
    }

    @Test
    fun `a press places the call and starts the service, the end stops it`() {
        coordinator.press(a)
        assertEquals(PlacePhase.PLACING, coordinator.state().snapshot.phase)
        assertEquals(1, starts)
        assertEquals("portico/content/run", cmds.sent.single().type)
        cmds.last("portico/content/run").error("busy")
        assertEquals(1, stops)
        assertEquals(Kind.BUSY, coordinator.state().snapshot.failure!!.kind)
        assertFalse(coordinator.state().isLive)
    }

    @Test
    fun `the same button twice is one call`() {
        coordinator.press(a)
        coordinator.press(a)
        assertEquals(1, cmds.count("portico/content/run"))
    }

    @Test
    fun `another button while a call is live is refused and the live call is untouched`() {
        coordinator.press(a)
        cmds.last("portico/content/run").ok(granted())
        coordinator.press(b)
        assertEquals(1, cmds.count("portico/content/run"))
        val st = coordinator.state()
        assertEquals(Kind.ANOTHER_CALL, st.snapshot.failure!!.kind)
        assertEquals(b, st.request)
    }

    @Test
    fun `a door call being answered refuses a call button`() {
        otherCall = true
        coordinator.press(a)
        assertEquals(Kind.ANOTHER_CALL, coordinator.state().snapshot.failure!!.kind)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `paused refuses and sends nothing`() {
        paused = true
        coordinator.press(a)
        assertEquals(Kind.PAUSED, coordinator.state().snapshot.failure!!.kind)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `retry is a new press of the same button after a retryable failure`() {
        coordinator.press(a)
        cmds.last("portico/content/run").error("no_answer")
        assertTrue(PlaceUi.controls(coordinator.state().snapshot).retry)
        val g = coordinator.state().generation
        coordinator.retry()
        assertEquals(2, cmds.count("portico/content/run"))
        assertTrue(coordinator.state().generation > g)
        assertEquals(a, coordinator.state().request)
    }

    @Test
    fun `events and hang-up reach the live session`() {
        coordinator.press(a)
        cmds.last("portico/content/run").ok(granted())
        coordinator.onHouseCall(HouseCallEvent("hso-1-a", "answered", null))
        assertEquals(PlacePhase.CONNECTING, coordinator.state().snapshot.phase)
        media.legs.single().listener.onConnected()
        coordinator.setMuted(true)
        assertTrue(coordinator.state().snapshot.muted)
        coordinator.hangUp()
        assertEquals(PlacePhase.ENDED, coordinator.state().snapshot.phase)
        assertEquals(1, cmds.count("portico/house/hangup"))
        assertEquals(1, stops)
    }

    @Test
    fun `listeners hear every change and a failing listener does not break the rest`() {
        coordinator.addListener { throw IllegalStateException("boom") }
        coordinator.press(a)
        assertTrue(states.any { it.snapshot.phase == PlacePhase.PLACING })
    }

    // ---- a door outranks a call button ----------------------------------------------------------------

    @Test
    fun `a door ring ends a live call, stops the service, and the screen shows why`() {
        coordinator.press(a)
        cmds.last("portico/content/run").ok(granted())
        coordinator.onDoorRinging()
        assertEquals(Kind.DOOR_RINGING, coordinator.state().snapshot.failure!!.kind)
        assertFalse(coordinator.state().isLive)
        assertEquals(1, stops)
        assertEquals(1, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `endForDoorAnswer ends the call before the answer begins, with nothing live it does nothing`() {
        coordinator.endForDoorAnswer()
        assertEquals(PlacePhase.IDLE, coordinator.state().snapshot.phase)
        coordinator.press(a)
        cmds.last("portico/content/run").ok(granted())
        coordinator.endForDoorAnswer()
        assertEquals(Kind.DOOR_RINGING, coordinator.state().snapshot.failure!!.kind)
        assertEquals(1, stops)
        assertEquals(1, cmds.count("portico/house/hangup"))
    }

    @Test
    fun `an answered door call still blocks a new press`() {
        otherCall = true
        coordinator.press(a)
        assertEquals(Kind.ANOTHER_CALL, coordinator.state().snapshot.failure!!.kind)
        assertTrue(cmds.sent.isEmpty())
    }
}
