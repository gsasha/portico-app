package com.meda.portico.core.ring

import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionStatusTest {

    private fun of(link: LinkState, path: LinkPath? = null) = ConnectionModel.of(link, path)

    @Test
    fun `green only when connected, over either route`() {
        assertEquals(ConnectionStatus(Health.OK, StatusLabel.DIRECT), of(LinkState.CONNECTED, LinkPath.DIRECT))
        assertEquals(ConnectionStatus(Health.OK, StatusLabel.TAILNET), of(LinkState.CONNECTED, LinkPath.TAILNET))
        assertEquals(ConnectionStatus(Health.OK, StatusLabel.CONNECTED), of(LinkState.CONNECTED, null))
        for (s in LinkState.entries.filter { it != LinkState.CONNECTED }) {
            for (p in listOf(null, LinkPath.DIRECT, LinkPath.TAILNET)) {
                assertTrue(of(s, p).health != Health.OK, "$s with $p must not be green")
            }
        }
    }

    @Test
    fun `the tailnet is not a degraded state`() {
        assertEquals(of(LinkState.CONNECTED, LinkPath.DIRECT).health, of(LinkState.CONNECTED, LinkPath.TAILNET).health)
    }

    @Test
    fun `a stale path never makes a closed socket look connected or routed`() {
        // linkPath is cleared when the socket closes; if it were not, a left-over TAILNET must not label an offline link.
        for (s in listOf(LinkState.WAITING_TO_RETRY, LinkState.STOPPED, LinkState.CONNECTING, LinkState.AUTH_REJECTED)) {
            val st = of(s, LinkPath.TAILNET)
            assertTrue(st.label != StatusLabel.TAILNET && st.label != StatusLabel.DIRECT, "$s shows ${st.label}")
        }
    }

    @Test
    fun `paused is its own sign and word, amber, never a fault`() {
        val p = of(LinkState.PAUSED)
        assertEquals(StatusLabel.PAUSED, p.label)
        assertEquals(Health.WARN, p.health)
        assertEquals("⏸", p.glyph)
    }

    @Test
    fun `connecting is amber, retrying and the states that will not heal alone are red`() {
        assertEquals(Health.WARN, of(LinkState.CONNECTING).health)
        for (s in listOf(LinkState.WAITING_TO_RETRY, LinkState.STOPPED, LinkState.NOT_CONFIGURED, LinkState.AUTH_REJECTED, LinkState.URL_REFUSED)) {
            assertEquals(Health.BAD, of(s).health, s.name)
        }
        assertEquals(StatusLabel.NOT_SET_UP, of(LinkState.NOT_CONFIGURED).label)
        assertEquals(StatusLabel.TOKEN_REFUSED, of(LinkState.AUTH_REJECTED).label)
        assertEquals(StatusLabel.ADDRESS_REFUSED, of(LinkState.URL_REFUSED).label)
        assertEquals(StatusLabel.OFFLINE, of(LinkState.WAITING_TO_RETRY).label)
    }

    @Test
    fun `the dot is filled only for green`() {
        assertEquals("●", of(LinkState.CONNECTED, LinkPath.DIRECT).glyph)
        for (s in LinkState.entries.filter { it != LinkState.CONNECTED && it != LinkState.PAUSED }) {
            assertEquals("○", of(s).glyph, s.name)
        }
        assertFalse(of(LinkState.PAUSED).glyph == "●")
    }

    @Test
    fun `every link state has a status`() {
        for (s in LinkState.entries) of(s) // a new LinkState must be mapped (the when is exhaustive)
    }
}
