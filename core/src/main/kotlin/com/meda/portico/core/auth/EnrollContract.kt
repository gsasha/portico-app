package com.meda.portico.core.auth

import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy

/**
 * DEV-544, the app's side of `contract/enroll_v1.json` (vendored byte for byte in `core/src/test/resources`, sha256 pinned
 * by `EnrollContractTest`; the server's `tests/test_enroll_redeem.py` replays the same file against the real view).
 *
 * A format-2 enrollment QR carries a ONE-TIME code, not a Home Assistant token. The phone redeems the code over HTTP
 * (`POST /api/portico/enroll/redeem`) for a refresh token, keeps that, and trades it for 24-hour access tokens at HA's own
 * `/auth/token` before each WebSocket connect. A phone enrolled before this (format 1) keeps its long-lived token and its
 * connection path is not touched ([AccessTokens] is used only when a refresh token is stored).
 *
 * Nothing in this package logs, and no toString shows a code, a nonce, a token or a client id.
 */
object EnrollContract {
    const val REDEEM_PATH = "/api/portico/enroll/redeem"
    const val TOKEN_PATH = "/auth/token"

    /** The code is good for this long (server: `CODE_LIFETIME`). */
    const val CODE_TTL_MS = 600_000L

    /** A lost response can be answered again with the SAME request for this long after the redemption (server: `REPLAY_WINDOW`). */
    const val REPLAY_WINDOW_MS = 120_000L

    /** A pending redemption older than this can no longer succeed: the code (10 min) plus the replay window (2 min). */
    const val PENDING_MAX_AGE_MS = CODE_TTL_MS + REPLAY_WINDOW_MS

    /** Refresh the access token before a connect when it has less than this left. */
    const val REFRESH_MARGIN_MS = 5 * 60_000L

    /** The server's `Retry-After` is whole seconds, 1..60. */
    const val RETRY_AFTER_MIN_S = 1
    const val RETRY_AFTER_MAX_S = 60

    /** A response bigger than this is not an answer (the real ones are well under 2 KB). */
    const val MAX_RESPONSE_CHARS = 16 * 1024

    // The words of `messages` in the contract (the test compares them with the fixture).
    const val MSG_INVALID_OR_EXPIRED = "This QR has expired or was already used. Ask for a new one in Home Assistant."
    const val MSG_RATE_LIMITED = "Home Assistant asked this phone to wait a minute."
    const val MSG_SIGNED_OUT = "This phone was signed out of Home Assistant. Enroll it again."
    const val MSG_UNREACHABLE = "Cannot reach Home Assistant here. Join the home network or enroll again."
    const val MSG_TAILNET_NODE_GONE = "The tailnet no longer knows this phone. Enroll it again."
    const val MSG_OLD_QR_FORMAT =
        "This QR was made by an older Portico integration. Update Portico in Home Assistant, then enroll this phone again."

    private val CODE_RE = Regex("[A-Za-z0-9_-]{22,128}")
    private val NONCE_RE = Regex("[A-Za-z0-9_-]{16,64}")
    private val INSTALLATION_RE = Regex("[A-Za-z0-9_.:-]{1,128}")

    fun isValidCode(s: String?): Boolean = s != null && CODE_RE.matches(s)
    fun isValidNonce(s: String?): Boolean = s != null && NONCE_RE.matches(s)
    fun isValidInstallationId(s: String?): Boolean = s != null && INSTALLATION_RE.matches(s)

    /**
     * A fresh nonce: 24 random bytes as 48 lowercase hex characters (within the contract's 16..64 of `[A-Za-z0-9_-]`).
     * [random] fills the array; the app passes `SecureRandom::nextBytes`.
     */
    fun newNonce(random: (ByteArray) -> Unit): String {
        val b = ByteArray(24)
        random(b)
        return com.meda.portico.core.Hex.encode(b)
    }

    /** `ha_url` + [path] (the redeem or the token path), or null when the URL policy refuses `ha_url` (https always, plain http only to a private host). */
    fun urlFor(haUrl: String?, path: String): String? {
        val u = haUrl?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty() || UrlPolicy.check(u) is UrlCheck.Refused) return null
        return u + path
    }

    /** 1..60 whole seconds from a `Retry-After` header; anything else (absent, a date, 0, negative) is the long end. */
    fun retryAfterSeconds(header: String?): Int {
        val n = header?.trim()?.toIntOrNull() ?: return RETRY_AFTER_MAX_S
        return n.coerceIn(RETRY_AFTER_MIN_S, RETRY_AFTER_MAX_S)
    }
}
