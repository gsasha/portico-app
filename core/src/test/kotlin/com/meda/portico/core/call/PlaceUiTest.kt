package com.meda.portico.core.call

import com.meda.portico.core.call.PlaceUi.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceUiTest {
    private fun snap(phase: PlacePhase, reason: EndReason? = null, failure: PlaceFailure? = null, muted: Boolean = false, at: Long? = null) =
        PlaceSnapshot(phase, reason, failure, muted, at)

    @Test
    fun `each phase has its status`() {
        assertEquals(Status.NONE, PlaceUi.status(snap(PlacePhase.IDLE)))
        assertEquals(Status.NEED_MIC, PlaceUi.status(snap(PlacePhase.AWAITING_MIC)))
        assertEquals(Status.PLACING, PlaceUi.status(snap(PlacePhase.PLACING)))
        assertEquals(Status.RINGING_OUT, PlaceUi.status(snap(PlacePhase.RINGING)))
        assertEquals(Status.CONNECTING, PlaceUi.status(snap(PlacePhase.CONNECTING)))
        assertEquals(Status.IN_CALL, PlaceUi.status(snap(PlacePhase.IN_CALL)))
        assertEquals(Status.IN_CALL_MUTED, PlaceUi.status(snap(PlacePhase.IN_CALL, muted = true)))
        assertEquals(Status.FAILURE, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.FAILED, PlaceErrors.timeout())))
        assertEquals(Status.ENDED_USER, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.USER_HANGUP)))
        assertEquals(Status.ENDED_REMOTE, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.REMOTE_HANGUP)))
        assertEquals(Status.ENDED_NETWORK, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.NETWORK_LOST)))
        assertEquals(Status.ENDED_CAP, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.TIME_CAP)))
        assertEquals(Status.ENDED_STOPPED, PlaceUi.status(snap(PlacePhase.ENDED, EndReason.APP_STOPPED)))
    }

    @Test
    fun `the first control reads Cancel until the far end has answered, and always works`() {
        for (p in listOf(PlacePhase.AWAITING_MIC, PlacePhase.PLACING, PlacePhase.RINGING)) {
            val c = PlaceUi.controls(snap(p))
            assertTrue(c.hangUp && c.hangUpIsCancel, p.name)
            assertTrue(c.keepScreenOn && !c.close)
        }
        for (p in listOf(PlacePhase.CONNECTING, PlacePhase.IN_CALL)) {
            val c = PlaceUi.controls(snap(p))
            assertTrue(c.hangUp && !c.hangUpIsCancel, p.name)
        }
        assertTrue(PlaceUi.controls(snap(PlacePhase.IN_CALL)).mute)
        assertFalse(PlaceUi.controls(snap(PlacePhase.RINGING)).mute)
        assertTrue(PlaceUi.controls(snap(PlacePhase.IN_CALL, at = 1L)).timer)
    }

    @Test
    fun `retry and settings appear only for the failures they fit`() {
        val busy = PlaceUi.controls(snap(PlacePhase.ENDED, EndReason.FAILED, PlaceErrors.fromCode("busy", "X")))
        assertTrue(busy.retry && busy.close && !busy.hangUp && !busy.openSettings)
        val declined = PlaceUi.controls(snap(PlacePhase.ENDED, EndReason.FAILED, PlaceErrors.fromCode("declined", "X")))
        assertFalse(declined.retry)
        assertTrue(declined.close)
        val mic = PlaceUi.controls(snap(PlacePhase.ENDED, EndReason.FAILED, PlaceErrors.micDenied()))
        assertTrue(mic.retry && mic.openSettings)
        assertFalse(PlaceUi.controls(snap(PlacePhase.ENDED, EndReason.USER_HANGUP)).retry)
    }

    @Test
    fun `the screen leaves after the user's own hang-up or when there is nothing`() {
        val none = PlaceUiState(null, snap(PlacePhase.IDLE), 0)
        assertTrue(PlaceUi.shouldFinish(none))
        val r = PlaceRequest("a", "A")
        assertTrue(PlaceUi.shouldFinish(PlaceUiState(r, snap(PlacePhase.ENDED, EndReason.USER_HANGUP), 1)))
        assertFalse(PlaceUi.shouldFinish(PlaceUiState(r, snap(PlacePhase.ENDED, EndReason.FAILED, PlaceErrors.timeout()), 1)))
        assertFalse(PlaceUi.shouldFinish(PlaceUiState(r, snap(PlacePhase.RINGING), 1)))
    }
}
