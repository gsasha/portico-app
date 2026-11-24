package com.meda.portico.core.netmem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkIdentityTest {
    private val home = NetworkIdentity(ssid = "HomeNet", ipv4Subnet = "192.168.0.0/16", gateway = "192.168.1.1")

    @Test
    fun `nothing known is no key`() {
        assertNull(NetworkIdentity().key())
        assertNull(NetworkIdentity(ssid = " ", ipv4Subnet = "").key())
    }

    @Test
    fun `the key is 16 hex characters and stable`() {
        assertTrue(Regex("[0-9a-f]{16}").matches(home.key()!!))
        assertEquals(home.key(), home.copy().key())
    }

    @Test
    fun `a key made with the SSID differs from one made without it - a later grant starts over`() {
        assertNotEquals(home.key(), home.copy(ssid = null).key())
    }

    @Test
    fun `the same range at another SSID is another network, and the same SSID in another range is too`() {
        val a = NetworkIdentity("Hotel", "192.168.0.0/24", "192.168.0.1")
        assertNotEquals(a.key(), a.copy(ssid = "Cafe").key())
        assertNotEquals(a.key(), a.copy(ipv4Subnet = "192.168.1.0/24").key())
    }

    @Test
    fun `a field cannot be moved into another to forge a collision`() {
        assertNotEquals(NetworkIdentity(ssid = "a", gateway = "b").key(), NetworkIdentity(ssid = "b", gateway = "a").key())
        assertNotEquals(NetworkIdentity(ssid = "x|y").key(), NetworkIdentity(ssid = "x", ipv4Subnet = "y").key())
    }

    @Test
    fun `toString never shows an address or a name`() {
        val s = home.toString()
        assertFalse(s.contains("HomeNet") || s.contains("10.2") )
    }
}
