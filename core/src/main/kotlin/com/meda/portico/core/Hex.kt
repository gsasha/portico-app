package com.meda.portico.core

/**
 * Lowercase hex encoding. The first resident of the pure-Kotlin core (no android.* imports):
 * the redaction helper (P2 plan §3.7, `sha256[:8]`, the short form the server logs) builds
 * on it.
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for ((i, b) in bytes.withIndex()) {
            val v = b.toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }
}
