package com.meda.portico.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The network URL policy (PR-J): https always, plain http only to a private/LAN host, and every
 * odd address form refused rather than guessed at.
 */
class UrlPolicyTest {

    private sealed interface Want
    private data class H(val host: String) : Want // Https
    private data class P(val host: String) : Want // HttpPrivate
    private data class R(val reason: UrlRefusal) : Want // Refused

    private fun verdict(c: UrlCheck): Want = when (c) {
        is UrlCheck.Https -> H(c.host)
        is UrlCheck.HttpPrivate -> P(c.host)
        is UrlCheck.Refused -> R(c.reason)
    }

    private val table: List<Pair<String, Want>> = listOf(
        // ---- https: always allowed, any well-formed host ----------------------------------------
        "https://ha.example.com" to H("ha.example.com"),
        "https://ha.example.com:8123/" to H("ha.example.com"),
        "HTTPS://HA.Example.COM:8123/lovelace?x=1#y" to H("ha.example.com"),
        "https://abcdefghijklmnopqrstuvwxyz012345.ui.nabu.casa" to H("abcdefghijklmnopqrstuvwxyz012345.ui.nabu.casa"),
        "https://8.8.8.8" to H("8.8.8.8"),
        "https://10.0.0.5:8123" to H("10.0.0.5"),
        "https://homeassistant.local:8123" to H("homeassistant.local"),
        "https://[2001:db8::1]:8443/" to H("2001:db8::1"),
        "https://10.0.0.1.evil.com" to H("10.0.0.1.evil.com"),
        "https://xn--bcher-kva.example" to H("xn--bcher-kva.example"),
        "https://ha.example.com." to H("ha.example.com"),

        // ---- http to private IPv4 ranges -----------------------------------------------------------
        "http://10.0.0.1" to P("10.0.0.1"),
        "http://10.255.255.255:8123/" to P("10.255.255.255"),
        "http://172.16.0.1" to P("172.16.0.1"),
        "http://172.31.255.254:8123" to P("172.31.255.254"),
        "http://192.168.1.10:8123" to P("192.168.1.10"),
        "http://169.254.10.20" to P("169.254.10.20"),
        "http://100.64.0.1" to P("100.64.0.1"),
        "http://100.127.255.254:8123" to P("100.127.255.254"),
        "http://100.101.102.103:8123" to P("100.101.102.103"), // a typical tailnet address
        "http://127.0.0.1:8123" to P("127.0.0.1"),
        "http://127.255.0.1" to P("127.255.0.1"),
        "http://0.0.0.0" to R(UrlRefusal.HTTP_PUBLIC_HOST),

        // ---- http to just-outside-the-range IPv4 ----------------------------------------------
        "http://172.15.255.255" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://172.32.0.1" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://192.169.0.1" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://169.253.1.1" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://100.63.255.255" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://100.128.0.0" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://11.0.0.1" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://8.8.8.8" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://255.255.255.255" to R(UrlRefusal.HTTP_PUBLIC_HOST),

        // ---- non-canonical IPv4: refused whatever the scheme -------------------------------------
        "http://010.0.0.1" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10.0.0.01" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://0x7f.1" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://0x7f000001" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://127.1" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://2130706433" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10.0.0" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10.0.0.256" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10.0.0.1.2" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10.0.0.1." to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "https://0x08.0x08.0x08.0x08" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "https://127.1" to R(UrlRefusal.NON_CANONICAL_IPV4),
        "http://10..0.1" to R(UrlRefusal.BAD_HOSTNAME),

        // ---- a private-looking prefix does not make a hostname private ---------------------------
        "http://10.0.0.1.evil.com" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://192.168.1.1.nip.io" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://127.0.0.1.example.com:8123" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://localhost.evil.com" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://local.evil.com" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://evil.com.lan.example.com" to R(UrlRefusal.HTTP_PUBLIC_HOST),

        // ---- http to private hostnames ---------------------------------------------------------
        "http://homeassistant.local:8123" to P("homeassistant.local"),
        "http://HomeAssistant.LOCAL:8123/" to P("homeassistant.local"),
        "http://homeassistant.local.:8123" to P("homeassistant.local"),
        "http://ha.lan" to P("ha.lan"),
        "http://ha.home.arpa" to P("ha.home.arpa"),
        "http://ha.internal" to P("ha.internal"),
        "http://ha.localdomain" to P("ha.localdomain"),
        "http://ha.estate.lan:8123" to P("ha.estate.lan"),
        "http://homeassistant:8123" to P("homeassistant"),
        "http://localhost:8123" to P("localhost"),
        "http://home.arpa" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://homeassistant.:8123" to R(UrlRefusal.HTTP_PUBLIC_HOST), // absolute: no search domain
        "http://ha.example.com" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://ha.example.com:8123/api" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://ha.duckdns.org" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://ha.locall" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://halocal" to P("halocal"), // single label

        // ---- IPv6 --------------------------------------------------------------------------------
        "http://[::1]:8123" to P("::1"),
        "http://[fd00::10]:8123" to P("fd00::10"),
        "http://[FC00::1]" to P("fc00::1"),
        "http://[fdff:ffff::1]" to P("fdff:ffff::1"),
        "http://[fe80::1]" to P("fe80::1"),
        "http://[febf:ffff::1]" to P("febf:ffff::1"),
        "http://[fec0::1]" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://[fbff::1]" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://[2001:db8::1]" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://[::]" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://[::ffff:192.168.1.5]:8123" to P("::ffff:192.168.1.5"),
        "http://[::ffff:c0a8:105]" to P("::ffff:c0a8:105"),
        "http://[::ffff:8.8.8.8]" to R(UrlRefusal.HTTP_PUBLIC_HOST),
        "http://[::ffff:010.0.0.1]" to R(UrlRefusal.BAD_IPV6),
        "http://[::10.0.0.1]" to R(UrlRefusal.HTTP_PUBLIC_HOST), // deprecated IPv4-compatible form
        "http://[64:ff9b::10.0.0.1]" to R(UrlRefusal.HTTP_PUBLIC_HOST), // NAT64 is not the LAN
        "http://[0:0:0:0:0:0:0:1]" to P("0:0:0:0:0:0:0:1"),
        "http://[fe80::1%25wlan0]" to R(UrlRefusal.BAD_IPV6),
        "http://[1:2:3:4:5:6:7:8:9]" to R(UrlRefusal.BAD_IPV6),
        "http://[1::2::3]" to R(UrlRefusal.BAD_IPV6),
        "http://[12345::1]" to R(UrlRefusal.BAD_IPV6),
        "http://[]" to R(UrlRefusal.BAD_IPV6),
        "http://[::1" to R(UrlRefusal.BAD_IPV6),
        "http://[::1]x" to R(UrlRefusal.BAD_PORT),
        "http://::1" to R(UrlRefusal.BAD_PORT),
        "http://fd00::1" to R(UrlRefusal.BAD_PORT),

        // ---- ports ------------------------------------------------------------------------------
        "http://10.0.0.1:65535" to P("10.0.0.1"),
        "http://10.0.0.1:0" to R(UrlRefusal.BAD_PORT),
        "http://10.0.0.1:65536" to R(UrlRefusal.BAD_PORT),
        "http://10.0.0.1:" to R(UrlRefusal.BAD_PORT),
        "http://10.0.0.1:80a" to R(UrlRefusal.BAD_PORT),
        "http://10.0.0.1:-1" to R(UrlRefusal.BAD_PORT),
        "http://10.0.0.1:1:2" to R(UrlRefusal.BAD_PORT),

        // ---- userinfo, schemes, empties, garbage -----------------------------------------------
        "http://user:pass@10.0.0.1" to R(UrlRefusal.USERINFO),
        "https://user@ha.example.com" to R(UrlRefusal.USERINFO),
        "http://ha.example.com@10.0.0.1" to R(UrlRefusal.USERINFO),
        "http://10.0.0.1@ha.example.com" to R(UrlRefusal.USERINFO),
        "http://@10.0.0.1" to R(UrlRefusal.USERINFO),
        "ws://10.0.0.1" to R(UrlRefusal.NOT_HTTP),
        "ftp://10.0.0.1" to R(UrlRefusal.NOT_HTTP),
        "file:///etc/passwd" to R(UrlRefusal.NOT_HTTP),
        "javascript:alert(1)" to R(UrlRefusal.NOT_HTTP),
        "10.0.0.1:8123" to R(UrlRefusal.NOT_HTTP),
        "homeassistant.local" to R(UrlRefusal.NOT_HTTP),
        "http:/10.0.0.1" to R(UrlRefusal.NOT_HTTP),
        "http:10.0.0.1" to R(UrlRefusal.NOT_HTTP),
        "" to R(UrlRefusal.EMPTY),
        "   " to R(UrlRefusal.EMPTY),
        "http://" to R(UrlRefusal.EMPTY_HOST),
        "http:///path" to R(UrlRefusal.EMPTY_HOST),
        "http://:8123" to R(UrlRefusal.EMPTY_HOST),
        "http://?x" to R(UrlRefusal.EMPTY_HOST),
        "http://10.0.0.1 /x" to R(UrlRefusal.MALFORMED),
        "http://10.0.0.1/a b" to R(UrlRefusal.MALFORMED),
        "http://10.0.0.1\n.evil.com" to R(UrlRefusal.MALFORMED),
        "http://10.0.0.1\\@evil.com" to R(UrlRefusal.MALFORMED),
        "http://ha_server.local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://-ha.local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://ha-.local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://.local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://ha..local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://bücher.local" to R(UrlRefusal.BAD_HOSTNAME),
        "http://ha%2elocal" to R(UrlRefusal.BAD_HOSTNAME),
        "http://${"a".repeat(64)}.local" to R(UrlRefusal.BAD_HOSTNAME),
        "  http://10.0.0.1:8123  " to P("10.0.0.1"), // outer whitespace is trimmed like the paste field
    )

    @Test
    fun theTableIsBigEnough() {
        assertTrue(table.size >= 60, "only ${table.size} cases")
        assertEquals(table.size, table.map { it.first }.toSet().size, "duplicate inputs in the table")
    }

    @TestFactory
    fun everyRow(): List<DynamicTest> = table.mapIndexed { i, (url, want) ->
        DynamicTest.dynamicTest("[$i] '${url.replace("\n", "\\n")}' -> $want") {
            assertEquals(want, verdict(UrlPolicy.check(url)), url)
        }
    }

    @Test
    fun nullIsEmpty() {
        assertEquals(UrlCheck.Refused(UrlRefusal.EMPTY), UrlPolicy.check(null))
    }

    @Test
    fun noRefusalMessageQuotesTheInput() {
        val secret = "s3cr3t-token-value"
        for (url in listOf("http://u:$secret@10.0.0.1", "http://$secret.example.com", "ftp://$secret", "http://10.0.0.1/$secret x")) {
            val c = UrlPolicy.check(url)
            assertTrue(c is UrlCheck.Refused, url)
            assertFalse(secret in (c as UrlCheck.Refused).reason.message, "refusal quotes the input: ${c.reason.message}")
            assertFalse(secret in c.toString(), "toString quotes the input")
        }
    }

    @Test
    fun webSocketUrlsAreCheckedAsTheirHttpEquivalent() {
        assertEquals(UrlCheck.Https("ha.example.com"), UrlPolicy.checkWebSocket("wss://ha.example.com/api/websocket"))
        assertEquals(UrlCheck.HttpPrivate("10.0.0.1"), UrlPolicy.checkWebSocket("ws://10.0.0.1:8123/api/websocket"))
        assertEquals(UrlCheck.Refused(UrlRefusal.HTTP_PUBLIC_HOST), UrlPolicy.checkWebSocket("ws://ha.example.com/api/websocket"))
        assertEquals(UrlCheck.Refused(UrlRefusal.NOT_HTTP), UrlPolicy.checkWebSocket("ftp://10.0.0.1"))
        assertEquals(UrlCheck.HttpPrivate("10.0.0.1"), UrlPolicy.checkWebSocket("http://10.0.0.1"))
    }
}
