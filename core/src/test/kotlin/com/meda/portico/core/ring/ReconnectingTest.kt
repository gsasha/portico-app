package com.meda.portico.core.ring

import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D-20261118-e0b1: "HA reconnecting" exactly while the app is bringing the link back, and never for a state that will not heal. */
class ReconnectingTest {
    @Test
    fun `a backoff wait is reconnecting, a connect only after the link was up once`() {
        assertTrue(Reconnecting.now(LinkState.WAITING_TO_RETRY, false))
        assertTrue(Reconnecting.now(LinkState.WAITING_TO_RETRY, true))
        assertTrue(Reconnecting.now(LinkState.CONNECTING, true))
        assertFalse(Reconnecting.now(LinkState.CONNECTING, false), "the first connect of a launch is just connecting")
    }

    @Test
    fun `connected, paused, not set up, refused, signed out and stopped are never reconnecting`() {
        for (s in listOf(LinkState.CONNECTED, LinkState.PAUSED, LinkState.NOT_CONFIGURED, LinkState.AUTH_REJECTED, LinkState.SIGNED_OUT, LinkState.URL_REFUSED, LinkState.STOPPED)) {
            for (was in listOf(false, true)) assertFalse(Reconnecting.now(s, was), "$s $was")
        }
    }

    @Test
    fun `the status bar says reconnecting for those states and keeps every other label`() {
        assertEquals(StatusLabel.RECONNECTING, ConnectionModel.of(LinkState.WAITING_TO_RETRY, null).label)
        assertEquals(StatusLabel.RECONNECTING, ConnectionModel.of(LinkState.CONNECTING, null, wasConnected = true).label)
        assertEquals(StatusLabel.CONNECTING, ConnectionModel.of(LinkState.CONNECTING, null).label)
        assertEquals(StatusLabel.PAUSED, ConnectionModel.of(LinkState.PAUSED, null, true).label)
        assertEquals(StatusLabel.TOKEN_REFUSED, ConnectionModel.of(LinkState.AUTH_REJECTED, null, true).label)
        assertEquals(StatusLabel.NOT_SET_UP, ConnectionModel.of(LinkState.NOT_CONFIGURED, null, true).label)
        assertEquals(StatusLabel.TAILNET, ConnectionModel.of(LinkState.CONNECTED, LinkPath.TAILNET, true).label)
        assertEquals(StatusLabel.OFFLINE, ConnectionModel.of(LinkState.STOPPED, null, true).label)
        assertEquals("HA reconnecting", Reconnecting.TEXT)
    }
}
