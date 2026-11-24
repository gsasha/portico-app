package com.meda.portico.core.callbar

import com.meda.portico.core.call.AnswerSnapshot
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.call.PlacePhase
import com.meda.portico.core.call.PlaceRequest
import com.meda.portico.core.call.PlaceSnapshot
import com.meda.portico.core.call.PlaceUiState
import com.meda.portico.core.call.RouteChoice
import com.meda.portico.core.call.RouteDevice
import com.meda.portico.core.call.TalkPhase
import com.meda.portico.core.call.TalkRequest
import com.meda.portico.core.call.TalkSnapshot
import com.meda.portico.core.call.TalkUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallBarStateTest {
    private fun talk(p: TalkPhase, at: Long? = null) = TalkUiState(TalkRequest("a1", "Gate", "door1"), TalkSnapshot(p, connectedAtMs = at), 1)
    private fun place(p: PlacePhase, at: Long? = null) = PlaceUiState(PlaceRequest("c1", "Kitchen panel"), PlaceSnapshot(p, connectedAtMs = at), 1)
    private fun answer(p: Phase, at: Long? = null) = CallUiState(CallKey("door1", "call1"), AnswerSnapshot(p, connectedAtMs = at), 1)

    @Test fun talkPhases() {
        assertEquals(CallPhase.IDLE, CallBars.fromTalk(talk(TalkPhase.IDLE)).phase)
        for (p in listOf(TalkPhase.AWAITING_MIC, TalkPhase.STARTING, TalkPhase.CONNECTING)) assertEquals(CallPhase.DIALING, CallBars.fromTalk(talk(p)).phase)
        assertEquals(CallPhase.IN_CALL, CallBars.fromTalk(talk(TalkPhase.IN_CALL, 5)).phase)
        assertEquals(CallPhase.ENDED, CallBars.fromTalk(talk(TalkPhase.ENDED)).phase)
    }

    @Test fun nameIsTheServersNotAnId() {
        assertEquals("Gate", CallBars.fromTalk(talk(TalkPhase.CONNECTING)).name)
        assertEquals("Kitchen panel", CallBars.fromPlace(place(PlacePhase.RINGING)).name)
        assertEquals("Front door", CallBars.fromAnswer(answer(Phase.CONNECTING), "Front door").name)
    }

    @Test fun placePhases() {
        for (p in listOf(PlacePhase.AWAITING_MIC, PlacePhase.PLACING, PlacePhase.RINGING, PlacePhase.CONNECTING)) assertEquals(CallPhase.DIALING, CallBars.fromPlace(place(p)).phase)
        assertEquals(CallPhase.IN_CALL, CallBars.fromPlace(place(PlacePhase.IN_CALL, 9)).phase)
        assertEquals(CallPhase.ENDED, CallBars.fromPlace(place(PlacePhase.ENDED)).phase)
        assertEquals(CallPhase.IDLE, CallBars.fromPlace(place(PlacePhase.IDLE)).phase)
    }

    @Test fun answerPhases() {
        for (p in listOf(Phase.AWAITING_MIC, Phase.PREPARING, Phase.CLAIMING, Phase.NEGOTIATING, Phase.CONNECTING)) assertEquals(CallPhase.DIALING, CallBars.fromAnswer(answer(p), "D").phase)
        assertEquals(CallPhase.IN_CALL, CallBars.fromAnswer(answer(Phase.IN_CALL, 3), "D").phase)
        assertEquals(CallPhase.ENDED, CallBars.fromAnswer(answer(Phase.ENDED), "D").phase)
        assertEquals(CallPhase.IDLE, CallBars.fromAnswer(CallUiState(null, AnswerSnapshot(Phase.CONNECTING), 0), "D").phase)
    }

    @Test fun activeOnlyWhileDialingOrInCall() {
        assertTrue(CallBarState(CallPhase.DIALING, "x", null).isActive)
        assertTrue(CallBarState(CallPhase.IN_CALL, "x", 1).isActive)
        assertFalse(CallBarState(CallPhase.ENDED, "x", 1).isActive)
        assertFalse(CallBarState.IDLE.isActive)
    }

    @Test fun pickTakesTheLiveOneAndNeverAnEndedOne() {
        val a = CallBars.fromAnswer(answer(Phase.IN_CALL, 1), "Front door")
        val p = CallBars.fromPlace(place(PlacePhase.ENDED))
        val t = CallBars.fromTalk(talk(TalkPhase.IDLE))
        assertEquals("Front door", CallBars.pick(a, p, t).name)
        assertEquals(CallBarState.IDLE, CallBars.pick(CallBarState.IDLE, p, t))
        val t2 = CallBars.fromTalk(talk(TalkPhase.CONNECTING))
        assertEquals("Gate", CallBars.pick(CallBarState.IDLE, p, t2).name)
    }

    @Test fun durationFormat() {
        assertEquals("00:00", CallClock.format(0))
        assertEquals("00:59", CallClock.format(59_999))
        assertEquals("01:00", CallClock.format(60_000))
        assertEquals("59:59", CallClock.format(3_599_000))
        assertEquals("1:00:00", CallClock.format(3_600_000))
        assertEquals("2:03:04", CallClock.format((2 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("00:00", CallClock.format(-5_000)) // a clock stepped back
    }

    @Test fun durationDoesNotRunWhileDialing() {
        val dialing = CallBarState(CallPhase.DIALING, "Gate", null)
        assertEquals("00:00", dialing.duration(1_000_000))
        val live = CallBarState(CallPhase.IN_CALL, "Gate", 1_000_000)
        assertEquals("01:05", live.duration(1_065_000))
    }

    @Test fun routesOfferBluetoothOnlyWhenConnected() {
        assertEquals(listOf(CallRoute.SPEAKER, CallRoute.PHONE), CallRoutes.available(hasEarpiece = true, bluetoothConnected = false))
        assertEquals(listOf(CallRoute.SPEAKER, CallRoute.BLUETOOTH, CallRoute.PHONE), CallRoutes.available(true, true))
    }

    @Test fun tabletHasNothingToChooseUnlessBluetooth() {
        assertFalse(CallRoutes.chooserVisible(CallRoutes.available(hasEarpiece = false, bluetoothConnected = false)))
        assertTrue(CallRoutes.chooserVisible(CallRoutes.available(hasEarpiece = false, bluetoothConnected = true)))
    }

    @Test fun tapsStoreTheMatchingChoice() {
        assertEquals(RouteChoice.SPEAKER, CallRoutes.choiceFor(CallRoute.SPEAKER))
        assertEquals(RouteChoice.BLUETOOTH, CallRoutes.choiceFor(CallRoute.BLUETOOTH))
        assertEquals(RouteChoice.EARPIECE, CallRoutes.choiceFor(CallRoute.PHONE))
    }

    @Test fun litWordIsWhereTheSoundGoes() {
        assertEquals(CallRoute.SPEAKER, CallRoutes.lit(RouteDevice.SPEAKER, false))
        assertEquals(CallRoute.PHONE, CallRoutes.lit(RouteDevice.EARPIECE, false))
        assertEquals(CallRoute.BLUETOOTH, CallRoutes.lit(RouteDevice.HEADSET, true))
        assertNull(CallRoutes.lit(RouteDevice.HEADSET, false))
    }
}
