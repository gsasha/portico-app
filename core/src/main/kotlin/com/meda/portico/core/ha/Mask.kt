package com.meda.portico.core.ha

import com.meda.portico.core.Hex
import java.security.MessageDigest

/**
 * PR-D's own redaction helper (P2 plan §3.7). A registration value (FID or token) and the HA
 * access token are never logged or rendered in full: only `sha256(value)[:8]`, the same short
 * form the server logs, so a phone log line and an HA log line can be matched without either
 * holding the value.
 *
 * Deliberately local: PR-B's `Redact` is not merged yet. When it is, this can delegate to it.
 */
object Mask {
    fun short(value: String?): String {
        if (value == null) return "null"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha:" + Hex.encode(digest).substring(0, 8)
    }
}

/** Where the WebSocket core writes its journal. The app maps it to logcat; tests capture it. */
fun interface HaLog {
    fun log(level: Level, message: String)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        val NONE = HaLog { _, _ -> }
    }
}

/** Milliseconds since an arbitrary epoch. Injected so nothing here reads real time. */
fun interface Clock {
    fun nowMs(): Long
}
