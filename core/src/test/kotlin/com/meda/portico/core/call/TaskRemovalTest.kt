package com.meda.portico.core.call

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class TaskRemovalTest {
    private val windows = setOf("com.meda.portico.ring.RingCallActivity")

    @Test fun `closing the ring window's own task does not release a live answer`() {
        assertFalse(TaskRemoval.releasesCalls("com.meda.portico.ring.RingCallActivity", windows))
    }

    @Test fun `swiping the main task away releases the calls`() {
        assertTrue(TaskRemoval.releasesCalls("com.meda.portico.home.HomeActivity", windows))
    }

    @Test fun `an unknown root releases the calls as before`() {
        assertTrue(TaskRemoval.releasesCalls(null, windows))
    }
}
