package com.meda.portico.core.call

import com.meda.portico.core.push.CallKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `CallUiState.isLive` is what the path recheck asks before it would drop the socket. */
class CallUiStateLiveTest {
    private val key = CallKey("door", "c1")

    @Test fun `idle and ended are not live, every phase between is`() {
        for (p in Phase.values()) {
            val live = p != Phase.IDLE && p != Phase.ENDED
            assertEquals(live, CallUiState(key, AnswerSnapshot(p), 1).isLive, p.name)
        }
    }

    @Test fun `no call key is never live`() =
        assertEquals(false, CallUiState(null, AnswerSnapshot(Phase.IN_CALL), 0).isLive)
}
