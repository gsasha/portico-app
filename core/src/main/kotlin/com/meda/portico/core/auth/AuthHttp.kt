package com.meda.portico.core.auth

import com.meda.portico.core.ha.LinkPath
import java.io.IOException

/**
 * One HTTP answer. [body] is capped by the transport at [EnrollContract.MAX_RESPONSE_CHARS]. [toString] shows the status
 * only: the body of a redemption or a refresh is a credential.
 */
class HttpReply(val status: Int, val body: String, val retryAfter: String? = null) {
    override fun toString(): String = "HttpReply($status)"
}

/**
 * A POST to Home Assistant over ONE path. Throws [IOException] when the request could not be completed (no route, a
 * refused or timed-out connection, a TLS failure, a redirect, a body that is too long): "unreachable", not a refusal.
 * An HTTP answer of any status is a [HttpReply], never an exception. The app's implementations never follow a redirect
 * (a credential must not be sent anywhere the enrolled URL did not name) and never log the request or the answer.
 */
fun interface HttpPost {
    @Throws(IOException::class)
    fun post(url: String, contentType: String, body: String): HttpReply
}

/** Both paths failed (or the only path did). The message names no URL, no address and no body. */
class PathsFailedException(val tried: List<LinkPath>) : IOException("no path to Home Assistant answered (${tried.joinToString { it.name.lowercase() }})")

/**
 * The same path choice the WebSocket makes (`FallbackConnector`, docs/tailnet.md "Path choice"), for one HTTP request:
 * `ha_url` directly first; when that cannot complete AND the tailnet could help ([tailnetUsable]), the tailnet. An HTTP answer
 * on the direct path (any status) ends it: a 400 or a 429 is HA talking, not a reason to ask again by another route.
 *
 * Retrying the SAME request on the second path after the first one lost its answer is what the redemption contract allows
 * (the same `nonce` within 2 minutes returns the same token), and a refresh grant is idempotent.
 *
 * [paused] (fail open) refuses before anything is sent: a paused phone does not talk to Home Assistant.
 */
class PathedHttp(
    private val direct: HttpPost,
    private val tailnet: HttpPost?,
    private val tailnetUsable: () -> Boolean,
    private val paused: () -> Boolean = { false },
    /** Every path attempt that ended without an answer, for the app's per-path diagnostics. */
    private val onPathFailed: (LinkPath) -> Unit = {},
) : HttpPost {

    override fun post(url: String, contentType: String, body: String): HttpReply {
        if (isPaused()) throw IOException("paused")
        val tried = ArrayList<LinkPath>(2)
        try {
            return direct.post(url, contentType, body)
        } catch (e: IOException) {
            tried += LinkPath.DIRECT
            runCatching { onPathFailed(LinkPath.DIRECT) }
        }
        val second = tailnet
        if (second == null || !usable() || isPaused()) throw PathsFailedException(tried)
        try {
            return second.post(url, contentType, body)
        } catch (e: IOException) {
            tried += LinkPath.TAILNET
            runCatching { onPathFailed(LinkPath.TAILNET) }
            throw PathsFailedException(tried)
        }
    }

    private fun usable(): Boolean = try {
        tailnetUsable()
    } catch (_: Exception) {
        false
    }

    private fun isPaused(): Boolean = try {
        paused()
    } catch (_: Exception) {
        false
    }
}
