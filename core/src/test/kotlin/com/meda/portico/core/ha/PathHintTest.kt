package com.meda.portico.core.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-694 P1b: the Stage 1 path-hint rules, row by row, then every combination against an independent oracle. */
class PathHintTest {
    private fun hint(
        paused: Boolean = false,
        needed: Boolean = true,
        net: Boolean = true,
        vpn: Boolean = false,
        cellular: Boolean = false,
        validatedWifi: Boolean = false,
        anyWifi: Boolean = validatedWifi,
    ) = PathHint.judge(PathFacts(paused, needed, net, vpn, cellular, validatedWifi, anyWifi))

    // ---- the rows of design section 5.2 / 6, in order

    @Test
    fun `rule 0 - paused connects nothing, whatever else is true`() {
        assertEquals(PathMode.DIRECT_FIRST, hint(paused = true, cellular = true).mode)
    }

    @Test
    fun `rule 0 - the tailnet not needed or not usable is today's behaviour even on mobile data, and still reports mobile only`() {
        // a stored identity missing / a pending key / direct known to work outside / no tailnet in this APK
        val h = hint(needed = false, cellular = true)
        assertEquals(PathMode.DIRECT_FIRST, h.mode)
        assertTrue(h.mobileOnly, "or a DIRECT_FIRST attempt could never clear the 'direct works outside' flag")
    }

    @Test
    fun `rule 1 - no network at all is direct first and starts nothing`() {
        assertEquals(PathMode.DIRECT_FIRST, hint(net = false).mode)
        assertEquals(PathMode.DIRECT_FIRST, hint(net = false, cellular = true).mode) // an impossible mix falls here
    }

    @Test
    fun `rule 2 - another app's VPN keeps direct first, and is never mobile only`() {
        val h = hint(vpn = true, cellular = true) // a VPN declares its underlying cellular transport
        assertEquals(PathMode.DIRECT_FIRST, h.mode)
        assertFalse(h.mobileOnly)
    }

    @Test
    fun `rule 3 - mobile data and no Wi-Fi anywhere starts the tailnet at once, and is mobile only`() {
        val h = hint(cellular = true)
        assertEquals(PathMode.TAILNET_FIRST, h.mode)
        assertTrue(h.mobileOnly)
    }

    @Test
    fun `rule 3 - the dead-WAN house - the Wi-Fi is present but not validated, cellular is the default`() {
        val h = hint(cellular = true, validatedWifi = false, anyWifi = true)
        assertEquals(PathMode.TAILNET_FIRST, h.mode)
        assertFalse(h.mobileOnly, "a Wi-Fi is associated, so this attempt must not teach 'direct works from outside'")
    }

    @Test
    fun `rule 4 - a validated Wi-Fi alongside mobile data is direct first`() {
        assertEquals(PathMode.DIRECT_FIRST, hint(cellular = true, validatedWifi = true).mode)
    }

    @Test
    fun `rule 4 - a Wi-Fi or Ethernet default is direct first, foreign or not`() {
        assertEquals(PathMode.DIRECT_FIRST, hint(validatedWifi = true).mode)
        assertEquals(PathMode.DIRECT_FIRST, hint().mode) // a default that is neither cellular nor Wi-Fi (USB or Bluetooth tether)
    }

    @Test
    fun `every verdict carries a reason`() {
        for (m in 0 until 128) {
            val h = PathHint.judge(factsOf(m))
            assertTrue(h.why.isNotBlank())
        }
    }

    // ---- every combination of the seven booleans against an oracle written another way

    private fun factsOf(m: Int) = PathFacts(
        paused = m and 1 != 0, tailnetNeeded = m and 2 != 0, haveDefaultNet = m and 4 != 0,
        hasVpnDefault = m and 8 != 0, hasCellularDefault = m and 16 != 0,
        validatedWifiOrEthernetExists = m and 32 != 0, wifiOrEthernetExists = m and 64 != 0,
    )

    @Test
    fun `all 128 combinations agree with the oracle`() {
        for (m in 0 until 128) {
            val f = factsOf(m)
            // TAILNET_FIRST exactly when: not paused, needed, a network, no VPN, cellular, no validated Wi-Fi.
            val tailnetFirst = !f.paused && f.tailnetNeeded && f.haveDefaultNet && !f.hasVpnDefault &&
                f.hasCellularDefault && !f.validatedWifiOrEthernetExists
            val mobileOnly = f.haveDefaultNet && f.hasCellularDefault && !f.wifiOrEthernetExists && !f.hasVpnDefault
            val h = PathHint.judge(f)
            assertEquals(if (tailnetFirst) PathMode.TAILNET_FIRST else PathMode.DIRECT_FIRST, h.mode, f.toString())
            assertEquals(mobileOnly, h.mobileOnly, f.toString())
        }
    }

    @Test
    fun `mobileOnly never holds under a VPN, with a Wi-Fi present or without a network`() {
        for (m in 0 until 128) {
            val f = factsOf(m)
            if (f.hasVpnDefault || f.wifiOrEthernetExists || !f.haveDefaultNet) assertFalse(PathHint.judge(f).mobileOnly, f.toString())
        }
    }

    @Test
    fun `rule 3b - on a Wi-Fi where the tailnet won last time the tailnet starts at once, direct capped`() {
        val f = PathFacts(tailnetNeeded = true, validatedWifiOrEthernetExists = true, tailnetWonHere = true)
        val h = PathHint.judge(f)
        assertEquals(PathMode.TAILNET_FIRST, h.mode)
        assertEquals(PathHint.DIRECT_CAP_MS, h.directCapMs)
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f.copy(tailnetWonHere = false)).mode)
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f.copy(tailnetNeeded = false)).mode, "an unusable tailnet is never started")
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f.copy(paused = true)).mode)
        assertEquals(PathMode.DIRECT_FIRST, PathHint.judge(f.copy(hasVpnDefault = true)).mode)
    }
}
