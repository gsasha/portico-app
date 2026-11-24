package com.meda.portico.core.netmem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The address prior, against an example home layout: 10.1-10.7.0.0/16 plus the ULA fd00:aaaa:bbbb::/48, passed in as data. */
class EstateShapeTest {
    private val prefixes = (1..7).map { "10.$it.0.0/16" } + "fd00:aaaa:bbbb::/48"
    private val ha = "10.2.0.5"
    private fun shape(v4: String?, v6: List<String> = emptyList(), dial: String? = ha) = EstateShape.of(v4, v6, dial, prefixes)

    @Test
    fun `every estate VLAN is home-shaped, not only HA's own`() {
        for (n in 1..7) assertEquals(Shape.HOME, shape("10.$n.31.97"), "10.$n")
    }

    @Test
    fun `the estate ULA is home-shaped, even with no IPv4`() {
        assertEquals(Shape.HOME, shape(null, listOf("fd00:aaaa:bbbb:2::beef")))
        assertEquals(Shape.HOME, shape("192.168.1.9", listOf("fe80::1", "fd00:aaaa:bbbb:0004:aaaa:bbbb:cccc:dddd")))
    }

    @Test
    fun `most foreign Wi-Fi is a different private block from HA's`() {
        assertEquals(Shape.NOT_ESTATE, shape("192.168.1.23"))
        assertEquals(Shape.NOT_ESTATE, shape("172.20.10.4")) // a phone hotspot
        assertEquals(Shape.NOT_ESTATE, shape("172.31.255.255"))
    }

    @Test
    fun `a public or CGNAT address is not a private LAN at all`() {
        assertEquals(Shape.NOT_ESTATE, shape("8.8.8.8"))
        assertEquals(Shape.NOT_ESTATE, shape("100.64.1.2"))
        assertEquals(Shape.NOT_ESTATE, shape("172.32.0.1")) // just outside 172.16/12
        assertEquals(Shape.NOT_ESTATE, shape("169.254.3.4"))
    }

    @Test
    fun `another address in HA's own block is not told apart - corporate 10 dot x`() {
        assertEquals(Shape.OTHER, shape("10.20.30.40"))
        assertEquals(Shape.OTHER, shape("10.0.0.7"))
        assertEquals(Shape.OTHER, shape("10.8.0.1"))
    }

    @Test
    fun `no HA address to compare with - a foreign private block cannot be called foreign`() {
        assertEquals(Shape.OTHER, shape("192.168.1.23", dial = null))
        assertEquals(Shape.HOME, shape("10.2.1.24", dial = null)) // the home prefixes need no HA address
        assertEquals(Shape.NOT_ESTATE, shape("8.8.8.8", dial = null)) // public is foreign regardless
    }

    @Test
    fun `an IPv6-only network without the estate ULA cannot be told`() {
        assertEquals(Shape.OTHER, shape(null, listOf("2001:db8::1")))
        assertEquals(Shape.OTHER, shape(null))
    }

    @Test
    fun `HA in another family compares against that family`() {
        assertEquals(Shape.NOT_ESTATE, shape("10.9.9.9", dial = "192.168.1.5")) // phone 10/8, HA 192.168/16, not a home prefix
        assertEquals(Shape.OTHER, shape("192.168.7.7", dial = "192.168.1.5"))
    }

    @Test
    fun `junk input is unknown, never a crash, never HOME`() {
        for (bad in listOf("", "1.2.3", "300.1.1.1", "10.2.0.5.7", "010.2.0.5x", "::", "a.b.c.d", " ")) {
            val s = shape(bad)
            assert(s != Shape.HOME) { "$bad -> $s" }
        }
        assertEquals(Shape.OTHER, shape(null, listOf("not-an-address", "::::", "1:2:3")))
    }

    @Test
    fun `custom home prefixes from enrollment replace the default`() {
        assertEquals(Shape.HOME, EstateShape.of("192.168.50.9", emptyList(), ha, listOf("192.168.50.0/24")))
        assertEquals(Shape.OTHER, EstateShape.of("10.2.1.24", emptyList(), ha, listOf("192.168.50.0/24"))) // 10.2 no longer a prefix, same block as HA
        assertEquals(Shape.NOT_ESTATE, EstateShape.of("192.168.51.9", emptyList(), ha, listOf("192.168.50.0/24")))
    }

    @Test
    fun `IPv6 prefix edge cases`() {
        val p = listOf("fd00:aaaa:bbbb::/48")
        assertEquals(Shape.HOME, EstateShape.of(null, listOf("fd00:aaaa:bbbb:ffff:ffff:ffff:ffff:ffff"), ha, p))
        assertEquals(Shape.OTHER, EstateShape.of(null, listOf("fd00:aaaa:bbbc::1"), ha, p))
        assertEquals(Shape.HOME, EstateShape.of(null, listOf("FD00:AAAA:BBBB::1%wlan0"), ha, p)) // case and zone id
    }

    @Test
    fun `the app ships no home prefixes, so by default nothing is HOME`() {
        assertEquals(emptyList<String>(), EstateShape.DEFAULT_HOME_PREFIXES)
        assertEquals(Shape.OTHER, EstateShape.of("10.2.1.24", emptyList(), ha))
        assertEquals(Shape.NOT_ESTATE, EstateShape.of("192.168.1.9", emptyList(), ha))
        assertEquals(Shape.OTHER, EstateShape.of(null, listOf("fd00:aaaa:bbbb::1"), ha))
    }
}
