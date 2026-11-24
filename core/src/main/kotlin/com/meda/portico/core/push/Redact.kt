package com.meda.portico.core.push

import com.meda.portico.core.Hex
import java.security.MessageDigest

/**
 * Log-safe short forms (P2 plan §3.7, spec §6). Never log in full: an FID, an FCM token,
 * `ha_token`, `api_key` or `tailscale_authkey`.
 *
 * - [reg]: `sha256(value)[:8]`, lowercase hex of the UTF-8 bytes. The short form spec §6 names
 *   for the server's logs, so a phone journal line and an HA log line can be matched without either
 *   holding the value. Use it for every secret or registration value.
 * - [prefix]: the first 6 characters plus the length, e.g. `gso-17…(32)`. For ids that are not
 *   secret but long (call ids); never for a registration, whose prefix is part of the value.
 *
 * A null or empty value renders as `<none>` so a missing value is visible in the journal.
 */
object Redact {
    const val NONE = "<none>"

    fun reg(value: String?): String {
        if (value.isNullOrEmpty()) return NONE
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Hex.encode(digest).substring(0, 8)
    }

    fun prefix(value: String?): String {
        if (value.isNullOrEmpty()) return NONE
        // A value of 6 characters or fewer would be shown whole: show only its length.
        return if (value.length <= 6) "…(${value.length})" else "${value.take(6)}…(${value.length})"
    }
}
