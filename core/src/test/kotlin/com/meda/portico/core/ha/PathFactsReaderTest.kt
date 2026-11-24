package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-694 P1c: what Android's list of networks means for the hint. */
class PathFactsReaderTest {
    private val cell = NetInfo(wifiOrEthernet = false, cellular = true, vpn = false, validated = true)
    private val wifi = NetInfo(wifiOrEthernet = true, cellular = false, vpn = false, validated = true)
    private val wifiNoNet = wifi.copy(validated = false)
    private val vpnOverCell = NetInfo(wifiOrEthernet = false, cellular = true, vpn = true, validated = true)
    private val vpnOverWifi = NetInfo(wifiOrEthernet = true, cellular = false, vpn = true, validated = true)

    private fun facts(snap: NetSnapshot?, needed: Boolean = true, paused: Boolean = false) = PathFactsReader.of(snap, paused, needed)
    private fun mode(snap: NetSnapshot?, needed: Boolean = true) = PathHint.judge(facts(snap, needed)).mode

    @Test
    fun `an unreadable network fails open - the tailnet is not needed, so direct first`() {
        val f = facts(null)
        assertFalse(f.tailnetNeeded)
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f).mode)
    }

    @Test
    fun `mobile data alone is the tailnet-first case`() {
        assertEquals(PathMode.TAILNET_FIRST, mode(NetSnapshot(listOf(cell), cell)))
    }

    @Test
    fun `Wi-Fi alone, or Wi-Fi and mobile with Wi-Fi the default, is direct first`() {
        assertEquals(PathMode.DIRECT_FIRST, mode(NetSnapshot(listOf(wifi), wifi)))
        assertEquals(PathMode.DIRECT_FIRST, mode(NetSnapshot(listOf(wifi, cell), wifi)))
    }

    @Test
    fun `the house Wi-Fi demoted by a dead WAN - cellular default, Wi-Fi present but not validated - is tailnet first, not mobile only`() {
        val snap = NetSnapshot(listOf(wifiNoNet, cell), cell)
        val h = PathHint.judge(facts(snap))
        assertEquals(PathMode.TAILNET_FIRST, h.mode)
        assertFalse(h.mobileOnly)
    }

    @Test
    fun `a validated Wi-Fi that is merely not the default keeps direct first`() {
        assertEquals(PathMode.DIRECT_FIRST, mode(NetSnapshot(listOf(wifi, cell), cell)))
    }

    @Test
    fun `another app's VPN over mobile data is direct first and not mobile only`() {
        val snap = NetSnapshot(listOf(vpnOverCell, cell), vpnOverCell)
        val h = PathHint.judge(facts(snap))
        assertEquals(PathMode.DIRECT_FIRST, h.mode)
        assertFalse(h.mobileOnly)
    }

    @Test
    fun `a VPN network is not a Wi-Fi for the 'a Wi-Fi exists' facts`() {
        // mobile data is the default and the only non-VPN network: still tailnet-first once the VPN is gone
        val f = facts(NetSnapshot(listOf(vpnOverWifi, cell), cell))
        assertFalse(f.wifiOrEthernetExists)
        assertFalse(f.validatedWifiOrEthernetExists)
    }

    @Test
    fun `no default network`() {
        val f = facts(NetSnapshot(listOf(cell), null))
        assertFalse(f.haveDefaultNet)
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f).mode)
    }

    @Test
    fun `paused and not-needed pass through`() {
        assertTrue(facts(NetSnapshot(listOf(cell), cell), paused = true).paused)
        assertEquals(PathMode.DIRECT_FIRST, mode(NetSnapshot(listOf(cell), cell), needed = false))
    }
}
