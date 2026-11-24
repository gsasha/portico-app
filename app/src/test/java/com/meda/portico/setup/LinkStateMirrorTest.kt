package com.meda.portico.setup

import com.meda.portico.core.ring.LinkState
import com.meda.portico.signaling.SignalingClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Setup maps SignalingClient.State to :core's LinkState by name; a renamed state must fail here, not show "stopped". */
class LinkStateMirrorTest {
    @Test
    fun `every socket state has a LinkState of the same name`() {
        assertEquals(SignalingClient.State.values().map { it.name }.toSet(), LinkState.values().map { it.name }.toSet())
    }
}
