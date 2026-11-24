package com.meda.portico.core.connlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-705: a close reason may carry only fixed words, an exception class name and a status code. */
class ReasonWordsTest {
    @Test
    fun `the reasons the socket stack produces become short fixed words`() {
        assertEquals("failure_unknownhostexception", ReasonWords.of("failure: UnknownHostException"))
        assertEquals("closed_1006", ReasonWords.of("closed (1006)"))
        assertEquals("closed_by_server_1001", ReasonWords.of("closed by server (1001)"))
        assertEquals("paused", ReasonWords.of("paused"))
        assertEquals("reconnect_requested", ReasonWords.of("reconnect requested"))
        assertEquals("contract_mismatch", ReasonWords.of("contract mismatch"))
    }

    @Test
    fun `free text, addresses, urls and names are dropped`() {
        val raw = "direct: failure: IOException; tailnet: the target 192.168.1.5:443 refused for user alice at https://ha.example.net/api token tskey-auth-abc"
        val w = ReasonWords.of(raw)
        for (secret in listOf("10", "alice", "ha_example", "https", "tskey", "abc", "target", "api")) assertFalse(w.contains(secret), "$secret in $w")
        assertTrue(w.startsWith("direct_failure_ioexception"), w)
        assertTrue(w.length <= 32)
    }

    @Test
    fun `nothing usable is other, and the length is bounded`() {
        assertEquals("other", ReasonWords.of(null))
        assertEquals("other", ReasonWords.of(""))
        assertEquals("other", ReasonWords.of("   "))
        assertEquals("other", ReasonWords.of("192.168.1.5:443 @ home"))
        assertTrue(ReasonWords.of("failure ".repeat(100)).length <= 32)
    }

    @Test
    fun `only exception CLASS names survive, not any capitalised word`() {
        assertEquals("other", ReasonWords.of("Alice Example"))
        assertEquals("sockettimeoutexception", ReasonWords.of("SocketTimeoutException"))
    }
}
