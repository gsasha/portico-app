package com.meda.portico.core.ha

import com.meda.portico.core.auth.AccessTokens

/**
 * Where the HA address and token come from. PR-C's encrypted Settings implements it (PR-E
 * wires it); read on every connect, so a Setup save takes effect on the next one.
 * Implementations must not render the token in `toString()`.
 */
interface HaConnectionConfig {
    /** The enrolled `ha_url` (e.g. `http://homeassistant.local:8123`), or null before enrolment. */
    val haUrl: String?

    /** The phone's own long-lived HA access token, or null before enrolment / after sign-out. */
    val accessToken: String?

    /**
     * DEV-544: the refresh-token login of a phone enrolled by one-time code, or null (the default) for every other phone.
     * Non-null means: do NOT use [accessToken]; ask [AccessTokens.cached] / [AccessTokens.obtain] before each connect.
     * A phone with only the old long-lived token has null here, and its connection path is exactly what it was.
     */
    val login: AccessTokens? get() = null
}

/** One open WebSocket, as the core sees it. */
interface WsTransport {
    /** False when the socket can no longer take the frame (closed or failed). */
    fun send(text: String): Boolean

    /** Bytes accepted by [send] and not yet written to the network (away call audio drops frames when this grows). */
    fun queueSize(): Long = 0L
    fun close(code: Int, reason: String)

    /** DEV-811: this socket, opened by [Connector.openCandidate] beside a working one, became THE socket (the old one is closing). Default: nothing to do. */
    fun promote() {}
}

/** Socket callbacks, from the transport to the client (may arrive on any thread). */
interface WsListener {
    fun onOpen()
    fun onText(text: String)

    /** Closed or failed; [reason] is for the log only. Called at most once per socket. */
    fun onClosed(reason: String)

    /**
     * A connector that knows WHY a socket failed says so here, just before [onClosed]
     * (Setup's per-path line). Optional: the default ignores it.
     */
    fun onFailure(failure: LinkFailure) {}
}

/**
 * Opens a WebSocket to a URL. P2 has the direct-URL connector (OkHttp, in `:app`); the tsnet
 * connector plugs in here later without touching the protocol core (plan §3.2).
 */
fun interface Connector {
    fun open(url: String, listener: WsListener): WsTransport

    /**
     * [open] for a connector that may block while connecting: [cancelled] turns true once the
     * caller no longer wants the socket (it was closed, or the other path won), so a long wait can
     * end early. The default ignores it.
     */
    fun open(url: String, listener: WsListener, cancelled: () -> Boolean): WsTransport = open(url, listener)

    /**
     * DEV-811: [open] for a SECOND socket made while a working one is still up (the link race). It must not touch what the working
     * socket owns (the published path, the node's sleep, the connect timeline); [WsTransport.promote] hands those over if it wins.
     */
    fun openCandidate(url: String, listener: WsListener): WsTransport = open(url, listener)
}

object HaUrls {
    /**
     * `ha_url` -> its WebSocket endpoint: `http(s)://host[:port][/path]` becomes
     * `ws(s)://host[:port][/path]/api/websocket`. Null when it is not an http(s) or ws(s) URL.
     */
    fun websocketUrl(haUrl: String?): String? {
        val u = haUrl?.trim()?.trimEnd('/') ?: return null
        val rest = when {
            u.startsWith("https://", ignoreCase = true) -> "wss://" + u.substring(8)
            u.startsWith("http://", ignoreCase = true) -> "ws://" + u.substring(7)
            u.startsWith("wss://", ignoreCase = true) || u.startsWith("ws://", ignoreCase = true) -> u
            else -> return null
        }
        if (rest.substringAfter("://").isEmpty()) return null
        return if (rest.endsWith("/api/websocket")) rest else "$rest/api/websocket"
    }
}

/**
 * Capped exponential reconnect backoff: [baseMs], doubling, never above [maxMs]; [reset] after a
 * successful `auth_ok`. [jitter] maps a delay to the one actually used (tests pass identity).
 *
 * For [freshForMs] after the link was last [markConnected] the cap is [freshMaxMs] instead: a socket that was just up and
 * dropped is almost always a Home Assistant restart or a network hand-over, and the server is back within a minute, so the
 * phone should look again every few seconds rather than wait out a 35 s step (a measured 59 s reconnect, DEV-705). A phone that
 * has been unreachable longer, or never connected, keeps the long cap, so a night of no signal is not a night of probes.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val freshMaxMs: Long = 8_000,
    private val freshForMs: Long = 300_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val jitter: (Long) -> Long = { d -> d / 2 + (Math.random() * (d / 2)).toLong() }, // last, so a trailing lambda is the jitter
) {
    private var attempt = 0
    private var connectedAtMs: Long? = null

    /** The link reached CONNECTED now (also resets the attempt count, as [reset]). */
    fun markConnected() {
        connectedAtMs = clockMs()
        attempt = 0
    }

    fun nextDelayMs(): Long {
        val shift = attempt.coerceAtMost(20)
        attempt++
        val fresh = connectedAtMs?.let { clockMs() - it < freshForMs } ?: false
        val cap = if (fresh) minOf(freshMaxMs, maxMs) else maxMs
        val raw = (baseMs shl shift).coerceAtMost(cap).coerceAtLeast(minOf(baseMs, cap))
        return jitter(raw).coerceIn(0, cap)
    }

    fun reset() {
        attempt = 0
    }
}
