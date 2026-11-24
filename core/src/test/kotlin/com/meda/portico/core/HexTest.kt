package com.meda.portico.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class HexTest {
    @Test
    fun encodesBytesAsLowercaseHex() {
        // Empty input, nibble order, sign handling (0x80, 0xff) and padding (0x0a).
        assertEquals("", Hex.encode(byteArrayOf()))
        assertEquals("000a7f80ff", Hex.encode(byteArrayOf(0x00, 0x0a, 0x7f, 0x80.toByte(), 0xff.toByte())))
        // Known vector: SHA-256("abc") (FIPS 180-2, appendix B.1).
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hex.encode(digest),
        )
    }
}
