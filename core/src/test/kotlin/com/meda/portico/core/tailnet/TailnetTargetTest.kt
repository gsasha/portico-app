package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TailnetTargetTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "192.168.1.5:443", "10.255.255.255:1", "172.16.0.1:8123", "172.31.9.9:65535", "192.168.1.10:8123",
            "100.64.0.1:443", "100.127.255.254:443", "[fd7a:115c:a1e0::5]:443", "[fc00::1]:80",
        ],
    )
    fun `private and tailnet addresses with a port are accepted`(v: String) {
        assertEquals(v, TailnetTarget.parseHaDial(v))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "192.168.1.5", "192.168.1.5:", ":443", "192.168.1.5:0", "192.168.1.5:65536", "192.168.1.5:0443", "192.168.1.5:44a",
            // a name needs the DNS that is missing away from home
            "haos.example.org:443", "homeassistant:8123", "ha.local:8123",
            // public, loopback, link-local
            "8.8.8.8:443", "100.128.0.1:443", "172.32.0.1:443", "127.0.0.1:443", "169.254.1.1:443",
            "[::1]:443", "[fe80::1]:443", "[2001:db8::1]:443", "[::ffff:192.168.1.5]:443",
            // non-canonical or ambiguous forms
            "0192.168.1.5:443", "10.2.5:443", "0x0a.2.0.5:443", "192.168.1.5:443/path", "http://192.168.1.5:443",
            "user@192.168.1.5:443", "fd7a::1:443", "[fd7a::1]", "[fd7a::1]443", "192.168.1.5:443:1",
        ],
    )
    fun `anything else is refused`(v: String) {
        assertNull(TailnetTarget.parseHaDial(v), v)
    }

    @Test
    fun `ipv6 is lower-cased and the value is trimmed`() {
        assertEquals("[fd7a:115c:a1e0::5]:443", TailnetTarget.parseHaDial("  [FD7A:115C:A1E0::5]:443 "))
    }

    @Test
    fun `resolve prefers ha_dial`() {
        assertEquals("192.168.1.5:443", TailnetTarget.resolve("https://haos.example.org", "192.168.1.5:443"))
    }

    @Test
    fun `resolve falls back to ha_url's host and port`() {
        assertEquals("haos.example.org:443", TailnetTarget.resolve("https://haos.example.org", null))
        assertEquals("haos.example.org:443", TailnetTarget.resolve("https://haos.example.org/", "garbage"))
        assertEquals("192.168.1.5:8123", TailnetTarget.resolve("http://192.168.1.5:8123", null))
        assertEquals("homeassistant.local:80", TailnetTarget.resolve("http://homeassistant.local", null))
        assertEquals("[fd7a:115c:a1e0::5]:8123", TailnetTarget.resolve("http://[fd7a:115c:a1e0::5]:8123", null))
    }

    @Test
    fun `resolve refuses what UrlPolicy refuses`() {
        assertNull(TailnetTarget.resolve(null, null))
        assertNull(TailnetTarget.resolve("", null))
        assertNull(TailnetTarget.resolve("http://example.com", null)) // plain http to a public host
        assertNull(TailnetTarget.resolve("ftp://192.168.1.5", null))
    }
}
