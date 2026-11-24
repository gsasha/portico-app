package com.meda.portico.core.callwindow

import com.meda.portico.core.call.AnswerFailure
import com.meda.portico.core.call.AnswerSnapshot
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.RingPage
import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.push.CallKey
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.75 / 0.6.76: the view-model of the incoming-call window (a station call): who, the Ring page's content, Answer and Decline. It is the ringing screen only. */
class CallWindowModelTest {
    private val endpoint = "portico_fake:gate-1"
    private fun action(id: String) = ContentAction(id, id, "script_run", null, false, null, true)
    private val open = action("a-open")
    private val side = action("a-side")
    private val cam = ContentCamera("c-1", "Gate", null, true)
    private val cam2 = ContentCamera("c-2", "Yard", null, true)

    private fun profile(caller: ContentCaller?) =
        ContentProfile("", "r1", emptyList(), emptyList(), callers = if (caller == null) emptyMap() else mapOf(endpoint to caller))

    private fun ringPage(p: ContentProfile) = RingPage.content(p, endpoint, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())

    private val idle = CallUiState(null, AnswerSnapshot(Phase.IDLE), 0)
    private fun answer(p: Phase, failure: AnswerFailure? = null) =
        CallUiState(CallKey("ref-gate", "call1"), AnswerSnapshot(p, failure = failure), 1)

    private fun call(phase: StationPhase, canAnswer: Boolean = true) = StationCall("ref-gate", "door-gate", "call1", phase, "Front gate", canAnswer)

    private fun show(s: CallWindowStep): CallWindowView = (s as CallWindowStep.Show).view
    private fun finish(s: CallWindowStep): CloseReason = (s as CallWindowStep.Finish).reason

    private val full = ringPage(profile(ContentCaller("Front gate", "door", listOf(cam, cam2), listOf(open), listOf(side, open))))

    // ---- ringing ----

    @Test fun `ringing shows the door name, Answer and Decline, nothing of a call`() {
        val v = show(CallWindowModel.step(call(StationPhase.RINGING), idle, full, null))
        assertEquals(CallWindowPhase.RINGING, v.phase)
        assertEquals("Front gate", v.doorName)
        assertEquals(CallWindowStatus.INCOMING, v.status)
        assertTrue(v.showAnswer && v.showDecline)
        assertFalse(v.showClose)
        assertTrue(v.keepScreenOn)
    }

    @Test fun `a ring-only door offers Decline alone`() {
        val v = show(CallWindowModel.step(call(StationPhase.RINGING, canAnswer = false), idle, full, null))
        assertFalse(v.showAnswer)
        assertTrue(v.showDecline)
        assertEquals(CallWindowStatus.INCOMING_RING_ONLY, v.status)
    }

    @Test fun `the middle is the Ring page - its cameras and its buttons ring then call, each once, in order`() {
        val v = show(CallWindowModel.step(call(StationPhase.RINGING), idle, full, null))
        assertEquals(listOf("c-1", "c-2"), v.cameras.map { it.id })
        assertEquals(listOf("a-open", "a-side"), v.buttons.map { it.id })
        assertTrue(v.hasMiddle)
    }

    @Test fun `a Ring page with no cameras still shows its buttons, and one with no buttons its cameras`() {
        val noCams = show(CallWindowModel.step(call(StationPhase.RINGING), idle, ringPage(profile(ContentCaller("G", "door", emptyList(), listOf(open), emptyList()))), null))
        assertTrue(noCams.cameras.isEmpty())
        assertEquals(listOf("a-open"), noCams.buttons.map { it.id })
        val noButtons = show(CallWindowModel.step(call(StationPhase.RINGING), idle, ringPage(profile(ContentCaller("G", "door", listOf(cam), emptyList(), emptyList()))), null))
        assertEquals(listOf("c-1"), noButtons.cameras.map { it.id })
        assertTrue(noButtons.buttons.isEmpty())
    }

    @Test fun `no Ring page at all - an empty middle, and the call controls are all still there`() {
        for (page in listOf(null, ringPage(profile(null)), ringPage(profile(ContentCaller("G", "door", emptyList(), emptyList(), emptyList()))))) {
            val v = show(CallWindowModel.step(call(StationPhase.RINGING), idle, page, null))
            assertFalse(v.hasMiddle)
            assertTrue(v.showAnswer && v.showDecline)
        }
    }

    // ---- answered: the call is Home's, the window is done ----

    @Test fun `once the call is no longer ringing the window finishes as answered - connecting, microphone, in call`() {
        for ((phase, ans) in listOf(
            StationPhase.CONNECTING to answer(Phase.CONNECTING),
            StationPhase.CONNECTING to answer(Phase.AWAITING_MIC),
            StationPhase.IN_CALL to answer(Phase.IN_CALL),
        )) {
            assertEquals(CloseReason.ANSWERED, finish(CallWindowModel.step(call(phase, canAnswer = false), ans, full, CallWindowPhase.RINGING)), "$phase")
        }
        // even a window that never showed anything (a stale launch on a call already answered) has nothing to show
        assertEquals(CloseReason.ANSWERED, finish(CallWindowModel.step(call(StationPhase.IN_CALL, false), answer(Phase.IN_CALL), full, null)))
    }

    @Test fun `the window has only the ringing and failed phases and no call statuses`() {
        assertEquals(listOf("RINGING", "FAILED"), CallWindowPhase.values().map { it.name })
        assertEquals(listOf("INCOMING", "INCOMING_RING_ONLY", "FAILED"), CallWindowStatus.values().map { it.name })
        assertEquals(listOf("RING_ENDED", "ANSWERED", "NO_CALL", "DECLINED", "BACK", "FAILURE_SHOWN", "CLOSED_BY_USER", "SYSTEM"), CloseReason.values().map { it.name })
    }

    // ---- the end: the window closes itself, with the reason ----

    @Test fun `the ring ending - answered elsewhere, expired, declined elsewhere - closes the window as ring ended`() {
        assertEquals(CloseReason.RING_ENDED, finish(CallWindowModel.step(null, idle, null, CallWindowPhase.RINGING)))
        assertEquals(CloseReason.RING_ENDED, finish(CallWindowModel.step(null, idle, null, CallWindowPhase.FAILED)))
    }

    private fun failure() = AnswerFailure(AnswerFailure.Kind.DEVICE_BUSY, "busy", "The door is busy.", dismissRing = false, retryable = true)

    @Test fun `a launch with no call to show closes at once`() {
        assertEquals(CloseReason.NO_CALL, finish(CallWindowModel.step(null, idle, null, null)))
        assertEquals(CloseReason.NO_CALL, finish(CallWindowModel.step(null, answer(Phase.ENDED, failure = failure()), null, null)), "a stale failure from an earlier call is not shown to a window that never saw a call")
    }

    @Test fun `an answer that failed while the window is up keeps it up with the failure's words and a Close button`() {
        val v = show(CallWindowModel.step(null, answer(Phase.ENDED, failure = failure()), full, CallWindowPhase.RINGING))
        assertEquals(CallWindowPhase.FAILED, v.phase)
        assertEquals("The door is busy.", v.failureMessage)
        assertTrue(v.showClose)
        assertFalse(v.showAnswer || v.showDecline || v.keepScreenOn)
        // and it stays that way on the next look (the activity closes it after FAILED_LINGER_MS)
        assertEquals(CallWindowPhase.FAILED, show(CallWindowModel.step(null, answer(Phase.ENDED, failure = failure()), full, CallWindowPhase.FAILED)).phase)
        assertTrue(CallWindowModel.FAILED_LINGER_MS in 3_000L..15_000L)
    }

    @Test fun `a second ring while a window is up replaces what it shows - the window follows the call it is given`() {
        val other = StationCall("ref-side", "door-side", "call2", StationPhase.RINGING, "Side door", true)
        val v = show(CallWindowModel.step(other, idle, null, CallWindowPhase.RINGING))
        assertEquals("call2", v.callId)
        assertEquals("Side door", v.doorName)
    }

    @Test fun `the journal lines`() {
        assertEquals("ring screen: call window", CallWindowModel.OPENED_LINE)
        assertEquals("ring screen: call window closed (ring ended)", CallWindowModel.closedLine(CloseReason.RING_ENDED))
        assertEquals("ring screen: call window closed (declined)", CallWindowModel.closedLine(CloseReason.DECLINED))
        assertEquals("ring screen: call window closed (answered)", CallWindowModel.closedLine(CloseReason.ANSWERED))
        assertEquals("ring screen: answered -> home", CallWindowModel.ANSWERED_HOME_LINE)
    }
}
