package com.meda.portico.core.call

import com.meda.portico.core.push.CallKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndAutoCloseTest {
    private var now = 1_000L
    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    private val closed = mutableListOf<Pair<Long, String?>>()
    private val sut = EndAutoClose(
        schedule = { d, r -> val t = Task(now + d, r); tasks += t; EndAutoClose.Cancel { t.cancelled = true } },
        nowMs = { now },
        close = { ms, id -> closed += ms to id },
    )

    private fun advance(ms: Long) {
        now += ms
        tasks.filter { !it.cancelled && it.at <= now }.forEach { it.cancelled = true; it.run() }
    }

    private fun ended(reason: EndReason, gen: Int = 1, callId: String = "c1", failure: AnswerFailure? = null) =
        CallUiState(CallKey("door", callId), AnswerSnapshot(Phase.ENDED, reason, failure), gen)

    private val live get() = CallUiState(CallKey("door", "c2"), AnswerSnapshot(Phase.IN_CALL), 2)

    @Test fun `remote hangup closes after three seconds and reports the wait`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(2_999)
        assertTrue(closed.isEmpty())
        advance(1)
        assertEquals(listOf(3_000L to "c1"), closed)
    }

    @Test fun `repeated renders of the same end do not restart the countdown`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(2_000)
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(1_000)
        assertEquals(1, closed.size)
    }

    @Test fun `other ends never auto close`() {
        for (r in listOf(EndReason.USER_HANGUP, EndReason.NETWORK_LOST, EndReason.TIME_CAP, EndReason.APP_STOPPED)) {
            sut.onState(ended(r), false)
        }
        advance(10_000)
        assertTrue(closed.isEmpty())
        assertFalse(sut.touched())
    }

    @Test fun `a remote end with a failure stays`() {
        val f = AnswerFailure(AnswerFailure.Kind.MIC_DENIED, "x", "x", false, false)
        sut.onState(ended(EndReason.REMOTE_HANGUP, failure = f), false)
        advance(10_000)
        assertTrue(closed.isEmpty())
    }

    @Test fun `a new call before the timer cancels it`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(1_000)
        sut.onState(live, false)
        advance(10_000)
        assertTrue(closed.isEmpty())
    }

    @Test fun `a held call keeps the screen`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), true)
        advance(10_000)
        assertTrue(closed.isEmpty())
        // and one that appears during the countdown cancels it
        sut.onState(ended(EndReason.REMOTE_HANGUP, gen = 3), false)
        sut.onState(ended(EndReason.REMOTE_HANGUP, gen = 3), true)
        advance(10_000)
        assertTrue(closed.isEmpty())
    }

    @Test fun `a touch closes at once, once`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(500)
        assertTrue(sut.touched())
        assertEquals(listOf(500L to "c1"), closed)
        assertFalse(sut.touched())
        advance(10_000)
        assertEquals(1, closed.size)
    }

    @Test fun `a touch with nothing pending does nothing`() {
        assertFalse(sut.touched())
        sut.onState(live, false)
        assertFalse(sut.touched())
    }

    @Test fun `a second call's own remote end gets its own countdown`() {
        sut.onState(ended(EndReason.REMOTE_HANGUP), false)
        advance(1_000)
        sut.onState(ended(EndReason.REMOTE_HANGUP, gen = 2, callId = "c2"), false)
        advance(2_999)
        assertTrue(closed.isEmpty())
        advance(1)
        assertEquals(listOf(3_000L to "c2"), closed)
    }
}
